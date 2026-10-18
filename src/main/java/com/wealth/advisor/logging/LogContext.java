package com.wealth.advisor.logging;

import com.google.adk.tools.ToolContext;
import com.wealth.advisor.constants.LogKeys;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.MDC;

/**
 * Puts identifying values (user, session, request...) onto every log line written while a scope is
 * open, and takes them off again afterwards.
 *
 * <p>MDC is per <em>thread</em>, and a chat turn does not stay on the request thread: ADK runs tools
 * on gRPC executor threads, and parts of the allocation tool run on virtual threads. So the context
 * is opened where the work actually happens (see {@link #forTool}) and carried across explicit
 * thread hops with {@link #propagating}. Every scope <em>restores</em> whatever was there before,
 * which matters on pooled threads: leaving a value behind would stamp the next, unrelated task
 * with another user's id.
 *
 * <pre>{@code
 * try (LogContext.Scope ignored = LogContext.with(Map.of(LogKeys.USER_ID, userId))) {
 *     log.info("...");   // carries userId
 * }                      // userId removed again here
 * }</pre>
 */
public final class LogContext {

	private LogContext() {}

	/** An open logging context; closing it restores the previous values. */
	public static final class Scope implements AutoCloseable {

		private final Map<String, String> previous;

		private Scope(Map<String, String> previous) {
			this.previous = previous;
		}

		@Override
		public void close() {
			previous.forEach((key, old) -> restore(key, old));
		}

		private static void restore(String key, String old) {
			if (old == null) {
				MDC.remove(key);
			} else {
				MDC.put(key, old);
			}
		}
	}

	/** Sets the given values (null or blank ones are skipped) until the returned scope is closed. */
	public static Scope with(Map<String, String> values) {
		Map<String, String> previous = new HashMap<>();
		values.forEach(
				(key, value) -> {
					if (value != null && !value.isBlank()) {
						previous.put(key, MDC.get(key));
						MDC.put(key, value);
					}
				});
		return new Scope(previous);
	}

	/** The context of a running tool call: who asked, in which conversation and turn, via which agent. */
	public static Scope forTool(ToolContext toolContext) {
		return with(
				Map.of(
						LogKeys.USER_ID, toolContext.userId(),
						LogKeys.SESSION_ID, toolContext.sessionId(),
						LogKeys.INVOCATION_ID, toolContext.invocationId(),
						LogKeys.AGENT, toolContext.agentName()));
	}

	/**
	 * Wraps a task so it runs with the context of the thread that <em>created</em> it, whichever
	 * thread eventually runs it. Use this when handing work to another executor.
	 */
	public static <T> Supplier<T> propagating(Supplier<T> task) {
		Map<String, String> snapshot = MDC.getCopyOfContextMap();
		return () -> {
			try (Scope ignored = with(snapshot == null ? Map.of() : snapshot)) {
				return task.get();
			}
		};
	}
}
