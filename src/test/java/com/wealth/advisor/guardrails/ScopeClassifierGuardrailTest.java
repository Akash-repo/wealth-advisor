package com.wealth.advisor.guardrails;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealth.advisor.agent.prompt.PromptLoader;
import com.wealth.advisor.config.AdvisorProperties;
import com.wealth.advisor.config.TestProperties;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class ScopeClassifierGuardrailTest {

	private static AdvisorProperties withFailClosed(boolean failClosed) {
		AdvisorProperties d = TestProperties.defaults();
		AdvisorProperties.Guardrails g = d.guardrails();
		return new AdvisorProperties(
				d.model(),
				d.chat(),
				d.compaction(),
				d.memory(),
				new AdvisorProperties.Guardrails(
						g.enabled(), g.classifierModel(), failClosed, g.historyMessages(), g.refusalMessage()));
	}

	private static ScopeClassifierGuardrail classifier(UnaryOperator<String> model, boolean failClosed) {
		return new ScopeClassifierGuardrail(model, withFailClosed(failClosed), new ObjectMapper(), new PromptLoader());
	}

	@Test
	void inScope_allows() {
		assertThat(classifier(p -> "{\"verdict\":\"IN_SCOPE\"}", false)
						.check("Roth vs traditional IRA?", List.of())
						.allowed())
				.isTrue();
	}

	@Test
	void offTopic_rejectsWithTheVerdictAsReason() {
		GuardrailVerdict verdict = classifier(p -> "{\"verdict\":\"OFF_TOPIC\"}", false)
				.check("Write a Python function", List.of());

		assertThat(verdict.allowed()).isFalse();
		assertThat(verdict.layer()).isEqualTo(ScopeClassifierGuardrail.LAYER);
		assertThat(verdict.reason()).isEqualTo("OFF_TOPIC");
	}

	@Test
	void unsafe_rejects() {
		assertThat(classifier(p -> "{\"verdict\":\"UNSAFE\"}", false).check("x", List.of()).allowed())
				.isFalse();
	}

	@Test
	void promptCarriesTheInstructionRecentMessagesAndTheLatestMessage() {
		AtomicReference<String> seen = new AtomicReference<>();
		classifier(
						p -> {
							seen.set(p);
							return "{\"verdict\":\"IN_SCOPE\"}";
						},
						false)
				.check("and the second one?", List.of("user: what is VTI?", "agent: A broad-market ETF."));

		assertThat(seen.get())
				.contains("scope gate")
				.contains("- user: what is VTI?")
				.contains("- agent: A broad-market ETF.")
				.contains("<<<\nand the second one?\n>>>");
	}

	@Test
	void modelFailure_failsOpenWhenConfigured() {
		ScopeClassifierGuardrail c = classifier(
				p -> {
					throw new IllegalStateException("boom");
				},
				false);

		assertThat(c.check("anything", List.of()).allowed()).isTrue();
	}

	@Test
	void modelFailure_failsClosedWhenConfigured() {
		ScopeClassifierGuardrail c = classifier(
				p -> {
					throw new IllegalStateException("boom");
				},
				true);

		GuardrailVerdict verdict = c.check("anything", List.of());

		assertThat(verdict.allowed()).isFalse();
		assertThat(verdict.reason()).isEqualTo("CLASSIFIER_ERROR");
	}

	@Test
	void unparseableOrEmptyOutput_isTreatedAsAClassifierFailure() {
		assertThat(classifier(p -> "not json", false).check("x", List.of()).allowed()).isTrue();
		assertThat(classifier(p -> "{}", true).check("x", List.of()).reason()).isEqualTo("CLASSIFIER_ERROR");
	}
}
