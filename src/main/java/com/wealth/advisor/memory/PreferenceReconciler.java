package com.wealth.advisor.memory;

import com.wealth.advisor.config.AdvisorProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Decides which of the extractor's proposed {@link PreferenceOperation}s are applied to a profile.
 * The model only <em>proposes</em>; everything that makes the profile trustworthy is checked here, in
 * plain code:
 *
 * <ul>
 *   <li>the quoted evidence really appears, word for word, in that user message;
 *   <li>the value is valid for its category (a known sector or industry group, a ticker-shaped symbol,
 *       short single-line text);
 *   <li>confidence is at or above the configured threshold;
 *   <li><strong>newer overrides older by message time</strong>: an operation is applied only if its
 *       message is newer than the entry's {@code evidenceAt}, so reprocessing an old conversation after a
 *       newer one cannot undo the newer statement;
 *   <li>entries typed in by the user (source {@code MANUAL}) are never changed by the extractor.
 * </ul>
 *
 * Operations are applied oldest message first. Dropped operations are returned with a reason, for
 * logs and tests; nothing here throws on bad model output.
 */
@Component
public class PreferenceReconciler {

	private static final Pattern TICKER = Pattern.compile("[A-Z][A-Z0-9.\\-]{0,9}");
	private static final Pattern WHITESPACE_OR_CONTROL = Pattern.compile("[\\s\\p{Cntrl}]+");
	private static final int MAX_VALUE_LENGTH = 200;
	private static final int MIN_QUOTE_LENGTH = 4;

	public record Result(PreferenceProfile profile, int applied, List<String> dropped) {}

	private record Step(PreferenceProfile profile, String droppedReason) {
		static Step applied(PreferenceProfile profile) {
			return new Step(profile, null);
		}

		static Step dropped(PreferenceProfile unchanged, String reason) {
			return new Step(unchanged, reason);
		}

		boolean isDropped() {
			return droppedReason != null;
		}
	}

	private final double confidenceThreshold;
	private final int maxEntries;

	public PreferenceReconciler(AdvisorProperties properties) {
		this.confidenceThreshold = properties.profile().confidenceThreshold();
		this.maxEntries = properties.profile().maxEntries();
	}

	public Result reconcile(
			PreferenceProfile profile, List<PreferenceOperation> operations, List<TimedMessage> userMessages, String sessionId) {
		Map<Integer, TimedMessage> byIndex = userMessages.stream().collect(Collectors.toMap(TimedMessage::index, Function.identity()));
		List<PreferenceOperation> ordered = operations.stream()
				.sorted(Comparator.comparing(op -> byIndex.containsKey(op.messageIndex()) ? byIndex.get(op.messageIndex()).at() : Instant.MAX))
				.toList();

		PreferenceProfile current = profile;
		int applied = 0;
		List<String> dropped = new ArrayList<>();
		for (PreferenceOperation op : ordered) {
			Step step = apply(current, op, byIndex.get(op.messageIndex()), sessionId);
			if (step.isDropped()) {
				dropped.add(describe(op) + ": " + step.droppedReason());
			} else {
				current = step.profile();
				applied++;
			}
		}
		return new Result(current, applied, dropped);
	}

	private Step apply(PreferenceProfile profile, PreferenceOperation op, TimedMessage message, String sessionId) {
		if (op.op() == null || op.category() == null) {
			return Step.dropped(profile, "operation or category missing");
		}
		if (message == null || !message.isUser()) {
			return Step.dropped(profile, "no such user message");
		}
		if (op.confidence() < confidenceThreshold) {
			return Step.dropped(profile, "confidence below threshold");
		}
		if (!quoteAppears(op.evidenceQuote(), message.text())) {
			return Step.dropped(profile, "evidence quote not found in the message");
		}
		Optional<String> value = normalizedValue(op.category(), op.value());
		if (value.isEmpty()) {
			return Step.dropped(profile, "invalid value for the category");
		}
		String quote = op.evidenceQuote().strip();
		return switch (op.op()) {
			case ADD -> add(profile, op.category(), value.get(), message, quote, sessionId);
			case REMOVE -> remove(profile, op.category(), value.get(), message);
			case UPDATE -> update(profile, op, value.get(), message, quote, sessionId);
		};
	}

