package com.wealth.advisor.config;

import com.google.adk.agents.SequentialAgent;
import com.google.adk.apps.App;
import com.google.adk.memory.FirestoreMemoryService;
import com.google.adk.models.Gemini;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.FirestoreSessionService;
import com.google.adk.summarizer.EventsCompactionConfig;
import com.google.adk.summarizer.LlmEventSummarizer;
import com.google.cloud.firestore.Firestore;
import com.google.genai.Client;
import com.wealth.advisor.agent.AgentChatService;
import com.wealth.advisor.agent.GuardedMemoryService;
import com.wealth.advisor.audit.AgentAuditRecorder;
import com.wealth.advisor.audit.ToolUsageRecorder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the ADK {@link Runner} — the object {@link AgentChatService} calls once per turn — and
 * decides where its three kinds of state live: session history, long-term memory, and compaction.
 * Kept apart from {@link AgentConfig} (which defines <em>what</em> the agents are) because this is
 * about <em>how</em> a conversation is stored and trimmed.
 *
 * <p><strong>Context growth.</strong> Every model call rebuilds its context by resending the whole
 * session's event history (verified in ADK's {@code Contents} request processor, not assumed).
 * Within one conversation that means per-turn cost and latency grow until the model's context
 * window is reached. See {@code Optimization.md} P1-1.
 *
 * <p>ADK's fix is tail-retention event compaction: once a prompt exceeds {@code
 * advisor.compaction.token-threshold}, everything older than the most recent {@code
 * advisor.compaction.event-retention-size} events is summarized by an LLM call into one rolling
 * synthetic event that is sent instead of the raw history it replaces (see {@code
 * com.google.adk.summarizer.TailRetentionEventCompactor}). It runs before every model call, on
 * the request path.
 *
 * <p><strong>Caveat — suspected, not yet proven.</strong> From reading {@code
 * FirestoreSessionService.eventToMap}, only author, timestamp, content parts and keywords are
 * persisted; the event's {@code actions} (where a compaction summary lives) and usage metadata are
 * not. If so, a summary would be lost when the session is reloaded on the next turn. Real turns
 * have also never crossed the threshold (largest observed prompt: ~33k tokens against 50k), so
 * compaction has not been seen to fire at all. See the compaction design note.
 *
 * <p><strong>Gotcha found reading ADK's source:</strong> if no {@code summarizer} is supplied,
 * {@code Runner} tries to build a default one from the root agent's own model, which only works
 * when the root agent is an {@code LlmAgent}. Ours is a {@link SequentialAgent} (no model of its
 * own), so that default throws {@code "No BaseLlm model available for event compaction"} at
 * startup. The summarizer's {@link Gemini} model is therefore built explicitly, reusing the
 * {@code genAiClient} bean — same ADC path, no new credential.
 *
 * <p>Compaction needs {@link App}: {@code EventsCompactionConfig} has no home on {@code
 * Runner.Builder}, and {@code Runner.Builder.build()} throws if both {@code .agent(...)} and
 * {@code .app(...)} are set. For the same reason the audit plugins are registered here on the
 * {@code App}: {@code Runner.Builder.plugins(...)} throws once an app is set.
 */
@Configuration
public class RunnerConfig {

	/**
	 * The ADK application: the agent pipeline plus everything that applies across it — the global
	 * audit {@linkplain com.google.adk.plugins.Plugin plugins} and compaction.
	 */
	@Bean
	public App advisorApp(
			SequentialAgent wealthAdvisorPipeline,
			Client genAiClient,
			AdvisorProperties properties,
			AgentAuditRecorder agentAuditRecorder,
			ToolUsageRecorder toolUsageRecorder) {
		return App.builder()
				.name(wealthAdvisorPipeline.name())
				.rootAgent(wealthAdvisorPipeline)
				// Global plugins: ADK calls these for every agent's model and tool calls.
				.plugins(agentAuditRecorder, toolUsageRecorder)
				.eventsCompactionConfig(compaction(genAiClient, properties))
				.build();
	}

	/** Where conversation state lives: short-term sessions and long-term memory, both in Firestore. */
	@Bean
	public Runner runner(App advisorApp, Firestore firestore, AdvisorProperties properties) {
		return Runner.builder()
				.app(advisorApp)
				// Short-term memory: one session = one conversation's events + state.
				.sessionService(new FirestoreSessionService(firestore))
				// Long-term memory: keyword search over events across all of a user's sessions. Its
				// addSessionToMemory is a no-op — FirestoreSessionService already indexes keywords on
				// every appended event, so there's no separate "ingest" step. Wrapped in
				// GuardedMemoryService because the raw one throws on backend errors (failing the whole
				// turn) and returns unbounded results. See ARCHITECTURE.md.
				.memoryService(new GuardedMemoryService(new FirestoreMemoryService(firestore), properties.memory()))
				.build();
	}

	private static EventsCompactionConfig compaction(Client genAiClient, AdvisorProperties properties) {
		return EventsCompactionConfig.builder()
				.summarizer(new LlmEventSummarizer(new Gemini(properties.model().chat(), genAiClient)))
				.tokenThreshold(properties.compaction().tokenThreshold())
				.eventRetentionSize(properties.compaction().eventRetentionSize())
				.build();
	}
}
