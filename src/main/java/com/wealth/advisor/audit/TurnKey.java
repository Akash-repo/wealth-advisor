package com.wealth.advisor.audit;

/**
 * Identifies one in-flight conversation turn for the audit recorders' secondary index. A user can
 * have several sessions at once, so a turn is identified by the (user, session) pair rather than
 * the user alone. A NUL separator keeps ("a", "bc") and ("ab", "c") from producing the same key.
 */
final class TurnKey {

	private TurnKey() {}

	static String of(String userId, String sessionId) {
		return userId + "\u0000" + sessionId;
	}
}
