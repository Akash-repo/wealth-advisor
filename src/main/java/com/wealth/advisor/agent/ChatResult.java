package com.wealth.advisor.agent;

/** Outcome of one chat turn: the session it ran in (new or existing) and the reply to show. */
public record ChatResult(String sessionId, String reply) {}
