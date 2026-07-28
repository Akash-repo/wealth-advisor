package com.wealth.advisor.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.wealth.advisor.guardrails.ContentSanitizer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Real market data (price + fundamentals) from Financial Modeling Prep's free tier, with a
 * Firestore-backed 24h cache in front of it — see {@code ARCHITECTURE.md}'s "Real market data"
 * section for the full design rationale.
 *
 * <p>Cache: {@code market_data_cache/{TICKER}} — a direct document-by-id lookup (same pattern as
 * {@code users/{username}}, no query/index needed), holding the flattened result of 4 FMP calls
 * plus an {@code asOf} timestamp checked against a 24h TTL. Deliberately reused across every user
 * — a stock's price/fundamentals don't depend on who's asking, so one fetch serves everyone
 * asking about that ticker for the next 24h instead of one fetch per user per message.
 *
 * <p>On a cache miss, 4 FMP endpoints are called in parallel via {@link HttpClient#sendAsync} +
 * {@code CompletableFuture.allOf(...)} rather than sequentially or via Spring {@code @Async} — see
 * {@code ARCHITECTURE.md} for why: {@code sendAsync} is already non-blocking under the hood, so
 * this needs neither a Spring-managed thread pool nor {@code @EnableAsync} setup, and ties up no
 * thread while waiting on the network.
 *
 * <p>Each of the 4 calls degrades independently on failure (timeout, rate limit, bad response) —
 * logged as a warning, that field just comes back absent — rather than failing the whole lookup.
 * Genuinely unexpected exceptions (a bug in this class, not an external failure) are left to
 * propagate so {@code ToolUsageRecorder.onToolError} still captures them for real.
 *
 * <p>The ticker and any exception message logged here are run through {@link
 * ContentSanitizer#sanitizeForLog} first — {@code ticker} is ultimately model-supplied (from the
 * conversation, indirectly the user), and an exception message can in principle echo back part of
 * a malformed external response, so neither is trusted to be free of characters that could forge
 * or split a log line (CWE-117) once written to a text log.
 */
@Component
public class MarketDataService {

	private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

	private static final String CACHE_COLLECTION = "market_data_cache";
	private static final Duration CACHE_TTL = Duration.ofHours(24);
	private static final String FMP_BASE_URL = "https://financialmodelingprep.com/stable";

	private final Firestore firestore;
	private final HttpClient httpClient = HttpClient.newHttpClient();
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final String apiKey;
	private final ContentSanitizer contentSanitizer;

	public MarketDataService(Firestore firestore, @Value("${fmp.api-key:}") String apiKey, ContentSanitizer contentSanitizer) {
		this.firestore = firestore;
		this.apiKey = apiKey;
		this.contentSanitizer = contentSanitizer;
	}

	/** Ticker in, a flat {@code Map} of price + fundamentals out — cached for 24h per ticker. */
	public Map<String, Object> getStockData(String ticker) {
		String symbol = ticker.toUpperCase();

		if (apiKey.isBlank()) {
			log.warn(
					"getStockData(ticker='{}') called but fmp.api-key is not configured",
					contentSanitizer.sanitizeForLog(symbol));
			return Map.of("ticker", symbol, "found", false, "error", "Market data is not configured.");
		}

		Optional<Map<String, Object>> cached = readCache(symbol);
		if (cached.isPresent()) {
			log.debug("getStockData(ticker='{}') — cache hit", contentSanitizer.sanitizeForLog(symbol));
			return cached.get();
		}

		log.debug("getStockData(ticker='{}') — cache miss, calling FMP", contentSanitizer.sanitizeForLog(symbol));
		Map<String, Object> fresh = fetchFromFmp(symbol);
		writeCache(symbol, fresh);
		return fresh;
	}

	private Map<String, Object> fetchFromFmp(String symbol) {
		CompletableFuture<JsonNode> quoteFuture = fetchEndpoint("quote", symbol, null);
		CompletableFuture<JsonNode> ratiosFuture = fetchEndpoint("ratios", symbol, null);
		CompletableFuture<JsonNode> growthFuture = fetchEndpoint("financial-growth", symbol, null);
		CompletableFuture<JsonNode> estimatesFuture = fetchEndpoint("analyst-estimates", symbol, "period=annual");

		// Each future above already swallows its own failure into NullNode/MissingNode (see
		// fetchEndpoint), so allOf(...) never completes exceptionally here.
		CompletableFuture.allOf(quoteFuture, ratiosFuture, growthFuture, estimatesFuture).join();

		JsonNode quote = firstElement(quoteFuture.join());
		if (quote.isMissingNode()) {
			// Every FMP endpoint independently returns an empty array for an unknown ticker, so
			// this alone is enough to say "not found" without inspecting the other 3 responses.
			return Map.of("ticker", symbol, "found", false);
		}
		JsonNode ratios = firstElement(ratiosFuture.join());
		JsonNode growth = firstElement(growthFuture.join());

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("ticker", symbol);
		result.put("found", true);
		result.put("price", numberOrNull(quote, "price"));
		result.put("peRatio", numberOrNull(ratios, "priceToEarningsRatio"));
		result.put("pegRatio", numberOrNull(ratios, "priceToEarningsGrowthRatio"));
		result.put("freeCashFlowPerShare", numberOrNull(ratios, "freeCashFlowPerShare"));
		result.put("epsGrowth", numberOrNull(growth, "epsgrowth"));
		result.put("forwardPeRatio", computeForwardPe(quote, estimatesFuture.join()));
		result.put("asOf", Instant.now().toString());
		return result;
	}

	/**
	 * One FMP call, fully async and self-contained: never completes exceptionally. A non-200
	 * response, a network failure, or a parse error all collapse to {@link NullNode} — logged as a
	 * warning here, since that's the one place that actually knows which endpoint/ticker failed.
	 */
	private CompletableFuture<JsonNode> fetchEndpoint(String endpoint, String symbol, String extraParam) {
		String url =
				FMP_BASE_URL
						+ "/"
						+ endpoint
						+ "?symbol="
						+ symbol
						+ (extraParam == null ? "" : "&" + extraParam)
						+ "&apikey="
						+ apiKey;
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
		return httpClient
				.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.thenApply(
						response -> {
							if (response.statusCode() != 200) {
								log.warn(
										"FMP '{}' call for ticker='{}' returned HTTP {}",
										endpoint,
										contentSanitizer.sanitizeForLog(symbol),
										response.statusCode());
								return (JsonNode) NullNode.getInstance();
							}
							try {
								return objectMapper.readTree(response.body());
							} catch (Exception e) {
								log.warn(
										"Failed to parse FMP '{}' response for ticker='{}'",
										endpoint,
										contentSanitizer.sanitizeForLog(symbol),
										e);
								return NullNode.getInstance();
							}
						})
				.exceptionally(
						e -> {
							log.warn(
									"FMP '{}' call for ticker='{}' failed: {}",
									endpoint,
									contentSanitizer.sanitizeForLog(symbol),
									contentSanitizer.sanitizeForLog(e.getMessage()));
							return NullNode.getInstance();
						});
	}

	/** FMP's list endpoints return newest-first; a missing/empty/non-array response is absent. */
	private static JsonNode firstElement(JsonNode array) {
		if (array == null || !array.isArray() || array.isEmpty()) {
			return MissingNode.getInstance();
		}
		return array.get(0);
	}

	/**
	 * Forward P/E = current price ÷ the <em>nearest upcoming</em> fiscal year's consensus EPS
	 * estimate — deliberately not {@code estimatesArray.get(0)}: live testing showed FMP's
	 * analyst-estimates array is not reliably nearest-year-first, so this scans every entry's own
	 * {@code date} field for the closest one that hasn't already passed.
	 */
	private static Double computeForwardPe(JsonNode quote, JsonNode estimatesArray) {
		Double price = numberOrNull(quote, "price");
		if (price == null || estimatesArray == null || !estimatesArray.isArray()) {
			return null;
		}
		LocalDate today = LocalDate.now();
		JsonNode nearest = null;
		LocalDate nearestDate = null;
		for (JsonNode estimate : estimatesArray) {
			LocalDate date;
			try {
				date = LocalDate.parse(estimate.path("date").asText());
			} catch (Exception e) {
				continue;
			}
			if (date.isBefore(today)) {
				continue;
			}
			if (nearestDate == null || date.isBefore(nearestDate)) {
				nearestDate = date;
				nearest = estimate;
			}
		}
		Double epsAvg = nearest == null ? null : numberOrNull(nearest, "epsAvg");
		if (epsAvg == null || epsAvg == 0) {
			return null;
		}
		return price / epsAvg;
	}

	private static Double numberOrNull(JsonNode node, String field) {
		if (node == null || node.isNull() || node.isMissingNode() || !node.has(field) || !node.get(field).isNumber()) {
			return null;
		}
		return node.get(field).asDouble();
	}

	private Optional<Map<String, Object>> readCache(String symbol) {
		try {
			DocumentSnapshot doc = firestore.collection(CACHE_COLLECTION).document(symbol).get().get();
			if (!doc.exists()) {
				return Optional.empty();
			}
			String asOf = doc.getString("asOf");
			if (asOf == null || Duration.between(Instant.parse(asOf), Instant.now()).compareTo(CACHE_TTL) > 0) {
				return Optional.empty();
			}
			return Optional.of(doc.getData());
		} catch (Exception e) {
			log.warn(
					"Failed to read market data cache for '{}' — falling through to a live fetch: {}",
					contentSanitizer.sanitizeForLog(symbol),
					contentSanitizer.sanitizeForLog(e.getMessage()));
			return Optional.empty();
		}
	}

	private void writeCache(String symbol, Map<String, Object> data) {
		try {
			firestore.collection(CACHE_COLLECTION).document(symbol).set(data).get();
		} catch (Exception e) {
			// Not fatal — the caller already has the freshly fetched data regardless of whether
			// this write succeeded; the only cost is the next request re-fetching from FMP.
			log.warn(
					"Failed to write market data cache for '{}': {}",
					contentSanitizer.sanitizeForLog(symbol),
					contentSanitizer.sanitizeForLog(e.getMessage()));
		}
	}
}
