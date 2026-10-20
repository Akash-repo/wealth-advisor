package com.wealth.advisor.constants;

/**
 * BigQuery table and view names for the audit and tool-usage data. Constants rather than
 * configuration: the schema initializers create these, and queries, dashboards and docs refer to
 * them by name. The <em>dataset</em> they live in is configuration ({@code advisor.bigquery.dataset}),
 * so local runs and production never share one.
 */
public final class BigQueryNames {

	private BigQueryNames() {}

	public static final String AGENT_AUDIT_LOG = "agent_audit_log";
	public static final String TOOL_INVOCATIONS = "tool_invocations";
	public static final String TOOL_REGISTRY_EVENTS = "tool_registry_events";
	public static final String TOOL_REGISTRY_VIEW = "tool_registry";
	public static final String TOKEN_USAGE_BY_SESSION = "token_usage_by_session";
	public static final String TOKEN_USAGE_BY_USER = "token_usage_by_user";
	public static final String TOKEN_USAGE_BY_DAY = "token_usage_by_day";
}
