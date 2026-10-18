package com.wealth.advisor.memory;

/** An inclusive run of message numbers inside one session, as numbered by {@link ConversationReader}. */
public record MessageRange(int from, int to) {

	public boolean contains(int index) {
		return index >= from && index <= to;
	}
}
