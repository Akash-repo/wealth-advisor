package com.wealth.advisor.memory;

import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.Callbacks;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.wealth.advisor.config.AdvisorProperties;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Adds the user's preference profile to the system instruction of every model call of both agents, so
 * the model never has to remember to look it up. Registered as a {@code beforeModelCallback} on each
 * agent rather than built into their instructions, because ADK skips {@code {state}} substitution for
 * an instruction built by a provider (which {@code stock_recommendation} needs) and a user's stored text
 * could contain braces.
 *
 * <p>A failure loading the profile never fails the turn: the model call simply goes without it.
 */
@Component
public class PreferenceInjector implements Callbacks.BeforeModelCallbackSync {

	private static final Logger log = LoggerFactory.getLogger(PreferenceInjector.class);

	private final PreferenceStore store;
	private final int maxWords;

	public PreferenceInjector(PreferenceStore store, AdvisorProperties properties) {
		this.store = store;
		this.maxWords = properties.profile().maxWords();
	}

	@Override
	public Optional<LlmResponse> call(CallbackContext callbackContext, LlmRequest.Builder request) {
		inject(callbackContext.userId(), request);
		return Optional.empty();
	}

	void inject(String userId, LlmRequest.Builder request) {
		try {
			String block = store.load(userId).promptBlock(maxWords);
			if (!block.isEmpty()) {
				request.appendInstructions(List.of(block));
			}
		} catch (RuntimeException e) {
			log.warn("Could not load the preference profile for userId='{}'; continuing without it", userId, e);
		}
	}
}
