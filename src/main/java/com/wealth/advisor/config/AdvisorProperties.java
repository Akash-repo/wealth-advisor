package com.wealth.advisor.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The app's tunables, bound from the {@code advisor.*} block of {@code application.yml}.
 *
 * <p>Deliberately has <em>no defaults in code</em>: {@code application.yml} is the single source of
 * truth, and {@code @Validated} makes a missing or non-positive value fail at startup instead of
 * surfacing mid-request. Each group can be overridden per environment with a relaxed-binding env
 * var (e.g. {@code ADVISOR_COMPACTION_TOKENTHRESHOLD}) — on Cloud Run that is a new revision, not a
 * new image.
 *
 * <p>What does <em>not</em> belong here: values that only change with the code (Firestore field
 * names, session-state keys, table names — those are constants) and secrets (env var / Secret
 * Manager). See {@code docs/design/00-package-structure.md}.
 */
@ConfigurationProperties(prefix = "advisor")
@Validated
public record AdvisorProperties(
		@NotNull @Valid Model model,
		@NotNull @Valid Chat chat,
		@NotNull @Valid Compaction compaction,
		@NotNull @Valid Memory memory) {

	/** Which Gemini model each job uses. */
	public record Model(@NotBlank String chat, @NotBlank String extraction) {}

	/** Per-user chat limits and session-list bounds. */
	public record Chat(
			@Positive int maxRequestsPerMinute, @Positive int maxListedSessions, @Positive int maxTitleLength) {}

	/**
	 * ADK tail-retention compaction: once a prompt exceeds {@code tokenThreshold}, everything older
	 * than the most recent {@code eventRetentionSize} events is summarized.
	 */
	public record Compaction(@Positive int tokenThreshold, @Positive int eventRetentionSize) {}

	/** Caps on what long-term memory search may hand back to the model. */
	public record Memory(@Positive int maxEntries, @Positive int maxTextChars) {}
}
