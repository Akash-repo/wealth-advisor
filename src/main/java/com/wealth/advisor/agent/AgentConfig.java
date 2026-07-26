package com.wealth.advisor.agent;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.tools.FunctionTool;
import com.wealth.advisor.agent.tool.StockLookupTool;
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
	 * First stage: reads the conversation so far and (once RAG lands in a later milestone) the
	 * user's actual holdings, and produces a short analysis for the next agent to act on. For now,
	 * with no portfolio data wired up yet, it's told to say so plainly rather than invent numbers.
	 */
	@Bean
	public LlmAgent portfolioAnalysisAgent() {
		return LlmAgent.builder()
				.name("portfolio_analysis")
				.description(
						"Analyzes the user's holdings, risk exposure, and any stock mentioned in their"
								+ " question.")
				.model("gemini-2.5-flash")
				.instruction(
						"""
						You are a portfolio analyst. Portfolio document upload isn't wired up yet, so you
						have no real holdings data for this user — say so plainly rather than inventing
						numbers or a fake allocation.

						If the user mentions a specific stock, use the getStockPrice tool to look up its
						current price. Write 2-3 sentences summarizing what you found for a colleague who
						will write the final recommendation; don't address the user directly yet.
						""")
				.tools(FunctionTool.create(StockLookupTool.class, "getStockPrice"))
				.outputKey("portfolio_analysis")
				.build();
	}

	/**
	 * Second stage: turns the first agent's analysis (injected via the {@code
	 * {portfolio_analysis}} placeholder below) into the actual reply shown to the user.
	 */
	@Bean
	public LlmAgent stockRecommendationAgent() {
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
