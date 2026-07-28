package com.wealth.advisor.agent.tool;

import com.google.adk.tools.Annotations.Schema;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A demo "tool" for the portfolio advisor agent.
 *
 * <p>In Google ADK, a tool is just a plain Java method that the LLM can choose to call mid
 * conversation. ADK inspects the method's signature (via reflection) to build a JSON schema it
 * hands to Gemini, describing what arguments the tool takes. When the model decides it needs
 * this data, ADK invokes the method for you and feeds the return value back into the
 * conversation as a "function response" — the model then uses that to write its final answer.
 *
 * <p>Registered on an agent like this (see {@code AgentConfig}):
 *
 * <pre>{@code
 * LlmAgent.builder()
 *     .instruction("Use the getStockPrice tool to look up a current price.")
 *     .tools(FunctionTool.create(StockLookupTool.class, "getStockPrice"))
 *     .build();
 * }</pre>
 *
 * <p>Two things ADK requires of a tool method:
 * <ul>
 *   <li>It must be {@code static} (for the {@code FunctionTool.create(Class, String)} overload
 *       used to register it in {@code AgentConfig}).
 *   <li>Its parameters must be annotated with {@link Schema} so ADK knows what to tell the model
 *       each argument means (name + description) — without it, the model has no idea what to
 *       pass in.
 * </ul>
 */
public final class StockLookupTool {

	private static final Logger log = LoggerFactory.getLogger(StockLookupTool.class);

	// Fixed prices for local learning/demo purposes only; wire up a real market data
	// API here when this moves past the ADK learning stage.
	private static final Map<String, Double> MOCK_PRICES =
			Map.of(
					"NVDA", 178.32,
					"AAPL", 231.10,
					"MSFT", 512.87,
					"GOOG", 195.40,
					"TSLA", 342.75);

	private StockLookupTool() {
	}

	/**
	 * Looks up a stock's current price.
	 *
	 * <p>Called by the agent's underlying LLM (Gemini), not directly by application code — Gemini
	 * decides when a user's question needs this tool and generates the {@code ticker} argument
	 * itself based on the conversation.
	 *
	 * @param ticker stock ticker symbol, e.g. {@code NVDA} or {@code AAPL}; the {@link Schema}
	 *     annotation's description is what the model reads to know what to pass here
	 * @return a map describing the lookup result; returned as-is to the model as the tool's
	 *     "function response", which it then reads to compose its reply
	 */
	@Schema(description = "Looks up a stock's current price by ticker symbol.")
	public static Map<String, Object> getStockPrice(
			@Schema(name = "ticker", description = "Stock ticker symbol, e.g. NVDA or AAPL") String ticker) {
		String symbol = ticker.toUpperCase();
		Double price = MOCK_PRICES.get(symbol);
		log.debug("getStockPrice(ticker='{}') -> {}", symbol, price);
		if (price == null) {
			return Map.of("ticker", symbol, "found", false);
		}
		return Map.of("ticker", symbol, "price", price, "currency", "USD", "found", true);
	}
}
