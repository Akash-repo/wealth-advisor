package com.wealth.advisor;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

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
 * {@code mvn test -Dgroups=eval}) rather than on every {@code mvn test}, same reasoning fast unit
 * tests and slow integration tests are usually kept separate.
 *
 * <p><strong>Fixture</strong>: the standard 3-account portfolio from
 * {@code src/test/resources/eval-fixtures/} (Vanguard/Fidelity/Merrill, converted from plain text
 * to real PDF bytes via PDFBox at setup time, uploaded through the real
 * {@code /api/portfolio/upload} endpoint) — JPM ~43% of value (the largest position), zero
 * healthcare exposure, tech-sector concentration comparable in size to JPM alone. Seeded fresh
 * against an isolated eval user per run, not the shared {@code demo} account, so eval assertions
 * never drift out of sync with whatever manual testing happens to have left in Firestore.
 *
 * <p>All five cases are {@link Disabled} for now — {@code JudgeService} (the LLM-as-judge helper)
 * and the PDFBox fixture-conversion helper don't exist yet. Case bodies land once those do; the
 * javadoc on each is the agreed spec to implement against, not a placeholder.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("eval")
class PortfolioEvalTest {

	/**
	 * Case 1 — no-ticker crash regression (P0).
	 *
	 * <p><strong>Guards</strong>: the ADK {@code @Schema(optional = true)} bug that already broke
	 * this exact flow live once — see {@code PortfolioHoldingsTool}'s javadoc for the full story.
	 *
	 * <p><strong>Input</strong>: {@code "What are all my holdings?"} — deliberately narrow, not
	 * "analyze and diversify" (which could legitimately route to {@code getPortfolioAllocation}
	 * instead now that it exists, making it a weaker trigger for this specific regression).
	 *
	 * <p><strong>Assertions, all exact-match — no LLM judge needed</strong>:
	 * <ul>
	 *   <li>{@code tool_invocations} shows {@code getPortfolioHoldings} called with {@code ticker}
	 *       absent/blank, and {@code status = SUCCESS}, not {@code ERROR}.
	 *   <li>Response text does not contain any of: "internal error", "technical issue", "unable to
	 *       access", "encountered an error".
	 *   <li>Response is non-empty and mentions at least one real ticker from the fixture.
	 * </ul>
	 *
	 * <p><strong>Deliberately not checked here</strong>: whether the share counts/totals are
	 * numerically correct — that's a different failure mode, owned by a separate aggregation-
	 * correctness case, not this one. This case is surgical to "did the no-ticker call succeed at
	 * all," so a future failure here points at exactly one thing.
	 */
	@Test
	@Disabled("Pending JudgeService + PDFBox fixture helper — spec above is agreed, not yet built")
	void getPortfolioHoldings_noTicker_doesNotCrash() {}

	/**
	 * Case 2 — concentration warning (JPM).
	 *
	 * <p><strong>Guards</strong>: that portfolio context (not just stock fundamentals in
	 * isolation) actually reaches the final recommendation — the three-link chain documented on
	 * {@code AgentConfig}: stage 1 calls the right tool, stage 1's summary conveys the finding,
	 * stage 2's final answer uses it.
	 *
	 * <p><strong>Input</strong>: {@code "Should I buy more JPM?"}
	 *
	 * <p><strong>Ground truth</strong> (see eval-fixtures README): JPM is already the single
	 * largest position, ~43% of portfolio value.
	 *
	 * <p><strong>Assertions, layered to pinpoint where a future failure lives</strong>:
	 * <ul>
	 *   <li><em>Trajectory</em> (exact-match): {@code portfolio_analysis} calls
	 *       {@code getPortfolioAllocation} and {@code getStockData(JPM)}.
	 *   <li><em>Hand-off</em> (exact-match/substring against
	 *       {@code agent_audit_log.input_to_agent2}): stage 1's summary mentions JPM's existing
	 *       weight/concentration.
	 *   <li><em>Final response</em> (LLM judge, grounded with the 43% fact): does the response cite
	 *       the user's existing JPM position as a specific reason for caution — not a generic risk
	 *       disclaimer, tied to JPM.
	 * </ul>
	 */
	@Test
	@Disabled("Pending JudgeService + PDFBox fixture helper — spec above is agreed, not yet built")
	void concentrationWarning_jpm() {}

