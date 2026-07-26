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
import org.springframework.stereotype.Service;

/**
 * Implements FR-2 of the design doc: parses an uploaded portfolio PDF, splits it into ~250-word
 * chunks (see {@link TextChunker}), embeds each chunk, and stores them in the {@code
 * portfolio_vectors} Firestore collection for {@link com.wealth.advisor.agent.tool
 * .PortfolioSearchTool} to search later.
 */
@Service
public class PortfolioIngestionService {

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
		List<String> chunks = TextChunker.chunk(extractText(pdfBytes));

		for (String chunk : chunks) {
			writeChunk(userId, filename, chunk, embeddingService.embed(chunk));
		}

		markPortfolioUploaded(userId);
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
			throw new IllegalStateException("Failed to store portfolio chunk", e);
		}
	}

	private void markPortfolioUploaded(String userId) {
		try {
			firestore.collection("users").document(userId).update("hasUploadedPortfolio", true).get();
		} catch (InterruptedException | ExecutionException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Failed to update user record", e);
		}
	}
}
