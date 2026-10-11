package com.wealth.advisor.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.adk.tools.FunctionTool;
import com.google.adk.tools.LoadMemoryTool;
import com.wealth.advisor.agent.tool.PortfolioHoldingsTool;
import org.junit.jupiter.api.Test;

/**
 * Pins the function declarations the model actually sees. The model never reads our Java — only
 * this JSON — so an accidentally-required parameter (the {@code optional = true} gotcha) or a lost
 * description is a behavior regression that no compile error would catch.
 */
class ToolDeclarationsTest {

	@Test
	void holdingsTool_tickerIsOptional() {
		var decl = FunctionTool.create(new PortfolioHoldingsTool(null), "getPortfolioHoldings").declaration().orElseThrow();

		assertThat(decl.name()).hasValue("getPortfolioHoldings");
		assertThat(decl.parameters().orElseThrow().properties().orElseThrow()).containsKey("ticker");
		assertThat(decl.parameters().orElseThrow().required().orElse(java.util.List.of())).doesNotContain("ticker");
	}

	@Test
	void loadMemoryTool_exposesAQueryParameter() {
		var decl = new LoadMemoryTool().declaration().orElseThrow();

		assertThat(decl.name()).hasValue("loadMemory");
		assertThat(decl.parameters().orElseThrow().properties().orElseThrow()).containsKey("query");
	}
}