	/**
	 * Case 3 — diversification opportunity (JNJ). Paired deliberately with {@link
	 * #concentrationWarning_jpm()}.
	 *
	 * <p><strong>Guards</strong>: real allocation-aware reasoning, not reflexive hedging. An agent
	 * that cautions against buying <em>anything</em> would pass Case 2 alone — pairing it with a
	 * case where the correct answer points the opposite direction is what actually proves the
	 * agent's caution tracks the real data rather than being a canned response. Directly connected
	 * to the hedging bug fixed earlier this session — this case exists so that fix can't silently
	 * regress into a different flavor of the same shortcut.
	 *
	 * <p><strong>Input</strong>: {@code "Should I buy some Johnson & Johnson?"}
	 *
	 * <p><strong>Ground truth</strong>: zero healthcare exposure anywhere in the fixture; {@code
	 * JNJ} confirmed live against FMP's free tier (200, not 402 — verified before this case was
	 * written, see eval-fixtures README for which tickers are safe).
	 *
	 * <p><strong>Assertions</strong>: same trajectory/hand-off shape as Case 2, swapping the
	 * ticker. Final response (LLM judge, grounded with the "zero healthcare exposure" fact): does
	 * the response frame this purchase as <em>improving</em> diversification rather than adding
	 * concentration risk — the opposite conclusion from Case 2, from the same reasoning process.
	 */
	@Test
	@Disabled("Pending JudgeService + PDFBox fixture helper — spec above is agreed, not yet built")
	void diversificationOpportunity_jnj() {}

	/**
	 * Case 4 — portfolio risk analysis (assessment only, no recommendation expected).
	 *
	 * <p><strong>Guards</strong>: the "analyze" half of "analyze, then recommend" — tested
	 * separately from Case 5's "recommend" half so a failure pinpoints whether the underlying
	 * analysis is wrong, or a correct analysis is being acted on wrong.
	 *
	 * <p><strong>Input</strong>: {@code "Analyze my portfolio's risk — where am I concentrated,
	 * and what am I missing?"}
	 *
	 * <p><strong>Assertions</strong>:
	 * <ul>
	 *   <li><em>Trajectory</em> (exact-match): {@code getPortfolioAllocation} called.
	 *   <li>Names JPM as the largest position with a number near 43% (exact-match/substring).
	 *   <li>Names at least one real gap — healthcare, fixed income, or international (exact-match
	 *       on any one, not all three — a stricter all-three requirement would be brittle).
	 *   <li><strong>Hallucination check (exact-match, not judge)</strong>: response must not claim
	 *       a concentration that isn't real — a targeted negative-keyword check against the known
	 *       fixture (e.g. must never say "overweight healthcare"). The single most important check
	 *       in this case, and one that doesn't need a judge to catch reliably.
	 *   <li>Correctly characterizes tech-sector concentration as a pattern across GOOGL/AAPL/MSFT/
	 *       NVDA together (LLM judge — this nuance isn't cleanly substring-matchable).
	 * </ul>
	 */
	@Test
	@Disabled("Pending JudgeService + PDFBox fixture helper — spec above is agreed, not yet built")
	void portfolioRiskAnalysis() {}

	/**
	 * Case 5 — actionable trim/add suggestion, built on top of Case 4's analysis.
	 *
	 * <p><strong>Guards</strong>: the "recommend" half — does the system correctly <em>act</em> on
	 * a correct analysis, not just restate it.
	 *
	 * <p><strong>Input</strong>: {@code "Given my current holdings, what should I trim, and what
	 * should I consider adding?"}
	 *
	 * <p><strong>Assertions</strong>:
	 * <ul>
	 *   <li><em>Trajectory</em> (exact-match): reuses {@code getPortfolioAllocation}.
	 *   <li><strong>Hard negative (exact-match, the highest-signal single assertion in this whole
	 *       suite)</strong>: response must never recommend adding to JPM or another already-large
	 *       tech position — would directly contradict its own analysis. Cheap keyword/logic check,
	 *       no judge required, and the assertion most likely to catch a real contradiction if one
	 *       ever exists.
	 *   <li>Specifically names JPM (not a vague "diversify more") as the trim candidate (LLM
	 *       judge).
	 *   <li>Suggests an underrepresented area to add — healthcare/fixed income/international (LLM
	 *       judge). Deliberately does <strong>not</strong> require a specific named ticker for the
	 *       "add" suggestion; a sector/asset-class-level answer is legitimate and arguably more
	 *       appropriately cautious than manufacturing a specific stock pick.
	 * </ul>
	 */
	@Test
	@Disabled("Pending JudgeService + PDFBox fixture helper — spec above is agreed, not yet built")
	void trimAddSuggestion() {}
}
