package com.wealth.advisor.agent;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The requested session doesn't exist <em>for this user</em>. ADK sessions are keyed (app, user,
 * session), so someone else's session id is indistinguishable from a nonexistent one — deliberately
 * the same 404, so ids can't be probed.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class ChatSessionNotFoundException extends RuntimeException {

	public ChatSessionNotFoundException(String sessionId) {
		super("No such conversation: " + sessionId);
	}
}
