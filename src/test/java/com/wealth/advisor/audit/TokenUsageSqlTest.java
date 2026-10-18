package com.wealth.advisor.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenUsageSqlTest {

	private static final String TABLE = "proj.wealth_agent.agent_audit_log";

	@Test
	void everyView_readsTheAuditTableAndCountsThinkingInTheTotal() {
		for (String sql : new String[] {TokenUsageSql.bySession(TABLE), TokenUsageSql.byUser(TABLE), TokenUsageSql.byDay(TABLE)}) {
			assertThat(sql)
					.contains("`" + TABLE + "`")
					.contains("agent1_thinking_tokens", "agent2_thinking_tokens", "agent1_cached_tokens", "agent2_cached_tokens")
					.contains("SUM(prompt_tokens + completion_tokens + thinking_tokens) AS total_tokens");
		}
	}

	@Test
	void cachedTokens_areNeverAddedToTheTotalBecauseTheyAreASubsetOfThePrompt() {
		assertThat(TokenUsageSql.byUser(TABLE)).doesNotContain("prompt_tokens + cached_tokens").doesNotContain("+ cached_tokens");
	}

	@Test
	void rollups_groupByTheirOwnKey() {
		assertThat(TokenUsageSql.bySession(TABLE)).contains("GROUP BY user_id, session_id").contains("MIN(created_at) AS first_turn_at");
		assertThat(TokenUsageSql.byUser(TABLE)).contains("GROUP BY user_id").contains("COUNT(DISTINCT session_id) AS sessions");
		assertThat(TokenUsageSql.byDay(TABLE)).contains("GROUP BY day").contains("DATE(created_at) AS day");
	}

	@Test
	void missingColumnValues_countAsZero() {
		assertThat(TokenUsageSql.byUser(TABLE)).contains("IFNULL(agent1_thinking_tokens, 0)");
	}
}
