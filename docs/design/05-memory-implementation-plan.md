# 05 — Memory: implementation plan

Status: **plan only; nothing is built.** Written 2026-10-17 so that whoever implements it (a later session)
has the whole picture in one place. Read [03 — User preferences](03-user-preferences-memory.md) and
[04 — Episodic memory](04-episodic-memory.md) for the reasoning; this document is the build plan that ties
them together. **Implementation starts only when the owner says "go"** (they asked to discuss design before
code). Where a statement here depends on something not yet verified, it is marked **VERIFY**.

## 1. The picture in one page

Three memory layers; two are new.

| Layer | What | Where | Status |
|---|---|---|---|
| Short-term | One conversation's history | ADK session store (Firestore) | Exists |
| **Profile** (semantic) | Durable facts about the user: excluded sectors and tickers, goals and horizon, risk stance, style, other | New: `user_preferences/{userId}` | To build (doc 03) |
| **Episodes** (episodic) | Topic summaries of past conversations, searchable by meaning, entity and time | New: `episodes` collection | To build (doc 04) |

Flow:

- **Write side (background, when the user opens the chat page):** a job finds the user's sessions whose
  message count changed and that have settled (no message in the last 30 minutes). For each, a small model
  extracts profile operations and groups the conversation into topic episodes. Code validates everything,
  then writes. The job stores the session's message count when done.
- **Read side, profile:** loaded on every turn and injected into the agents' instructions as data, not
  instructions. Always on, under about 500 words.
- **Read side, episodes:** a model-called tool `searchPastConversations(topic, window)` returns the best
  matching episodes; `getEpisodeDetails(episodeId)` loads the raw turns.
- **User control:** a preferences panel in the UI (stage 3).

## 2. Decisions that govern the build (all settled unless marked)

| # | Decision | Why |
|---|---|---|
| 1 | Memory is not holdings; holdings stay exact from statements | They would go stale and contradict the real data |
| 2 | Code decides *when* extraction runs; a small model decides *what*; code validates and applies | A missed write is silent and permanent; a wrong write persists |
| 3 | Batch only, no in-turn write tool | Keep it simple; memory only matters across sessions |
| 4 | Trigger on chat-page load, in the background | "New session" is not an event: the server creates the session on the first message |
| 5 | Change check = one number per session (message count when last processed) | Within a session the whole history is already in the prompt, so no incremental bookkeeping |
| 6 | Reprocess the **whole** changed session; no watermark, no lease | Outputs are a function of the session; writes are idempotent (upsert by key, predictable episode IDs) |
| 7 | Settle rule: skip sessions whose last message is under 30 minutes old | Do not summarize a half-finished conversation |
| 8 | Only durable facts are saved (would it still be true and relevant in three months?) | Moods, questions and holdings are ignored |
| 9 | Profile categories: excluded sectors (fixed GICS list), excluded tickers, goals and horizon, risk stance, style, `other` | Typed values can be validated and enforced by code |
| 10 | Profile injected whole, under about 500 words, labelled as data | Small enough to always include; avoids relying on a tool call |
| 11 | Manual entries are never auto-overridden by the extractor | The user's deliberate edit wins |
| 12 | UI delete is a soft delete (`status = REMOVED_BY_USER`, `removedAt`); enforcement of "do not re-add" is **deferred** | Keeps the later fix small; accepted edge case |
| 13 | An episode = a topic with a list of message ranges inside one session; ranges never overlap; episodes never span sessions | People return to topics; the session is the user-chosen boundary |
| 14 | One embedding per episode (title plus summary); no chunking | The summary is short; "chunking" is done by episode boundaries |
| 15 | Episodes live in their own collection with predictable IDs (session ID plus position) | One session yields many episodes; ADK owns the session documents; vector index is per collection |
| 16 | Retrieval: filter by user and time window (an enum converted to dates by code), then a hybrid score `0.6 × similarity + 0.3 × entity match + 0.1 × recency` computed in code over the nearest candidates | Exact tickers plus fuzzy meaning plus a recency tie-break; weights tunable |
| 17 | Bad model output falls back to one episode for the whole session | Quality may drop, the store cannot be corrupted |
| 18 | Whether profile operations and episodes come from one model call or two | **Open**; lean toward two separate calls first (easier to test each) |

## 3. Proposed structure (names are suggestions)

New package `com.wealth.advisor.memory`, in the same style as `guardrails`, `audit` and `portfolio`:

