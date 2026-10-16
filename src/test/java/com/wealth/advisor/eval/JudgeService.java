package com.wealth.advisor.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.TestComponent;

/**
 * LLM-as-judge for eval assertions that can't be exact-matched — "does this response cite the
 * user's existing JPM position as a reason for caution" has no single fixed wording to string-
 * match against, so a second Gemini call grades it instead. See {@code ARCHITECTURE.md}'s eval
 * design discussion for the full rationale, in particular: why this is just another call to the
 * same model rather than a dedicated "judge" product, the self-preference-bias risk of judging a
 * model with itself, and why judge verdicts need periodic human calibration rather than being
 * trusted as ground truth forever.
 *
 * <p><strong>Not a production class</strong> — lives under {@code src/test/java} (a {@code
 * com.wealth.advisor.eval} package of its own, separate from {@code PortfolioEvalTest}) so it's
 * never compiled into the app's own JAR. {@link TestComponent} (not plain {@code @Component}) is
 * Spring Boot's own stereotype for exactly this: a Spring-managed bean that only exists inside a
 * {@code @SpringBootTest} context — same {@code @Autowired} ergonomics as every other bean in this
 * app, without shipping in production.
 *
 * <p>Reuses the plain {@code genai} {@link Client} bean ({@code GenAiClientConfig}) — the same one
 * {@code HoldingsExtractionService} already uses — and the exact same
 * "constrain Gemini to a JSON {@code responseSchema} instead of parsing free text" pattern {@code
 * HoldingsExtractionService} already established. No new infrastructure, just a new schema.
 *
 * <p><strong>Every call requires explicit {@code groundTruthFacts}</strong> — deliberately not an
 * optional parameter. Grounding the judge with facts it's told are true ("JPM is 43% of the
 * user's portfolio, the largest position") converts an open-ended "is this a good response"
 * judgment into something much closer to a fact-check, which is the single biggest lever for
 * judge reliability. A judge call with no grounding is a judge call with no accuracy guarantee.
 */
@TestComponent
public class JudgeService {

	private static final Logger log = LoggerFactory.getLogger(JudgeService.class);
	private static final String MODEL = "gemini-2.5-flash";

	private final Client genAiClient;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public JudgeService(Client genAiClient) {
		this.genAiClient = genAiClient;
	}

	/** A judge's answer to one yes/no rubric question, plus its reasoning for later human audit. */
	public record Verdict(boolean answer, String reasoning) {}

	/**
	 * @param rubricQuestion a single, narrow yes/no question — not "is this response good," which
	 *     is exactly the kind of vague holistic judgment that makes judges unreliable
	 * @param groundTruthFacts facts the judge should treat as true and check the response against,
	 *     not reason about from scratch — see the class javadoc for why this isn't optional
	 * @param responseToGrade the actual agent response text being evaluated
	 * @throws IllegalStateException if the judge call itself fails (network error, malformed JSON,
	 *     etc.) — deliberately not swallowed into a default verdict. A broken judge call is a
	 *     broken test harness, not a legitimate "no" answer; silently treating it as one would fail
	 *     the eval case for the wrong reason and hide the actual problem.
	 */
	public Verdict judge(String rubricQuestion, String groundTruthFacts, String responseToGrade) {
		try {
			GenerateContentConfig config =
					GenerateContentConfig.builder()
							.responseMimeType("application/json")
							.responseSchema(verdictSchema())
							.build();

			String prompt =
					"""
					You are grading an AI assistant's response against a single yes/no question. Base
					your answer only on the stated facts and the response text below — do not use
					outside knowledge, and do not credit the response for sounding confident or
					well-written if it doesn't actually satisfy the question.

					Known facts (treat as true):
					%s

					Question about the response below:
					%s

					Response being graded:
					\"\"\"
					%s
					\"\"\"
					"""
							.formatted(groundTruthFacts, rubricQuestion, responseToGrade);

			GenerateContentResponse response = genAiClient.models.generateContent(MODEL, prompt, config);
			Verdict verdict = objectMapper.readValue(response.text(), Verdict.class);
			log.debug("Judge verdict for \"{}\": {}", rubricQuestion, verdict);
			return verdict;
		} catch (Exception e) {
			throw new IllegalStateException("Judge call failed for question: " + rubricQuestion, e);
		}
	}

	private static Schema verdictSchema() {
		return Schema.builder()
				.type(Type.Known.OBJECT)
				.properties(
						Map.of(
								"answer",
								Schema.builder()
										.type(Type.Known.BOOLEAN)
										.description("true if the response satisfies the question, false otherwise")
										.build(),
								"reasoning",
								Schema.builder()
										.type(Type.Known.STRING)
										.description("One or two sentences explaining the verdict, for later human review")
										.build()))
				.required("answer", "reasoning")
				.build();
	}
}
