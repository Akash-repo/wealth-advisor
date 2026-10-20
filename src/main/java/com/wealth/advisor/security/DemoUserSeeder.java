package com.wealth.advisor.security;

import com.wealth.advisor.constants.FirestoreCollections;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.Firestore;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Ensures one login-able user exists in Firestore on startup, so there's something to log in
 * with without building a signup flow (not in scope yet — see the milestone plan).
 *
 * <p>Runs once at boot; if Firestore isn't reachable (no emulator running, no credentials
 * configured), this logs a warning and the app still starts — you just won't be able to log in
 * until Firestore is reachable and the app is restarted (or the document is created by hand).
 */
@Component
public class DemoUserSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(DemoUserSeeder.class);

	static final String DEFAULT_PASSWORD = "changeme";

	private final Firestore firestore;
	private final PasswordEncoder passwordEncoder;
	private final Environment environment;

	@Value("${wealth.demo-user.username:demo}")
	private String demoUsername;

	@Value("${wealth.demo-user.password:changeme}")
	private String demoPassword;

	public DemoUserSeeder(Firestore firestore, PasswordEncoder passwordEncoder, Environment environment) {
		this.firestore = firestore;
		this.passwordEncoder = passwordEncoder;
		this.environment = environment;
	}

	/**
	 * The shipped default password is public (it is in the repository), so the deployed service must not
	 * start with it.
	 *
	 * @throws IllegalStateException in the {@code cloud} profile while the default password is in use
	 */
	static void requireNonDefaultPasswordInCloud(boolean cloudProfile, String password) {
		if (cloudProfile && DEFAULT_PASSWORD.equals(password)) {
			throw new IllegalStateException(
					"The demo user still has the default password. Set DEMO_USER_PASSWORD (from Secret Manager) before"
							+ " running the 'cloud' profile.");
		}
	}

	@Override
	public void run(ApplicationArguments args) {
		requireNonDefaultPasswordInCloud(environment.matchesProfiles("cloud"), demoPassword);
		try {
			DocumentReference userRef = firestore.collection(FirestoreCollections.USERS).document(demoUsername);
			if (userRef.get().get().exists()) {
				log.debug("Demo user '{}' already exists, nothing to seed", demoUsername);
				return;
			}
			userRef
					.set(
							Map.of(
									"email", demoUsername + "@example.com",
									"passwordHash", passwordEncoder.encode(demoPassword),
									"createdAt", Timestamp.now()))
					.get();
			log.info(
					"Seeded demo user '{}' with the configured wealth.demo-user.password."
							+ " Change it via that property before this leaves local dev.",
					demoUsername);
		} catch (Exception e) {
			log.warn(
					"Could not seed demo user (is Firestore/the emulator reachable?): {}", e.getMessage());
		}
	}
}
