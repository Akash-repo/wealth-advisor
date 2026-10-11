package com.wealth.advisor.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Binds the real {@code application.yml} with Spring's {@link Binder} and validates the result, so
 * a typo'd key, a missing value, or a non-positive tunable is caught here rather than at deploy
 * time. No Spring application context and no GCP access.
 *
 * <p>{@code overrides} stand in for an env-var override (e.g. {@code
 * ADVISOR_COMPACTION_TOKENTHRESHOLD}): they take precedence over the file, as they do in the app.
 */
class AdvisorPropertiesTest {

	private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

	private static AdvisorProperties bind(Map<String, Object> overrides) throws Exception {
		List<PropertySource<?>> sources = new ArrayList<>();
		sources.add(new MapPropertySource("overrides", overrides));
		sources.addAll(new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml")));
		return new Binder(ConfigurationPropertySources.from(sources))
				.bind("advisor", AdvisorProperties.class)
				.get();
	}

	@Test
	void applicationYml_bindsAndValidates() throws Exception {
		AdvisorProperties p = bind(Map.of());

		assertThat(VALIDATOR.validate(p)).isEmpty();
		assertThat(p.model().chat()).isEqualTo("gemini-2.5-flash");
		assertThat(p.model().embedding()).isEqualTo("text-embedding-004");
		assertThat(p.chat().maxRequestsPerMinute()).isEqualTo(20);
		assertThat(p.compaction().tokenThreshold()).isEqualTo(50_000);
		assertThat(p.compaction().eventRetentionSize()).isEqualTo(20);
		assertThat(p.memory().maxEntries()).isEqualTo(8);
	}

	@Test
	void testFixture_matchesTheRealFile() throws Exception {
		assertThat(bind(Map.of())).isEqualTo(TestProperties.defaults());
	}

	@Test
	void override_takesPrecedenceOverTheFile() throws Exception {
		AdvisorProperties p = bind(Map.of("advisor.compaction.token-threshold", "12345"));

		assertThat(p.compaction().tokenThreshold()).isEqualTo(12345);
	}

	@Test
	void nonPositiveTunable_isRejected() throws Exception {
		AdvisorProperties p = bind(Map.of("advisor.chat.max-requests-per-minute", "0"));

		assertThat(VALIDATOR.validate(p)).isNotEmpty();
	}

	@Test
	void blankModelName_isRejected() throws Exception {
		AdvisorProperties p = bind(Map.of("advisor.model.chat", ""));

		assertThat(VALIDATOR.validate(p)).isNotEmpty();
	}
}
