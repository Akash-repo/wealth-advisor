package com.wealth.advisor.agent.tool;

import com.google.adk.tools.Annotations.Schema;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The ADK-facing surface for real market data — a thin wrapper, all the actual work (the
 * Firestore cache, the parallel FMP calls) lives in {@link MarketDataService}. See {@code
 * ARCHITECTURE.md}'s "Real market data" section for the full design.
 *
 * <p>Spring-managed instance tool (constructor-injected with {@link MarketDataService}), same
 * shape as {@link PortfolioSearchTool} — registered in {@code AgentConfig} with the {@code
 * instance, "..."} overload, not {@code Class, "..."}:
 *
 * <pre>{@code
 * @Bean
 * public LlmAgent portfolioAnalysisAgent(StockLookupTool stockLookupTool) {
 *   return LlmAgent.builder()
 *       .tools(FunctionTool.create(stockLookupTool, "getStockData"))
 *       .build();
 * }
 * }</pre>
 */
@Component
public class StockLookupTool {

	private final MarketDataService marketDataService;

	public StockLookupTool(MarketDataService marketDataService) {
		this.marketDataService = marketDataService;
	}

	@Schema(
			description =
					"Looks up a stock's current price and key fundamentals: P/E ratio, PEG ratio, forward"
							+ " P/E, EPS growth, and free cash flow per share. Data may be up to a day stale.")
	public Map<String, Object> getStockData(
			@Schema(name = "ticker", description = "Stock ticker symbol, e.g. NVDA or AAPL") String ticker) {
		return marketDataService.getStockData(ticker);
	}
}
