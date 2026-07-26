package com.wealth.advisor.config;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the {@link Firestore} client used for both user accounts (auth) and, in a later
 * milestone, portfolio vectors and ADK conversation sessions.
 *
 * <p>No credentials are configured here explicitly — {@code FirestoreOptions} resolves them the
 * standard Google Cloud way (checked in this order): the {@code GOOGLE_APPLICATION_CREDENTIALS}
 * env var pointing at a service account key file, {@code gcloud auth application-default login}
 * output, or (on Cloud Run/GCE) the metadata server's attached service account. Locally, set
 * {@code FIRESTORE_EMULATOR_HOST=localhost:8080} (see the emulator instructions from the original
 * design doc) and this client talks to the emulator instead — no real credentials needed then.
 */
@Configuration
public class FirestoreConfig {

	@Bean
	public Firestore firestore(@Value("${gcp.project-id}") String projectId) {
		return FirestoreOptions.newBuilder().setProjectId(projectId).build().getService();
	}
}
