package com.wealth.advisor.guardrails;

import static org.assertj.core.api.Assertions.assertThat;

import com.wealth.advisor.config.AdvisorProperties;
import com.wealth.advisor.config.TestProperties;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GuardrailChainTest {

	private final List<String> calls = new ArrayList<>();

	private InputGuardrail layer(String name, GuardrailVerdict verdict) {
		return (message, recent) -> {
			calls.add(name);
			return verdict;
		};
	}

	private static AdvisorProperties withEnabled(boolean enabled) {
		AdvisorProperties d = TestProperties.defaults();
		AdvisorProperties.Guardrails g = d.guardrails();
		return d.withGuardrails(new AdvisorProperties.Guardrails(
				enabled, g.classifierModel(), g.failClosed(), g.historyMessages(), g.refusalMessage()));
	}

	@Test
	void stopsAtTheFirstRejection_laterLayersNeverRun() {
		GuardrailChain chain = new GuardrailChain(
				List.of(
						layer("L1", GuardrailVerdict.reject("L1", "pattern")),
						layer("L2", GuardrailVerdict.ALLOW)),
				TestProperties.defaults());

		GuardrailVerdict verdict = chain.check("hi", List.of());

		assertThat(verdict.allowed()).isFalse();
		assertThat(verdict.layer()).isEqualTo("L1");
		assertThat(calls).containsExactly("L1");
	}

	@Test
	void runsEveryLayerWhenAllAllow() {
		GuardrailChain chain = new GuardrailChain(
				List.of(layer("L1", GuardrailVerdict.ALLOW), layer("L2", GuardrailVerdict.ALLOW)),
				TestProperties.defaults());

		assertThat(chain.check("hi", List.of()).allowed()).isTrue();
		assertThat(calls).containsExactly("L1", "L2");
	}

	@Test
	void disabled_skipsEverything() {
		GuardrailChain chain =
				new GuardrailChain(List.of(layer("L1", GuardrailVerdict.reject("L1", "x"))), withEnabled(false));

		assertThat(chain.check("hi", List.of()).allowed()).isTrue();
		assertThat(calls).isEmpty();
	}
}
