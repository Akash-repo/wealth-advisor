# 04 — Episodic memory

Status: **built 2026-10-18.** Decisions below were agreed on 2026-10-17 and 2026-10-18; where the build differs,
`05-memory-implementation-plan.md` section 9 ("As built") says how and why. Open points at the end are
mostly resolved; what remains is in the deferred register there. Companion to [03 — User preferences](03-user-preferences-memory.md), which covers the profile (semantic)
memory. The two are separate layers.

## 1. What we want to tackle

Let a user say "let's talk more about the JPMorgan idea we discussed last week" and have the advisor find
that earlier conversation and continue it. Episodic memory is the record of *what happened* in past
conversations, as opposed to the profile, which holds *who the user is*.

## 2. What we have today

`loadMemory` (ADK `LoadMemoryTool`), registered only on the first agent, `portfolio_analysis`. The model
decides whether to call it and writes the keyword query itself. It searches raw past events, unranked,
including the current session and the agent's own replies, capped by `GuardedMemoryService` at 8 entries of
500 characters. It currently returns nothing, because the Firestore collection-group index it needs was never
created. See ARCHITECTURE.md "Sessions and memory".

## 3. Decisions so far

### 3.1 The unit of memory is an episode, not a message

An **episode** is a chunk of conversation about one topic: a short summary plus metadata and a pointer back
to the raw events. Raw messages stay in the session store and are not copied. Messages are too small and lose
context; whole sessions are too coarse.

The extractor must **group the conversation by topic** (one episode per distinct topic, with a cap), so a
single vector is never a blur of three topics. People return to topics (valuation, retirement, RSUs, then
valuation again), so an episode is a **topic with a list of message ranges**, not one unbroken stretch: the
two valuation stretches form one episode. Ranges of different episodes never overlap, and an episode never
spans sessions (the session is the boundary the user chose, and deletion works per session). A topic that
recurs across sessions is handled at search time, by returning one episode per session with dates.

### 3.2 Its own collection: `episodes`

Not a field on the session document. One session produces many episodes; ADK's `FirestoreSessionService` owns
the session documents; a vector index is defined per collection with `userId` and time as pre-filters; and
episodes have their own lifecycle (regenerate when the summary prompt improves, delete when a session is
deleted, retention rules).

### 3.3 Schema (one document per episode)

| Field | Purpose |
|---|---|
| `episodeId`, `userId` | Identity. Every query filters on `userId`. |
| `sessionId`, `ranges` | Pointer to the raw events: a list of (from, to) message ranges inside one session, so detail is fetched without duplicating them |
| `startTs`, `endTs` | Time filtering ("last week") |
| `title` | One line, shown in lists |
| `summary` | About 100 to 150 words, written by the model |
| `entities` | Tickers and sectors discussed (exact matching) |
| `topics` | A few keywords |
| `decisions` | What advice was given or decided; matters most in finance |
| `embedding` | Vector of title plus summary |
| `extractorVersion`, `createdAt` | Provenance |

### 3.4 One embedding per episode, no chunking

The embedded text (title plus summary, about 100 to 150 words) is far below any embedding model's input
limit. Chunking is for long raw text. The "chunking" decision is made earlier, at episode boundaries (3.1). We
do not embed raw messages. A message-level index could be added later as a separate decision; the episode's
pointer to raw events already supports "find the episode, then drill down".

### 3.5 Retrieval

1. The model calls `searchPastConversations(topic, tickers?, window?)`. `window` is a **rolling enum**
   (`past_week`, `past_month`, `past_3_months`, `past_year`, `any`); calendar weeks were rejected as ambiguous
   ("last week" maps to `past_week`, slightly over-inclusive, which is safe because the score ranks). The model
   never does date arithmetic and **code** converts the enum to a start time. `any` is bounded to the last 12
   months. `tickers` is optional: symbols the model extracted from the user's words, used for the entity-match
   part of the score.
2. Filter by `userId` and the date range. This is a filter, not a score.
3. Take the nearest candidates by embedding similarity (the query is embedded with the same model used at
   write time), then rerank in application code with a **hybrid score**:

   ```
   score = 0.6 × similarity + 0.3 × entity_match + 0.1 × recency
   ```

   Similarity is meaning (embeddings); entity match is exact (a ticker or word appears); recency is a
   decaying boost. The weights are tunable. Reciprocal rank fusion is an alternative that needs no weights.
4. Return the top few as title, date and summary.
5. If the model needs detail, `getEpisodeDetails(episodeId)` loads the raw turns from the session store.

Reads are allowed to be model-judged (a tool call) because a missed read is cheap and self-correcting, unlike
a missed write (see doc 03).

### 3.6 Written by the batch job

The same lazy batch job as the profile produces episodes, using the same change check (the message count when
the session was last processed) and the same 30-minute settle rule (see doc 03, section 4.2). When a session
has changed, **all of its episodes are regenerated from the whole session**: the episodes are a function of the
session. Episode IDs are predictable (session ID plus position), so a rerun overwrites instead of duplicating,
and leftover episodes beyond the new count are deleted. No lease is needed.

How the model is used: it receives the numbered messages of the session and returns topics, each with its
message ranges, title, summary, entities, topics and decisions. Code validates the answer (ranges exist, none
overlaps, count within the cap, chit-chat dropped). If validation fails, the fallback is **one episode over
the whole session**. Whether the job makes one call with two outputs (profile operations plus episodes) or two
separate calls is open; leaning toward two separate calls at first, because each is easier to test on its own.

## 4. Industry context (from general knowledge; verify before quoting)

CoALA taxonomy (working, episodic, semantic, procedural memory). Generative Agents (2023): a memory stream
retrieved by relevance plus recency plus importance. MemGPT/Letta: tiered core, recall and archival memory.
LangMem uses "episodic" to mean stored experiences (situation, reasoning, outcome), a different sense. Mem0,
Zep (temporal knowledge graph) and Vertex AI Memory Bank extract memories in the background, usually with
embeddings. Common practices: retrieve episodes rather than raw messages, filter on metadata before similarity
search, keep raw history as the source of truth, write by background extraction.

### 3.7 Where the similarity search runs (decided 2026-10-18)

**Brute force in the app first**: read the user's episodes in the window and compute cosine similarity in Java,
behind a small `EpisodeStore.search(...)` interface so it can be swapped. A user has hundreds of episodes, so
this is enough, needs no vector index, and works on the Firestore emulator. Narrowing by ticker first (an
`array-contains-any` query with a two-step fallback to the wider scan) and moving to a Firestore vector index
are deferred (D18, D19 in doc 05).

## 5. Open points

- Verify the Firestore Java client supports vector search with pre-filters (the earlier RAG used Firestore
  vectors), choose the embedding model available through Vertex, and create the vector index (an
  infrastructure change that needs the project owner's approval).
- One call or two for the batch job.
- Weights of the hybrid score, the number of candidates, and the recency decay.
- Retention and deletion: deleting a session deletes its episodes; whether old episodes are merged or expire.
- Whether the second agent should also get the retrieval tools (today only `portfolio_analysis` has
  `loadMemory`).
- What happens to `loadMemory` and `GuardedMemoryService` once this exists (replace, or keep as a fallback).
- Eval cases: a paraphrase finding the right episode ("that bank idea" finds the JPMorgan episode), an exact
  ticker, a time-window filter, a user who has no matching episode, a multi-topic session being split, and
  another user's episodes never being returned.
- Privacy and compliance: whether advice records need a retention period separate from user deletion (the
  BigQuery audit log already keeps one record per turn).
