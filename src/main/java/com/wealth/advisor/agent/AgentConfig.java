package com.wealth.advisor.agent;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.apps.App;
import com.google.adk.memory.FirestoreMemoryService;
import com.google.adk.models.Gemini;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.FirestoreSessionService;
import com.google.adk.summarizer.EventsCompactionConfig;
import com.google.adk.summarizer.LlmEventSummarizer;
import com.google.adk.tools.FunctionTool;
import com.google.adk.tools.LoadMemoryTool;
import com.google.cloud.firestore.Firestore;
import com.google.genai.Client;
import com.wealth.advisor.agent.tool.PortfolioAllocationTool;
import com.wealth.advisor.config.AdvisorProperties;
import com.wealth.advisor.agent.tool.PortfolioHoldingsTool;
import com.wealth.advisor.agent.tool.PortfolioSearchTool;
import com.wealth.advisor.agent.tool.StockLookupTool;
import com.wealth.advisor.audit.AgentAuditRecorder;
import com.wealth.advisor.audit.ToolUsageRecorder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Defines the agents that make up this app's ADK pipeline.
 *
 * <p>An {@link LlmAgent} is ADK's wrapper around a single LLM "persona": a model, a system
 * instruction, and the tools it's allowed to call. It is stateless by itself — the actual
 * conversation state (message history, per-user session) is handled separately by a
 * {@code Runner} + {@code SessionService} pair, see {@link AgentChatService}.
 *
 * <p>This app chains two agents with {@link SequentialAgent}, mirroring the BRD's two-stage
 * pipeline (analyze, then recommend). A {@code SequentialAgent} just runs its sub-agents one
 * after another against the same session — it doesn't pass data between them directly. The
 * hand-off happens through {@code outputKey}/{@code {placeholder}} instead: {@code
 * portfolioAnalysisAgent} writes its final text into session state under the key
 * {@code portfolio_analysis} (via {@code .outputKey(...)}), and {@code stockRecommendationAgent}'s
 * instruction references that value with {@code {portfolio_analysis}}, which ADK substitutes
 * before calling the model.
 */
@Configuration
public class AgentConfig {

	/**
	 * First stage: reads the conversation so far and the user's actual holdings (via {@link
	 * PortfolioSearchTool}'s RAG search over their uploaded PDF), and produces a short analysis for
	 * the next agent to act on.
	 */
	@Bean
	public LlmAgent portfolioAnalysisAgent(
			PortfolioSearchTool portfolioSearchTool,
			PortfolioHoldingsTool portfolioHoldingsTool,
			PortfolioAllocationTool portfolioAllocationTool,
			StockLookupTool stockLookupTool,
			AgentAuditRecorder agentAuditRecorder,
			ToolUsageRecorder toolUsageRecorder,
			AdvisorProperties properties) {
		return LlmAgent.builder()
				.name("portfolio_analysis")
				.description(
						"Analyzes the user's holdings, risk exposure, and any stock mentioned in their"
								+ " question.")
				.model(properties.model().chat())
				.instruction(
						"""
						You are a portfolio analyst. Use these tools as needed:

						- searchPortfolio — qualitative context from the user's uploaded documents (risk
						  notes, sector allocation commentary, cost-basis narrative). If it comes back with
						  found=false, no portfolio has been uploaded yet, so say that plainly instead of
						  inventing numbers.
						- getPortfolioHoldings — the exact number of shares of a ticker the user holds,
						  summed across every brokerage account they've uploaded a statement for, plus cost
						  basis. Use this for "how many shares of X do I hold" or "what's my cost basis on
						  X" — it does not know dollar value or how big a position is relative to the rest
						  of the portfolio.
						- getPortfolioAllocation — each position's current dollar value and % weight of the
						  total, ranked largest to smallest. Always use this (not getPortfolioHoldings) for
						  concentration/risk questions: "what's my biggest position", "how much of my
						  portfolio is X", "am I overexposed to tech", "top N holdings". Share count alone
						  cannot answer these — value and weight can.
						- getStockData — current price and fundamentals (P/E, PEG, forward P/E, EPS growth,
						  free cash flow per share) for a stock the user mentions. Data may be up to a day
						  stale, which is fine to mention if relevant but not something to caveat every time.
						- loadMemory — searches this user's past conversations (including other chats) by
						  keyword. Use it only when the user refers to something they said or decided
						  earlier ("like I told you before", "my goal from last time", "what did we
						  conclude about X"), or states a personal preference or goal you'd otherwise have
						  to guess. Pass a few distinctive keywords, not a full sentence. Its results can
						  include this conversation and your own earlier replies — treat them as context,
						  not as authoritative portfolio numbers; the portfolio tools above remain the
						  source of truth for holdings and prices.

						Never ask the user whether you should look something up, and never answer from
						memory when a tool could give you the real number — just call the tool. In
						particular, any question about buying, adding to, trimming, or evaluating a specific
						stock (e.g. "should I buy more X") always requires BOTH getPortfolioAllocation (to
						see whether the user already holds it or something in the same sector, and how
						concentrated they already are) AND getStockData (for that ticker's current price and
						fundamentals) before you write your summary — regardless of whether the user
						mentioned their portfolio explicitly. A recommendation written without checking
						current allocation first is incomplete.

						getPortfolioAllocation only returns weights per ticker, not per sector — for any
						broad risk/concentration question ("where am I concentrated", "what am I missing"),
						you must do the sector grouping yourself: use your own knowledge of what sector each
						ticker belongs to, add up the weights of every position in the same sector, and call
						out that combined sector concentration by name if it's large — don't stop at
						reporting the single largest position and treating a same-sized group of same-sector
						positions as unrelated numbers.

						Write 2-3 sentences summarizing what you found for a colleague who will write the
						final recommendation; don't address the user directly yet.
						""")
				.tools(
						FunctionTool.create(stockLookupTool, "getStockData"),
						FunctionTool.create(portfolioSearchTool, "searchPortfolio"),
						FunctionTool.create(portfolioHoldingsTool, "getPortfolioHoldings"),
						FunctionTool.create(portfolioAllocationTool, "getPortfolioAllocation"),
						// Long-term memory: keyword search over this user's events across ALL their
						// sessions, backed by the FirestoreMemoryService wired into the Runner below.
						new LoadMemoryTool())
				.outputKey("portfolio_analysis")
				// Every real call to Gemini this agent makes gets recorded to BigQuery — see
				// AgentAuditRecorder's javadoc for how a whole turn gets correlated across agents.
				.beforeModelCallbackSync(agentAuditRecorder::beforeModel)
				.afterModelCallbackSync(agentAuditRecorder::afterModel)
				// Same idea, one level down: every real tool call this agent makes gets recorded to
				// BigQuery too — see ToolUsageRecorder's javadoc.
				.beforeToolCallbackSync(toolUsageRecorder::beforeTool)
				.afterToolCallbackSync(toolUsageRecorder::afterTool)
				.onToolErrorCallbackSync(toolUsageRecorder::onToolError)
				.build();
	}

