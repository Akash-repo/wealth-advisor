package com.wealth.advisor.web;

import com.wealth.advisor.constants.LogKeys;
import com.wealth.advisor.logging.LogContext;
import com.wealth.advisor.memory.MemoryExtractionJob;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Starts the background memory job for the signed-in user and returns at once. The chat page calls
 * this when it loads, so the job has the seconds the user spends typing their first message.
 *
 * <pre>{@code
 * POST /api/memory/refresh   -> 202 Accepted   (the work continues in the background)
 * }</pre>
 */
@RestController
public class MemoryController {

	private final MemoryExtractionJob job;
	private final ExecutorService executor;

	public MemoryController(MemoryExtractionJob job, @Qualifier("memoryExecutor") ExecutorService executor) {
		this.job = job;
		this.executor = executor;
	}

	@PostMapping("/api/memory/refresh")
	public ResponseEntity<Void> refresh(Authentication authentication) {
		String userId = authentication.getName();
		executor.execute(() -> {
			try (LogContext.Scope ignored = LogContext.with(Map.of(LogKeys.USER_ID, userId))) {
				job.refresh(userId);
			}
		});
		return ResponseEntity.accepted().build();
	}
}
