package com.wealth.advisor.agent.tool;

import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.ToolContext;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Exact holdings totals across every brokerage account a user has uploaded a statement for — the
 * complement to {@link PortfolioSearchTool}'s prose RAG search. See {@code ARCHITECTURE.md}'s
 * "Portfolio holdings" section for why this exists as a separate, structured-data tool: RAG's
 * top-K chunk retrieval has no guarantee of finding *every* chunk that mentions a given ticker, so
 * "how much GOOG do I hold in total across Vanguard, Fidelity, and Robinhood" needs a real,
 * exhaustive Firestore query summed in plain Java — not an LLM adding up numbers it happened to
 * retrieve.
 *
 * <p><strong>Gotcha:</strong> {@code ticker} must be marked {@code @Schema(..., optional = true)}
 * — {@code Annotations.Schema} defaults {@code optional()} to {@code false}, and when a parameter
 * isn't marked optional, ADK validates the model's function-call arguments against the declared
 * schema *before* invoking this method at all. A turn where the model correctly omits {@code
 * ticker} (asking for every holding) would then throw inside ADK's own binding layer — this
 * method's own {@code log.debug} never even fires — surfacing to the user as a generic "internal
 * error" with no indication of the real cause. Found live: asking "analyze my holdings" (no
 * ticker in mind) reliably failed this way until {@code optional = true} was added.
 *
 * <p>Reads {@code portfolio_holdings}, written by {@link
 * com.wealth.advisor.portfolio.HoldingsExtractionService} at upload time — one document per
 * holding-per-account, never derived from chunk text at query time.
 */
@Component
public class PortfolioHoldingsTool {

	private static final Logger log = LoggerFactory.getLogger(PortfolioHoldingsTool.class);

	private final Firestore firestore;

	public PortfolioHoldingsTool(Firestore firestore) {
		this.firestore = firestore;
	}

	@Schema(
			description =
					"Returns the user's exact stock/ETF holdings, summed across every brokerage account"
							+ " they've uploaded a statement for. Use this for \"how much of X do I hold in"
							+ " total\" or \"what are all my holdings\" — not searchPortfolio, which can miss"
							+ " holdings split across multiple uploaded documents.")
	public Map<String, Object> getPortfolioHoldings(
			@Schema(
							name = "ticker",
							description = "Stock ticker to look up, e.g. GOOG. Leave blank to get every holding.",
							optional = true)
					String ticker,
			ToolContext toolContext) {
		String userId = toolContext.userId();
		boolean singleTicker = ticker != null && !ticker.isBlank();
		log.debug("getPortfolioHoldings(userId='{}', ticker='{}')", userId, singleTicker ? ticker : "<all>");

		Query query = firestore.collection("portfolio_holdings").whereEqualTo("userId", userId);
		if (singleTicker) {
			query = query.whereEqualTo("ticker", ticker.toUpperCase());
		}

		List<QueryDocumentSnapshot> docs;
		try {
			docs = query.get().get().getDocuments();
		} catch (InterruptedException | ExecutionException e) {
			Thread.currentThread().interrupt();
			log.error("Portfolio holdings query failed for userId='{}'", userId, e);
			throw new IllegalStateException("Portfolio holdings query failed", e);
		}

		if (docs.isEmpty()) {
			return singleTicker
					? Map.of("ticker", ticker.toUpperCase(), "found", false)
					: Map.of("holdings", List.of(), "found", false);
		}

		Map<String, List<QueryDocumentSnapshot>> byTicker =
				docs.stream().collect(Collectors.groupingBy(doc -> doc.getString("ticker")));
		List<Map<String, Object>> summaries =
				byTicker.entrySet().stream().map(e -> summarize(e.getKey(), e.getValue())).toList();

		return singleTicker ? summaries.get(0) : Map.of("holdings", summaries, "found", true);
	}

	/**
	 * Package-private, not private: {@link PortfolioAllocationTool} shares this exact "group by
	 * ticker, sum shares, weighted-average cost basis" logic rather than duplicating it — it just
	 * layers current price/value/weight on top of the same summary this tool returns on its own.
	 */
	static Map<String, Object> summarize(String ticker, List<QueryDocumentSnapshot> docs) {
		Map<String, Double> byAccount = new LinkedHashMap<>();
		double totalShares = 0;
		double costBasisWeightedSum = 0;
		double sharesWithKnownCostBasis = 0;

		for (DocumentSnapshot doc : docs) {
			double shares = doc.getDouble("shares") == null ? 0 : doc.getDouble("shares");
			String account = doc.getString("account");
			String accountKey = (account == null || account.isBlank()) ? doc.getString("filename") : account;
			byAccount.merge(accountKey, shares, Double::sum);
			totalShares += shares;

			Double costBasis = doc.getDouble("costBasis");
			if (costBasis != null) {
				costBasisWeightedSum += costBasis * shares;
				sharesWithKnownCostBasis += shares;
			}
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("ticker", ticker);
		result.put("found", true);
		result.put("totalShares", totalShares);
		result.put("byAccount", byAccount);
		if (sharesWithKnownCostBasis > 0) {
			result.put("averageCostBasis", costBasisWeightedSum / sharesWithKnownCostBasis);
		}
		return result;
	}
}
