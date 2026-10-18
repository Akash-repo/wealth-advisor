package com.wealth.advisor.audit;

import com.wealth.advisor.constants.BigQueryNames;
import com.wealth.advisor.util.AdkContentText;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.agents.CallbackContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.Plugin;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.InsertAllRequest;
import com.google.cloud.bigquery.InsertAllResponse;
import com.google.cloud.bigquery.TableId;
import io.reactivex.rxjava3.core.Maybe;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Records every real call this app makes to Gemini into BigQuery's {@code agent_audit_log} table
 * — this is the "see exactly what happened internally" tool, one row per user chat turn.
 *
 * <p>An ADK global {@link Plugin}, registered once on the {@code App} in {@code RunnerConfig}
 * rather than wired onto each agent: ADK then calls it for <em>every</em> agent's model calls, so a
 * new agent is audited automatically.
 *
 * <p>{@code beforeModelCallback} fires immediately before every actual Gemini call, {@code
 * afterModelCallback} immediately after. A single user
 * turn is correlated across both agents (and across however many times each agent calls the
 * model) using ADK's own {@code invocationId} — generated once per incoming user message by
 * {@code Runner} and threaded through every sub-agent in a {@code SequentialAgent}, which is
 * exactly the "one id per user prompt" concept this table's {@code user_prompt_id} column needs.
 *
 * <p>Token counts are kept both per call (inside the JSON) and summed per agent in their own
 * columns: prompt, cached (a subset of prompt, served from Gemini's cache), completion (the visible
 * answer) and thinking (hidden reasoning, billed like output, not part of completion). The
 * token-usage views roll these up per session, user and day.
 *
 * <p>Why one agent can have more than one LLM call per turn: if {@code portfolio_analysis}
 * decides to call {@code getPortfolioAllocation}, that's two round-trips to Gemini for that one agent —
 * a first call where the model asks to invoke the tool, then (after the tool runs) a second call
 * where the model reacts to the tool's result. Both get captured, in order, in that agent's
 * {@code *_llm_calls} JSON array.
 */
@Component
public class AgentAuditRecorder implements Plugin {

	private static final Logger log = LoggerFactory.getLogger(AgentAuditRecorder.class);

	private final BigQuery bigQuery;
	private final ObjectMapper objectMapper;

	// One entry per in-flight user turn, keyed by ADK's invocationId. Removed by finalizeTurn.
	private final Map<String, TurnAudit> turnsByInvocationId = new ConcurrentHashMap<>();
	// Secondary index so AgentChatService can finalize a turn knowing only (userId, sessionId) — it
	// never sees ADK's invocationId directly, and this also means a turn that fails before
	// producing any events (so there's no Event to read an invocationId back off of) can still be
	// finalized. Keyed by TurnKey (userId + sessionId), NOT userId alone: a user can have several
	// sessions open at once (two tabs), and keying by user would let one session's turn overwrite
	// another's invocationId. AgentChatService guarantees one in-flight turn per session.
	private final Map<String, String> invocationIdByTurnKey = new ConcurrentHashMap<>();

	public AgentAuditRecorder(BigQuery bigQuery, ObjectMapper objectMapper) {
		this.bigQuery = bigQuery;
		this.objectMapper = objectMapper;
	}

	/** One accumulating record per user turn, built up across both agents' model calls. */
	private static final class TurnAudit {
		final String userId;
		final String sessionId;
		final String userPromptText;
		// agentName -> ordered list of completed round-trips for that agent this turn.
		final Map<String, List<RoundTrip>> callsByAgent = new LinkedHashMap<>();
		// agentName -> the round-trip currently awaiting its afterModel callback.
		final Map<String, PendingRoundTrip> pendingByAgent = new ConcurrentHashMap<>();

		TurnAudit(String userId, String sessionId, String userPromptText) {
			this.userId = userId;
			this.sessionId = sessionId;
			this.userPromptText = userPromptText;
		}
	}

	private static final class PendingRoundTrip {
		final String requestJson;
		final long startNanos;

		PendingRoundTrip(String requestJson) {
			this.requestJson = requestJson;
			this.startNanos = System.nanoTime();
		}
	}

	/**
	 * One completed Gemini call: what was sent, what came back, how long it took, token usage.
	 * {@code requestJson}/{@code responseJson} are pre-serialized JSON (from ADK's own {@code
	 * LlmRequest.toJson()}/{@code LlmResponse.toJson()}) — {@code @JsonRawValue} embeds them as
	 * genuine nested JSON in the final array rather than double-escaped strings, so BigQuery's
	 * JSON functions can query into them directly.
	 */
	private record RoundTrip(
			@JsonRawValue String requestJson,
			@JsonRawValue String responseJson,
			long latencyMs,
			Integer promptTokens,
			Integer cachedTokens,
			Integer completionTokens,
			Integer thinkingTokens) {}

	/**
	 * Token counts from one Gemini response. {@code cached} is the part of {@code prompt} served from
	 * Gemini's cache (a subset, not additional); {@code thinking} is the model's hidden reasoning,
	 * which is billed like output but is <em>not</em> included in {@code completion}.
	 */
	private record Usage(Integer prompt, Integer cached, Integer completion, Integer thinking) {

		static Usage of(LlmResponse response) {
			return response
					.usageMetadata()
					.map(
							u ->
									new Usage(
											u.promptTokenCount().orElse(null),
											u.cachedContentTokenCount().orElse(null),
											u.candidatesTokenCount().orElse(null),
											u.thoughtsTokenCount().orElse(null)))
					.orElse(new Usage(null, null, null, null));
		}
	}

	@Override
	public String getName() {
		return "agent_audit_recorder";
	}

