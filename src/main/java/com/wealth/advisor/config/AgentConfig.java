package com.wealth.advisor.config;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.tools.FunctionTool;
import com.google.adk.tools.LoadMemoryTool;
import com.wealth.advisor.agent.AgentChatService;
import com.wealth.advisor.agent.prompt.PromptLoader;
import com.wealth.advisor.agent.tool.PortfolioAllocationTool;
import com.wealth.advisor.agent.tool.PortfolioHoldingsTool;
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
	 * First stage: reads the conversation so far and the user's actual holdings and market data (via
	 * its tools), and produces a short analysis for the next agent to act on.
	 */
	@Bean
	public LlmAgent portfolioAnalysisAgent(
			PortfolioHoldingsTool portfolioHoldingsTool,
			PortfolioAllocationTool portfolioAllocationTool,
			StockLookupTool stockLookupTool,
			AgentAuditRecorder agentAuditRecorder,
			ToolUsageRecorder toolUsageRecorder,
			AdvisorProperties properties,
			PromptLoader promptLoader) {
		return LlmAgent.builder()
				.name("portfolio_analysis")
				.description(
						"Analyzes the user's holdings, risk exposure, and any stock mentioned in their"
								+ " question.")
				.model(properties.model().chat())
				.instruction(promptLoader.load("portfolio_analysis").text())
				.tools(
						FunctionTool.create(stockLookupTool, "getStockData"),
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
			AdvisorProperties properties,
			PromptLoader promptLoader) {
		return LlmAgent.builder()
				.name("stock_recommendation")
				.description("Turns a portfolio analysis into a balanced recommendation with risk warnings.")
				.model(properties.model().chat())
				.instruction(promptLoader.load("stock_recommendation").text())
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
}
