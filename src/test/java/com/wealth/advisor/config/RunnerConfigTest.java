package com.wealth.advisor.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.apps.App;
import com.google.adk.plugins.Plugin;
import com.google.cloud.bigquery.BigQuery;
import com.google.genai.Client;
import com.wealth.advisor.audit.AgentAuditRecorder;
import com.wealth.advisor.audit.ToolUsageRecorder;
import org.junit.jupiter.api.Test;

/**
 * Pins the wiring that is easiest to lose silently: if the audit plugins are not registered on the
 * ADK {@code App}, the app still compiles and chats normally but stops writing audit rows. (This
 * happened once during the move from per-agent callbacks to plugins, and was only caught live.)
 */
class RunnerConfigTest {

	private final BigQuery bigQuery = mock(BigQuery.class);
	private final ObjectMapper mapper = new ObjectMapper();

	private App buildApp() {
		LlmAgent agent = LlmAgent.builder().name("agent_one").model("gemini-2.5-flash").instruction("x").build();
		SequentialAgent pipeline = SequentialAgent.builder().name("pipeline").subAgents(agent).build();
		return new RunnerConfig()
				.advisorApp(
						pipeline,
						mock(Client.class),
						TestProperties.defaults(),
						new AgentAuditRecorder(bigQuery, mapper),
						new ToolUsageRecorder(bigQuery, mapper));
	}

	@Test
	void auditPlugins_areRegisteredOnTheApp() {
		assertThat(buildApp().plugins()).extracting(Plugin::getName).contains("agent_audit_recorder", "tool_usage_recorder");
	}

	@Test
	void compaction_usesTheConfiguredThresholds() {
		var compaction = buildApp().eventsCompactionConfig();

		assertThat(compaction.tokenThreshold()).isEqualTo(TestProperties.defaults().compaction().tokenThreshold());
		assertThat(compaction.eventRetentionSize()).isEqualTo(TestProperties.defaults().compaction().eventRetentionSize());
	}
}
