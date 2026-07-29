package com.wealth.advisor.eval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Sanity check for {@link JudgeService} itself, independent of any portfolio eval case — if this
 * breaks, every case in {@code PortfolioEvalTest} that relies on a judge becomes unusable for a
 * reason that has nothing to do with the agent under test, so it's worth verifying in isolation.
 * Component-level, not system-level, deliberately: unlike the ADK tool-binding bug {@code
 * PortfolioEvalTest} guards against, nothing about {@code JudgeService} depends on going through
 * the real chat pipeline — it's a direct Gemini call, so a direct Java call is a faithful test.
 *
 * <p><strong>Gotcha found live</strong>: {@code @TestComponent} is deliberately excluded from a
 * plain {@code @SpringBootTest}'s normal component scan — that's the whole point of the
 * annotation, so test-only beans don't leak into unrelated test slices that happen to share the
 * same base package. It has to be pulled in explicitly via {@code @Import}.
 */
@SpringBootTest
@Import(JudgeService.class)
@Tag("eval")
class JudgeServiceTest {

	@Autowired JudgeService judgeService;

	@Test
	void judge_returnsTrueWhenResponseSatisfiesTheQuestion() {
		JudgeService.Verdict verdict =
				judgeService.judge(
						"Does the response explicitly state that the sky is blue?",
						"This is a plain factual-recall check, no domain-specific facts needed.",
						"On a clear day, the sky is blue.");

		assertThat(verdict.answer()).isTrue();
		assertThat(verdict.reasoning()).isNotBlank();
	}

	@Test
	void judge_returnsFalseWhenResponseDoesNotSatisfyTheQuestion() {
		JudgeService.Verdict verdict =
				judgeService.judge(
						"Does the response explicitly state that the sky is green?",
						"This is a plain factual-recall check, no domain-specific facts needed.",
						"On a clear day, the sky is blue.");

		assertThat(verdict.answer()).isFalse();
		assertThat(verdict.reasoning()).isNotBlank();
	}
}
