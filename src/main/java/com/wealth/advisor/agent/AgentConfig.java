package com.wealth.advisor.agent;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.tools.FunctionTool;
import com.wealth.advisor.agent.tool.PortfolioAllocationTool;
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
			ToolUsageRecorder toolUsageRecorder) {
		return LlmAgent.builder()
				.name("portfolio_analysis")
				.description(
						"Analyzes the user's holdings, risk exposure, and any stock mentioned in their"
								+ " question.")
				.model("gemini-2.5-flash")
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
						FunctionTool.create(portfolioAllocationTool, "getPortfolioAllocation"))
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
			AgentAuditRecorder agentAuditRecorder, ToolUsageRecorder toolUsageRecorder) {
		return LlmAgent.builder()
				.name("stock_recommendation")
				.description("Turns a portfolio analysis into a balanced recommendation with risk warnings.")
				.model("gemini-2.5-flash")
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
}
