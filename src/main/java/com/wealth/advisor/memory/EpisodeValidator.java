package com.wealth.advisor.memory;

import com.wealth.advisor.config.AdvisorProperties;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Turns the extractor's proposed {@link EpisodeDraft}s into trusted {@link Episode}s. The model's
 * message ranges are checked and repaired rather than believed: clamped to the messages that exist, with
 * any message already claimed by an earlier episode removed, so ranges never overlap. Titles and
 * summaries must be present, text is collapsed to one line and capped, tickers and sectors must be
 * valid, and there are at most {@code advisor.episodes.max-per-session} episodes.
 *
 * <p>If the model returned episodes but none survives checking, a deterministic fallback episode covers
 * the whole conversation, so a bad answer costs quality but never loses the conversation. A model that
 * legitimately returns nothing (all small talk) gets no episodes.
 */
@Component
public class EpisodeValidator {

	private static final int MAX_TITLE = 120;
	private static final int MAX_SUMMARY = 1_500;
	private static final int MAX_TOPIC = 60;
	private static final int MAX_DECISION = 300;
	private static final int MAX_LIST = 8;
	private static final int FALLBACK_SUMMARY = 400;
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneOffset.UTC);

	private final int maxEpisodes;

	public EpisodeValidator(AdvisorProperties properties) {
		this.maxEpisodes = properties.episodes().maxPerSession();
	}

	private record Checked(EpisodeDraft draft, List<MessageRange> ranges) {}

	public List<Episode> validate(List<EpisodeDraft> drafts, List<TimedMessage> messages, String userId, String sessionId) {
		if (drafts.isEmpty() || messages.isEmpty()) {
			return List.of();
		}
		Map<Integer, TimedMessage> byIndex = messages.stream().collect(Collectors.toMap(TimedMessage::index, Function.identity()));
		boolean[] claimed = new boolean[messages.size() + 2];
		List<Checked> checked = new ArrayList<>();
		for (EpisodeDraft draft : drafts) {
			List<MessageRange> ranges = claim(draft.ranges(), claimed, messages.size());
			if (!ranges.isEmpty() && notBlank(draft.title()) && notBlank(draft.summary())) {
				checked.add(new Checked(draft, ranges));
			}
		}
		if (checked.isEmpty()) {
			return fallback(messages, userId, sessionId);
		}
		checked.sort(Comparator.comparingInt(c -> c.ranges().get(0).from()));
		List<Episode> episodes = new ArrayList<>();
		for (int i = 0; i < Math.min(maxEpisodes, checked.size()); i++) {
			episodes.add(build(checked.get(i), i, byIndex, userId, sessionId));
		}
		return episodes;
	}

	/** The parts of the proposed ranges that exist and are not yet taken, as runs of consecutive messages. */
	private static List<MessageRange> claim(List<EpisodeDraft.Range> proposed, boolean[] claimed, int messageCount) {
		List<MessageRange> result = new ArrayList<>();
		if (proposed == null) {
			return result;
		}
		for (EpisodeDraft.Range range : proposed) {
			int from = Math.max(1, range.from());
			int to = Math.min(messageCount, range.to());
			int runStart = -1;
			for (int i = from; i <= to + 1; i++) {
				boolean free = i <= to && !claimed[i];
				if (free && runStart < 0) {
					runStart = i;
				} else if (!free && runStart >= 0) {
					result.add(new MessageRange(runStart, i - 1));
					runStart = -1;
				}
				if (free) {
					claimed[i] = true;
				}
			}
		}
		result.sort(Comparator.comparingInt(MessageRange::from));
		return result;
	}

	private static Episode build(Checked c, int position, Map<Integer, TimedMessage> byIndex, String userId, String sessionId) {
		EpisodeDraft d = c.draft();
		Instant start = c.ranges().stream().map(r -> byIndex.get(r.from()).at()).min(Comparator.naturalOrder()).orElseThrow();
		Instant end = c.ranges().stream().map(r -> byIndex.get(r.to()).at()).max(Comparator.naturalOrder()).orElseThrow();
		return new Episode(
				sessionId + "__" + position,
				userId,
				sessionId,
				c.ranges(),
				start,
				end,
				clean(d.title(), MAX_TITLE),
				clean(d.summary(), MAX_SUMMARY),
				tickers(d.tickers()),
				sectors(d.sectors()),
				texts(d.topics(), MAX_TOPIC),
				texts(d.decisions(), MAX_DECISION),
				null);
	}

	private static List<Episode> fallback(List<TimedMessage> messages, String userId, String sessionId) {
		TimedMessage firstUser = messages.stream().filter(TimedMessage::isUser).findFirst().orElse(null);
		if (firstUser == null) {
			return List.of();
		}
		TimedMessage last = messages.get(messages.size() - 1);
		return List.of(new Episode(
				sessionId + "__0",
				userId,
				sessionId,
				List.of(new MessageRange(1, messages.size())),
				messages.get(0).at(),
				last.at(),
				"Conversation on " + DAY.format(messages.get(0).at()),
				clean(firstUser.text(), FALLBACK_SUMMARY),
				List.of(),
				List.of(),
				List.of(),
				List.of(),
				null));
	}

	private static List<String> tickers(List<String> raw) {
		return nonNull(raw).stream()
				.map(t -> PreferenceValues.normalize(PreferenceCategory.EXCLUDED_TICKERS, t))
				.flatMap(java.util.Optional::stream)
				.distinct()
				.limit(MAX_LIST)
				.toList();
	}

	private static List<String> sectors(List<String> raw) {
		return nonNull(raw).stream()
				.map(Sector::fromLabel)
				.flatMap(java.util.Optional::stream)
				.map(Sector::label)
				.distinct()
				.toList();
	}

	private static List<String> texts(List<String> raw, int maxLength) {
		return nonNull(raw).stream()
				.map(t -> clean(t, maxLength))
				.filter(t -> !t.isEmpty())
				.distinct()
				.limit(MAX_LIST)
				.toList();
	}

	private static List<String> nonNull(List<String> list) {
		return list == null ? List.of() : list.stream().filter(s -> s != null).toList();
	}

	private static String clean(String text, int maxLength) {
		String line = PreferenceValues.singleLine(text);
		return line.length() <= maxLength ? line : line.substring(0, maxLength - 1).stripTrailing() + "…";
	}

	private static boolean notBlank(String text) {
		return text != null && !text.isBlank();
	}
}
