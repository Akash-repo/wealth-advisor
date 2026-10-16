package com.wealth.advisor.audit;

import com.wealth.advisor.constants.BigQueryNames;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.DatasetInfo;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.PrimaryKey;
import com.google.cloud.bigquery.Schema;
import com.google.cloud.bigquery.StandardSQLTypeName;
import com.google.cloud.bigquery.StandardTableDefinition;
import com.google.cloud.bigquery.Table;
import com.google.cloud.bigquery.TableConstraints;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.bigquery.TableInfo;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Creates the {@code wealth_agent.agent_audit_log} BigQuery dataset/table on startup if they
 * don't already exist — same "seed what's missing, log a warning and keep going if unreachable"
 * pattern as {@code DemoUserSeeder}, just for a different piece of infrastructure.
 *
 * <p>One row per user chat turn, written by {@link AgentAuditRecorder#finalizeTurn}. {@code
 * agent1_llm_calls}/{@code agent2_llm_calls} are JSON arrays (one element per actual Gemini call
 * that agent made — usually one, more if it called a tool and needed a follow-up call to react
 * to the tool's result) rather than a single request/response pair, because one agent can
 * genuinely call the LLM more than once within a single user turn.
 *
 * <p>Declares {@code user_prompt_id} as this table's primary key (BigQuery {@code NOT ENFORCED}
 * — metadata only, see {@code ARCHITECTURE.md}'s "Tool usage tracking" section for why that's
 * still worth doing) so {@code tool_invocations} can declare a foreign key back to it. Existing
 * deployments that already had this table before that constraint was added get it retrofitted
 * on the next startup rather than requiring a manual migration.
 *
 * <p>Schema evolution: a column added to {@link #schema()} later (for example the cached and
 * thinking token columns) is added to the live table in place on the next startup, as a nullable
 * column so existing rows stay valid. Columns are only ever added, never changed or removed. The
 * token-usage views over this table are created by {@link TokenUsageViewInitializer}.
 *
 * <p>{@code @Order(1)} — must run, and create/retrofit {@code agent_audit_log}'s primary key,
 * before {@link ToolUsageSchemaInitializer} tries to create {@code tool_invocations}' foreign key
 * pointing at it; BigQuery requires the referenced primary key to already exist.
 */
@Component
@Order(1)
public class AuditSchemaInitializer implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AuditSchemaInitializer.class);


	private final BigQuery bigQuery;

	public AuditSchemaInitializer(BigQuery bigQuery) {
		this.bigQuery = bigQuery;
	}

	@Override
	public void run(ApplicationArguments args) {
		try {
			ensureDataset();
			Table table = auditTable();
			if (table == null) {
				createTable();
			} else {
				addMissingColumns(table);
				ensurePrimaryKey(auditTable());
			}
		} catch (Exception e) {
			log.warn(
					"Could not verify/create the BigQuery audit dataset/table (is bigquery.googleapis.com"
							+ " enabled and reachable?): {}",
					e.getMessage());
		}
	}

	private void ensureDataset() {
		if (bigQuery.getDataset(BigQueryNames.DATASET) == null) {
			bigQuery.create(DatasetInfo.newBuilder(BigQueryNames.DATASET).build());
			log.info("Created BigQuery dataset '{}'", BigQueryNames.DATASET);
		}
	}

	private Table auditTable() {
		return bigQuery.getTable(BigQueryNames.DATASET, BigQueryNames.AGENT_AUDIT_LOG);
	}

	private void createTable() {
		bigQuery.create(
				TableInfo.newBuilder(TableId.of(BigQueryNames.DATASET, BigQueryNames.AGENT_AUDIT_LOG), StandardTableDefinition.of(schema()))
						.setTableConstraints(primaryKeyConstraint())
						.build());
		log.info("Created BigQuery table '{}.{}'", BigQueryNames.DATASET, BigQueryNames.AGENT_AUDIT_LOG);
	}

	/** Schema evolution: columns added to {@link #schema()} later are added to the live table in place. */
	private void addMissingColumns(Table table) {
		Schema current = table.<StandardTableDefinition>getDefinition().getSchema();
		Schema evolved = withMissingColumns(current, schema());
		if (evolved.getFields().size() > current.getFields().size()) {
			bigQuery.update(table.toBuilder().setDefinition(StandardTableDefinition.of(evolved)).build());
			log.info("Added {} column(s) to BigQuery table '{}.{}'", evolved.getFields().size() - current.getFields().size(), BigQueryNames.DATASET, BigQueryNames.AGENT_AUDIT_LOG);
		}
	}

	/**
	 * The current schema with every column of {@code desired} that it lacks appended (as nullable, so
	 * existing rows stay valid). Columns already present are never changed or removed.
	 */
	static Schema withMissingColumns(Schema current, Schema desired) {
		List<Field> merged = new ArrayList<>(current.getFields());
		desired.getFields().stream()
				.filter(wanted -> current.getFields().stream().noneMatch(existing -> existing.getName().equals(wanted.getName())))
				.forEach(merged::add);
		return Schema.of(merged);
	}

	private void ensurePrimaryKey(Table table) {
		if (table.getTableConstraints() == null || table.getTableConstraints().getPrimaryKey() == null) {
			bigQuery.update(table.toBuilder().setTableConstraints(primaryKeyConstraint()).build());
			log.info("Retrofitted primary key constraint onto existing BigQuery table '{}.{}'", BigQueryNames.DATASET, BigQueryNames.AGENT_AUDIT_LOG);
		}
	}

	private static TableConstraints primaryKeyConstraint() {
		return TableConstraints.newBuilder()
				.setPrimaryKey(PrimaryKey.newBuilder().setColumns(List.of("user_prompt_id")).build())
				.build();
	}

	static Schema schema() {
		return Schema.of(
				Field.of("user_prompt_id", StandardSQLTypeName.STRING),
				Field.of("user_id", StandardSQLTypeName.STRING),
				Field.of("session_id", StandardSQLTypeName.STRING),
				Field.of("user_prompt_text", StandardSQLTypeName.STRING),
				Field.of("agent1_name", StandardSQLTypeName.STRING),
				Field.of("agent1_llm_calls", StandardSQLTypeName.JSON),
				Field.of("agent1_latency_ms", StandardSQLTypeName.INT64),
				Field.of("agent1_prompt_tokens", StandardSQLTypeName.INT64),
				Field.of("agent1_cached_tokens", StandardSQLTypeName.INT64),
				Field.of("agent1_completion_tokens", StandardSQLTypeName.INT64),
				Field.of("agent1_thinking_tokens", StandardSQLTypeName.INT64),
				Field.of("input_to_agent2", StandardSQLTypeName.STRING),
				Field.of("agent2_name", StandardSQLTypeName.STRING),
				Field.of("agent2_llm_calls", StandardSQLTypeName.JSON),
				Field.of("agent2_latency_ms", StandardSQLTypeName.INT64),
				Field.of("agent2_prompt_tokens", StandardSQLTypeName.INT64),
				Field.of("agent2_cached_tokens", StandardSQLTypeName.INT64),
				Field.of("agent2_completion_tokens", StandardSQLTypeName.INT64),
				Field.of("agent2_thinking_tokens", StandardSQLTypeName.INT64),
				Field.of("final_response_to_user", StandardSQLTypeName.STRING),
				Field.of("total_latency_ms", StandardSQLTypeName.INT64),
				Field.of("status", StandardSQLTypeName.STRING),
				Field.of("error_message", StandardSQLTypeName.STRING),
				Field.of("created_at", StandardSQLTypeName.TIMESTAMP));
	}
}
