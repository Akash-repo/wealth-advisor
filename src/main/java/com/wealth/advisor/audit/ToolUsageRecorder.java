package com.wealth.advisor.audit;

import com.wealth.advisor.constants.BigQueryNames;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.plugins.Plugin;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.InsertAllRequest;
import com.google.cloud.bigquery.InsertAllResponse;
import com.google.cloud.bigquery.TableId;
import io.reactivex.rxjava3.core.Maybe;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Records every real tool call this app's agents make into BigQuery's {@code tool_invocations}
 * table — the per-tool-call companion to {@link AgentAuditRecorder}, which records the LLM calls
 * around them. See {@code ARCHITECTURE.md}'s "Tool usage tracking" section for the full design
 * rationale.
 *
 * <p>An ADK global {@link Plugin}, registered once on the {@code App} in {@code RunnerConfig}, so
 * every agent's tool calls are recorded without wiring callbacks onto each agent.
 *
 * <p>Each tool call's own row is keyed by ADK's {@code functionCallId} (visible via {@link
 * ToolContext#functionCallId()}) — ADK already assigns this once per real tool invocation, so
 * reusing it as {@code tool_invocations.invocation_id} avoids inventing a second id space that
 * would just duplicate it. Rows are buffered per user turn (keyed by ADK's {@code invocationId},
 * same as {@link AgentAuditRecorder}) and flushed as a single batched {@code insertAll} call from
 * {@link #finalizeTurn} — one BigQuery write per turn regardless of how many tools it called,
 * not one write per tool call, which is what actually keeps this cheap and within streaming
 * quota as the number of tools/agents grows.
 */
@Component
public class ToolUsageRecorder implements Plugin {

	private static final Logger log = LoggerFactory.getLogger(ToolUsageRecorder.class);

	private final BigQuery bigQuery;
	private final ObjectMapper objectMapper;

	// functionCallId -> the call's start time + what to log once it finishes. Keyed globally
	// (not per-turn) since a functionCallId is already unique across the whole app.
	private final Map<String, PendingCall> pendingByCallId = new ConcurrentHashMap<>();
	// ADK invocationId (== agent_audit_log.user_prompt_id) -> completed rows for that turn so far.
	private final Map<String, List<Map<String, Object>>> rowsByUserPromptId = new ConcurrentHashMap<>();
	// Same secondary index AgentAuditRecorder keeps, for the same reason: AgentChatService only
	// ever knows (userId, sessionId), never ADK's invocationId directly. Keyed per session, not per
	// user, because one user can have several sessions running at once.
	private final Map<String, String> invocationIdByTurnKey = new ConcurrentHashMap<>();

	public ToolUsageRecorder(BigQuery bigQuery, ObjectMapper objectMapper) {
		this.bigQuery = bigQuery;
		this.objectMapper = objectMapper;
	}

	private record PendingCall(
			String userPromptId, String agentName, String toolName, String inputJson, long startNanos) {}

	@Override
	public String getName() {
		return "tool_usage_recorder";
	}

	/** Never short-circuits the call (an empty result means "carry on"). */
	@Override
	public Maybe<Map<String, Object>> beforeToolCallback(
			BaseTool tool, Map<String, Object> args, ToolContext toolContext) {
		invocationIdByTurnKey.put(TurnKey.of(toolContext.userId(), toolContext.sessionId()), toolContext.invocationId());
		pendingByCallId.put(
				callId(toolContext),
				new PendingCall(
						toolContext.invocationId(), toolContext.agentName(), tool.name(), toJson(args), System.nanoTime()));
		return Maybe.empty();
	}

	/** Never overrides the result. */
	@Override
	public Maybe<Map<String, Object>> afterToolCallback(
			BaseTool tool, Map<String, Object> args, ToolContext toolContext, Map<String, Object> result) {
		recordCompletion(toolContext, "SUCCESS", toJson(result), null);
		return Maybe.empty();
	}

	/** Never suppresses the error: the turn still fails exactly as it would without recording. */
	@Override
	public Maybe<Map<String, Object>> onToolErrorCallback(
			BaseTool tool, Map<String, Object> args, ToolContext toolContext, Throwable error) {
		recordCompletion(toolContext, "ERROR", null, error.getMessage());
		return Maybe.empty();
	}

	private void recordCompletion(ToolContext toolContext, String status, String outputJson, String errorMessage) {
		String callId = callId(toolContext);
		PendingCall pending = pendingByCallId.remove(callId);
		if (pending == null) {
			log.warn("Tool completion fired with no matching pending call for callId='{}'", callId);
			return;
		}
		long latencyMs = (System.nanoTime() - pending.startNanos()) / 1_000_000;

		Map<String, Object> row = new HashMap<>();
		row.put("invocation_id", callId);
		row.put("user_prompt_id", pending.userPromptId());
		row.put("agent_name", pending.agentName());
		row.put("tool_name", pending.toolName());
		row.put("tool_input", pending.inputJson());
		if (outputJson != null) {
			row.put("tool_output", outputJson);
		}
		row.put("status", status);
		if (errorMessage != null) {
			row.put("error_message", errorMessage);
		}
		row.put("latency_ms", latencyMs);
		row.put("called_at", Instant.now().toString());

		rowsByUserPromptId.computeIfAbsent(pending.userPromptId(), id -> new ArrayList<>()).add(row);
	}

	/**
	 * Called once by {@code AgentChatService} after a turn finishes (success or failure) to flush
	 * that turn's accumulated tool-call rows to BigQuery in a single batched write, and forget this
	 * turn's in-memory state. A no-op (no BigQuery call at all) for turns that called no tools.
	 *
	 * @param userId with {@code sessionId}, not ADK's invocationId itself — see {@link
	 *     #invocationIdByTurnKey}
	 * @param sessionId the session the turn ran in
	 */
	public void finalizeTurn(String userId, String sessionId) {
		String invocationId = invocationIdByTurnKey.remove(TurnKey.of(userId, sessionId));
		List<Map<String, Object>> rows = invocationId == null ? null : rowsByUserPromptId.remove(invocationId);
		if (rows == null || rows.isEmpty()) {
			return;
		}

		try {
			InsertAllRequest.Builder request =
					InsertAllRequest.newBuilder(
							TableId.of(BigQueryNames.DATASET, BigQueryNames.TOOL_INVOCATIONS));
			rows.forEach(request::addRow);
			InsertAllResponse response = bigQuery.insertAll(request.build());
			if (response.hasErrors()) {
				log.error(
						"BigQuery insert errors for tool_invocations, invocationId='{}': {}",
						invocationId,
						response.getInsertErrors());
			} else {
				log.debug(
						"{} tool invocation row(s) written to BigQuery for invocationId='{}'", rows.size(), invocationId);
			}
		} catch (Exception e) {
			log.error("Failed to write tool_invocations rows for invocationId='{}'", invocationId, e);
		}
	}

	private static String callId(ToolContext toolContext) {
		return toolContext.functionCallId().orElseGet(() -> "generated-" + UUID.randomUUID());
	}

	private String toJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (Exception e) {
			log.error("Failed to serialize tool call payload to JSON", e);
			return "null";
		}
	}
}
