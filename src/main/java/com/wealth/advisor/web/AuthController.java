package com.wealth.advisor.web;

import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets the logged-in-only chat UI find out who's logged in, to display/label the session.
 *
 * <pre>{@code
 * GET /api/me
 *
 * 200 OK
 * { "username": "demo" }
 * }</pre>
 */
@RestController
public class AuthController {

	@GetMapping("/api/me")
	public Map<String, String> me(Authentication authentication) {
		return Map.of("username", authentication.getName());
	}
}
