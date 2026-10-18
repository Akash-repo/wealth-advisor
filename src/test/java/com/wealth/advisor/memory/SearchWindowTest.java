package com.wealth.advisor.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class SearchWindowTest {

	private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");

	@Test
	void rollingWindowsGoBackThatManyDays() {
		assertThat(SearchWindow.PAST_WEEK.since(NOW, 365)).isEqualTo(Instant.parse("2026-10-23T12:00:00Z"));
		assertThat(SearchWindow.PAST_3_MONTHS.since(NOW, 365)).isEqualTo(Instant.parse("2026-08-01T12:00:00Z"));
	}

	@Test
	void anyMeansTheConfiguredDefault() {
		assertThat(SearchWindow.ANY.since(NOW, 30)).isEqualTo(Instant.parse("2026-09-30T12:00:00Z"));
	}

	@Test
	void parsingIsLenientAndUnknownMeansAny() {
		assertThat(SearchWindow.parse("past_week")).isEqualTo(SearchWindow.PAST_WEEK);
		assertThat(SearchWindow.parse(" Past-Month ")).isEqualTo(SearchWindow.PAST_MONTH);
		assertThat(SearchWindow.parse("past 3 months")).isEqualTo(SearchWindow.PAST_3_MONTHS);
		assertThat(SearchWindow.parse("last week")).isEqualTo(SearchWindow.ANY);
		assertThat(SearchWindow.parse(null)).isEqualTo(SearchWindow.ANY);
		assertThat(SearchWindow.parse("")).isEqualTo(SearchWindow.ANY);
	}
}
