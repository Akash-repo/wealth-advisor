package com.wealth.advisor.audit;

/**
 * The SQL behind the three token-usage views: the same totals rolled up per session, per user and per
 * day. Plain strings in a pure class so they can be checked without BigQuery.
 *
 * <p>Definitions (the same in all three views):
 *
 * <ul>
 *   <li>{@code prompt_tokens}: everything sent to Gemini, summed over both agents and every call.
 *   <li>{@code cached_tokens}: the part of the prompt served from Gemini's cache. A <em>subset</em>
 *       of {@code prompt_tokens}, so it is never added to the total.
 *   <li>{@code completion_tokens}: the visible answer.
 *   <li>{@code thinking_tokens}: the model's hidden reasoning. It is billed like output but is not
 *       part of {@code completion_tokens}, so leaving it out understates real usage.
 *   <li>{@code total_tokens} = prompt + completion + thinking.
 * </ul>
 *
 * Rows written before the cached and thinking columns existed hold NULL there until they are
 * backfilled from the stored JSON; the views count NULL as 0.
 */
final class TokenUsageSql {

	private TokenUsageSql() {}

	private static final String TEMPLATE =
			"""
			WITH turns AS (
			  SELECT user_id, session_id, created_at,
			         IFNULL(agent1_prompt_tokens, 0)     + IFNULL(agent2_prompt_tokens, 0)     AS prompt_tokens,
			         IFNULL(agent1_cached_tokens, 0)     + IFNULL(agent2_cached_tokens, 0)     AS cached_tokens,
			         IFNULL(agent1_completion_tokens, 0) + IFNULL(agent2_completion_tokens, 0) AS completion_tokens,
			         IFNULL(agent1_thinking_tokens, 0)   + IFNULL(agent2_thinking_tokens, 0)   AS thinking_tokens
			  FROM `%s`)
			SELECT %s,
			       COUNT(*) AS turns,
			       SUM(prompt_tokens)     AS prompt_tokens,
			       SUM(cached_tokens)     AS cached_tokens,
			       SUM(completion_tokens) AS completion_tokens,
			       SUM(thinking_tokens)   AS thinking_tokens,
			       SUM(prompt_tokens + completion_tokens + thinking_tokens) AS total_tokens,
			       SAFE_DIVIDE(SUM(cached_tokens), SUM(prompt_tokens))      AS cached_share
			FROM turns
			GROUP BY %s""";

	/** One row per conversation. */
	static String bySession(String auditTable) {
		return rollup(
				auditTable,
				"user_id, session_id, MIN(created_at) AS first_turn_at, MAX(created_at) AS last_turn_at",
				"user_id, session_id");
	}

	/** One row per user. */
	static String byUser(String auditTable) {
		return rollup(auditTable, "user_id, COUNT(DISTINCT session_id) AS sessions", "user_id");
	}

	/** One row per calendar day (UTC). */
	static String byDay(String auditTable) {
		return rollup(auditTable, "DATE(created_at) AS day, COUNT(DISTINCT user_id) AS users", "day");
	}

	private static String rollup(String auditTable, String selectKeys, String groupBy) {
		return TEMPLATE.formatted(auditTable, selectKeys, groupBy);
	}
}
