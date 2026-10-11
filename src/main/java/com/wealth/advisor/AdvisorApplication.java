package com.wealth.advisor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Wealth Advisor — a small, Google ADK-based multi-agent financial advisor, built to learn
 * agentic AI practically.
 *
 * <p>Start here for the big picture: <a href="../../../../../ARCHITECTURE.md">ARCHITECTURE.md</a>
 * at the repo root explains ADK's core concepts ({@code LlmAgent}, {@code Tool}, {@code
 * Runner}/{@code Session}, {@code SequentialAgent}), the full Firestore schema, and how a chat
 * request flows end to end through this app's two-agent RAG pipeline. Package-level starting
 * points once you're in the code:
 *
 * <ul>
 *   <li>{@code com.wealth.advisor.config} — every {@code @Configuration}: the agents ({@code
 *       AgentConfig}), the runner and its session/memory/compaction setup ({@code RunnerConfig}),
 *       the typed tunables bound from {@code application.yml} ({@code AdvisorProperties}), and the
 *       GCP clients.
 *   <li>{@code com.wealth.advisor.agent} — the service that runs a turn ({@code
 *       AgentChatService}), the memory guard, and the prompt loader.
 *   <li>{@code com.wealth.advisor.agent.tool} — the two tools those agents can call.
 *   <li>{@code com.wealth.advisor.portfolio} — PDF parsing, chunking, and embeddings (the RAG
 *       "write" path); {@code com.wealth.advisor.agent.tool.PortfolioSearchTool} is the "read" path.
 *   <li>{@code com.wealth.advisor.security} — Spring Security wiring, backed by Firestore.
 *   <li>{@code com.wealth.advisor.web} — the REST controllers tying it all to HTTP.
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AdvisorApplication {

	public static void main(String[] args) {
		SpringApplication.run(AdvisorApplication.class, args);
	}

}
