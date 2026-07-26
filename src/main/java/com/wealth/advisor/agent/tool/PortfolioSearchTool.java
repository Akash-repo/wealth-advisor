package com.wealth.advisor.agent.tool;

import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.ToolContext;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.VectorQuery;
import com.wealth.advisor.portfolio.EmbeddingService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.springframework.stereotype.Component;

/**
 * The RAG half of FR-3: lets {@code portfolio_analysis} pull relevant chunks out of whatever
 * portfolio PDF the user has uploaded (see {@link
 * com.wealth.advisor.portfolio.PortfolioIngestionService}).
 *
 * <p>Unlike {@link StockLookupTool}, this is a Spring-managed instance tool (constructor-injected
 * with {@link Firestore} and {@link EmbeddingService}), registered in {@code AgentConfig} via
 * {@code FunctionTool.create(portfolioSearchTool, "searchPortfolio")} rather than the {@code
 * Class, methodName} static overload.
 *
 * <p>{@code toolContext} isn't a real, model-supplied argument — ADK recognizes that literal
 * parameter name and injects the current {@link ToolContext} instead, which is how this method
 * gets the real authenticated user id ({@code toolContext.userId()}, threaded through from
 * whatever session id {@code AgentChatService} started the turn with) without ever trusting the
 * model to supply it. Relying on Spring Security's {@code SecurityContextHolder} here instead
 * would be fragile: ADK's execution is async/reactive under the hood and there's no guarantee a
 * tool call runs on the original servlet thread that {@code SecurityContextHolder}'s
 * ThreadLocal-based context lives on.
 */
@Component
public class PortfolioSearchTool {

	private static final int MAX_RESULTS = 3;

	private final Firestore firestore;
	private final EmbeddingService embeddingService;

	public PortfolioSearchTool(Firestore firestore, EmbeddingService embeddingService) {
		this.firestore = firestore;
		this.embeddingService = embeddingService;
	}

	@Schema(
			description =
					"Searches the user's uploaded portfolio document for holdings, asset allocation, or"
							+ " cost-basis information relevant to the query.")
	public Map<String, Object> searchPortfolio(
			@Schema(
							name = "query",
							description =
									"What to look for in the user's portfolio, e.g. 'technology sector exposure'"
											+ " or 'Nvidia holdings'")
					String query,
			ToolContext toolContext) {
		double[] queryVector = embeddingService.embed(query);

		VectorQuery vectorQuery =
				firestore
						.collection("portfolio_vectors")
						.whereEqualTo("userId", toolContext.userId())
						.findNearest("embedding", queryVector, MAX_RESULTS, VectorQuery.DistanceMeasure.COSINE);

		List<QueryDocumentSnapshot> matches;
		try {
			matches = vectorQuery.get().get().getDocuments();
		} catch (InterruptedException | ExecutionException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Portfolio vector search failed", e);
		}

		if (matches.isEmpty()) {
			return Map.of(
					"found",
					false,
					"message",
					"No portfolio has been uploaded yet, or nothing matched this query.");
		}

		StringBuilder context = new StringBuilder();
		for (QueryDocumentSnapshot doc : matches) {
			context.append(doc.getString("chunkText")).append("\n---\n");
		}
		return Map.of("found", true, "context", context.toString());
	}
}