| Piece | Responsibility |
|---|---|
| `PreferenceProfile`, `PreferenceEntry` (records) | The typed profile and its entries: value, status (`ACTIVE`, `REMOVED_BY_USER`), source (`extracted`, `manual`), first seen, last confirmed, originating session, history |
| `PreferenceStore` | Firestore read and write of `user_preferences/{userId}`; upsert by key (category plus normalized value) |
| `PreferenceExtractor` | One `genai` call with a response schema (pattern: `HoldingsExtractionService`); input is only the user's own messages plus the current profile; output is operations `add`, `update`, `remove`, `ignore` each with a confidence and the source message |
| `PreferenceValidator` | Code checks: taxonomy and enums, length and count caps, manual-wins, low-confidence handling |
| `EpisodeExtractor` | One `genai` call; input is the numbered messages of the session; output is topics with ranges, title, summary, entities, topics, decisions |
| `EpisodeValidator` | Ranges exist, do not overlap, count within the cap; chit-chat dropped; fallback to a single episode |
| `EpisodeStore`, `EmbeddingClient` | Firestore `episodes` collection with a vector field; embeddings through the `genai` client |
| `EpisodeSearchTool`, `EpisodeDetailsTool` | The two agent tools; `window` is an enum; the user id comes from `ToolContext`, never from the model |
| `MemoryExtractionJob` | For a user: list sessions, apply the change check and the settle rule, call both extractors, store the message count. Runs on a background executor |
| `ExtractionStateStore` | Collection `preference_extraction_state/{userId}_{sessionId}` holding the message count (not on ADK's session documents) |
| `PreferenceController` | `GET`, `PUT`, `DELETE /api/preferences/...` for the UI; every write goes through `PreferenceValidator` |

Hooks into existing code:

- **Trigger:** a small endpoint such as `POST /api/memory/refresh`, called by the chat page on load,
  returning immediately and running `MemoryExtractionJob` in the background. (The UI already calls
  `GET /api/sessions` on load; **VERIFY** in `chat.js` before choosing between hooking that call or adding
  the new endpoint.) Use a new executor bean next to `ExecutorConfig`'s existing one. Do not rely on work
  after the HTTP response: Cloud Run may throttle CPU then.
- **Profile injection:** ADK's `Instruction.Provider` (a function of `ReadonlyContext` returning the
  instruction text) lets `AgentConfig` build the instruction per user; `beforeModelCallback` is the
  alternative. **VERIFY** that a provider-built instruction still gets the `{portfolio_analysis}` state
  substitution that `stock_recommendation` relies on, and which of the two agents (probably both) must
  receive the block.
- **Retrieval tools:** registered in `AgentConfig` next to the existing tools on `portfolio_analysis`.
  `loadMemory` and `GuardedMemoryService` are replaced or kept as a fallback (open, see doc 04).
- **Guardrails:** the extractors must only ever see messages that passed the input guardrails. Rejected
  messages are never stored, so this already holds; keep it true. Treat injected profile text and episode
  summaries as data in prompts.

## 4. Configuration (additions to `application.yml` and `AdvisorProperties`)

No defaults in code, following the existing convention (`@Validated` records, values in `application.yml`,
`TestProperties` mirroring them):

- Preferences: extractor model, maximum profile words (about 500), settle minutes (30), maximum sessions per
  run.
- Episodes: extraction model, embedding model, maximum episodes per session, candidate count, the three
  weights, recency decay.
- The existing `advisor.memory.*` keys belong to `loadMemory` and stay until it is retired.

## 5. Build stages (each testable on its own; commit small, test the staged tree)

**Stage 1 — profile pipeline (backend).** Store, validator, extractor, extraction state, the job, the
trigger endpoint. Exit: unit tests for the validator and job (change check, settle rule, idempotent rerun);
`PreferenceExtractorEvalTest` with the nine cases in doc 03 (durable facts per category; a mood; a question
or a holding; the fintech reversal; a manual conflict; injected instruction-like text; several preferences in
one message; no preference; running twice produces no duplicates).

**Stage 2 — profile injection.** The instruction provider, size cap, trimming order (oldest-confirmed first,
never exclusions). Exit: a live end-to-end eval where a stored exclusion changes what the advisor says, and a
test that the block is labelled as data.

**Stage 3 — preferences UI.** Read-only list with source and date, then delete (soft), then add and edit with
dropdowns. Exit: endpoint tests and one browser check.

**Stage 4 — episodes (backend).** Extractor and validator, embeddings, store with the vector field, the two
tools, the hybrid ranking in code. Exit: unit tests for validation and ranking; a retrieval eval (a
paraphrase finds the right episode, an exact ticker, a time-window filter, no match returns nothing, a
multi-topic session is split, one user can never receive another user's episodes).

**Stage 5 — later:** the output check against hard constraints (the not-yet-built L4 in the guardrails),
remembering deletions, consolidation of a topic across sessions, retention rules.

## 6. Verify before coding

1. **Firestore vector search from the Java client** (3.44.0 includes `VectorQuery`) with pre-filters on
   `userId` and time. The earlier embedding implementation is in git history (commit `e5fb66b` removed it) and
   is a reference.
2. **Whether the Firestore emulator supports vector queries.** If not, the retrieval evals need a real
   (test-prefixed) collection, which needs the owner's approval.
3. **The embedding model** available through the project's Vertex setup, and the `genai` embedding call
   (the library, version 1.58.0, has the embedding types).
4. **Instruction provider and state substitution** (section 3).
5. **Creating the vector index** in the Firestore project is an infrastructure change: ask first. Also note
   the composite index that `loadMemory` needs was never created.
6. Chat-page load hook in `chat.js`.

## 7. Risks and how they are handled

- A small model mis-segments or mislabels: validators plus the single-episode fallback; evals.
- A re-added deleted preference: accepted (doc 03, section 6b), soft delete keeps the data.
- Persistent text reaching every prompt (injection): typed enum fields, length caps, free text validated,
  labelled as data, extractors see only the user's own messages (profile) or the numbered session text
  treated as data (episodes).
- Cost: one small-model call per changed session for each extractor, plus embeddings per episode; the
  token-usage views already record real numbers to measure it.
- Background work on Cloud Run: started only from a request, kept short and idempotent.

## 8. Housekeeping when it is built

Update, in the same pass as the code: ARCHITECTURE.md ("Sessions and memory" and a new section),
`Optimization.md`, `mental_model_spring_with_adk.md` where relevant, code comments, and the interview Q&A for
the memory question. Verify live; do not trust an unasserted string replace. Commit messages carry no AI
attribution trailer (owner's standing rule).
