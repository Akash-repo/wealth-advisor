package com.wealth.advisor.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealth.advisor.constants.LogKeys;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class GoogleCloudLogFormatterTest {

	private final GoogleCloudLogFormatter formatter =
			new GoogleCloudLogFormatter(new MockEnvironment().withProperty("gcp.project-id", "my-project"));
	private final Logger logger = new LoggerContext().getLogger("com.wealth.advisor.Demo");

	private LoggingEvent event(Level level, String message, Map<String, String> mdc, Throwable error) {
		LoggingEvent event = new LoggingEvent("fqcn", logger, level, message, error, null);
		event.setMDCPropertyMap(mdc);
		return event;
	}

	private JsonNode parse(String line) throws Exception {
		return new ObjectMapper().readTree(line);
	}

	@Test
	void output_isOneJsonObjectPerLine() throws Exception {
		String line = formatter.format(event(Level.INFO, "hello", Map.of(), null));

		assertThat(line).endsWith("\n");
		assertThat(line.trim()).doesNotContain("\n");
		assertThat(parse(line).path("message").asText()).isEqualTo("hello");
	}

	@Test
	void severity_usesCloudLoggingNames() throws Exception {
		assertThat(parse(formatter.format(event(Level.ERROR, "x", Map.of(), null))).path("severity").asText()).isEqualTo("ERROR");
		assertThat(parse(formatter.format(event(Level.WARN, "x", Map.of(), null))).path("severity").asText()).isEqualTo("WARNING");
		assertThat(parse(formatter.format(event(Level.INFO, "x", Map.of(), null))).path("severity").asText()).isEqualTo("INFO");
		assertThat(parse(formatter.format(event(Level.DEBUG, "x", Map.of(), null))).path("severity").asText()).isEqualTo("DEBUG");
		assertThat(parse(formatter.format(event(Level.TRACE, "x", Map.of(), null))).path("severity").asText()).isEqualTo("DEBUG");
	}

	@Test
	void context_becomesQueryableTopLevelFields() throws Exception {
		JsonNode json =
				parse(
						formatter.format(
								event(
										Level.INFO,
										"x",
										Map.of(LogKeys.USER_ID, "alice", LogKeys.SESSION_ID, "s-1", LogKeys.REQUEST_ID, "r-1", LogKeys.AGENT, "portfolio_analysis"),
										null)));

		assertThat(json.path("userId").asText()).isEqualTo("alice");
		assertThat(json.path("sessionId").asText()).isEqualTo("s-1");
		assertThat(json.path("requestId").asText()).isEqualTo("r-1");
		assertThat(json.path("agent").asText()).isEqualTo("portfolio_analysis");
	}

	@Test
	void absentContext_isOmittedRatherThanEmpty() throws Exception {
		JsonNode json = parse(formatter.format(event(Level.INFO, "x", Map.of(), null)));

		assertThat(json.has("userId")).isFalse();
		assertThat(json.has("sessionId")).isFalse();
		assertThat(json.has("logging.googleapis.com/trace")).isFalse();
	}

	@Test
	void traceId_isLinkedToTheProjectSoCloudLoggingGroupsTheLines() throws Exception {
		JsonNode json =
				parse(formatter.format(event(Level.INFO, "x", Map.of(LogKeys.TRACE_ID, "105445aa7843bc8bf206b12000100000"), null)));

		assertThat(json.path("logging.googleapis.com/trace").asText())
				.isEqualTo("projects/my-project/traces/105445aa7843bc8bf206b12000100000");
	}

	@Test
	void exception_stackTraceIsAppendedToTheMessage() throws Exception {
		JsonNode json = parse(formatter.format(event(Level.ERROR, "Turn failed", Map.of(), new IllegalStateException("boom"))));

		assertThat(json.path("message").asText()).startsWith("Turn failed").contains("IllegalStateException: boom");
	}

	@Test
	void messagesWithQuotesAndNewlines_stayOnOneValidLine() throws Exception {
		String line = formatter.format(event(Level.INFO, "he said \"hi\"\nsecond line", Map.of(), null));

		assertThat(line.trim()).doesNotContain("\n");
		assertThat(parse(line).path("message").asText()).isEqualTo("he said \"hi\"\nsecond line");
	}
}
