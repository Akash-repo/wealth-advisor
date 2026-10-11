package com.wealth.advisor.agent;

import com.google.adk.memory.BaseMemoryService;
import com.google.adk.memory.MemoryEntry;
import com.google.adk.memory.SearchMemoryResponse;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decorator around the real {@link BaseMemoryService} that makes long-term memory safe to hand to
 * an LLM. Two problems found running {@code FirestoreMemoryService} for real:
 *
 * <ul>
 *   <li><strong>It fails hard.</strong> A missing Firestore index (or any backend error) surfaces
 *       through {@code LoadMemoryTool} as a tool exception, which ADK turns into a failed turn —
 *       one optional lookup took down the whole chat with a 500. Memory is a nice-to-have, so an
 *       error here degrades to "no memories found" and is logged.
 *   <li><strong>It is unbounded.</strong> {@code searchMemory} returns every matching event in
 *       every session, unranked, in full. We keep only the {@value #MAX_ENTRIES} most recent and
 *       truncate each text to {@value #MAX_TEXT_CHARS} characters so a broad query can't flood
 *       the model's context.
 * </ul>
 *
 * Ranking by recency is a deliberately simple stand-in: the backend is keyword-only and offers no
 * relevance score to rank by.
 */
public class GuardedMemoryService implements BaseMemoryService {

	static final int MAX_ENTRIES = 8;
	static final int MAX_TEXT_CHARS = 500;

	private static final Logger log = LoggerFactory.getLogger(GuardedMemoryService.class);

	private final BaseMemoryService delegate;

	public GuardedMemoryService(BaseMemoryService delegate) {
		this.delegate = delegate;
	}

	@Override
	public Completable addSessionToMemory(Session session) {
		return delegate.addSessionToMemory(session);
	}

	@Override
	public Single<SearchMemoryResponse> searchMemory(String appName, String userId, String query) {
		return Single.defer(() -> delegate.searchMemory(appName, userId, query))
				.map(GuardedMemoryService::capped)
				.onErrorReturn(
						e -> {
							log.warn("Memory search failed for userId='{}' — continuing without memories: {}", userId, e.toString());
							return SearchMemoryResponse.builder().build();
						});
	}

	private static SearchMemoryResponse capped(SearchMemoryResponse response) {
		List<MemoryEntry> kept =
				response.memories().stream()
						.sorted(Comparator.comparing(MemoryEntry::timestamp).reversed())
						.limit(MAX_ENTRIES)
						.map(GuardedMemoryService::truncated)
						.collect(Collectors.toList());
		return SearchMemoryResponse.builder().setMemories(ImmutableList.copyOf(kept)).build();
	}

	private static MemoryEntry truncated(MemoryEntry entry) {
		Part[] parts =
				entry.content().parts().stream()
						.flatMap(List::stream)
						.map(p -> p.text().map(t -> Part.fromText(t.length() <= MAX_TEXT_CHARS ? t : t.substring(0, MAX_TEXT_CHARS) + "…")).orElse(p))
						.toArray(Part[]::new);
		return MemoryEntry.builder()
				.author(entry.author())
				.timestamp(entry.timestamp())
				.content(Content.fromParts(parts))
				.build();
	}
}
