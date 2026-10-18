package com.wealth.advisor.web;

import com.wealth.advisor.portfolio.PortfolioService;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * FR-2: lets an authenticated user upload portfolio statements, from which the exact holdings are
 * extracted — see {@link PortfolioService} for the read/extract/store steps and {@code
 * ARCHITECTURE.md}'s "Portfolio holdings" section.
 *
 * <pre>{@code
 * POST /api/portfolio/upload   (multipart/form-data, field "file" — a portfolio statement PDF)
 *
 * 200 OK
 * { "filename": "statement.pdf", "holdingsCount": 4 }
 *
 * 422 Unprocessable Entity   (not a readable PDF, no text, or no holdings found; nothing is stored)
 * { "title": "Not a portfolio statement", "status": 422, "detail": "This doesn't look like ..." }
 * (built by {@link ApiExceptionHandler})
 *
 * GET /api/portfolio/documents
 * 200 OK
 * [ { "filename": "...", "holdingsCount": 2, "uploadedAt": ... }, ... ]
 *
 * DELETE /api/portfolio/documents/{filename}
 * 200 OK
 * }</pre>
 */
@RestController
public class PortfolioController {

	private static final Logger log = LoggerFactory.getLogger(PortfolioController.class);

	private final PortfolioService portfolioService;

	public PortfolioController(PortfolioService portfolioService) {
		this.portfolioService = portfolioService;
	}

	@PostMapping("/api/portfolio/upload")
	public Map<String, Object> upload(@RequestParam("file") MultipartFile file, Authentication authentication)
			throws IOException {
		String userId = authentication.getName();
		log.info(
				"POST /api/portfolio/upload for userId='{}': filename='{}', size={} bytes",
				userId,
				file.getOriginalFilename(),
				file.getSize());
		int holdings = portfolioService.ingest(userId, file.getOriginalFilename(), file.getBytes());
		return Map.of("filename", file.getOriginalFilename(), "holdingsCount", holdings);
	}

	@GetMapping("/api/portfolio/documents")
	public List<Map<String, Object>> listDocuments(Authentication authentication) {
		return portfolioService.listStatements(authentication.getName());
	}

	@DeleteMapping("/api/portfolio/documents/{filename}")
	public void deleteDocument(@PathVariable String filename, Authentication authentication) {
		String userId = authentication.getName();
		log.info("DELETE /api/portfolio/documents/{} for userId='{}'", filename, userId);
		portfolioService.deleteStatement(userId, filename);
	}
}
