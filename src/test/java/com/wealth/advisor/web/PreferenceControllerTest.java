package com.wealth.advisor.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wealth.advisor.memory.PreferenceCategory;
import com.wealth.advisor.memory.PreferenceEntry;
import com.wealth.advisor.memory.PreferenceService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PreferenceControllerTest {

	private static final Instant T = Instant.parse("2026-10-01T10:00:00Z");

	private PreferenceService service;
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		service = mock(PreferenceService.class);
		mvc = MockMvcBuilders.standaloneSetup(new PreferenceController(service)).setControllerAdvice(new ApiExceptionHandler()).build();
	}

	private static UsernamePasswordAuthenticationToken user() {
		return new UsernamePasswordAuthenticationToken("u", null);
	}

	private static PreferenceEntry energy() {
		return new PreferenceEntry(
				PreferenceCategory.EXCLUDED_SECTORS, "Energy", PreferenceEntry.Status.ACTIVE, PreferenceEntry.Source.MANUAL, T, T, T, null, null);
	}

	@Test
	void listReturnsTheUsersActiveEntries() throws Exception {
		when(service.active("u")).thenReturn(List.of(energy()));

		mvc.perform(get("/api/preferences").principal(user()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].category").value("EXCLUDED_SECTORS"))
				.andExpect(jsonPath("$[0].categoryTitle").value("Excluded sectors"))
				.andExpect(jsonPath("$[0].value").value("Energy"))
				.andExpect(jsonPath("$[0].source").value("manual"))
				.andExpect(jsonPath("$[0].id").isNotEmpty());
	}

	@Test
	void optionsListTheFixedVocabulary() throws Exception {
		mvc.perform(get("/api/preferences/options").principal(user()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sectors.length()").value(11))
				.andExpect(jsonPath("$.industryGroups.length()").value(25))
				.andExpect(jsonPath("$.categories[0].name").value("EXCLUDED_SECTORS"));
	}

	@Test
	void addReturns201AndTheEntry() throws Exception {
		when(service.add("u", PreferenceCategory.EXCLUDED_SECTORS, "Energy")).thenReturn(energy());

		mvc.perform(post("/api/preferences")
						.principal(user())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"category\":\"EXCLUDED_SECTORS\",\"value\":\"Energy\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.value").value("Energy"));
	}

	@Test
	void anInvalidValueIsA400WithAProblemDetail() throws Exception {
		when(service.add(eq("u"), any(), any())).thenThrow(new PreferenceService.InvalidPreferenceException("Not valid."));

		mvc.perform(post("/api/preferences")
						.principal(user())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"category\":\"EXCLUDED_SECTORS\",\"value\":\"Fintech\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid preference"))
				.andExpect(jsonPath("$.detail").value("Not valid."));
	}

	@Test
	void aBlankValueOrMissingCategoryIsRejectedBeforeTheService() throws Exception {
		mvc.perform(post("/api/preferences").principal(user()).contentType(MediaType.APPLICATION_JSON).content("{\"category\":\"GOALS\",\"value\":\"  \"}"))
				.andExpect(status().isBadRequest());
		mvc.perform(post("/api/preferences").principal(user()).contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"x\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void deleteReturns204() throws Exception {
		mvc.perform(delete("/api/preferences/abc123").principal(user())).andExpect(status().isNoContent());

		verify(service).remove("u", "abc123");
	}

	@Test
	void deletingAnUnknownEntryIs404() throws Exception {
		doThrow(new PreferenceService.PreferenceNotFoundException("nope")).when(service).remove("u", "nope");

		mvc.perform(delete("/api/preferences/nope").principal(user()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.title").value("Preference not found"));
	}
}