	private Step add(PreferenceProfile profile, PreferenceCategory category, String value, TimedMessage message, String quote, String sessionId) {
		Optional<PreferenceEntry> existing = profile.find(category, value);
		if (existing.isPresent()) {
			if (!message.at().isAfter(existing.get().evidenceAt())) {
				return Step.dropped(profile, "older than the existing evidence");
			}
			return Step.applied(profile.with(existing.get().confirmedAt(message.at(), sessionId, quote)));
		}
		if (profile.active().size() >= maxEntries) {
			return Step.dropped(profile, "profile is full");
		}
		return Step.applied(profile.with(new PreferenceEntry(
				category, value, PreferenceEntry.Status.ACTIVE, PreferenceEntry.Source.EXTRACTED,
				message.at(), message.at(), message.at(), sessionId, quote)));
	}

	private Step remove(PreferenceProfile profile, PreferenceCategory category, String value, TimedMessage message) {
		Optional<PreferenceEntry> existing = profile.find(category, value);
		if (existing.isEmpty() || !existing.get().isActive()) {
			return Step.dropped(profile, "nothing active to remove");
		}
		PreferenceEntry entry = existing.get();
		if (entry.source() == PreferenceEntry.Source.MANUAL) {
			return Step.dropped(profile, "manual entries are only changed in the preferences panel");
		}
		if (!message.at().isAfter(entry.evidenceAt())) {
			return Step.dropped(profile, "older than the existing evidence");
		}
		return Step.applied(profile.with(entry.withStatus(PreferenceEntry.Status.REMOVED_BY_CONVERSATION, message.at())));
	}

	private Step update(PreferenceProfile profile, PreferenceOperation op, String value, TimedMessage message, String quote, String sessionId) {
		PreferenceProfile current = profile;
		Optional<String> replaced = Optional.ofNullable(op.replaces()).flatMap(r -> normalizedValue(op.category(), r));
		if (replaced.isPresent() && current.find(op.category(), replaced.get()).filter(PreferenceEntry::isActive).isPresent()) {
			Step removal = remove(current, op.category(), replaced.get(), message);
			if (removal.isDropped()) {
				return removal;
			}
			current = removal.profile();
		}
		return add(current, op.category(), value, message, quote, sessionId);
	}

	/** The value in its stored form, or empty if it is not valid for the category. */
	static Optional<String> normalizedValue(PreferenceCategory category, String raw) {
		if (raw == null || raw.isBlank()) {
			return Optional.empty();
		}
		return switch (category) {
			case EXCLUDED_SECTORS -> Sector.fromLabel(raw).map(Sector::label);
			case EXCLUDED_INDUSTRY_GROUPS -> IndustryGroup.fromLabel(raw).map(IndustryGroup::label);
			case EXCLUDED_TICKERS -> Optional.of(raw.strip().toUpperCase(Locale.ROOT)).filter(t -> TICKER.matcher(t).matches());
			default -> Optional.of(singleLine(raw)).filter(t -> !t.isEmpty() && t.length() <= MAX_VALUE_LENGTH);
		};
	}

	private static boolean quoteAppears(String quote, String messageText) {
		if (quote == null) {
			return false;
		}
		String wanted = singleLine(quote).toLowerCase(Locale.ROOT);
		return wanted.length() >= MIN_QUOTE_LENGTH && singleLine(messageText).toLowerCase(Locale.ROOT).contains(wanted);
	}

	private static String singleLine(String text) {
		return WHITESPACE_OR_CONTROL.matcher(text).replaceAll(" ").strip();
	}

	private static String describe(PreferenceOperation op) {
		return op.op() + " " + op.category() + " '" + op.value() + "'";
	}
}
