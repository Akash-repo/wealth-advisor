package com.wealth.advisor.agent;

import java.time.Instant;

/** One row in the user's conversation list. */
public record SessionSummary(String id, String title, Instant lastUpdated) {}
