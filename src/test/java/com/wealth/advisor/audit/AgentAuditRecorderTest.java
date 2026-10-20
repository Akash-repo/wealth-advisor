package com.wealth.advisor.audit;

import com.wealth.advisor.config.TestProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.adk.agents.CallbackContext;
import com.google.adk.models.LlmRequest;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.InsertAllRequest;
import com.google.cloud.bigquery.InsertAllResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Regression test for the multi-session fix: one user running turns in two sessions at once (two
 * browser tabs) must not have one session's in-flight turn overwrite the other's. Before turns were
 * keyed by (userId, sessionId) the second {@code beforeModel} replaced the first's invocation id,
 * so finalizing session A wrote session B's row and session B's finalize found nothing.
 */
class AgentAuditRecorderTest {

	private static CallbackContext ctx(String userId, String sessionId, String invocationId) {
		CallbackContext ctx = mock(CallbackContext.class);
		when(ctx.userId()).thenReturn(userId);
		when(ctx.sessionId()).thenReturn(sessionId);
		when(ctx.invocationId()).thenReturn(invocationId);
		when(ctx.agentName()).thenReturn("agent1");
		when(ctx.userContent()).thenReturn(java.util.Optional.empty());
		return ctx;
	}

	@Test
	void concurrentSessionsOfOneUserAreFinalizedIndependently() {
		BigQuery bigQuery = mock(BigQuery.class);
		InsertAllResponse ok = mock(InsertAllResponse.class);
		when(ok.hasErrors()).thenReturn(false);
		when(bigQuery.insertAll(any(InsertAllRequest.class))).thenReturn(ok);
		AgentAuditRecorder recorder = new AgentAuditRecorder(bigQuery, new com.fasterxml.jackson.databind.ObjectMapper(), TestProperties.defaults());

		recorder.beforeModelCallback(ctx("alice", "session-A", "inv-A"), LlmRequest.builder());
		recorder.beforeModelCallback(ctx("alice", "session-B", "inv-B"), LlmRequest.builder());

		recorder.finalizeTurn("alice", "session-A", "agent1", "agent2", "", "reply A", 1, "SUCCESS", null);
		recorder.finalizeTurn("alice", "session-B", "agent1", "agent2", "", "reply B", 1, "SUCCESS", null);

		ArgumentCaptor<InsertAllRequest> captor = ArgumentCaptor.forClass(InsertAllRequest.class);
		verify(bigQuery, Mockito.times(2)).insertAll(captor.capture());
		var rowA = captor.getAllValues().get(0).getRows().get(0).getContent();
		var rowB = captor.getAllValues().get(1).getRows().get(0).getContent();
		assertThat(rowA).containsEntry("user_prompt_id", "inv-A").containsEntry("session_id", "session-A");
		assertThat(rowB).containsEntry("user_prompt_id", "inv-B").containsEntry("session_id", "session-B");
	}

	@Test
	void tokenColumns_includeCachedAndThinkingTokens() {
		BigQuery bigQuery = mock(BigQuery.class);
		InsertAllResponse ok = mock(InsertAllResponse.class);
		when(ok.hasErrors()).thenReturn(false);
		when(bigQuery.insertAll(any(InsertAllRequest.class))).thenReturn(ok);
		AgentAuditRecorder recorder = new AgentAuditRecorder(bigQuery, new com.fasterxml.jackson.databind.ObjectMapper(), TestProperties.defaults());
		CallbackContext ctx = ctx("alice", "session-A", "inv-A");

		recorder.beforeModelCallback(ctx, LlmRequest.builder());
		recorder.afterModelCallback(
				ctx,
				com.google.adk.models.LlmResponse.builder()
						.usageMetadata(
								com.google.genai.types.GenerateContentResponseUsageMetadata.builder()
										.promptTokenCount(100)
										.cachedContentTokenCount(40)
										.candidatesTokenCount(10)
										.thoughtsTokenCount(25)
										.build())
						.build());
		recorder.finalizeTurn("alice", "session-A", "agent1", "agent2", "", "reply", 1, "SUCCESS", null);

		ArgumentCaptor<InsertAllRequest> captor = ArgumentCaptor.forClass(InsertAllRequest.class);
		verify(bigQuery).insertAll(captor.capture());
		assertThat(captor.getValue().getRows().get(0).getContent())
				.containsEntry("agent1_prompt_tokens", 100)
				.containsEntry("agent1_cached_tokens", 40)
				.containsEntry("agent1_completion_tokens", 10)
				.containsEntry("agent1_thinking_tokens", 25);
	}
}
