package com.wealth.advisor.portfolio;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import java.io.IOException;
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
 * Implements FR-2 of the design doc: parses an uploaded portfolio PDF, splits it into ~250-word
 * chunks (see {@link TextChunker}), embeds each chunk, and stores them in the {@code
 * portfolio_vectors} Firestore collection for {@link
 * com.wealth.advisor.agent.tool.PortfolioSearchTool} to search later. This is the "write" half of
 * this app's RAG pipeline — {@code PortfolioSearchTool} is the "read" half.
 *
 * <pre>{@code
 * int chunksStored = portfolioIngestionService.ingest(
 *     "demo", "statement.pdf", pdfBytes);
 * // -> parses the PDF, writes N documents to portfolio_vectors, sets
 * //    users/demo.hasUploadedPortfolio = true, and returns N
 * }</pre>
 */
@Service
public class PortfolioIngestionService {

	private static final Logger log = LoggerFactory.getLogger(PortfolioIngestionService.class);

	private final Firestore firestore;
	private final EmbeddingService embeddingService;

	public PortfolioIngestionService(Firestore firestore, EmbeddingService embeddingService) {
		this.firestore = firestore;
		this.embeddingService = embeddingService;
	}

	/**
	 * @return the number of chunks stored
	 */
	public int ingest(String userId, String filename, byte[] pdfBytes) throws IOException {
		log.info("Ingesting portfolio '{}' ({} bytes) for userId='{}'", filename, pdfBytes.length, userId);

		List<String> chunks = TextChunker.chunk(extractText(pdfBytes));
		log.debug("Split '{}' into {} chunk(s)", filename, chunks.size());

		for (String chunk : chunks) {
			writeChunk(userId, filename, chunk, embeddingService.embed(chunk));
		}

		markPortfolioUploaded(userId);
		log.info("Finished ingesting '{}' for userId='{}': {} chunk(s) stored", filename, userId, chunks.size());
		return chunks.size();
	}

	private static String extractText(byte[] pdfBytes) throws IOException {
		try (PDDocument document = Loader.loadPDF(pdfBytes)) {
			return new PDFTextStripper().getText(document);
		}
	}

	private void writeChunk(String userId, String filename, String chunkText, double[] embedding) {
		try {
			firestore
					.collection("portfolio_vectors")
					.add(
							Map.of(
									"userId", userId,
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

	private void markPortfolioUploaded(String userId) {
		try {
			firestore.collection("users").document(userId).update("hasUploadedPortfolio", true).get();
		} catch (InterruptedException | ExecutionException e) {
			Thread.currentThread().interrupt();
			log.error("Failed to set hasUploadedPortfolio for userId='{}'", userId, e);
			throw new IllegalStateException("Failed to update user record", e);
		}
	}
}
