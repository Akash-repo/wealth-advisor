package com.wealth.advisor.audit;

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
 * <p>{@code @Order(1)} — must run, and create/retrofit {@code agent_audit_log}'s primary key,
 * before {@link ToolUsageSchemaInitializer} tries to create {@code tool_invocations}' foreign key
 * pointing at it; BigQuery requires the referenced primary key to already exist.
 */
@Component
@Order(1)
public class AuditSchemaInitializer implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AuditSchemaInitializer.class);

	static final String DATASET = "wealth_agent";
	static final String TABLE = "agent_audit_log";

	private final BigQuery bigQuery;

	public AuditSchemaInitializer(BigQuery bigQuery) {
		this.bigQuery = bigQuery;
	}

	@Override
	public void run(ApplicationArguments args) {
		try {
			if (bigQuery.getDataset(DATASET) == null) {
				bigQuery.create(DatasetInfo.newBuilder(DATASET).build());
				log.info("Created BigQuery dataset '{}'", DATASET);
			}
			Table table = bigQuery.getTable(DATASET, TABLE);
			if (table == null) {
				bigQuery.create(
						TableInfo.newBuilder(TableId.of(DATASET, TABLE), StandardTableDefinition.of(schema()))
								.setTableConstraints(primaryKeyConstraint())
								.build());
				log.info("Created BigQuery table '{}.{}'", DATASET, TABLE);
			} else if (table.getTableConstraints() == null || table.getTableConstraints().getPrimaryKey() == null) {
				bigQuery.update(table.toBuilder().setTableConstraints(primaryKeyConstraint()).build());
				log.info("Retrofitted primary key constraint onto existing BigQuery table '{}.{}'", DATASET, TABLE);
			}
		} catch (Exception e) {
			log.warn(
					"Could not verify/create the BigQuery audit dataset/table (is bigquery.googleapis.com"
							+ " enabled and reachable?): {}",
					e.getMessage());
		}
	}

	private static TableConstraints primaryKeyConstraint() {
		return TableConstraints.newBuilder()
				.setPrimaryKey(PrimaryKey.newBuilder().setColumns(List.of("user_prompt_id")).build())
				.build();
	}

	private static Schema schema() {
		return Schema.of(
				Field.of("user_prompt_id", StandardSQLTypeName.STRING),
				Field.of("user_id", StandardSQLTypeName.STRING),
				Field.of("session_id", StandardSQLTypeName.STRING),
				Field.of("user_prompt_text", StandardSQLTypeName.STRING),
				Field.of("agent1_name", StandardSQLTypeName.STRING),
				Field.of("agent1_llm_calls", StandardSQLTypeName.JSON),
				Field.of("agent1_latency_ms", StandardSQLTypeName.INT64),
				Field.of("agent1_prompt_tokens", StandardSQLTypeName.INT64),
				Field.of("agent1_completion_tokens", StandardSQLTypeName.INT64),
				Field.of("input_to_agent2", StandardSQLTypeName.STRING),
				Field.of("agent2_name", StandardSQLTypeName.STRING),
				Field.of("agent2_llm_calls", StandardSQLTypeName.JSON),
				Field.of("agent2_latency_ms", StandardSQLTypeName.INT64),
				Field.of("agent2_prompt_tokens", StandardSQLTypeName.INT64),
				Field.of("agent2_completion_tokens", StandardSQLTypeName.INT64),
				Field.of("final_response_to_user", StandardSQLTypeName.STRING),
				Field.of("total_latency_ms", StandardSQLTypeName.INT64),
				Field.of("status", StandardSQLTypeName.STRING),
				Field.of("error_message", StandardSQLTypeName.STRING),
				Field.of("created_at", StandardSQLTypeName.TIMESTAMP));
	}
}
