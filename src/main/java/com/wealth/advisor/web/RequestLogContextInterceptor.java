package com.wealth.advisor.web;

import com.wealth.advisor.constants.LogKeys;
import com.wealth.advisor.logging.LogContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Tags every log line written while an HTTP request is handled with who made it and which request
 * it is, so "show me everything for user X" or "for this one request" is a single log query.
 *
 * <p>Runs after Spring Security, so the authenticated user is known. If the request came through
 * Cloud Run it also records the Cloud Trace id from {@code X-Cloud-Trace-Context}, which is what
 * lets Cloud Logging group the app's lines under the platform's own request log.
 */
@Component
public class RequestLogContextInterceptor implements HandlerInterceptor {

	static final String TRACE_HEADER = "X-Cloud-Trace-Context";
	private static final String SCOPE_ATTRIBUTE = RequestLogContextInterceptor.class.getName() + ".scope";

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		Map<String, String> values = new HashMap<>();
		values.put(LogKeys.REQUEST_ID, UUID.randomUUID().toString().substring(0, 8));
		values.put(LogKeys.TRACE_ID, traceIdFrom(request.getHeader(TRACE_HEADER)));
		if (request.getUserPrincipal() != null) {
			values.put(LogKeys.USER_ID, request.getUserPrincipal().getName());
		}
		request.setAttribute(SCOPE_ATTRIBUTE, LogContext.with(values));
		return true;
	}

	@Override
	public void afterCompletion(
			HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
		if (request.getAttribute(SCOPE_ATTRIBUTE) instanceof LogContext.Scope scope) {
			scope.close();
		}
	}

	/** The header looks like {@code TRACE_ID/SPAN_ID;o=1}; only the trace id is wanted. */
	static String traceIdFrom(String header) {
		if (header == null || header.isBlank()) {
			return null;
		}
		int slash = header.indexOf('/');
		return slash > 0 ? header.substring(0, slash) : header;
	}
}
