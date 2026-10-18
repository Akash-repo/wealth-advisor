package com.wealth.advisor.web;

import com.wealth.advisor.memory.PreferenceService;
import com.wealth.advisor.web.dto.AddPreferenceRequest;
import com.wealth.advisor.web.dto.PreferenceOptions;
import com.wealth.advisor.web.dto.PreferenceView;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The preferences panel's API: the user sees what the advisor has learned about them and can add or
 * delete entries. As everywhere, the user comes from the authenticated principal, never from the request.
 *
 * <pre>{@code
 * GET    /api/preferences          -> [ { "id", "category", "categoryTitle", "value", "source", "lastConfirmed" } ]
 * GET    /api/preferences/options  -> { "categories": [...], "sectors": [...], "industryGroups": [...] }
 * POST   /api/preferences          { "category": "EXCLUDED_SECTORS", "value": "Energy" }  -> 201 + the entry
 * DELETE /api/preferences/{id}     -> 204   (a soft delete: see PreferenceService)
 * }</pre>
 */
@RestController
public class PreferenceController {

	private final PreferenceService preferences;

	public PreferenceController(PreferenceService preferences) {
		this.preferences = preferences;
	}

	@GetMapping("/api/preferences")
	public List<PreferenceView> list(Authentication authentication) {
		return preferences.active(authentication.getName()).stream().map(PreferenceView::of).toList();
	}

	@GetMapping("/api/preferences/options")
	public PreferenceOptions options() {
		return PreferenceOptions.all();
	}

	@PostMapping("/api/preferences")
	@ResponseStatus(HttpStatus.CREATED)
	public PreferenceView add(@Valid @RequestBody AddPreferenceRequest request, Authentication authentication) {
		return PreferenceView.of(preferences.add(authentication.getName(), request.category(), request.value()));
	}

	@DeleteMapping("/api/preferences/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void remove(@PathVariable String id, Authentication authentication) {
		preferences.remove(authentication.getName(), id);
	}
}
