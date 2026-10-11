package com.wealth.advisor.config;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * BigQuery is used purely as an append-only audit log (see {@code
 * com.wealth.advisor.audit.AgentAuditRecorder}) — every LLM call this app makes gets a row,
 * queryable with real SQL. Chosen over Cloud SQL deliberately: BigQuery is serverless (billed
 * per query/storage, not per hour like an always-on Cloud SQL instance) and its free tier
 * (10GB storage + 1TB queries/month) comfortably covers this project's scale.
 *
 * <p>Same credential story as {@link FirestoreConfig} — no explicit key here, ADC resolves it.
 */
@Configuration
public class BigQueryConfig {

	@Bean
	public BigQuery bigQuery(@Value("${gcp.project-id}") String projectId) {
		return BigQueryOptions.newBuilder().setProjectId(projectId).build().getService();
	}
}
