package com.wealth.advisor.config;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the single {@link Firestore} client this whole app shares — for user accounts (auth,
 * see {@code FirestoreUserDetailsService}), uploaded statements and their holdings ({@code
 * PortfolioStatementStore}), and ADK's own conversation sessions ({@code
 * FirestoreSessionService}, wired up directly in {@code AgentChatService}).
 *
 * <p>No credentials are configured here explicitly — {@code FirestoreOptions} resolves them the
 * standard Google Cloud way (checked in this order): the {@code GOOGLE_APPLICATION_CREDENTIALS}
 * env var pointing at a service account key file, {@code gcloud auth application-default login}
 * output (ADC — what this project actually uses locally), or (on Cloud Run/GCE) the metadata
 * server's attached service account. Set {@code FIRESTORE_EMULATOR_HOST=localhost:8080} instead
 * to talk to a local emulator — no real credentials needed then (see {@code
 * application-local.yml} for how to start one).
 */
@Configuration
public class FirestoreConfig {

	@Bean
	public Firestore firestore(@Value("${gcp.project-id}") String projectId) {
		return FirestoreOptions.newBuilder().setProjectId(projectId).build().getService();
	}
}
