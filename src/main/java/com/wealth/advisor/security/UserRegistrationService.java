package com.wealth.advisor.security;

import com.wealth.advisor.constants.FirestoreCollections;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.Firestore;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * FR-1's signup half — creates a new user document in Firestore's {@code users} collection,
 * hashing the password the same way {@link DemoUserSeeder} does, so {@link
 * FirestoreUserDetailsService} can authenticate a self-registered user identically to the seeded
 * demo one.
 *
 * <pre>{@code
 * userRegistrationService.register("akash", "akash@example.com", "hunter22");
 * // -> writes users/akash = {email, passwordHash (bcrypt), createdAt, hasUploadedPortfolio: false}
 * }</pre>
 *
 * <p>The username becomes the Firestore document id directly (same convention as {@link
 * FirestoreUserDetailsService}), so it's restricted to a safe character set rather than allowing
 * anything a browser might submit.
 */
@Service
public class UserRegistrationService {

	private static final Logger log = LoggerFactory.getLogger(UserRegistrationService.class);
	private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{3,30}$");
	private static final int MIN_PASSWORD_LENGTH = 6;

	private final Firestore firestore;
	private final PasswordEncoder passwordEncoder;

	public UserRegistrationService(Firestore firestore, PasswordEncoder passwordEncoder) {
		this.firestore = firestore;
		this.passwordEncoder = passwordEncoder;
	}

	/** Thrown for a rejected registration — invalid input or a username already taken. */
	public static class RegistrationException extends RuntimeException {
		public RegistrationException(String message) {
			super(message);
		}
	}

	/**
	 * @throws RegistrationException if the username/password fail validation, or the username is
	 *     already taken
	 */
	public void register(String username, String email, String password) {
		if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
			throw new RegistrationException(
					"Username must be 3-30 characters: letters, numbers, underscore, or hyphen only.");
		}
		if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
			throw new RegistrationException(
					"Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
		}

		DocumentReference userRef = firestore.collection(FirestoreCollections.USERS).document(username);
		try {
			// Not transactional — a learning-app simplification. Two concurrent signups for the
			// same username could theoretically both pass this check; not worth a Firestore
			// transaction for this project's scale.
			if (userRef.get().get().exists()) {
				log.info("Registration rejected: username '{}' already taken", username);
				throw new RegistrationException("Username '" + username + "' is already taken.");
			}
			userRef
					.set(
							Map.of(
									"email", email,
									"passwordHash", passwordEncoder.encode(password),
									"createdAt", Timestamp.now(),
									"hasUploadedPortfolio", false))
					.get();
		} catch (InterruptedException | ExecutionException e) {
			Thread.currentThread().interrupt();
			log.error("Failed to register user '{}'", username, e);
			throw new IllegalStateException("Failed to register user", e);
		}
		log.info("Registered new user '{}'", username);
	}
}