	/**
	 * Second stage: turns the first agent's analysis (injected via the {@code
	 * {portfolio_analysis}} placeholder below) into the actual reply shown to the user.
	 */
	@Bean
	public LlmAgent stockRecommendationAgent(
			AgentAuditRecorder agentAuditRecorder,
			ToolUsageRecorder toolUsageRecorder,
			AdvisorProperties properties) {
		return LlmAgent.builder()
				.name("stock_recommendation")
				.description("Turns a portfolio analysis into a balanced recommendation with risk warnings.")
				.model(properties.model().chat())
				.instruction(
						"""
						You are a direct, opinionated financial analyst talking to the user. The user
						understands this is an educational analysis tool — nothing you say executes a
						trade or is binding on them, so there is no reason to withhold a real opinion or
						redirect them elsewhere instead of answering. A colleague already analyzed this
						conversation and reported back:

						{portfolio_analysis}

						Using that analysis, give the user a specific, direct answer. If asked something
						like "should I buy X" or "X or Y, which is better," state your actual assessment —
						which one the data favors and why — rather than declining to choose. Do not deflect
						with boilerplate like "I can't give investment advice" or "consult a financial
						professional" in place of an answer; give your reasoning and your actual take, then
						close with one brief, one-line risk disclaimer. If data for something the user asked
						about is missing, say so plainly and answer with whatever you do have rather than
						declining to engage at all.
						""")
				.beforeModelCallbackSync(agentAuditRecorder::beforeModel)
				.afterModelCallbackSync(agentAuditRecorder::afterModel)
				// No tools on this agent today, but wired for when it (or a future agent) gets one —
				// costs nothing and means nobody has to remember to add this later.
				.beforeToolCallbackSync(toolUsageRecorder::beforeTool)
				.afterToolCallbackSync(toolUsageRecorder::afterTool)
				.onToolErrorCallbackSync(toolUsageRecorder::onToolError)
				.build();
	}

	/** Chains the two agents above into the single root agent {@link AgentChatService} runs. */
	@Bean
	public SequentialAgent wealthAdvisorPipeline(
			LlmAgent portfolioAnalysisAgent, LlmAgent stockRecommendationAgent) {
		return SequentialAgent.builder()
				.name("wealth_advisor_pipeline")
				.description("Portfolio analysis followed by a balanced stock recommendation.")
				.subAgents(portfolioAnalysisAgent, stockRecommendationAgent)
				.build();
	}

	/**
	 * {@link AgentChatService} needs each stage's name (to attribute BigQuery audit rows and to
	 * find agent 1's hand-off text among a turn's events), but shouldn't have to know that stage 1
	 * is "whichever agent is at index 0" of {@code wealthAdvisorPipeline}'s sub-agent list — that's
	 * an implementation detail of how the pipeline happens to be built, not something a consumer
	 * should reverse-engineer. Exposing each name as its own bean, wired directly off the named
	 * agent bean it actually belongs to, makes the association explicit instead of positional.
	 */
	@Bean
	public String agent1Name(LlmAgent portfolioAnalysisAgent) {
		return portfolioAnalysisAgent.name();
	}

