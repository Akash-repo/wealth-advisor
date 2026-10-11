package com.wealth.advisor.portfolio;

import com.wealth.advisor.constants.FirestoreFields;
import com.wealth.advisor.constants.FirestoreCollections;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.WriteBatch;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Implements FR-2 of the design doc: parses an uploaded document, splits it into ~250-word chunks
 * (see {@link TextChunker}), embeds each chunk, and stores them in the {@code portfolio_vectors}
 * Firestore collection for {@link com.wealth.advisor.agent.tool.PortfolioSearchTool} to search
 * later. This is the "write" half of this app's RAG pipeline — {@code PortfolioSearchTool} is the
 * "read" half.
 *
 * <p>For brokerage-statement-shaped documents specifically, also runs {@link
 * HoldingsExtractionService} to pull out structured {@code portfolio_holdings} records (ticker,
 * shares, cost basis, account) alongside the prose chunks — see {@code ARCHITECTURE.md}'s
 * "Portfolio holdings" section for why exact aggregation across multiple brokerage accounts (e.g.
 * "how much GOOG do I hold in total") needs this instead of relying on RAG's top-K chunk
 * retrieval, which has no guarantee of retrieving *every* chunk that mentions a ticker.
 *
 * <p>One {@code portfolio_documents} metadata doc is written per upload (not per chunk) so
 * uploaded documents can be listed and deleted as a unit — see {@link #deleteDocument}.
 *
 * <pre>{@code
 * int chunksStored = portfolioIngestionService.ingest(
 *     "demo", "vanguard-statement.pdf", pdfBytes, "brokerage");
 * // -> parses the PDF, writes N chunks to portfolio_vectors, any holdings found to
 * //    portfolio_holdings, one portfolio_documents record, sets
 * //    users/demo.hasUploadedPortfolio = true, and returns N
 * }</pre>
 */
@Service
public class PortfolioIngestionService {

	private static final Logger log = LoggerFactory.getLogger(PortfolioIngestionService.class);

	private final Firestore firestore;
	private final EmbeddingService embeddingService;
	private final HoldingsExtractionService holdingsExtractionService;

	public PortfolioIngestionService(
			Firestore firestore, EmbeddingService embeddingService, HoldingsExtractionService holdingsExtractionService) {
		this.firestore = firestore;
		this.embeddingService = embeddingService;
		this.holdingsExtractionService = holdingsExtractionService;
	}

	/**
	 * @param documentType a free-form label like {@code brokerage}, {@code resume}, or {@code
	 *     bank_statement} — stored as metadata only; every document type goes through the same
	 *     chunk+embed and holdings-extraction steps (a resume just yields zero holdings)
	 * @return the number of chunks stored
	 */
	public int ingest(String userId, String filename, byte[] pdfBytes, String documentType) throws IOException {
		log.info(
				"Ingesting document '{}' ({} bytes, type={}) for userId='{}'",
				filename,
				pdfBytes.length,
				documentType,
				userId);

		String text = extractText(pdfBytes);
		List<String> chunks = TextChunker.chunk(text);
		log.debug("Split '{}' into {} chunk(s)", filename, chunks.size());
		for (String chunk : chunks) {
			writeChunk(userId, filename, chunk, embeddingService.embed(chunk));
		}

		List<Map<String, Object>> holdings = holdingsExtractionService.extract(text);
		for (Map<String, Object> holding : holdings) {
			writeHolding(userId, filename, holding);
		}

		writeDocumentMetadata(userId, filename, documentType, chunks.size(), holdings.size());
		markPortfolioUploaded(userId);
		log.info(
				"Finished ingesting '{}' for userId='{}': {} chunk(s), {} holding(s)",
				filename,
				userId,
				chunks.size(),
				holdings.size());
		return chunks.size();
	}

	/** Lists this user's uploaded documents (metadata only, newest first). */
	public List<Map<String, Object>> listDocuments(String userId) {
		try {
			return firestore.collection(FirestoreCollections.PORTFOLIO_DOCUMENTS).whereEqualTo(FirestoreFields.USER_ID, userId).get().get()
					.getDocuments().stream()
					.sorted((a, b) -> b.getTimestamp("uploadedAt").compareTo(a.getTimestamp("uploadedAt")))
					.map(QueryDocumentSnapshot::getData)
					.toList();
		} catch (InterruptedException | ExecutionException e) {
			Thread.currentThread().interrupt();
			log.error("Failed to list documents for userId='{}'", userId, e);
			throw new IllegalStateException("Failed to list documents", e);
		}
	}

	/** Deletes an uploaded document's chunks, extracted holdings, and its metadata record. */
	public void deleteDocument(String userId, String filename) {
		try {
			deleteWhereUserAndFilename(FirestoreCollections.PORTFOLIO_VECTORS, userId, filename);
			deleteWhereUserAndFilename(FirestoreCollections.PORTFOLIO_HOLDINGS, userId, filename);
			deleteWhereUserAndFilename(FirestoreCollections.PORTFOLIO_DOCUMENTS, userId, filename);
			log.info("Deleted document '{}' and its data for userId='{}'", filename, userId);
		} catch (InterruptedException | ExecutionException e) {
			Thread.currentThread().interrupt();
			log.error("Failed to delete document '{}' for userId='{}'", filename, userId, e);
			throw new IllegalStateException("Failed to delete document", e);
		}
	}

	private void deleteWhereUserAndFilename(String collection, String userId, String filename)
			throws InterruptedException, ExecutionException {
		List<QueryDocumentSnapshot> docs =
				firestore
						.collection(collection)
						.whereEqualTo(FirestoreFields.USER_ID, userId)
						.whereEqualTo("filename", filename)
						.get()
						.get()
						.getDocuments();
		if (docs.isEmpty()) {
			return;
		}
		WriteBatch batch = firestore.batch();
		docs.forEach(doc -> batch.delete(doc.getReference()));
		batch.commit().get();
	}

	private static String extractText(byte[] pdfBytes) throws IOException {
		try (PDDocument document = Loader.loadPDF(pdfBytes)) {
			return new PDFTextStripper().getText(document);
		}
	}

	private void writeChunk(String userId, String filename, String chunkText, double[] embedding) {
		try {
			firestore
					.collection(FirestoreCollections.PORTFOLIO_VECTORS)
					.add(
							Map.of(
									FirestoreFields.USER_ID, userId,
									"filename", filename,
									"chunkText", chunkText,
									"embedding", FieldValue.vector(embedding),
									"createdAt", Timestamp.now()))
					.get();
		} catch (InterruptedException | ExecutionException e) {
			Thread.currentThread().interrupt();
			log.error("Failed to store a portfolio chunk for userId='{}', filename='{}'", userId, filename, e);
			throw new IllegalStateException("Failed to store portfolio chunk", e);
		}
	}

	private void writeHolding(String userId, String filename, Map<String, Object> holding) {
		Object ticker = holding.get("ticker");
		Object shares = holding.get("shares");
		if (ticker == null || ticker.toString().isBlank() || shares == null) {
			log.warn("Skipping a malformed extracted holding for userId='{}', filename='{}': {}", userId, filename, holding);
			return;
		}
		try {
			Map<String, Object> doc = new HashMap<>();
			doc.put(FirestoreFields.USER_ID, userId);
			doc.put("filename", filename);
			doc.put("ticker", ticker.toString().toUpperCase());
			doc.put("shares", shares);
			doc.put("costBasis", holding.get("costBasis"));
			doc.put("account", holding.get("account"));
			doc.put("extractedAt", Timestamp.now());
			firestore.collection(FirestoreCollections.PORTFOLIO_HOLDINGS).add(doc).get();
		} catch (InterruptedException | ExecutionException e) {
			Thread.currentThread().interrupt();
			log.error("Failed to store a portfolio holding for userId='{}', filename='{}'", userId, filename, e);
			throw new IllegalStateException("Failed to store portfolio holding", e);
		}
	}

	private void writeDocumentMetadata(
			String userId, String filename, String documentType, int chunkCount, int holdingsCount) {
		try {
			firestore
					.collection(FirestoreCollections.PORTFOLIO_DOCUMENTS)
					.add(
							Map.of(
									FirestoreFields.USER_ID, userId,
									"filename", filename,
									"documentType", documentType,
									"chunkCount", chunkCount,
									"holdingsCount", holdingsCount,
									"uploadedAt", Timestamp.now()))
					.get();
		} catch (InterruptedException | ExecutionException e) {
			Thread.currentThread().interrupt();
			log.error("Failed to store document metadata for userId='{}', filename='{}'", userId, filename, e);
			throw new IllegalStateException("Failed to store document metadata", e);
		}
	}

	private void markPortfolioUploaded(String userId) {
		try {
			firestore.collection(FirestoreCollections.USERS).document(userId).update("hasUploadedPortfolio", true).get();
		} catch (InterruptedException | ExecutionException e) {
			Thread.currentThread().interrupt();
			log.error("Failed to set hasUploadedPortfolio for userId='{}'", userId, e);
			throw new IllegalStateException("Failed to update user record", e);
		}
	}
}
