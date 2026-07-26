package com.wealth.advisor.web;

import com.wealth.advisor.portfolio.PortfolioIngestionService;
import java.io.IOException;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** FR-2: lets an authenticated user upload their portfolio PDF for RAG search. */
@RestController
public class PortfolioController {

	private final PortfolioIngestionService portfolioIngestionService;

	public PortfolioController(PortfolioIngestionService portfolioIngestionService) {
		this.portfolioIngestionService = portfolioIngestionService;
	}

	@PostMapping("/api/portfolio/upload")
	public Map<String, Object> upload(
			@RequestParam("file") MultipartFile file, Authentication authentication)
			throws IOException {
		int chunkCount =
				portfolioIngestionService.ingest(
						authentication.getName(), file.getOriginalFilename(), file.getBytes());
		return Map.of("filename", file.getOriginalFilename(), "chunksStored", chunkCount);
	}
}
