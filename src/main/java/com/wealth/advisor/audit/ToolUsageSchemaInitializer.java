package com.wealth.advisor.audit;

import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.BaseTool;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.Clustering;
import com.google.cloud.bigquery.ColumnReference;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.ForeignKey;
import com.google.cloud.bigquery.InsertAllRequest;
import com.google.cloud.bigquery.InsertAllResponse;
import com.google.cloud.bigquery.PrimaryKey;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.Schema;
import com.google.cloud.bigquery.StandardSQLTypeName;
import com.google.cloud.bigquery.StandardTableDefinition;
import com.google.cloud.bigquery.TableConstraints;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.bigquery.TableInfo;
import com.google.cloud.bigquery.TimePartitioning;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Creates {@code wealth_agent.tool_invocations}, {@code wealth_agent.tool_registry_events}, and
 * the {@code wealth_agent.tool_registry} view on startup if missing, and keeps the registry in
 * sync with whatever tools are actually configured in code — same "seed what's missing, warn and
 * keep going if unreachable" pattern as {@link AuditSchemaInitializer}. See {@code
 * ARCHITECTURE.md}'s "Tool usage tracking" section for the full design rationale and the
 * alternatives that were considered and rejected — including the MERGE-based upsert this replaced
 * after live testing surfaced a real duplicate-row race.
 *
 * <p>{@code tool_invocations} — one row per real tool call (written by {@link
 * ToolUsageRecorder#finalizeTurn}), partitioned by day on {@code called_at} and clustered by
 * {@code tool_name}/{@code agent_name} so "usage of tool X over the last N days" style queries
 * stay cheap as call volume grows. {@code invocation_id} is its primary key (ADK's own
 * per-function-call id, see {@link ToolUsageRecorder}) and {@code user_prompt_id} is a {@code NOT
 * ENFORCED} foreign key back to {@link AuditSchemaInitializer}'s {@code agent_audit_log} table —
 * BigQuery doesn't runtime-enforce this, but declaring it documents the relationship and lets BI
 * tools/query generators pick it up automatically.
 *
 * <p>{@code tool_registry_events} / {@code tool_registry} — every tool currently registered on
 * every configured agent gets one <em>appended</em> row in {@code tool_registry_events} per
 * startup (via {@link #syncToolRegistry}, a plain batched {@code insertAll} — never an update, so
 * there's no read-then-write race between concurrent app instances). {@code tool_registry} is a
 * view over it, deduplicated down to one row per {@code tool_name} with the latest description and
 * the earliest/latest sighting — this is the table anything downstream should actually query.
 * Deliberately does <em>not</em> store which agent(s) use a tool — that's usage data, not registry
 * metadata, and is already answerable by joining against {@code tool_invocations} instead of
 * duplicating it here.
 *
 * <p>{@code @Order(2)} — runs after {@link AuditSchemaInitializer} (order 1), which must create
 * or retrofit {@code agent_audit_log}'s primary key before this class's foreign key to it can be
 * declared.
 */
@Component
@Order(2)
public class ToolUsageSchemaInitializer implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(ToolUsageSchemaInitializer.class);

	static final String INVOCATIONS_TABLE = "tool_invocations";
	static final String REGISTRY_EVENTS_TABLE = "tool_registry_events";
	static final String REGISTRY_VIEW = "tool_registry";

	private final BigQuery bigQuery;
	private final List<LlmAgent> agents;

	public ToolUsageSchemaInitializer(BigQuery bigQuery, List<LlmAgent> agents) {
		this.bigQuery = bigQuery;
		this.agents = agents;
	}

	@Override
	public void run(ApplicationArguments args) {
		try {
			ensureTablesExist();
			syncToolRegistry();
		} catch (Exception e) {
			log.warn(
					"Could not verify/create the BigQuery tool-usage tables (is bigquery.googleapis.com"
							+ " enabled and reachable?): {}",
					e.getMessage());
		}
	}

	private void ensureTablesExist() throws InterruptedException {
		if (bigQuery.getTable(AuditSchemaInitializer.DATASET, INVOCATIONS_TABLE) == null) {
			bigQuery.create(
					TableInfo.newBuilder(
									TableId.of(AuditSchemaInitializer.DATASET, INVOCATIONS_TABLE), invocationsDefinition())
							.setTableConstraints(invocationsConstraints())
							.build());
			log.info("Created BigQuery table '{}.{}'", AuditSchemaInitializer.DATASET, INVOCATIONS_TABLE);
		}
		if (bigQuery.getTable(AuditSchemaInitializer.DATASET, REGISTRY_EVENTS_TABLE) == null) {
			bigQuery.create(
					TableInfo.of(
							TableId.of(AuditSchemaInitializer.DATASET, REGISTRY_EVENTS_TABLE), registryEventsDefinition()));
			log.info("Created BigQuery table '{}.{}'", AuditSchemaInitializer.DATASET, REGISTRY_EVENTS_TABLE);
		}
		// Re-issued every startup (idempotent, metadata-only, no data risk) so the view definition
		// can never drift from what this class actually creates it as.
		bigQuery.query(
				QueryJobConfiguration.newBuilder(
								"""
								CREATE OR REPLACE VIEW `%s` AS
								SELECT
								  tool_name,
								  ARRAY_AGG(description ORDER BY seen_at DESC LIMIT 1)[OFFSET(0)] AS description,
								  MIN(seen_at) AS first_registered_at,
								  MAX(seen_at) AS last_seen_at
								FROM `%s`
								GROUP BY tool_name
								"""
								.formatted(
										qualifiedTableRef(REGISTRY_VIEW), qualifiedTableRef(REGISTRY_EVENTS_TABLE)))
						.build());
	}

	/**
	 * Records every tool currently registered on every configured agent as one new "seen at
	 * startup" row each in {@code tool_registry_events} — a single batched {@code insertAll}, run
	 * once per startup, blocking (same justification as {@code AgentChatService}'s use of {@code
	 * blockingGet()}: this happens during app bootstrap, not on a request path).
	 */
	private void syncToolRegistry() {
		Instant now = Instant.now();
		InsertAllRequest.Builder request =
				InsertAllRequest.newBuilder(TableId.of(AuditSchemaInitializer.DATASET, REGISTRY_EVENTS_TABLE));
		for (LlmAgent agent : agents) {
			for (BaseTool tool : agent.tools().blockingGet()) {
				Map<String, Object> row = new HashMap<>();
				row.put("tool_name", tool.name());
				row.put("description", tool.description());
				row.put("seen_at", now.toString());
				request.addRow(row);
			}
		}
		try {
			InsertAllResponse response = bigQuery.insertAll(request.build());
			if (response.hasErrors()) {
				log.error("BigQuery insert errors for tool_registry_events: {}", response.getInsertErrors());
			}
		} catch (Exception e) {
			log.warn("Failed to sync tool_registry_events: {}", e.getMessage());
		}
	}

	private String qualifiedTableRef(String table) {
		return "%s.%s.%s".formatted(bigQuery.getOptions().getProjectId(), AuditSchemaInitializer.DATASET, table);
	}

	private static StandardTableDefinition invocationsDefinition() {
		return StandardTableDefinition.newBuilder()
				.setSchema(
						Schema.of(
								Field.of("invocation_id", StandardSQLTypeName.STRING),
								Field.of("user_prompt_id", StandardSQLTypeName.STRING),
								Field.of("agent_name", StandardSQLTypeName.STRING),
								Field.of("tool_name", StandardSQLTypeName.STRING),
								Field.of("tool_input", StandardSQLTypeName.JSON),
								Field.of("tool_output", StandardSQLTypeName.JSON),
								Field.of("status", StandardSQLTypeName.STRING),
								Field.of("error_message", StandardSQLTypeName.STRING),
								Field.of("latency_ms", StandardSQLTypeName.INT64),
								Field.of("called_at", StandardSQLTypeName.TIMESTAMP)))
				.setTimePartitioning(TimePartitioning.newBuilder(TimePartitioning.Type.DAY).setField("called_at").build())
				.setClustering(Clustering.newBuilder().setFields(List.of("tool_name", "agent_name")).build())
				.build();
	}

	// Instance method, not static: the referenced table's TableId must be fully project-qualified
	// (a bare dataset+table TableId resolves fine for reads/writes but BigQuery rejects it as an
	// invalid foreign key target) — bigQuery.getOptions().getProjectId() is the project this
	// BigQuery client is actually configured against.
	private TableConstraints invocationsConstraints() {
		return TableConstraints.newBuilder()
				.setPrimaryKey(PrimaryKey.newBuilder().setColumns(List.of("invocation_id")).build())
				.setForeignKeys(
						List.of(
								ForeignKey.newBuilder()
										.setName("fk_tool_invocations_user_prompt_id")
										.setReferencedTable(
												TableId.of(
														bigQuery.getOptions().getProjectId(),
														AuditSchemaInitializer.DATASET,
														AuditSchemaInitializer.TABLE))
										.setColumnReferences(
												List.of(
														ColumnReference.newBuilder()
																.setReferencingColumn("user_prompt_id")
																.setReferencedColumn("user_prompt_id")
																.build()))
										.build()))
				.build();
	}

	private static StandardTableDefinition registryEventsDefinition() {
		return StandardTableDefinition.of(
				Schema.of(
						Field.of("tool_name", StandardSQLTypeName.STRING),
						Field.of("description", StandardSQLTypeName.STRING),
						Field.of("seen_at", StandardSQLTypeName.TIMESTAMP)));
	}
}
