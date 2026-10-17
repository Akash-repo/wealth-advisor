package com.wealth.advisor.guardrails;

import com.wealth.advisor.config.AdvisorProperties;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs the {@link InputGuardrail} layers in {@code @Order}, cheapest first, and stops at the first
 * rejection — fail fast, so a message caught by L1 never costs an L2 model call, and a rejected
 * message never reaches the agents. The caller (see {@code AgentChatService.chat}) must also not
 * save a rejected message to the session, so it can't colour later turns.
 */
@Component
public class GuardrailChain {

	private static final Logger log = LoggerFactory.getLogger(GuardrailChain.class);

	private final List<InputGuardrail> layers;
	private final AdvisorProperties.Guardrails properties;

	public GuardrailChain(List<InputGuardrail> layers, AdvisorProperties properties) {
		this.layers = layers;
		this.properties = properties.guardrails();
	}

	public GuardrailVerdict check(String message, List<String> recentMessages) {
		if (!properties.enabled()) {
			return GuardrailVerdict.ALLOW;
		}
		for (InputGuardrail layer : layers) {
			GuardrailVerdict verdict = layer.check(message, recentMessages);
			if (!verdict.allowed()) {
				log.warn("Guardrail rejected a message: layer={} reason={}", verdict.layer(), verdict.reason());
				return verdict;
			}
		}
		return GuardrailVerdict.ALLOW;
	}

	/** The canned reply for any rejection; identical for every layer so it reveals nothing about which fired. */
	public String refusalMessage() {
		return properties.refusalMessage();
	}

	/** How many recent accepted messages the caller should pass to {@link #check}. */
	public int historyMessages() {
		return properties.historyMessages();
	}
}
