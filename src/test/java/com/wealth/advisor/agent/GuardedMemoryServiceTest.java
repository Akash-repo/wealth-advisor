package com.wealth.advisor.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.adk.memory.BaseMemoryService;
import com.google.adk.memory.MemoryEntry;
import com.google.adk.memory.SearchMemoryResponse;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GuardedMemoryServiceTest {

	private static BaseMemoryService stub(Single<SearchMemoryResponse> result) {
		return new BaseMemoryService() {
			public Completable addSessionToMemory(Session s) {
				return Completable.complete();
			}

			public Single<SearchMemoryResponse> searchMemory(String app, String user, String q) {
				return result;
			}
		};
	}

	private static MemoryEntry entry(String text, String timestamp) {
		return MemoryEntry.builder().author("user").timestamp(timestamp).content(Content.fromParts(Part.fromText(text))).build();
	}

	@Test
	void backendError_degradesToNoMemories() {
		var guarded = new GuardedMemoryService(stub(Single.error(new RuntimeException("FAILED_PRECONDITION: needs an index"))));

		SearchMemoryResponse response = guarded.searchMemory("app", "alice", "goal").blockingGet();

		assertThat(response.memories()).isEmpty();
	}

	@Test
	void results_areCappedNewestFirstAndTruncated() {
		List<MemoryEntry> many = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			many.add(entry("x".repeat(2000), String.format("2026-01-%02dT00:00:00Z", i + 1)));
		}
		var guarded = new GuardedMemoryService(stub(Single.just(SearchMemoryResponse.builder().setMemories(ImmutableList.copyOf(many)).build())));

		var memories = guarded.searchMemory("app", "alice", "x").blockingGet().memories();

		assertThat(memories).hasSize(GuardedMemoryService.MAX_ENTRIES);
		assertThat(memories.get(0).timestamp()).isEqualTo("2026-01-20T00:00:00Z");
		String text = memories.get(0).content().parts().get().get(0).text().get();
		assertThat(text.length()).isEqualTo(GuardedMemoryService.MAX_TEXT_CHARS + 1);
	}
}
