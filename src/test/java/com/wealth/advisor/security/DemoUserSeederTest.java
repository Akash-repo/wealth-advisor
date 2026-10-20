package com.wealth.advisor.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DemoUserSeederTest {

	@Test
	void theDeployedServiceRefusesToStartWithTheDefaultPassword() {
		assertThatThrownBy(() -> DemoUserSeeder.requireNonDefaultPasswordInCloud(true, "changeme"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("DEMO_USER_PASSWORD");
	}

	@Test
	void aRealPasswordInCloudIsFine() {
		assertThatCode(() -> DemoUserSeeder.requireNonDefaultPasswordInCloud(true, "a-long-random-secret")).doesNotThrowAnyException();
	}

	@Test
	void localRunsMayKeepTheDefault() {
		assertThatCode(() -> DemoUserSeeder.requireNonDefaultPasswordInCloud(false, "changeme")).doesNotThrowAnyException();
	}
}
