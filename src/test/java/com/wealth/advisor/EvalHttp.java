package com.wealth.advisor;

import static org.assertj.core.api.Assertions.assertThat;

import com.wealth.advisor.web.dto.RegisterRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * The register, log in and chat calls the end-to-end evals all need, over real HTTP against the app
 * started by {@code @SpringBootTest}. Each {@link #newUser()} is a fresh account so tests never see each
 * other's sessions or preferences.
 */
final class EvalHttp {

	/** A registered, logged-in eval account. */
	record EvalUser(String name, HttpHeaders headers) {}

	private final TestRestTemplate restTemplate;

	EvalHttp(TestRestTemplate restTemplate) {
		this.restTemplate = restTemplate;
	}

	EvalUser newUser() {
		String username = "eval-" + UUID.randomUUID().toString().substring(0, 8);
		String password = "eval-password-not-a-real-account";
		assertThat(restTemplate
						.postForEntity(
								"/api/auth/register",
								new RegisterRequest(username, username + "@example.com", password),
								Map.class)
						.getStatusCode()
						.is2xxSuccessful())
				.isTrue();
		return new EvalUser(username, login(username, password));
	}

	private HttpHeaders login(String username, String password) {
		HttpHeaders formHeaders = new HttpHeaders();
		formHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("username", username);
		form.add("password", password);
		// Form login answers 302 + Set-Cookie; don't follow the redirect or the header is lost.
		String cookie = restTemplate
				.withRedirects(HttpRedirects.DONT_FOLLOW)
				.postForEntity("/api/auth/login", new HttpEntity<>(form, formHeaders), Void.class)
				.getHeaders()
				.getFirst(HttpHeaders.SET_COOKIE);
		assertThat(cookie).as("login must set a session cookie").isNotNull();
		HttpHeaders headers = new HttpHeaders();
		headers.add(HttpHeaders.COOKIE, cookie);
		return headers;
	}

	Map<String, Object> chat(EvalUser user, String sessionId, String message) {
		HttpHeaders headers = new HttpHeaders();
		headers.putAll(user.headers());
		headers.setContentType(MediaType.APPLICATION_JSON);
		Map<String, Object> body = new HashMap<>();
		body.put("sessionId", sessionId);
		body.put("message", message);
		return exchange("/api/chat", HttpMethod.POST, new HttpEntity<>(body, headers), new ParameterizedTypeReference<>() {});
	}

	List<Map<String, Object>> sessions(EvalUser user) {
		return exchange("/api/sessions", HttpMethod.GET, new HttpEntity<>(user.headers()), new ParameterizedTypeReference<>() {});
	}

	List<String> historyText(EvalUser user, String sessionId) {
		List<Map<String, Object>> messages = exchange(
				"/api/sessions/" + sessionId + "/messages",
				HttpMethod.GET,
				new HttpEntity<>(user.headers()),
				new ParameterizedTypeReference<>() {});
		return messages.stream().map(m -> (String) m.get("text")).toList();
	}

	/** Starts the background memory job; answers 202 straight away. */
	void refreshMemory(EvalUser user) {
		exchange("/api/memory/refresh", HttpMethod.POST, new HttpEntity<>(user.headers()), new ParameterizedTypeReference<Void>() {});
	}

	List<Map<String, Object>> preferences(EvalUser user) {
		return exchange("/api/preferences", HttpMethod.GET, new HttpEntity<>(user.headers()), new ParameterizedTypeReference<>() {});
	}

	void deletePreference(EvalUser user, String id) {
		exchange("/api/preferences/" + id, HttpMethod.DELETE, new HttpEntity<>(user.headers()), new ParameterizedTypeReference<Void>() {});
	}

	<T> T exchange(String path, HttpMethod method, HttpEntity<?> entity, ParameterizedTypeReference<T> type) {
		var response = restTemplate.exchange(path, method, entity, type);
		assertThat(response.getStatusCode().is2xxSuccessful()).as(path + " -> " + response.getStatusCode()).isTrue();
		return response.getBody();
	}
}
