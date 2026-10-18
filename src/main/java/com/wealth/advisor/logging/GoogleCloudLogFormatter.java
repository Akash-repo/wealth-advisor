package com.wealth.advisor.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealth.advisor.constants.LogKeys;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.logging.structured.StructuredLogFormatter;
import org.springframework.core.env.Environment;

/**
 * One JSON object per log line, shaped the way Cloud Logging reads it, so lines can be filtered by
 * field instead of searched as text:
 *
 * <pre>{@code
 * jsonPayload.userId="demo" AND severity>=WARNING
 * jsonPayload.sessionId="121f8cca-..."
 * }</pre>
 *
 * <p>Why a custom formatter: Spring Boot's built-in JSON formats (ECS, GELF, Logstash) name the
 * level {@code log.level} or {@code level}, but Cloud Logging only recognizes {@code severity}, so
 * every line would show as the default severity and error filters would find nothing. Cloud Logging
 * also links an app line to the platform's own request log when it carries {@code
 * logging.googleapis.com/trace}, which is built from the project id and the Cloud Trace id the
 * request interceptor stored in the MDC.
 *
 * <p>Enabled by the {@code cloud} profile ({@code application-cloud.yml}); local runs keep the
 * readable text format.
 */
public class GoogleCloudLogFormatter implements StructuredLogFormatter<ILoggingEvent> {

	private static final String TRACE_FIELD = "logging.googleapis.com/trace";
	private static final List<String> CONTEXT_KEYS =
			List.of(LogKeys.USER_ID, LogKeys.SESSION_ID, LogKeys.REQUEST_ID, LogKeys.INVOCATION_ID, LogKeys.AGENT);

	private final ObjectMapper json = new ObjectMapper();
	private final String projectId;

	public GoogleCloudLogFormatter(Environment environment) {
		this.projectId = environment.getProperty("gcp.project-id");
	}

	@Override
	public String format(ILoggingEvent event) {
		try {
			return json.writeValueAsString(toFields(event)) + "\n";
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Could not serialize a log event", e);
		}
	}

	private Map<String, Object> toFields(ILoggingEvent event) {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("severity", severity(event.getLevel()));
		fields.put("timestamp", Instant.ofEpochMilli(event.getTimeStamp()).toString());
		fields.put("message", message(event));
		fields.put("logger", event.getLoggerName());
		fields.put("thread", event.getThreadName());
		Map<String, String> mdc = event.getMDCPropertyMap();
		CONTEXT_KEYS.forEach(key -> putIfPresent(fields, key, mdc.get(key)));
		putIfPresent(fields, TRACE_FIELD, trace(mdc.get(LogKeys.TRACE_ID)));
		return fields;
	}

	/** The message, with the stack trace appended: that is the form Error Reporting groups by. */
	private static String message(ILoggingEvent event) {
		String message = event.getFormattedMessage();
		return event.getThrowableProxy() == null ? message : message + "\n" + ThrowableProxyUtil.asString(event.getThrowableProxy());
	}

	private String trace(String traceId) {
		return traceId == null || projectId == null ? null : "projects/" + projectId + "/traces/" + traceId;
	}

	private static void putIfPresent(Map<String, Object> fields, String key, String value) {
		if (value != null && !value.isBlank()) {
			fields.put(key, value);
		}
	}

	/** Logback levels mapped onto Cloud Logging's severity names. */
	static String severity(Level level) {
		return switch (level.toInt()) {
			case Level.ERROR_INT -> "ERROR";
			case Level.WARN_INT -> "WARNING";
			case Level.INFO_INT -> "INFO";
			default -> "DEBUG";
		};
	}
}
