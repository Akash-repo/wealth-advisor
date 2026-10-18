package com.wealth.advisor.memory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import com.wealth.advisor.agent.prompt.PromptLoader;
import com.wealth.advisor.config.AdvisorProperties;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Asks a small model to group a conversation by topic and summarise each topic as an
 * {@link EpisodeDraft}. A plain {@code genai} call with a response schema and no tools. Unlike the
 * preference extractor it sees the assistant's replies too, because an episode records what advice was
 * given; they are passed as data. The result is only a proposal: {@link EpisodeValidator} decides what
 * becomes an {@link Episode}.
 */
@Component
public class EpisodeExtractor {

	private static final Logger log = LoggerFactory.getLogger(EpisodeExtractor.class);

	/** One message is cut to this many characters; long agent replies would otherwise dominate. */
	static final int MAX_MESSAGE_CHARS = 4_000;
	/** Beyond this the oldest messages are left out (rare; see docs/design/05, D10). */
	static final int MAX_TOTAL_CHARS = 300_000;

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Response(List<EpisodeDraft> episodes) {}

	private final UnaryOperator<String> model;
	private final ObjectMapper objectMapper;
	private final String template;
	private final int maxEpisodes;

	@Autowired
	public EpisodeExtractor(Client genAiClient, AdvisorProperties properties, ObjectMapper objectMapper, PromptLoader promptLoader) {
		this(
				prompt -> genAiClient
						.models
						.generateContent(properties.episodes().extractorModel(), prompt, config())
						.text(),
				properties,
				objectMapper,
				promptLoader);
	}

	/** Test seam: {@code model} maps the full prompt to the model's raw JSON reply. */
	EpisodeExtractor(UnaryOperator<String> model, AdvisorProperties properties, ObjectMapper objectMapper, PromptLoader promptLoader) {
		this.model = model;
		this.objectMapper = objectMapper;
		this.template = promptLoader.load("episode_extractor").text();
		this.maxEpisodes = properties.episodes().maxPerSession();
	}

	/** @throws IllegalStateException if the model call fails or returns something unparseable */
	public List<EpisodeDraft> extract(List<TimedMessage> messages) {
		if (messages.isEmpty()) {
			return List.of();
		}
		String json;
		try {
			json = model.apply(prompt(messages));
		} catch (RuntimeException e) {
			throw new IllegalStateException("Episode extraction call failed", e);
		}
		List<EpisodeDraft> drafts = parse(json);
		log.debug("Episode extractor proposed {} episode(s) from {} message(s)", drafts.size(), messages.size());
		return drafts;
	}

	String prompt(List<TimedMessage> messages) {
		return PromptTemplate.fill(
				template,
				Map.of(
						"sectors", String.join("; ", Sector.labels()),
						"max_episodes", String.valueOf(maxEpisodes),
						"messages", numbered(messages)));
	}

	private List<EpisodeDraft> parse(String json) {
		try {
			Response response = objectMapper.readValue(json, Response.class);
			return response.episodes() == null ? List.of() : response.episodes();
		} catch (JsonProcessingException | RuntimeException e) {
			throw new IllegalStateException("Episode extraction returned unparseable output", e);
		}
	}

	private static String numbered(List<TimedMessage> messages) {
		StringBuilder text = new StringBuilder();
		for (int i = messages.size() - 1; i >= 0; i--) {
			TimedMessage m = messages.get(i);
			String body = m.text().length() <= MAX_MESSAGE_CHARS ? m.text() : m.text().substring(0, MAX_MESSAGE_CHARS) + " [cut]";
			String line = "[" + m.index() + "] " + (m.isUser() ? "USER" : "ASSISTANT") + " (" + m.at() + "): " + body + "\n";
			if (text.length() + line.length() > MAX_TOTAL_CHARS && text.length() > 0) {
				break;
			}
			text.insert(0, line);
		}
		return text.toString();
	}

	private static GenerateContentConfig config() {
		return GenerateContentConfig.builder()
				.temperature(0.0f)
				.responseMimeType("application/json")
				.responseSchema(responseSchema())
				.build();
	}

	private static Schema responseSchema() {
		Schema range = Schema.builder()
				.type(Type.Known.OBJECT)
				.properties(Map.of("from", field(Type.Known.INTEGER), "to", field(Type.Known.INTEGER)))
				.required("from", "to")
				.build();
		Schema episode = Schema.builder()
				.type(Type.Known.OBJECT)
				.properties(Map.of(
						"title", field(Type.Known.STRING),
						"summary", field(Type.Known.STRING),
						"ranges", Schema.builder().type(Type.Known.ARRAY).items(range).build(),
						"tickers", strings(),
						"sectors", strings(),
						"topics", strings(),
						"decisions", strings()))
				.required("title", "summary", "ranges", "tickers", "sectors", "topics", "decisions")
				.build();
		return Schema.builder()
				.type(Type.Known.OBJECT)
				.properties(Map.of("episodes", Schema.builder().type(Type.Known.ARRAY).items(episode).build()))
				.required("episodes")
				.build();
	}

	private static Schema strings() {
		return Schema.builder().type(Type.Known.ARRAY).items(field(Type.Known.STRING)).build();
	}

	private static Schema field(Type.Known type) {
		return Schema.builder().type(type).build();
	}
}
