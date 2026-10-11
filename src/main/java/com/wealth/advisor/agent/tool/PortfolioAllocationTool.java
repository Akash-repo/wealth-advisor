package com.wealth.advisor.agent.tool;

import com.wealth.advisor.constants.FirestoreFields;
import com.wealth.advisor.constants.FirestoreCollections;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.ToolContext;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Holdings valued at current market prices — dollar value and % weight of each position across
 * the user's <em>uploaded</em> holdings, ranked largest to smallest. The complement to {@link
 * PortfolioHoldingsTool}, which only knows share counts and cost basis, never price. See {@code
 * ARCHITECTURE.md}'s "Portfolio allocation" section for why share count alone can't answer risk/
 * concentration questions and why value+weight has to be computed here rather than left to the
 * model to multiply and sum across several tool results.
 *
 * <p><strong>Not the user's true net worth</strong> — only the value of accounts they've actually
 * uploaded a statement for. Real estate, cash outside a brokerage, and anything never uploaded
 * isn't reflected here; the tool's own description says "uploaded holdings," not "net worth," for
 * exactly this reason — don't let the agent overclaim what this number means.
 *
 * <p>Reuses {@link PortfolioHoldingsTool#summarize} for the share-count/cost-basis part of each
 * position, then fetches current price per <em>distinct</em> ticker via {@link MarketDataService}
 * — one call per ticker, not per holding-per-account row — in parallel via {@code
 * CompletableFuture}, the same "many independent lookups, combine results" shape {@code
 * MarketDataService} itself already uses for its 4 FMP endpoints, for the same reason: a handful
 * of sequential blocking price lookups would otherwise sum their latency instead of overlapping
 * it. Dispatched on the dedicated {@code priceLookupExecutor} bean ({@link
 * com.wealth.advisor.infra.ExecutorConfig}) rather than the JVM-wide {@code
 * ForkJoinPool.commonPool()} {@code CompletableFuture.supplyAsync(...)} would otherwise silently
 * use — see that class's javadoc and {@code Optimization.md} P1-5 for why that mattered here
 * specifically (each task blocks on real network I/O, and the common pool's default parallelism is
 * minimal on a 1-vCPU Cloud Run instance).
 *
 * <p>A ticker whose price lookup fails is <strong>excluded</strong> from the ranking and from
 * {@code totalValue} — not treated as $0, which would silently understate every other position's
 * weight — and surfaced separately via {@code pricingUnavailableFor} so the agent can say so
 * rather than presenting a quietly-incomplete picture as complete.
 */
@Component
public class PortfolioAllocationTool {

	private static final Logger log = LoggerFactory.getLogger(PortfolioAllocationTool.class);

	private final Firestore firestore;
	private final MarketDataService marketDataService;
	private final ExecutorService priceLookupExecutor;

	public PortfolioAllocationTool(
			Firestore firestore, MarketDataService marketDataService, ExecutorService priceLookupExecutor) {
		this.firestore = firestore;
		this.marketDataService = marketDataService;
		this.priceLookupExecutor = priceLookupExecutor;
	}

	@Schema(
			description =
					"Returns the user's uploaded holdings valued at current market prices: dollar value"
							+ " and % weight of each position, ranked largest to smallest, plus total value of"
							+ " everything uploaded. Use this for concentration/risk questions (\"what's my"
							+ " biggest position\", \"how much of my portfolio is X\", \"am I overexposed to"
							+ " tech\") — getPortfolioHoldings only knows share counts, not value or weight, so"
							+ " it can't answer these.")
	public Map<String, Object> getPortfolioAllocation(
			@Schema(
							name = "topN",
							description = "Limit to the top N positions by weight. Leave blank to return everything.",
							optional = true)
					Integer topN,
			ToolContext toolContext) {
		String userId = toolContext.userId();
		log.debug("getPortfolioAllocation(userId='{}', topN={})", userId, topN);

		List<QueryDocumentSnapshot> docs;
		try {
			docs = firestore.collection(FirestoreCollections.PORTFOLIO_HOLDINGS).whereEqualTo(FirestoreFields.USER_ID, userId).get().get().getDocuments();
		} catch (InterruptedException | ExecutionException e) {
			Thread.currentThread().interrupt();
			log.error("Portfolio holdings query failed for userId='{}'", userId, e);
			throw new IllegalStateException("Portfolio holdings query failed", e);
		}

		if (docs.isEmpty()) {
			return Map.of("holdings", List.of(), "totalValue", 0, "found", false);
		}

		Map<String, List<QueryDocumentSnapshot>> byTicker =
				docs.stream().collect(Collectors.groupingBy(doc -> doc.getString("ticker")));

		Map<String, CompletableFuture<Map<String, Object>>> priceFutures =
				byTicker.keySet().stream()
						.collect(
								Collectors.toMap(
										t -> t,
										t ->
												CompletableFuture.supplyAsync(
														() -> marketDataService.getStockData(t), priceLookupExecutor)));
		CompletableFuture.allOf(priceFutures.values().toArray(new CompletableFuture[0])).join();

		Map<String, Double> valueByTicker = new LinkedHashMap<>();
		Map<String, Double> priceByTicker = new LinkedHashMap<>();
		Map<String, Map<String, Object>> shareSummaryByTicker = new LinkedHashMap<>();
		List<String> pricingUnavailable = new ArrayList<>();
		double totalValue = 0;

		for (Map.Entry<String, List<QueryDocumentSnapshot>> entry : byTicker.entrySet()) {
			String ticker = entry.getKey();
			Map<String, Object> shareSummary = PortfolioHoldingsTool.summarize(ticker, entry.getValue());
			shareSummaryByTicker.put(ticker, shareSummary);

			Map<String, Object> priceData = priceFutures.get(ticker).join();
			Object price = Boolean.TRUE.equals(priceData.get("found")) ? priceData.get("price") : null;
			if (price instanceof Number priceNumber) {
				double shares = (double) shareSummary.get("totalShares");
				double value = shares * priceNumber.doubleValue();
				valueByTicker.put(ticker, value);
				priceByTicker.put(ticker, priceNumber.doubleValue());
				totalValue += value;
			} else {
				pricingUnavailable.add(ticker);
			}
		}

		double finalTotalValue = totalValue;
		List<Map<String, Object>> ranked =
				valueByTicker.entrySet().stream()
						.sorted(Map.Entry.<String, Double>comparingByValue().reversed())
						.map(
								e -> {
									Map<String, Object> position = new LinkedHashMap<>(shareSummaryByTicker.get(e.getKey()));
									position.put("price", priceByTicker.get(e.getKey()));
									position.put("value", e.getValue());
									position.put(
											"weightPercent", finalTotalValue > 0 ? (e.getValue() / finalTotalValue) * 100 : 0);
									return position;
								})
						.toList();

		if (topN != null && topN > 0 && ranked.size() > topN) {
			ranked = ranked.subList(0, topN);
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("found", true);
		result.put("totalValue", totalValue);
		result.put("holdings", ranked);
		if (!pricingUnavailable.isEmpty()) {
			result.put("pricingUnavailableFor", pricingUnavailable);
		}
		return result;
	}
}
