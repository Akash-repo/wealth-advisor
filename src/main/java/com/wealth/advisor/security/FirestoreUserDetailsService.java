package com.wealth.advisor.security;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import java.util.concurrent.ExecutionException;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Loads user accounts from the Firestore {@code users} collection for Spring Security's form
 * login to authenticate against.
 *
 * <p>The document id itself is treated as the username (so {@code users/demo} logs in as
 * {@code demo}) — a direct document lookup rather than a query, which keeps this fast and
 * matches the simple 1:1 mapping the BRD's {@code users} collection schema implies. The BRD's
 * original schema (email, createdAt, hasUploadedPortfolio) didn't include a password field —
 * {@code passwordHash} is added here (a bcrypt hash, never a plaintext password) since real auth
 * needs one.
 */
@Service
public class FirestoreUserDetailsService implements UserDetailsService {

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
			throw new UsernameNotFoundException("Failed to look up user '" + username + "'", e);
		}

		if (!snapshot.exists()) {
			throw new UsernameNotFoundException("No such user: " + username);
		}

		String passwordHash = snapshot.getString("passwordHash");
		return User.withUsername(username).password(passwordHash).authorities("ROLE_USER").build();
	}
}
