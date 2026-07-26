package com.wealth.advisor.agent;

import com.google.adk.agents.LlmAgent;
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
 */
@Configuration
public class AgentConfig {

	/**
	 * The first agent in the pipeline: answers general investing questions and can look up a
	 * stock's price via {@link StockLookupTool#getStockPrice}.
	 *
	 * <p>Later milestones add a second agent (stock recommendation) and compose the two with a
	 * {@code SequentialAgent}; for now there's just this one agent handling the whole
	 * conversation.
	 */
	@Bean
	public LlmAgent portfolioAdvisorAgent() {
		return LlmAgent.builder()
				.name("portfolio_advisor")
				.description("Answers questions about stock investments and portfolio risk.")
				.model("gemini-2.5-flash")
				.instruction(
						"""
						You are a cautious financial advisor assistant. Use the getStockPrice tool
						to look up a current price whenever the user asks about a specific stock.
						Always include a brief risk disclaimer in your final answer.
						""")
				// FunctionTool.create(Class, methodName) reflects on the static method below and
				// registers it as a callable tool the model can invoke mid-conversation.
				.tools(FunctionTool.create(StockLookupTool.class, "getStockPrice"))
				.build();
	}
}
