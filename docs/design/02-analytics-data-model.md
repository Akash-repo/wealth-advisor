# 02 — Analytics data model

Status: **decided: keep the wide row per turn (option A), unchanged.** Written 2026-10-16.

## 1. What we want to tackle

Where per-turn analytics data lives and how it is shaped, given the questions we want to answer: tokens
and latency per user, session and turn; which tools ran; guardrail outcomes; cost; and replaying a
call. This is part (b) of the observability topic.

## 2. What we have today (measured 2026-10-16)

One BigQuery row per **turn** in `wealth_agent.agent_audit_log`, plus one row per tool call in
`tool_invocations` and an append-only `tool_registry_events` table with a view.

A **turn** is one user message and everything that happens until the reply is sent: in a typical turn
agent 1 makes about 2 model calls, runs 1 to 2 tools, and agent 2 makes 1 call. All of that is stored
in the turn's single row (the model calls as a JSON array).

| Fact | Value |
|---|---|
| `agent_audit_log` | 143 rows, 14 MB, unpartitioned, unclustered (about 98 KB per turn) |
| Average JSON per turn | about 55k characters (agent 1) and 43k (agent 2); maximum 217k |
| Average user prompt | 94 characters |
| `tool_invocations` | 120 rows, partitioned by day, clustered by tool and agent |

## 3. Gaps (accepted for now)

- Each model call stores its full request again, so the same text (system prompt, tool schemas, the
  history so far) is stored repeatedly and a turn's size grows with the square of conversation length.
- The row is built in memory and written once at the end, so a crash mid-turn loses that turn.
- A new kind of event (guardrail decisions, the compaction summarizer's calls) needs a schema change.
- Prompts contain portfolio data and have no retention rule of their own.
- Cached and thinking tokens, prompt version and model exist only inside the JSON.
- `tool_invocations` has no user or session column.

## 4. Options considered

| Option | Shape |
|---|---|
| **A. Wide row per turn** | **chosen** |
| B. Typed fact tables per grain (`turns`, `model_calls`, `tool_calls`, `guardrail_events`) + dimensions | not chosen now |
| C. One generic `events` table with a JSON payload | not chosen |
| D. B plus the claim-check pattern (large prompts and responses stored once in GCS, referenced by hash) | the recommendation at the time; not chosen |

## 5. Decision

**Keep A as is.** Reasons: it is the simplest design, nothing needs migrating, it works today, and at
the current volume (a few hundred turns) the size and scan costs are negligible. The gaps above are
real but not yet costly.

### Small fixes that stay inside A (available later, not scheduled)

1. Recreate the table partitioned by day on `created_at` and clustered by `user_id`, `session_id`
   (BigQuery cannot add partitioning in place; it is a create-and-copy).
2. Add narrow columns so cost and quality queries need no JSON parsing: cached tokens, thinking
   tokens, prompt version, model, app version.
3. Add `user_id` and `session_id` to `tool_invocations`.
4. A partition expiration (for example 90 days) so heavy rows age out.
5. Optional: split the large JSON into a second table with a shorter retention than the summary
   columns.

## 6. Revisit this decision when

- the table passes roughly a few GB, or turns per day grow into the thousands;
- guardrail decisions or summarizer calls need to be recorded as their own events;
- a retention or privacy requirement applies to prompt contents specifically;
- a query that needs per-call detail becomes slow or expensive.

At that point the next step is option B or D, not more columns on the wide row.
