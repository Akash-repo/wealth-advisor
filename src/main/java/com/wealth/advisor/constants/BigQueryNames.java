package com.wealth.advisor.constants;

/**
 * BigQuery dataset, table and view names for the audit and tool-usage data. Constants rather than
 * configuration: the schema initializers create these, and queries, dashboards and docs refer to
 * them by name.
 */
public final class BigQueryNames {

	private BigQueryNames() {}

	public static final String DATASET = "wealth_agent";
	public static final String AGENT_AUDIT_LOG = "agent_audit_log";
	public static final String TOOL_INVOCATIONS = "tool_invocations";
	public static final String TOOL_REGISTRY_EVENTS = "tool_registry_events";
	public static final String TOOL_REGISTRY_VIEW = "tool_registry";
}