	@Bean
	public String agent2Name(LlmAgent stockRecommendationAgent) {
		return stockRecommendationAgent.name();
	}

	/**
	 * Every turn's context is rebuilt from scratch by resending the <em>entire</em> session event
	 * history (verified directly from ADK's own {@code Contents} request processor, not assumed) —
	 * and this app's session never resets (one continuous conversation per user, forever; see
	 * {@link AgentChatService}). Left alone, that means unbounded per-turn cost/latency growth and,
	 * eventually, hitting {@code gemini-2.5-flash}'s 1,048,576-token context window. See {@code
	 * Optimization.md} P1-1.
	 *
	 * <p>ADK ships a fix for exactly this: tail-retention event compaction. Once a turn's prompt
	 * token count exceeds {@code advisor.compaction.token-threshold}, everything older than the most recent
	 * {@code advisor.compaction.event-retention-size} events gets summarized by an LLM call into one rolling
	 * synthetic event, and <em>that</em> gets resent instead of the raw history it replaces — see
	 * {@code com.google.adk.summarizer.TailRetentionEventCompactor} for the exact algorithm (a rolling
	 * summary: each new compaction re-covers everything the previous one did, plus what's new since).
	 *
	 * <p><strong>Gotcha found reading ADK's own source, not documented anywhere</strong>: if no
	 * {@code summarizer} is supplied, {@code Runner} tries to build a default one from the root
	 * agent's own model — but that only works when the root agent is an {@link LlmAgent} directly.
	 * This app's root agent is {@link SequentialAgent} (a {@code SequentialAgent} has no model of its
	 * own), so relying on that default throws {@code IllegalArgumentException: "No BaseLlm model
	 * available for event compaction"} at startup. Worked around by constructing the summarizer's
	 * {@code Gemini} model explicitly, reusing the same {@code genAiClient} bean already used
	 * elsewhere for embeddings — no new credential, same ADC path as everything else.
	 *
	 * <p>Threshold/retention values are a reasonable starting point, not a measured optimum — 50,000
	 * tokens is generous headroom below the 1M context window while still well above a typical
	 * single turn's usage, and retaining the last 20 raw events keeps roughly the last several turns
	 * fully verbatim (recency detail matters more than compression there) while summarizing anything
	 * older. Revisit both against real {@code agent_audit_log.agent1_prompt_tokens}/
	 * {@code agent2_prompt_tokens} distributions once there's real usage to tune against (see
	 * {@code Optimization.md} P2-1).
	 */
	/**
	 * Ties {@link #wealthAdvisorPipeline} to a Firestore-backed session store and to compaction
	 * (see the constants above) — the object {@link AgentChatService} actually calls per turn. Kept
	 * here rather than built inline in {@code AgentChatService}'s constructor so every ADK object
	 * this app builds (both agents, the pipeline, and the runner) is assembled in one place, the same
	 * way every other Spring bean in this app is.
	 *
	 * <p>Building via {@code App} rather than {@code Runner.builder().agent(...)} directly is what
	 * compaction requires — {@code EventsCompactionConfig} only has a home on {@code App}, not on
	 * {@code Runner.Builder} itself (verified in ADK's source: {@code Runner.Builder.build()} throws
	 * if both {@code .agent(...)} and {@code .app(...)} are set, so this app had to switch to the
	 * {@code .app(...)} path entirely rather than adding compaction alongside the old one).
	 */
	@Bean
	public Runner runner(
			SequentialAgent wealthAdvisorPipeline,
			Firestore firestore,
			Client genAiClient,
			AdvisorProperties properties) {
		App app =
				App.builder()
						.name(wealthAdvisorPipeline.name())
						.rootAgent(wealthAdvisorPipeline)
						.eventsCompactionConfig(
								EventsCompactionConfig.builder()
										.summarizer(new LlmEventSummarizer(new Gemini(properties.model().chat(), genAiClient)))
										.tokenThreshold(properties.compaction().tokenThreshold())
										.eventRetentionSize(properties.compaction().eventRetentionSize())
										.build())
						.build();
		return Runner.builder()
				.app(app)
				// Short-term memory: one session = one conversation's events + state.
				.sessionService(new FirestoreSessionService(firestore))
				// Long-term memory: searches events across all of a user's sessions. Its
				// addSessionToMemory is a no-op — FirestoreSessionService already indexes keywords on
				// every appended event, so there's no separate "ingest" step. Wrapped in
				// GuardedMemoryService because the raw one throws on backend errors (failing the whole
				// turn) and returns unbounded results. See ARCHITECTURE.md.
				.memoryService(new GuardedMemoryService(new FirestoreMemoryService(firestore), properties.memory()))
				.build();
	}
}
