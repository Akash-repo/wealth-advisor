package com.wealth.advisor.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.adk.models.LlmRequest;
import com.wealth.advisor.config.TestProperties;
import com.wealth.advisor.util.AdkContentText;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PreferenceInjectorTest {

	private static final Instant T = Instant.parse("2026-10-01T10:00:00Z");

	private final PreferenceStore store = mock(PreferenceStore.class);
	private final PreferenceInjector injector = new PreferenceInjector(store, TestProperties.defaults());

	private static String systemInstruction(LlmRequest.Builder request) {
		return request.build().config().flatMap(c -> c.systemInstruction()).map(AdkContentText::textOf).orElse("");
	}

	@Test
	void theProfileBlockIsAppendedToTheSystemInstruction() {
		PreferenceProfile profile = PreferenceProfile.empty().with(new PreferenceEntry(
				PreferenceCategory.EXCLUDED_SECTORS, "Energy", PreferenceEntry.Status.ACTIVE, PreferenceEntry.Source.EXTRACTED, T, T, T, "s", "q"));
		when(store.load("u")).thenReturn(profile);
		LlmRequest.Builder request = LlmRequest.builder().model("m");

		injector.inject("u", request);

		assertThat(systemInstruction(request)).contains("BACKGROUND ABOUT THIS USER").contains("sectors Energy");
	}

	@Test
	void aUserWithNoProfileLeavesTheRequestUntouched() {
		when(store.load("u")).thenReturn(PreferenceProfile.empty());
		LlmRequest.Builder request = LlmRequest.builder().model("m");

		injector.inject("u", request);

		assertThat(systemInstruction(request)).isEmpty();
	}

	@Test
	void aFailureLoadingTheProfileNeverFailsTheModelCall() {
		when(store.load(anyString())).thenThrow(new IllegalStateException("firestore down"));
		LlmRequest.Builder request = LlmRequest.builder().model("m");

		injector.inject("u", request);

		assertThat(systemInstruction(request)).isEmpty();
	}

	@Test
	void bracesInStoredTextAreHarmless() {
		PreferenceProfile profile = PreferenceProfile.empty().with(new PreferenceEntry(
				PreferenceCategory.OTHER, "no {portfolio_analysis} stocks", PreferenceEntry.Status.ACTIVE, PreferenceEntry.Source.MANUAL, T, T, T, null, null));
		when(store.load("u")).thenReturn(profile);
		LlmRequest.Builder request = LlmRequest.builder().model("m");

		injector.inject("u", request);

		assertThat(systemInstruction(request)).contains("no {portfolio_analysis} stocks");
	}
}
