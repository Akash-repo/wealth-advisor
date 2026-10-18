package com.wealth.advisor.agent.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PromptLoaderTest {

	private final PromptLoader loader = new PromptLoader();

	@Test
	void loadsBothAgentPrompts() {
		Prompt analysis = loader.load("portfolio_analysis");
		Prompt recommendation = loader.load("stock_recommendation");

		assertThat(analysis.text()).startsWith("You are a portfolio analyst.");
		assertThat(recommendation.text()).startsWith("You are a direct, opinionated financial analyst");
	}

	@Test
	void recommendationPrompt_stillTemplatesAgentOnesOutput() {
		// ADK fills {portfolio_analysis} from the state key agent 1 writes via outputKey(...). If
		// either side is renamed alone, agent 2 silently loses agent 1's findings.
		assertThat(loader.load("stock_recommendation").text()).contains("{portfolio_analysis}");
	}

	@Test
	void version_isStableAndChangesWithContent() {
		Prompt first = loader.load("portfolio_analysis");
		Prompt again = loader.load("portfolio_analysis");
		Prompt other = loader.load("stock_recommendation");

		assertThat(first.version()).isEqualTo(again.version()).hasSize(12).matches("[0-9a-f]{12}");
		assertThat(first.version()).isNotEqualTo(other.version());
	}

	@Test
	void missingPrompt_failsLoudly() {
		assertThatThrownBy(() -> loader.load("does_not_exist"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("prompts/does_not_exist.txt");
	}
}
