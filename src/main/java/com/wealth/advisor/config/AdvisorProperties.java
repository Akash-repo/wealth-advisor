package com.wealth.advisor.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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
		@NotNull @Valid Guardrails guardrails,
		@NotNull @Valid Profile profile,
		@NotNull @Valid Extraction extraction,
		@NotNull @Valid Episodes episodes) {

	/** A copy with different guardrail settings; the other groups are unchanged. */
	public AdvisorProperties withGuardrails(Guardrails replacement) {
		return new AdvisorProperties(model, chat, compaction, replacement, profile, extraction, episodes);
	}

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

	/**
	 * Input guardrails run before a turn reaches the agents: {@code classifierModel} judges scope,
	 * {@code failClosed} decides what a failed classifier call means, {@code historyMessages} bounds
	 * the context it sees, and {@code refusalMessage} is the canned reply for any rejection.
	 */
	public record Guardrails(
			boolean enabled,
			@NotBlank String classifierModel,
			boolean failClosed,
			@Positive int historyMessages,
			@NotBlank String refusalMessage) {}

	/**
	 * The user preference profile: {@code extractorModel} reads conversations, operations below
	 * {@code confidenceThreshold} are not applied, {@code maxWords} caps the block injected into the
	 * agents' prompts and {@code maxEntries} caps active entries per user.
	 */
	public record Profile(
			@NotBlank String extractorModel,
			@Positive int maxWords,
			@Positive int maxEntries,
			@DecimalMin("0.0") @DecimalMax("1.0") double confidenceThreshold) {}

	/**
	 * The background job that turns finished conversations into profile entries and episodes: a
	 * session is read only once it has been quiet for {@code settleMinutes}, and at most {@code
	 * maxSessionsPerRun} sessions are processed per run.
	 */
	public record Extraction(@Positive int settleMinutes, @Positive int maxSessionsPerRun) {}

	/**
	 * Episodic memory: topic summaries of past conversations. {@code defaultWindowDays} is what an
	 * unbounded search covers; the three weights make up the hybrid score and are applied as given.
	 * Embedding models score even unrelated text well above zero, so a raw cosine similarity at or below
	 * {@code similarityFloor} counts as 0 and one at or above {@code similarityCeiling} counts as 1;
	 * both are specific to the embedding model and were calibrated with {@code EpisodeRetrievalEvalTest}.
	 */
	public record Episodes(
			@NotBlank String extractorModel,
			@NotBlank String embeddingModel,
			@Positive int embeddingDimensions,
			@Positive int maxPerSession,
			@Positive int maxSearchResults,
			@DecimalMin("0.0") @DecimalMax("1.0") double minScore,
			@Positive int recencyHalfLifeDays,
			@Positive int defaultWindowDays,
			@Positive int detailsMaxChars,
			@DecimalMin("0.0") double similarityWeight,
			@DecimalMin("0.0") double entityWeight,
			@DecimalMin("0.0") double recencyWeight,
			@DecimalMin("0.0") @DecimalMax("1.0") double similarityFloor,
			@DecimalMin("0.0") @DecimalMax("1.0") double similarityCeiling) {}
}
