package com.wealth.advisor.constants;

/**
 * The MDC (per-thread diagnostic context) keys attached to log lines, so one user, one request or
 * one conversation can be followed through the logs. These are also the field names in the
 * structured JSON logs, so renaming one changes every saved log query.
 */
public final class LogKeys {

	private LogKeys() {}

	/** The authenticated user. */
	public static final String USER_ID = "userId";

	/** The conversation (ADK session). */
	public static final String SESSION_ID = "sessionId";

	/** One chat turn, as ADK numbers it; also {@code agent_audit_log.user_prompt_id}. */
	public static final String INVOCATION_ID = "invocationId";

	/** The agent whose tool is running. */
	public static final String AGENT = "agent";

	/** One HTTP request. */
	public static final String REQUEST_ID = "requestId";

	/** The Cloud Trace id from Cloud Run's {@code X-Cloud-Trace-Context} header, when present. */
	public static final String TRACE_ID = "traceId";
}
