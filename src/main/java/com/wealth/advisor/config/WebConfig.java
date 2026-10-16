package com.wealth.advisor.config;

import com.wealth.advisor.web.RequestLogContextInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Spring MVC wiring: the interceptor that tags each request's log lines with user and request id. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

	private final RequestLogContextInterceptor requestLogContextInterceptor;

	public WebConfig(RequestLogContextInterceptor requestLogContextInterceptor) {
		this.requestLogContextInterceptor = requestLogContextInterceptor;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(requestLogContextInterceptor);
	}
}
