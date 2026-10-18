package com.wealth.advisor.agent;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A turn is already running in this session; two concurrent turns would interleave its history. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ChatSessionBusyException extends RuntimeException {

	public ChatSessionBusyException(String sessionId) {
		super("A message is already being processed in conversation " + sessionId);
	}
}
