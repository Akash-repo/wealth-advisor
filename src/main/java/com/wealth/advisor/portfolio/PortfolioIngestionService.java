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
 * Implements FR-2 of the design doc: parses an uploaded <strong>portfolio statement</strong>,
 * splits it into ~250-word chunks (see {@link TextChunker}), embeds each chunk, and stores them in
 * the {@code portfolio_vectors} Firestore collection for {@link
 * com.wealth.advisor.agent.tool.PortfolioSearchTool} to search later. This is the "write" half of
 * this app's RAG pipeline — {@code PortfolioSearchTool} is the "read" half.
 *
 * <p>Only portfolio statements are accepted. The structured holdings are extracted <em>first</em>
 * ({@link HoldingsExtractionService}: ticker, shares, cost basis, account) and a document with no
 * holdings in it (a resume, a bank statement, a scanned image) is rejected with {@link
 * InvalidPortfolioDocumentException} <em>before anything is embedded or stored</em> — so a bad
 * upload costs one extraction call and leaves no partial data behind. See {@code ARCHITECTURE.md}'s
 * "Portfolio holdings" section for why exact aggregation across several brokerage accounts (e.g.
 * "how much GOOG do I hold in total") needs structured holdings instead of relying on RAG's top-K
 * chunk retrieval, which has no guarantee of retrieving *every* chunk that mentions a ticker.
 * Several statements per user (one per account) are still supported.
 *
 * <p>One {@code portfolio_documents} metadata doc is written per upload (not per chunk) so
 * uploaded documents can be listed and deleted as a unit — see {@link #deleteDocument}.
 *
 * <pre>{@code
 * int chunksStored = portfolioIngestionService.ingest("demo", "vanguard-statement.pdf", pdfBytes);
 * // -> parses the PDF and extracts holdings (rejecting the file if there are none), then writes
 * //    N chunks to portfolio_vectors, the holdings to portfolio_holdings, one
 * //    portfolio_documents record, sets users/demo.hasUploadedPortfolio = true, and returns N
 * }</pre>
 */
@Service
public class PortfolioIngestionService {

	private static final Logger log = LoggerFactory.getLogger(PortfolioIngestionService.class);

	/** Every accepted upload is a portfolio statement; the field is kept so existing documents list uniformly. */
	private static final String DOCUMENT_TYPE = "brokerage";

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
	 * @return the number of chunks stored
	 * @throws InvalidPortfolioDocumentException if the file can't be read as a PDF, has no text, or
	 *     contains no holdings — nothing is stored in that case
	 */
	public int ingest(String userId, String filename, byte[] pdfBytes) {
		log.info("Ingesting statement '{}' ({} bytes) for userId='{}'", filename, pdfBytes.length, userId);

		String text = readText(pdfBytes);
		List<Map<String, Object>> holdings = holdingsExtractionService.extract(text);
		if (holdings.isEmpty()) {
			log.info("Rejected '{}' for userId='{}': no holdings found", filename, userId);
			throw new InvalidPortfolioDocumentException(
					"This doesn't look like a portfolio statement: no holdings were found in it. "
							+ "Please upload a brokerage or portfolio statement PDF.");
		}

		List<String> chunks = TextChunker.chunk(text);
		log.debug("Split '{}' into {} chunk(s)", filename, chunks.size());
		for (String chunk : chunks) {
			writeChunk(userId, filename, chunk, embeddingService.embed(chunk));
		}
		for (Map<String, Object> holding : holdings) {
			writeHolding(userId, filename, holding);
		}

		writeDocumentMetadata(userId, filename, DOCUMENT_TYPE, chunks.size(), holdings.size());
		markPortfolioUploaded(userId);
		log.info(
				"Finished ingesting '{}' for userId='{}': {} chunk(s), {} holding(s)",
				filename,
				userId,
				chunks.size(),
				holdings.size());
		return chunks.size();
	}

	private static String readText(byte[] pdfBytes) {
		String text;
		try {
			text = extractText(pdfBytes);
		} catch (IOException e) {
			throw new InvalidPortfolioDocumentException(
					"That file couldn't be read as a PDF. It may be corrupted or password protected.");
		}
		if (text.isBlank()) {
			throw new InvalidPortfolioDocumentException(
					"No readable text was found in that PDF (is it a scanned image?). "
							+ "Please upload a statement with selectable text.");
		}
		return text;
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