	/** Never overrides the request (an empty result means "carry on"). */
	@Override
	public Maybe<LlmResponse> beforeModelCallback(CallbackContext ctx, LlmRequest.Builder requestBuilder) {
		invocationIdByTurnKey.put(TurnKey.of(ctx.userId(), ctx.sessionId()), ctx.invocationId());
		TurnAudit turn =
				turnsByInvocationId.computeIfAbsent(
						ctx.invocationId(),
						id ->
								new TurnAudit(
										ctx.userId(), ctx.sessionId(), AdkContentText.textOf(ctx.userContent().orElse(null))));
		turn.pendingByAgent.put(ctx.agentName(), new PendingRoundTrip(requestBuilder.build().toJson()));
		return Maybe.empty();
	}

	/** Never overrides the response. */
	@Override
	public Maybe<LlmResponse> afterModelCallback(CallbackContext ctx, LlmResponse response) {
		TurnAudit turn = turnsByInvocationId.get(ctx.invocationId());
		if (turn == null) {
			log.warn("afterModel fired with no matching turn for invocationId='{}'", ctx.invocationId());
			return Maybe.empty();
		}
		PendingRoundTrip pending = turn.pendingByAgent.remove(ctx.agentName());
		long latencyMs =
				pending == null ? -1 : (System.nanoTime() - pending.startNanos) / 1_000_000;

		Usage usage = Usage.of(response);

		turn.callsByAgent
				.computeIfAbsent(ctx.agentName(), a -> new ArrayList<>())
				.add(
						new RoundTrip(
								pending == null ? null : pending.requestJson,
								response.toJson(),
								latencyMs,
								usage.prompt(),
								usage.cached(),
								usage.completion(),
								usage.thinking()));
		return Maybe.empty();
	}

	/**
	 * Called once by {@code AgentChatService} after a turn finishes (success or failure) to write
	 * the accumulated row to BigQuery and forget this turn's in-memory state.
	 *
	 * @param userId with {@code sessionId}, identifies which in-flight turn to finalize (see {@link
	 *     #invocationIdByTurnKey}) — not ADK's invocationId itself, which {@code AgentChatService}
	 *     never needs to know
	 * @param sessionId the session the turn ran in
	 */
	public void finalizeTurn(
			String userId,
			String sessionId,
			String agent1Name,
			String agent2Name,
			String inputToAgent2,
			String finalResponse,
			long totalLatencyMs,
			String status,
			String errorMessage) {
		String invocationId = invocationIdByTurnKey.remove(TurnKey.of(userId, sessionId));
		TurnAudit turn = invocationId == null ? null : turnsByInvocationId.remove(invocationId);
		if (turn == null) {
			log.warn(
					"No audit data recorded for userId='{}' sessionId='{}' — skipping BigQuery write",
					userId,
					sessionId);
			return;
		}

		try {
			Map<String, Object> row = new HashMap<>();
			row.put("user_prompt_id", invocationId);
			row.put("user_id", turn.userId);
			row.put("session_id", turn.sessionId);
			row.put("user_prompt_text", turn.userPromptText);
			putAgentColumns(row, "agent1", agent1Name, turn.callsByAgent.get(agent1Name));
			row.put("input_to_agent2", inputToAgent2);
			putAgentColumns(row, "agent2", agent2Name, turn.callsByAgent.get(agent2Name));
			row.put("final_response_to_user", finalResponse);
			row.put("total_latency_ms", totalLatencyMs);
			row.put("status", status);
			if (errorMessage != null) {
				row.put("error_message", errorMessage);
			}
			row.put("created_at", Instant.now().toString());

			InsertAllResponse response =
					bigQuery.insertAll(
							InsertAllRequest.newBuilder(TableId.of(BigQueryNames.DATASET, BigQueryNames.AGENT_AUDIT_LOG))
									.addRow(row)
									.build());
			if (response.hasErrors()) {
				log.error(
						"BigQuery insert errors for invocationId='{}': {}", invocationId, response.getInsertErrors());
			} else {
				log.debug("Audit row written to BigQuery for invocationId='{}'", invocationId);
			}
		} catch (Exception e) {
			log.error("Failed to write audit row for invocationId='{}'", invocationId, e);
		}
	}

	private void putAgentColumns(
			Map<String, Object> row, String columnPrefix, String agentName, List<RoundTrip> calls) {
		List<RoundTrip> safeCalls = calls == null ? List.of() : calls;
		row.put(columnPrefix + "_name", agentName);
		row.put(columnPrefix + "_llm_calls", toJsonArray(safeCalls));
		row.put(
				columnPrefix + "_latency_ms", safeCalls.stream().mapToLong(RoundTrip::latencyMs).sum());
		row.put(columnPrefix + "_prompt_tokens", sumNullable(safeCalls, RoundTrip::promptTokens));
		row.put(columnPrefix + "_cached_tokens", sumNullable(safeCalls, RoundTrip::cachedTokens));
		row.put(
				columnPrefix + "_completion_tokens", sumNullable(safeCalls, RoundTrip::completionTokens));
		row.put(columnPrefix + "_thinking_tokens", sumNullable(safeCalls, RoundTrip::thinkingTokens));
	}

	private static Integer sumNullable(
			List<RoundTrip> calls, java.util.function.Function<RoundTrip, Integer> extractor) {
		int sum = 0;
		boolean any = false;
		for (RoundTrip call : calls) {
			Integer value = extractor.apply(call);
			if (value != null) {
				sum += value;
				any = true;
			}
		}
		return any ? sum : null;
	}

	private String toJsonArray(List<RoundTrip> calls) {
		try {
			return objectMapper.writeValueAsString(calls);
		} catch (Exception e) {
			log.error("Failed to serialize LLM call list to JSON", e);
			return "[]";
		}
	}
}
