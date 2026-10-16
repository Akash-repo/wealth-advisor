package com.wealth.advisor.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.adk.tools.ToolContext;
import com.wealth.advisor.constants.LogKeys;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class LogContextTest {

	@AfterEach
	void clean() {
		MDC.clear();
	}

	@Test
	void values_areSetInsideTheScopeAndGoneAfterwards() {
		try (LogContext.Scope ignored = LogContext.with(Map.of(LogKeys.USER_ID, "alice"))) {
			assertThat(MDC.get(LogKeys.USER_ID)).isEqualTo("alice");
		}

		assertThat(MDC.get(LogKeys.USER_ID)).isNull();
	}

	@Test
	void nestedScope_restoresTheOuterValueNotJustClearsIt() {
		try (LogContext.Scope outer = LogContext.with(Map.of(LogKeys.USER_ID, "alice"))) {
			try (LogContext.Scope inner = LogContext.with(Map.of(LogKeys.USER_ID, "bob"))) {
				assertThat(MDC.get(LogKeys.USER_ID)).isEqualTo("bob");
			}
			assertThat(MDC.get(LogKeys.USER_ID)).isEqualTo("alice");
		}
	}

	@Test
	void nullAndBlankValues_areSkipped() {
		Map<String, String> values = new java.util.HashMap<>();
		values.put(LogKeys.USER_ID, null);
		values.put(LogKeys.SESSION_ID, "  ");
		try (LogContext.Scope ignored = LogContext.with(values)) {
			assertThat(MDC.get(LogKeys.USER_ID)).isNull();
			assertThat(MDC.get(LogKeys.SESSION_ID)).isNull();
		}
	}

	@Test
	void forTool_setsUserSessionTurnAndAgent() {
		ToolContext ctx = mock(ToolContext.class);
		when(ctx.userId()).thenReturn("alice");
		when(ctx.sessionId()).thenReturn("s-1");
		when(ctx.invocationId()).thenReturn("inv-1");
		when(ctx.agentName()).thenReturn("portfolio_analysis");

		try (LogContext.Scope ignored = LogContext.forTool(ctx)) {
			assertThat(MDC.getCopyOfContextMap())
					.containsEntry(LogKeys.USER_ID, "alice")
					.containsEntry(LogKeys.SESSION_ID, "s-1")
					.containsEntry(LogKeys.INVOCATION_ID, "inv-1")
					.containsEntry(LogKeys.AGENT, "portfolio_analysis");
		}
		assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
	}

	@Test
	void propagating_carriesTheContextToAnotherThread() throws Exception {
		ExecutorService other = Executors.newSingleThreadExecutor();
		try (LogContext.Scope ignored = LogContext.with(Map.of(LogKeys.USER_ID, "alice"))) {
			String seen = CompletableFuture.supplyAsync(LogContext.propagating(() -> MDC.get(LogKeys.USER_ID)), other).get();

			assertThat(seen).isEqualTo("alice");
		} finally {
			other.shutdown();
		}
	}

	@Test
	void withoutPropagating_theOtherThreadHasNoContext() throws Exception {
		ExecutorService other = Executors.newSingleThreadExecutor();
		try (LogContext.Scope ignored = LogContext.with(Map.of(LogKeys.USER_ID, "alice"))) {
			String seen = CompletableFuture.supplyAsync(() -> MDC.get(LogKeys.USER_ID), other).get();

			assertThat(seen).as("this is why propagating() exists").isNull();
		} finally {
			other.shutdown();
		}
	}

	@Test
	void propagatedTask_doesNotLeakItsContextOntoThePooledThread() throws Exception {
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			try (LogContext.Scope ignored = LogContext.with(Map.of(LogKeys.USER_ID, "alice"))) {
				CompletableFuture.supplyAsync(LogContext.propagating(() -> "x"), pool).get();
			}
			// the same pooled thread now runs an unrelated task: it must not still carry alice
			String leaked = CompletableFuture.supplyAsync(() -> MDC.get(LogKeys.USER_ID), pool).get();

			assertThat(leaked).isNull();
		} finally {
			pool.shutdown();
		}
	}
}
