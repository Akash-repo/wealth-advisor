package com.wealth.advisor.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.google.adk.tools.FunctionTool;
import com.wealth.advisor.agent.tool.EpisodeDetailsTool;
import com.wealth.advisor.agent.tool.EpisodeSearchTool;
import com.wealth.advisor.agent.tool.MarketDataService;
import com.wealth.advisor.agent.tool.PortfolioHoldingsTool;
import com.wealth.advisor.agent.tool.StockLookupTool;
import com.wealth.advisor.config.TestProperties;
import com.wealth.advisor.memory.EpisodeSearch;
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
	void stockTool_exposesOnlyTheTickerToTheModel_notTheInjectedToolContext() {
		var decl = FunctionTool.create(new StockLookupTool(mock(MarketDataService.class)), "getStockData").declaration().orElseThrow();

		assertThat(decl.parameters().orElseThrow().properties().orElseThrow()).containsOnlyKeys("ticker");
	}

	@Test
	void searchPastConversations_onlyTheTopicIsRequired() {
		var decl = FunctionTool.create(new EpisodeSearchTool(mock(EpisodeSearch.class)), "searchPastConversations").declaration().orElseThrow();

		assertThat(decl.name()).hasValue("searchPastConversations");
		assertThat(decl.parameters().orElseThrow().properties().orElseThrow()).containsOnlyKeys("topic", "tickers", "window");
		assertThat(decl.parameters().orElseThrow().required().orElse(java.util.List.of())).containsExactly("topic");
	}

	@Test
	void getEpisodeDetails_takesJustTheEpisodeId() {
		var decl = FunctionTool.create(new EpisodeDetailsTool(null, null, TestProperties.defaults()), "getEpisodeDetails").declaration().orElseThrow();

		assertThat(decl.parameters().orElseThrow().properties().orElseThrow()).containsOnlyKeys("episodeId");
		assertThat(decl.parameters().orElseThrow().required().orElse(java.util.List.of())).containsExactly("episodeId");
	}
}
