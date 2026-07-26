package com.wealth.advisor.web;

import com.wealth.advisor.security.UserRegistrationService;
import com.wealth.advisor.security.UserRegistrationService.RegistrationException;
import com.wealth.advisor.web.dto.RegisterRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * FR-1's signup endpoint — unauthenticated (see {@code SecurityConfig}'s permitAll rule), unlike
 * every other controller in this app.
 *
 * <pre>{@code
 * POST /api/auth/register
 * { "username": "akash", "email": "akash@example.com", "password": "hunter22" }
 *
 * 200 OK                                   409 Conflict (or invalid input)
 * { "username": "akash" }                  { "error": "Username 'akash' is already taken." }
 * }</pre>
 *
 * <p>On success, the client redirects to {@code /login.html} to authenticate — this endpoint
 * only creates the account, it doesn't log the new user in itself.
 */
@RestController
public class RegistrationController {

	private static final Logger log = LoggerFactory.getLogger(RegistrationController.class);

	private final UserRegistrationService userRegistrationService;

	public RegistrationController(UserRegistrationService userRegistrationService) {
		this.userRegistrationService = userRegistrationService;
	}

	@PostMapping("/api/auth/register")
	public ResponseEntity<Map<String, String>> register(@RequestBody RegisterRequest request) {
		log.info("POST /api/auth/register for username='{}'", request.username());
		try {
			userRegistrationService.register(request.username(), request.email(), request.password());
			return ResponseEntity.ok(Map.of("username", request.username()));
		} catch (RegistrationException e) {
			log.info("Registration rejected for username='{}': {}", request.username(), e.getMessage());
			return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
		}
	}
}
