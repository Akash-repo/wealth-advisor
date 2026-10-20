package com.wealth.advisor.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** A local run must not be able to change production data by accident. */
class FirestoreConfigTest {

	@Test
	void theCloudServiceMayUseTheRealFirestore() {
		assertThatCode(() -> FirestoreConfig.requireSafeTarget(true, null, false)).doesNotThrowAnyException();
	}

	@Test
	void aLocalRunWithTheEmulatorIsFine() {
		assertThatCode(() -> FirestoreConfig.requireSafeTarget(false, "localhost:8086", false)).doesNotThrowAnyException();
	}

	@Test
	void aLocalRunWithoutTheEmulatorIsRefused_andTheMessageSaysWhatToDo() {
		assertThatThrownBy(() -> FirestoreConfig.requireSafeTarget(false, null, false))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("FIRESTORE_EMULATOR_HOST")
				.hasMessageContaining("ADVISOR_SAFETY_ALLOWREALFIRESTORE");
		assertThatThrownBy(() -> FirestoreConfig.requireSafeTarget(false, "  ", false)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void usingTheRealFirestoreLocallyIsPossibleButOnlyOnPurpose() {
		assertThatCode(() -> FirestoreConfig.requireSafeTarget(false, null, true)).doesNotThrowAnyException();
	}
}
