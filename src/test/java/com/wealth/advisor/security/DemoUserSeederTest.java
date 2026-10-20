package com.wealth.advisor.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

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

	private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);

	@Test
	void anAccountCreatedWithTheOldDefaultIsUpdatedWhenAnotherPasswordIsConfigured() {
		String oldHash = encoder.encode("changeme");

		assertThat(DemoUserSeeder.passwordNeedsUpdate(encoder, "a-long-random-secret", oldHash)).isTrue();
	}

	@Test
	void anAccountAlreadyOnTheConfiguredPasswordIsLeftAlone() {
		String hash = encoder.encode("a-long-random-secret");

		assertThat(DemoUserSeeder.passwordNeedsUpdate(encoder, "a-long-random-secret", hash)).isFalse();
	}

	@Test
	void aMissingStoredHashIsFixed() {
		assertThat(DemoUserSeeder.passwordNeedsUpdate(encoder, "a-long-random-secret", null)).isTrue();
	}

	@Test
	void localRunsMayKeepTheDefault() {
		assertThatCode(() -> DemoUserSeeder.requireNonDefaultPasswordInCloud(false, "changeme")).doesNotThrowAnyException();
	}
}
