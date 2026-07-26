package com.wealth.advisor.web;

import com.wealth.advisor.portfolio.PortfolioIngestionService;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * FR-2: lets an authenticated user upload their portfolio PDF for RAG search.
 *
 * <pre>{@code
 * POST /api/portfolio/upload   (multipart/form-data, field name "file")
 *
 * 200 OK
 * { "filename": "statement.pdf", "chunksStored": 4 }
 * }</pre>
 *
 * <p>See {@link PortfolioIngestionService#ingest} for the actual parse/chunk/embed/store steps.
 */
@RestController
public class PortfolioController {

	private static final Logger log = LoggerFactory.getLogger(PortfolioController.class);

	private final PortfolioIngestionService portfolioIngestionService;

	public PortfolioController(PortfolioIngestionService portfolioIngestionService) {
		this.portfolioIngestionService = portfolioIngestionService;
	}

	@PostMapping("/api/portfolio/upload")
	public Map<String, Object> upload(
			@RequestParam("file") MultipartFile file, Authentication authentication)
			throws IOException {
		String userId = authentication.getName();
		log.info(
				"POST /api/portfolio/upload for userId='{}': filename='{}', size={} bytes",
				userId,
				file.getOriginalFilename(),
				file.getSize());
		int chunkCount =
				portfolioIngestionService.ingest(userId, file.getOriginalFilename(), file.getBytes());
		return Map.of("filename", file.getOriginalFilename(), "chunksStored", chunkCount);
	}
}
