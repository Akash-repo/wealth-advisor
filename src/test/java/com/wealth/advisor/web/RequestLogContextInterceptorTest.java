package com.wealth.advisor.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.wealth.advisor.constants.LogKeys;
import java.security.Principal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestLogContextInterceptorTest {

	private final RequestLogContextInterceptor interceptor = new RequestLogContextInterceptor();
	private final MockHttpServletResponse response = new MockHttpServletResponse();

	@AfterEach
	void clean() {
		MDC.clear();
	}

	@Test
	void authenticatedRequest_isTaggedWithUserAndRequestIdWhileHandledThenCleared() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setUserPrincipal((Principal) () -> "alice");

		interceptor.preHandle(request, response, new Object());

		assertThat(MDC.get(LogKeys.USER_ID)).isEqualTo("alice");
		assertThat(MDC.get(LogKeys.REQUEST_ID)).hasSize(8);

		interceptor.afterCompletion(request, response, new Object(), null);

		assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
	}

	@Test
	void anonymousRequest_hasARequestIdButNoUser() {
		MockHttpServletRequest request = new MockHttpServletRequest();

		interceptor.preHandle(request, response, new Object());

		assertThat(MDC.get(LogKeys.REQUEST_ID)).isNotBlank();
		assertThat(MDC.get(LogKeys.USER_ID)).isNull();
	}

	@Test
	void cloudRunTraceHeader_isRecordedAsTheTraceId() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader(RequestLogContextInterceptor.TRACE_HEADER, "105445aa7843bc8bf206b12000100000/1;o=1");

		interceptor.preHandle(request, response, new Object());

		assertThat(MDC.get(LogKeys.TRACE_ID)).isEqualTo("105445aa7843bc8bf206b12000100000");
	}

	@Test
	void traceIdParsing_handlesMissingAndSpanlessHeaders() {
		assertThat(RequestLogContextInterceptor.traceIdFrom(null)).isNull();
		assertThat(RequestLogContextInterceptor.traceIdFrom("  ")).isNull();
		assertThat(RequestLogContextInterceptor.traceIdFrom("abc123")).isEqualTo("abc123");
	}
}
