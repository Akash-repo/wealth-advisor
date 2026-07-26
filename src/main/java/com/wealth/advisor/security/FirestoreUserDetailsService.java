package com.wealth.advisor.security;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Loads user accounts from the Firestore {@code users} collection for Spring Security's form
 * login to authenticate against.
 *
 * <p>This is the one piece of glue between plain Spring Security and Firestore: Spring Security
 * calls {@link #loadUserByUsername} itself (via the {@link UserDetailsService} interface) during
 * form login, then compares the submitted password against the {@code passwordHash} returned
 * here using whatever {@link org.springframework.security.crypto.password.PasswordEncoder} bean
 * is registered ({@link SecurityConfig#passwordEncoder()}, bcrypt) — this class never compares
 * passwords itself.
 *
 * <p>The document id itself is treated as the username (so {@code users/demo} logs in as
 * {@code demo}) — a direct document lookup rather than a query, which keeps this fast and
 * matches the simple 1:1 mapping the BRD's {@code users} collection schema implies:
 *
 * <pre>{@code
 * // users/demo
 * {
 *   "email": "demo@example.com",
 *   "passwordHash": "$2a$10$...",   // bcrypt; added here, not in the original BRD schema
 *   "createdAt": <timestamp>,
 *   "hasUploadedPortfolio": false
 * }
 * }</pre>
 */
@Service
public class FirestoreUserDetailsService implements UserDetailsService {

	private static final Logger log = LoggerFactory.getLogger(FirestoreUserDetailsService.class);

	private final Firestore firestore;

	public FirestoreUserDetailsService(Firestore firestore) {
		this.firestore = firestore;
	}

	@Override
	public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
		DocumentSnapshot snapshot;
		try {
			snapshot = firestore.collection("users").document(username).get().get();
		} catch (InterruptedException | ExecutionException e) {
			Thread.currentThread().interrupt();
			log.error("Failed to look up user '{}' in Firestore", username, e);
			throw new UsernameNotFoundException("Failed to look up user '" + username + "'", e);
		}

		if (!snapshot.exists()) {
			log.warn("Login attempt for unknown user '{}'", username);
			throw new UsernameNotFoundException("No such user: " + username);
		}

		log.debug("Loaded user '{}' from Firestore for authentication", username);
		String passwordHash = snapshot.getString("passwordHash");
		return User.withUsername(username).password(passwordHash).authorities("ROLE_USER").build();
	}
}
