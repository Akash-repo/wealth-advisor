package com.wealth.advisor.agent.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Loads agent instructions from {@code classpath:prompts/<name>.txt}.
 *
 * <p>Prompts are content, not wiring: keeping them in plain files (not Java text blocks or YAML)
 * makes a wording change a readable one-file diff, and lets the file's hash serve as a prompt
 * version for logging and evals. A missing or blank file fails at startup, when the agent beans
 * are built, rather than on the first user request.
 */
@Component
public class PromptLoader {

	private static final Logger log = LoggerFactory.getLogger(PromptLoader.class);
	private static final String LOCATION = "prompts/%s.txt";
	private static final int VERSION_LENGTH = 12;

	public Prompt load(String name) {
		String path = LOCATION.formatted(name);
		try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
			if (in == null) {
				throw new IllegalStateException("Prompt file not found on classpath: " + path);
			}
			String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			if (text.isBlank()) {
				throw new IllegalStateException("Prompt file is blank: " + path);
			}
			Prompt prompt = new Prompt(name, text, fingerprint(text));
			log.info("Loaded prompt '{}' version {}", name, prompt.version());
			return prompt;
		} catch (IOException e) {
			throw new IllegalStateException("Could not read prompt file: " + path, e);
		}
	}

	private static String fingerprint(String text) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest).substring(0, VERSION_LENGTH);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is required by the JDK spec", e);
		}
	}
}
