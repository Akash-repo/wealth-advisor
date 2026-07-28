package com.wealth.advisor.agent;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.tools.FunctionTool;
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
						  summed across every brokerage account they've uploaded a statement for. Always
						  use this (not searchPortfolio) for "how much of X do I hold" or "what are all my
						  holdings" — searchPortfolio can miss holdings split across multiple documents.
						- getStockData — current price and fundamentals (P/E, PEG, forward P/E, EPS growth,
						  free cash flow per share) for a stock the user mentions. Data may be up to a day
						  stale, which is fine to mention if relevant but not something to caveat every time.

						Write 2-3 sentences summarizing what you found for a colleague who will write the
						final recommendation; don't address the user directly yet.
						""")
				.tools(
						FunctionTool.create(stockLookupTool, "getStockData"),
						FunctionTool.create(portfolioSearchTool, "searchPortfolio"),
						FunctionTool.create(portfolioHoldingsTool, "getPortfolioHoldings"))
				.outputKey("portfolio_analysis")
				// Every real call to Gemini this agent makes gets recorded to BigQuery — see
				// AgentAuditRecorder's javadoc for how a whole turn gets correlated across agents.
				.beforeModelCallbackSync(agentAuditRecorder::beforeModel)
				.afterModelCallbackSync(agentAuditRecorder::afterModel)
				// Same idea, one level down: every real tool call this agent makes (searchPortfolio,
				// getStockData, getPortfolioHoldings) gets recorded to BigQuery too — see
				// ToolUsageRecorder's javadoc.
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
						You are a cautious financial advisor talking directly to the user. A colleague
						already analyzed this conversation and reported back:

						{portfolio_analysis}

						Using that analysis, answer the user's original question with a balanced,
						specific recommendation. Always include a brief risk disclaimer.
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
