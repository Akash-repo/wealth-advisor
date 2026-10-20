package com.wealth.advisor.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import com.wealth.advisor.config.AdvisorProperties;
import com.wealth.advisor.config.TestProperties;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * {@code schema-mode=verify} is what keeps a deployed revision (or anything pointed at production) from
 * changing the BigQuery schema: it may look, and it may never create, update, insert or query-to-modify.
 * {@code apply} still builds what is missing, in the configured dataset.
 */
class SchemaModeTest {

	private static final Set<String> CHANGING_CALLS = Set.of("create", "update", "delete", "insertAll", "query");

	private BigQuery bigQuery;

	@BeforeEach
	void setUp() {
		bigQuery = mock(BigQuery.class);
		BigQueryOptions options = mock(BigQueryOptions.class);
		when(options.getProjectId()).thenReturn("a-project");
		when(bigQuery.getOptions()).thenReturn(options);
		// everything is missing: no dataset, no tables, no views
		when(bigQuery.getDataset(org.mockito.ArgumentMatchers.anyString())).thenReturn(null);
		when(bigQuery.getTable(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(null);
	}

	private AdvisorProperties mode(AdvisorProperties.SchemaMode mode) {
		return TestProperties.defaults().withBigquery(new AdvisorProperties.Bigquery("the_dataset", mode));
	}

	private List<String> callsThatChangeSomething() {
		return mockingCalls().stream().filter(CHANGING_CALLS::contains).toList();
	}

	private List<String> mockingCalls() {
		return org.mockito.Mockito.mockingDetails(bigQuery).getInvocations().stream().map(Invocation::getMethod).map(m -> m.getName()).toList();
	}

	@Test
	void verifyModeNeverChangesAnythingEvenWhenEverythingIsMissing() throws Exception {
		AdvisorProperties verify = mode(AdvisorProperties.SchemaMode.VERIFY);

		new AuditSchemaInitializer(bigQuery, verify).run(new DefaultApplicationArguments());
		new ToolUsageSchemaInitializer(bigQuery, List.of(), verify).run(new DefaultApplicationArguments());
		new TokenUsageViewInitializer(bigQuery, verify).run(new DefaultApplicationArguments());

		assertThat(callsThatChangeSomething()).isEmpty();
		assertThat(mockingCalls()).contains("getTable");
	}

	@Test
	void verifyModeLooksInTheConfiguredDataset() throws Exception {
		new AuditSchemaInitializer(bigQuery, mode(AdvisorProperties.SchemaMode.VERIFY)).run(new DefaultApplicationArguments());

		org.mockito.Mockito.verify(bigQuery, org.mockito.Mockito.atLeastOnce()).getTable("the_dataset", "agent_audit_log");
	}

	@Test
	void applyModeCreatesWhatIsMissingInTheConfiguredDataset() throws Exception {
		new AuditSchemaInitializer(bigQuery, mode(AdvisorProperties.SchemaMode.APPLY)).run(new DefaultApplicationArguments());

		assertThat(callsThatChangeSomething()).contains("create");
		org.mockito.Mockito.verify(bigQuery, org.mockito.Mockito.atLeastOnce()).getDataset("the_dataset");
	}
}
