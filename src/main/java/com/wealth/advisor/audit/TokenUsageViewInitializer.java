package com.wealth.advisor.audit;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.Table;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.bigquery.TableInfo;
import com.google.cloud.bigquery.ViewDefinition;
import com.wealth.advisor.config.AdvisorProperties;
import com.wealth.advisor.constants.BigQueryNames;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Creates (or refreshes) the three token-usage views at startup, after {@link
 * AuditSchemaInitializer} has made sure the columns they read exist. A view whose SQL has not
 * changed is left alone; an edited one is updated in place, so changing {@link TokenUsageSql} takes
 * effect on the next start. Failure to reach BigQuery is logged, never fatal.
 */
@Component
@Order(2)
public class TokenUsageViewInitializer implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(TokenUsageViewInitializer.class);

	private final BigQuery bigQuery;
	private final String dataset;
	private final AdvisorProperties.SchemaMode schemaMode;

	public TokenUsageViewInitializer(BigQuery bigQuery, AdvisorProperties properties) {
		this.bigQuery = bigQuery;
		this.dataset = properties.bigquery().dataset();
		this.schemaMode = properties.bigquery().schemaMode();
	}

	@Override
	public void run(ApplicationArguments args) {
		if (schemaMode == AdvisorProperties.SchemaMode.VERIFY) {
			verify();
			return;
		}
		try {
			String auditTable = "%s.%s.%s".formatted(bigQuery.getOptions().getProjectId(), dataset, BigQueryNames.AGENT_AUDIT_LOG);
			ensureView(BigQueryNames.TOKEN_USAGE_BY_SESSION, TokenUsageSql.bySession(auditTable));
			ensureView(BigQueryNames.TOKEN_USAGE_BY_USER, TokenUsageSql.byUser(auditTable));
			ensureView(BigQueryNames.TOKEN_USAGE_BY_DAY, TokenUsageSql.byDay(auditTable));
		} catch (Exception e) {
			log.warn("Could not create the token-usage views: {}", e.getMessage());
		}
	}

	/** Read-only: reports which views are missing; creates and updates nothing. */
	private void verify() {
		try {
			SchemaVerifier.report(
					log,
					dataset,
					SchemaVerifier.missingTables(
							bigQuery,
							dataset,
							List.of(BigQueryNames.TOKEN_USAGE_BY_SESSION, BigQueryNames.TOKEN_USAGE_BY_USER, BigQueryNames.TOKEN_USAGE_BY_DAY)));
		} catch (Exception e) {
			log.warn("Could not verify the token-usage views: {}", e.getMessage());
		}
	}

	private void ensureView(String name, String sql) {
		TableId id = TableId.of(dataset, name);
		ViewDefinition definition = ViewDefinition.newBuilder(sql).setUseLegacySql(false).build();
		Table existing = bigQuery.getTable(id);
		if (existing == null) {
			bigQuery.create(TableInfo.of(id, definition));
			log.info("Created BigQuery view '{}.{}'", dataset, name);
		} else if (!sql.equals(existing.<ViewDefinition>getDefinition().getQuery())) {
			bigQuery.update(existing.toBuilder().setDefinition(definition).build());
			log.info("Updated BigQuery view '{}.{}'", dataset, name);
		}
	}
}
