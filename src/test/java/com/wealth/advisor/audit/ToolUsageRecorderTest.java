package com.wealth.advisor.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.InsertAllRequest;
import com.google.cloud.bigquery.InsertAllResponse;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The recorder is an ADK global {@code Plugin}: ADK calls these methods for every agent's tool
 * calls. The tests drive the plugin methods the way ADK does and check what reaches BigQuery.
 */
class ToolUsageRecorderTest {

	private BigQuery bigQuery;
	private ToolUsageRecorder recorder;
	private BaseTool tool;

	@BeforeEach
	void setUp() {
		bigQuery = mock(BigQuery.class);
		InsertAllResponse ok = mock(InsertAllResponse.class);
		when(ok.hasErrors()).thenReturn(false);
		when(bigQuery.insertAll(any(InsertAllRequest.class))).thenReturn(ok);
		recorder = new ToolUsageRecorder(bigQuery, new ObjectMapper());
		tool = mock(BaseTool.class);
		when(tool.name()).thenReturn("getStockData");
	}

	private static ToolContext context(String callId) {
		ToolContext ctx = mock(ToolContext.class);
		when(ctx.userId()).thenReturn("alice");
		when(ctx.sessionId()).thenReturn("session-1");
		when(ctx.invocationId()).thenReturn("inv-1");
		when(ctx.agentName()).thenReturn("portfolio_analysis");
		when(ctx.functionCallId()).thenReturn(Optional.of(callId));
		return ctx;
	}

	private Map<String, Object> onlyRowWritten() {
		ArgumentCaptor<InsertAllRequest> captor = ArgumentCaptor.forClass(InsertAllRequest.class);
		verify(bigQuery).insertAll(captor.capture());
		assertThat(captor.getValue().getRows()).hasSize(1);
		return captor.getValue().getRows().get(0).getContent();
	}

	@Test
	void isRegisteredUnderAStableName() {
		assertThat(recorder.getName()).isEqualTo("tool_usage_recorder");
	}

	@Test
	void successfulCall_isRecordedWithInputAndOutputAndNeverShortCircuits() {
		ToolContext ctx = context("fc-1");

		assertThat(recorder.beforeToolCallback(tool, Map.of("ticker", "NVDA"), ctx).isEmpty().blockingGet()).isTrue();
		assertThat(recorder.afterToolCallback(tool, Map.of("ticker", "NVDA"), ctx, Map.of("price", 131.2)).isEmpty().blockingGet())
				.isTrue();
		recorder.finalizeTurn("alice", "session-1");

		Map<String, Object> row = onlyRowWritten();
		assertThat(row)
				.containsEntry("invocation_id", "fc-1")
				.containsEntry("user_prompt_id", "inv-1")
				.containsEntry("agent_name", "portfolio_analysis")
				.containsEntry("tool_name", "getStockData")
				.containsEntry("status", "SUCCESS");
		assertThat(row.get("tool_input").toString()).contains("NVDA");
		assertThat(row.get("tool_output").toString()).contains("131.2");
	}

	@Test
	void failedCall_isRecordedAsAnErrorWithItsMessageAndIsNotSuppressed() {
		ToolContext ctx = context("fc-2");

		recorder.beforeToolCallback(tool, Map.of(), ctx).blockingGet();
		assertThat(recorder.onToolErrorCallback(tool, Map.of(), ctx, new IllegalStateException("firestore down")).isEmpty().blockingGet())
				.isTrue();
		recorder.finalizeTurn("alice", "session-1");

		assertThat(onlyRowWritten()).containsEntry("status", "ERROR").containsEntry("error_message", "firestore down");
	}

	@Test
	void turnWithNoToolCalls_writesNothing() {
		recorder.finalizeTurn("alice", "session-1");

		verify(bigQuery, never()).insertAll(any(InsertAllRequest.class));
	}
}
