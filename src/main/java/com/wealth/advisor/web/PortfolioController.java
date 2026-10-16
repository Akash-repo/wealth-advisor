package com.wealth.advisor.web;

import com.wealth.advisor.portfolio.InvalidPortfolioDocumentException;
import com.wealth.advisor.portfolio.PortfolioIngestionService;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * FR-2: lets an authenticated user upload documents for RAG search and, for brokerage statements,
 * exact holdings aggregation — see {@link PortfolioIngestionService} for the parse/chunk/embed/
 * extract/store steps and {@code ARCHITECTURE.md}'s "Portfolio holdings" section for why uploads
 * are more than just prose chunking now.
 *
 * <pre>{@code
 * POST /api/portfolio/upload   (multipart/form-data, field "file" — a portfolio statement PDF)
 *
 * 200 OK
 * { "filename": "statement.pdf", "chunksStored": 4 }
 *
 * 422 Unprocessable Entity   (not a readable PDF, no text, or no holdings found; nothing is stored)
 * { "error": "This doesn't look like a portfolio statement: ..." }
 *
 * GET /api/portfolio/documents
 * 200 OK
 * [ { "filename": "...", "documentType": "brokerage", "chunkCount": 4, "holdingsCount": 2, "uploadedAt": ... }, ... ]
 *
 * DELETE /api/portfolio/documents/{filename}
 * 200 OK
 * }</pre>
 */
@RestController
public class PortfolioController {

	private static final Logger log = LoggerFactory.getLogger(PortfolioController.class);

	private final PortfolioIngestionService portfolioIngestionService;

	public PortfolioController(PortfolioIngestionService portfolioIngestionService) {
		this.portfolioIngestionService = portfolioIngestionService;
	}

	@PostMapping("/api/portfolio/upload")
	public ResponseEntity<Map<String, Object>> upload(
			@RequestParam("file") MultipartFile file, Authentication authentication) throws IOException {
		String userId = authentication.getName();
		log.info(
				"POST /api/portfolio/upload for userId='{}': filename='{}', size={} bytes",
				userId,
				file.getOriginalFilename(),
				file.getSize());
		try {
			int chunkCount = portfolioIngestionService.ingest(userId, file.getOriginalFilename(), file.getBytes());
			return ResponseEntity.ok(Map.of("filename", file.getOriginalFilename(), "chunksStored", chunkCount));
		} catch (InvalidPortfolioDocumentException e) {
			return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getMessage()));
		}
	}

	@GetMapping("/api/portfolio/documents")
	public List<Map<String, Object>> listDocuments(Authentication authentication) {
		return portfolioIngestionService.listDocuments(authentication.getName());
	}

	@DeleteMapping("/api/portfolio/documents/{filename}")
	public void deleteDocument(@PathVariable String filename, Authentication authentication) {
		String userId = authentication.getName();
		log.info("DELETE /api/portfolio/documents/{} for userId='{}'", filename, userId);
		portfolioIngestionService.deleteDocument(userId, filename);
	}
}
