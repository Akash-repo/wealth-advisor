package com.wealth.advisor.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The single Jackson 2 {@link ObjectMapper} shared by the audit recorders, market-data parsing and
 * holdings extraction.
 *
 * <p>Deliberately <em>Jackson 2</em> ({@code com.fasterxml}), not Spring Boot's own mapper: Boot 4
 * moved its web/JSON stack to Jackson 3 ({@code tools.jackson}), which is a different type with a
 * different API. ADK and these classes are on Jackson 2, so a bean of that type is what they need.
 * Spring MVC keeps using Boot's Jackson 3 mapper and is unaffected.
 *
 * <p>It is a plain default mapper on purpose — the previous per-class {@code new ObjectMapper()}s
 * were all defaults, so sharing one changes no behavior; it only gives one place to configure
 * if that ever needs to change.
 */
@Configuration
public class JacksonConfig {

	@Bean
	public ObjectMapper jackson2ObjectMapper() {
		return new ObjectMapper();
	}
}
