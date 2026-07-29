package com.wealth.advisor;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.QueryParameterValue;
import com.google.cloud.bigquery.TableResult;
import com.wealth.advisor.eval.JudgeService;
import com.wealth.advisor.eval.PdfFixtures;
import com.wealth.advisor.web.dto.RegisterRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * The starting eval suite — five cases, chosen not to cover everything but to guard the specific,
 * concrete failure modes this app has actually hit or could silently hit. See
 * {@code ARCHITECTURE.md}'s eval-design discussion for the full rationale behind each case, the
 * grading-mechanics decisions (what's exact-match vs. LLM-judged and why), and
 * {@code src/test/resources/eval-fixtures/README.md} for the fixture's ground truth.
 *
 * <p><strong>System-level, not component-level, deliberately</strong>: every case here goes
 * through real HTTP ({@code TestRestTemplate}), real Gemini calls, and real ADK tool-argument
 * binding — not a direct Java call to a tool class. This is not just "more realistic" as a
 * preference; it's load-bearing. The {@code getPortfolioHoldings} crash this suite's first case
 * guards against lived inside ADK's own argument-validation layer — a test that called the tool
 * method directly in Java would never have exercised that code path, since Java doesn't care that
 * a parameter is null. Only a test that goes through the real model → real function-call decision
 * → real ADK binding could have caught it, and did, live, before this suite existed.
 *
 * <p><strong>Tagged {@code eval}, not run with the fast unit test suite</strong>: these cases make
 * real Gemini and FMP calls — slow (seconds, not milliseconds) and not free. Run explicitly (e.g.
 * {@code mvn test -Dgroups=eval}) rather than on every {@code mvn test}.
 *
 * <p><strong>Fixture</strong>: seeded once for the whole class ({@code @TestInstance(PER_CLASS)} +
 * {@code @BeforeAll}, not per test method — re-uploading 3 documents through real Gemini
 * extraction for every single case would be both slow and wasteful, and none of these cases mutate
 * the portfolio) against a freshly registered, uniquely-named eval user — never the shared {@code
 * demo} account, so assertions never drift out of sync with whatever manual testing happens to
 * have left in Firestore.
 *
 * <p><strong>BigQuery assertions poll, not query once</strong>: {@code tool_invocations}/{@code
 * agent_audit_log} are populated via streaming insert, which is not guaranteed instantly visible
 * to a query the moment the HTTP response returns. {@link #waitForLatestTurn} retries for up to 20
 * seconds rather than racing a single query against eventual consistency.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JudgeService.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("eval")
class PortfolioEvalTest {

	@Autowired private TestRestTemplate restTemplate;
	@Autowired private JudgeService judgeService;
	@Autowired private BigQuery bigQuery;

	private String evalUsername;
	private HttpHeaders sessionHeaders;

	@BeforeAll
	void seedEvalUserAndPortfolio() {
		evalUsername = "eval-" + UUID.randomUUID().toString().substring(0, 8);
		String password = "eval-password-not-a-real-account";

		ResponseEntity<Map> registerResponse =
				restTemplate.postForEntity(
						"/api/auth/register",
						new RegisterRequest(evalUsername, evalUsername + "@example.com", password),
						Map.class);
		assertThat(registerResponse.getStatusCode().is2xxSuccessful()).isTrue();

		sessionHeaders = login(evalUsername, password);

		uploadFixture("vanguard");
		uploadFixture("fidelity");
		uploadFixture("merrill");
	}

	/**
	 * Case 1 — no-ticker crash regression (P0).
	 *
	 * <p><strong>Guards</strong>: the ADK {@code @Schema(optional = true)} bug that already broke
	 * this exact flow live once — see {@code PortfolioHoldingsTool}'s javadoc for the full story.
	 *
	 * <p><strong>Assertions, all exact-match — no LLM judge needed</strong>: {@code
	 * getPortfolioHoldings} is called with {@code ticker} absent, its recorded status is {@code
	 * SUCCESS}, and the reply contains none of the error phrases that leaked to the user when this
	 * broke live.
	 *
	 * <p><strong>Deliberately not checked here</strong>: whether the share counts/totals are
	 * numerically correct — that's a different failure mode, not this one.
	 */
	@Test
	void getPortfolioHoldings_noTicker_doesNotCrash() {
		ChatTurn turn = chatAndWaitForAudit("What are all my holdings?");

		assertThat(turn.toolCalls())
				.as("getPortfolioHoldings should be called with no ticker, and must succeed")
				.anySatisfy(
						call -> {
							assertThat(call.toolName()).isEqualTo("getPortfolioHoldings");
							assertThat(call.toolInput()).doesNotContain("ticker");
							assertThat(call.status()).isEqualTo("SUCCESS");
						});

		assertThat(turn.reply())
				.isNotBlank()
				.satisfies(
						reply -> {
							String lower = reply.toLowerCase();
							assertThat(lower).doesNotContain("internal error");
							assertThat(lower).doesNotContain("technical issue");
							assertThat(lower).doesNotContain("unable to access");
							assertThat(lower).doesNotContain("encountered an error");
						});
		assertThat(turn.reply()).containsAnyOf("GOOGL", "JPM", "KO", "AAPL", "MSFT", "AMZN", "NVDA");
	}

	/**
	 * Case 2 — concentration warning (JPM).
	 *
	 * <p><strong>Ground truth</strong>: JPM is already the single largest position, ~43% of
	 * portfolio value.
	 *
	 * <p><strong>Layered assertions</strong>: trajectory (exact-match) → hand-off (substring
	 * against {@code input_to_agent2}) → final response (LLM judge, grounded with the 43% fact).
	 */
	@Test
	void concentrationWarning_jpm() {
		ChatTurn turn = chatAndWaitForAudit("Should I buy more JPM?");

		assertThat(turn.toolCalls()).extracting(ToolCall::toolName).contains("getPortfolioAllocation");
		assertThat(turn.inputToAgent2()).containsIgnoringCase("JPM");

		JudgeService.Verdict verdict =
				judgeService.judge(
						"Does this response cite the user's existing JPM position as a specific reason for"
								+ " caution about buying more, rather than just a generic risk disclaimer?",
						"The user's JPM position is approximately 43% of their total portfolio value — the"
								+ " single largest position they hold.",
						turn.reply());
		assertThat(verdict.answer()).as("judge reasoning: %s", verdict.reasoning()).isTrue();
	}

	/**
	 * Case 3 — diversification opportunity (JNJ). Paired deliberately with {@link
	 * #concentrationWarning_jpm()}: an agent that cautions against buying anything would pass Case
	 * 2 alone. Only a correct answer here too proves the reasoning tracks the real data rather than
	 * being reflexive hedging.
	 *
	 * <p><strong>Ground truth</strong>: zero healthcare exposure anywhere in the fixture; {@code
	 * JNJ} confirmed live against FMP's free tier before this case was written (200, not 402).
	 */
	@Test
	void diversificationOpportunity_jnj() {
		ChatTurn turn = chatAndWaitForAudit("Should I buy some Johnson & Johnson?");

		assertThat(turn.toolCalls()).extracting(ToolCall::toolName).contains("getPortfolioAllocation");

		JudgeService.Verdict verdict =
				judgeService.judge(
						"Does this response frame buying this stock as improving the user's diversification"
								+ " (a genuinely different sector for them), rather than warning against it as"
								+ " concentration risk?",
						"The user currently has zero healthcare-sector exposure across their entire portfolio"
								+ " (holdings: JPM, GOOGL, AAPL, KO, AMZN, MSFT, NVDA — financials, tech, and"
								+ " consumer staples only).",
						turn.reply());
		assertThat(verdict.answer()).as("judge reasoning: %s", verdict.reasoning()).isTrue();
	}

	/**
	 * Case 4 — portfolio risk analysis (assessment only, no recommendation expected).
	 *
	 * <p>Tested separately from Case 5 so a future failure pinpoints whether the underlying
	 * analysis is wrong, or a correct analysis is being acted on wrong.
	 *
	 * <p><strong>The hallucination check is the most important assertion here</strong>, and is
	 * itself a judge call (inverted: the verdict must be {@code false}) — the response must not
	 * claim a concentration that isn't real.
	 */
	@Test
	void portfolioRiskAnalysis() {
		ChatTurn turn =
				chatAndWaitForAudit("Analyze my portfolio's risk — where am I concentrated, and what am I missing?");

		assertThat(turn.toolCalls()).extracting(ToolCall::toolName).contains("getPortfolioAllocation");
		assertThat(turn.reply()).containsIgnoringCase("JPM");

		JudgeService.Verdict hallucinationVerdict =
				judgeService.judge(
						"Does this response falsely claim the user has significant exposure to healthcare,"
								+ " fixed income/bonds, or international investments?",
						"The user has zero exposure to healthcare, fixed income/bonds, and international"
								+ " investments — their entire portfolio is JPM, GOOGL, AAPL, KO, AMZN, MSFT, NVDA.",
						turn.reply());
		assertThat(hallucinationVerdict.answer())
				.as("hallucination check — judge reasoning: %s", hallucinationVerdict.reasoning())
				.isFalse();

		JudgeService.Verdict verdict =
				judgeService.judge(
						"Does this response correctly identify both (a) JPM as the largest single position at"
								+ " roughly 43% of the portfolio, and (b) a second concentration of roughly"
								+ " comparable size among the user's technology-adjacent holdings (some grouping"
								+ " of GOOGL, AAPL, MSFT, NVDA, and/or AMZN)? The exact tickers grouped together"
								+ " and the exact percentage don't need to match precisely — sector"
								+ " classification of AMZN is genuinely ambiguous (commonly bucketed with 'big"
								+ " tech' as well as with e-commerce/consumer) — what matters is that a second,"
								+ " comparably-sized concentration is correctly identified and named, not"
								+ " ignored.",
						"JPM is ~43% of portfolio value (largest single position). GOOGL+AAPL+MSFT+NVDA are"
								+ " ~43% of portfolio value; including AMZN as well brings that group to ~48%."
								+ " Either grouping represents a legitimate second, tech-sector-adjacent"
								+ " concentration of comparable size to the JPM position.",
						turn.reply());
		assertThat(verdict.answer()).as("judge reasoning: %s", verdict.reasoning()).isTrue();
	}

	/**
	 * Case 5 — actionable trim/add suggestion, built on top of Case 4's analysis.
	 *
	 * <p><strong>The hard negative is the highest-signal single assertion in this whole suite</strong>:
	 * exact-match, no judge — the response must never recommend adding to JPM or another
	 * already-large tech position, which would directly contradict its own analysis.
	 */
	@Test
	void trimAddSuggestion() {
		ChatTurn turn =
				chatAndWaitForAudit("Given my current holdings, what should I trim, and what should I consider adding?");

		assertThat(turn.toolCalls()).extracting(ToolCall::toolName).contains("getPortfolioAllocation");

		JudgeService.Verdict wrongDirectionVerdict =
				judgeService.judge(
						"Does this response recommend adding to or buying more of JPM, GOOGL, AAPL, MSFT, or"
								+ " NVDA — the user's already-largest and already-concentrated positions?",
						"JPM (~43%) and the combined tech names GOOGL/AAPL/MSFT/NVDA (~43%) are the user's two"
								+ " existing concentrations — adding to any of these would be self-contradictory"
								+ " advice given the user is asking what to trim.",
						turn.reply());
		assertThat(wrongDirectionVerdict.answer())
				.as("hard-negative check — judge reasoning: %s", wrongDirectionVerdict.reasoning())
				.isFalse();

		JudgeService.Verdict trimVerdict =
				judgeService.judge(
						"Does this response specifically name JPM (not a vague \"diversify more\") as a"
								+ " position to trim or cap?",
						"JPM is the user's largest position at ~43% of portfolio value.",
						turn.reply());
		assertThat(trimVerdict.answer()).as("judge reasoning: %s", trimVerdict.reasoning()).isTrue();

		JudgeService.Verdict addVerdict =
				judgeService.judge(
						"Does this response suggest adding exposure to an area the user currently lacks —"
								+ " healthcare, fixed income/bonds, or international — whether or not it names a"
								+ " specific ticker?",
						"The user has zero exposure to healthcare, fixed income/bonds, and international"
								+ " investments.",
						turn.reply());
		assertThat(addVerdict.answer()).as("judge reasoning: %s", addVerdict.reasoning()).isTrue();
	}

	// --- shared HTTP/BigQuery plumbing ---

	private HttpHeaders login(String username, String password) {
		HttpHeaders formHeaders = new HttpHeaders();
		formHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("username", username);
		form.add("password", password);

		// Spring Security's form login responds 302 with Set-Cookie on success. TestRestTemplate
		// follows redirects by default, which loses that header on the object postForEntity
		// returns — withRedirects(DONT_FOLLOW) gets the raw 302 response back directly.
		ResponseEntity<Void> response =
				restTemplate
						.withRedirects(HttpRedirects.DONT_FOLLOW)
						.postForEntity("/api/auth/login", new HttpEntity<>(form, formHeaders), Void.class);
		String sessionCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
		assertThat(sessionCookie).as("login must set a session cookie").isNotNull();

		HttpHeaders headers = new HttpHeaders();
		headers.add(HttpHeaders.COOKIE, sessionCookie);
		return headers;
	}

	private void uploadFixture(String fixtureName) {
		MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
		body.add(
				"file",
				new ByteArrayResource(PdfFixtures.load(fixtureName)) {
					@Override
					public String getFilename() {
						return fixtureName + ".pdf";
					}
				});
		body.add("documentType", "brokerage");

		HttpHeaders headers = new HttpHeaders();
		headers.addAll(sessionHeaders);
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);

		ResponseEntity<Map> response =
				restTemplate.postForEntity("/api/portfolio/upload", new HttpEntity<>(body, headers), Map.class);
		assertThat(response.getStatusCode().is2xxSuccessful()).as("uploading fixture '%s'", fixtureName).isTrue();
	}

	private record ToolCall(String agentName, String toolName, String toolInput, String status) {}

	private record ChatTurn(String reply, String userPromptId, String inputToAgent2, List<ToolCall> toolCalls) {}

	private ChatTurn chatAndWaitForAudit(String message) {
		Instant before = Instant.now();

		HttpHeaders headers = new HttpHeaders();
		headers.addAll(sessionHeaders);
		headers.setContentType(MediaType.APPLICATION_JSON);
		ResponseEntity<Map> chatResponse =
				restTemplate.postForEntity(
						"/api/chat", new HttpEntity<>(Map.of("message", message), headers), Map.class);
		assertThat(chatResponse.getStatusCode().is2xxSuccessful()).isTrue();
		String reply = (String) chatResponse.getBody().get("reply");

		return waitForLatestTurn(evalUsername, before, reply);
	}

	private ChatTurn waitForLatestTurn(String userId, Instant after, String reply) {
		Duration timeout = Duration.ofSeconds(20);
		Instant deadline = Instant.now().plus(timeout);

		while (Instant.now().isBefore(deadline)) {
			Map<String, Object> turnRow = latestAuditRow(userId, after);
			if (turnRow != null) {
				String userPromptId = (String) turnRow.get("user_prompt_id");
				String inputToAgent2 = (String) turnRow.get("input_to_agent2");
				List<ToolCall> toolCalls = toolInvocationsFor(userPromptId);
				return new ChatTurn(reply, userPromptId, inputToAgent2, toolCalls);
			}
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while waiting for BigQuery audit row", e);
			}
		}
		throw new IllegalStateException(
				"No agent_audit_log row for userId='" + userId + "' appeared within " + timeout);
	}

	private Map<String, Object> latestAuditRow(String userId, Instant after) {
		try {
			QueryJobConfiguration config =
					QueryJobConfiguration.newBuilder(
									"""
									SELECT user_prompt_id, input_to_agent2, created_at
									FROM `wealth_agent.agent_audit_log`
									WHERE user_id = @userId AND created_at >= @after
									ORDER BY created_at DESC
									LIMIT 1
									""")
							.addNamedParameter("userId", QueryParameterValue.string(userId))
							.addNamedParameter(
									"after",
									QueryParameterValue.timestamp(
											after.getEpochSecond() * 1_000_000L + after.getNano() / 1_000L))
							.build();
			TableResult result = bigQuery.query(config);
			for (FieldValueList row : result.iterateAll()) {
				return Map.of(
						"user_prompt_id", row.get("user_prompt_id").getStringValue(),
						"input_to_agent2", row.get("input_to_agent2").isNull() ? "" : row.get("input_to_agent2").getStringValue());
			}
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("BigQuery query interrupted", e);
		}
	}

	private List<ToolCall> toolInvocationsFor(String userPromptId) {
		try {
			QueryJobConfiguration config =
					QueryJobConfiguration.newBuilder(
									"""
									SELECT agent_name, tool_name, tool_input, status
									FROM `wealth_agent.tool_invocations`
									WHERE user_prompt_id = @userPromptId
									ORDER BY called_at
									""")
							.addNamedParameter("userPromptId", QueryParameterValue.string(userPromptId))
							.build();
			TableResult result = bigQuery.query(config);
			List<ToolCall> calls = new ArrayList<>();
			for (FieldValueList row : result.iterateAll()) {
				calls.add(
						new ToolCall(
								row.get("agent_name").getStringValue(),
								row.get("tool_name").getStringValue(),
								row.get("tool_input").getStringValue(),
								row.get("status").getStringValue()));
			}
			return calls;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("BigQuery query interrupted", e);
		}
	}
}
