package com.wealth.advisor.audit;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.Table;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.bigquery.TableInfo;
import com.google.cloud.bigquery.ViewDefinition;
import com.wealth.advisor.constants.BigQueryNames;
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

	public TokenUsageViewInitializer(BigQuery bigQuery) {
		this.bigQuery = bigQuery;
	}

	@Override
	public void run(ApplicationArguments args) {
		try {
			String auditTable = "%s.%s.%s".formatted(bigQuery.getOptions().getProjectId(), BigQueryNames.DATASET, BigQueryNames.AGENT_AUDIT_LOG);
			ensureView(BigQueryNames.TOKEN_USAGE_BY_SESSION, TokenUsageSql.bySession(auditTable));
			ensureView(BigQueryNames.TOKEN_USAGE_BY_USER, TokenUsageSql.byUser(auditTable));
			ensureView(BigQueryNames.TOKEN_USAGE_BY_DAY, TokenUsageSql.byDay(auditTable));
		} catch (Exception e) {
			log.warn("Could not create the token-usage views: {}", e.getMessage());
		}
	}

	private void ensureView(String name, String sql) {
		TableId id = TableId.of(BigQueryNames.DATASET, name);
		ViewDefinition definition = ViewDefinition.newBuilder(sql).setUseLegacySql(false).build();
		Table existing = bigQuery.getTable(id);
		if (existing == null) {
			bigQuery.create(TableInfo.of(id, definition));
			log.info("Created BigQuery view '{}.{}'", BigQueryNames.DATASET, name);
		} else if (!sql.equals(existing.<ViewDefinition>getDefinition().getQuery())) {
			bigQuery.update(existing.toBuilder().setDefinition(definition).build());
			log.info("Updated BigQuery view '{}.{}'", BigQueryNames.DATASET, name);
		}
	}
}
