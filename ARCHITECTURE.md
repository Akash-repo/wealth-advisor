# Wealth Advisor — Architecture & Concept Guide

This is a learning project: a small, "production-shaped" financial advisor built with **Google's
Agent Development Kit (ADK) for Java**, Spring Boot, Spring Security, and Firestore. The goal is
to learn agentic AI practically — this doc is the map for reading the code, written assuming you
know roughly what RAG and LLM tool-calling are, but haven't used ADK specifically before.

If you only read one section, read [Agentic AI / ADK concepts](#agentic-ai--adk-concepts) first —
everything else builds on it. See also [FAQ.md](FAQ.md) for practical questions answered along the
way (how to run it locally, worked examples of how the DB queries actually work, etc.) — that
file grows as new questions come up.

## Table of contents

- [What this app does](#what-this-app-does)
- [Agentic AI / ADK concepts](#agentic-ai--adk-concepts)
- [Architecture diagram](#architecture-diagram)
- [End-to-end request flows](#end-to-end-request-flows)
- [Firestore schema](#firestore-schema)
- [Real market data](#real-market-data)
- [BigQuery audit log](#bigquery-audit-log)
- [Environment variables](#environment-variables)
- [Gotchas discovered the hard way](#gotchas-discovered-the-hard-way)
- [Milestone status](#milestone-status)

## What this app does

A user logs in, optionally uploads a portfolio PDF, and asks natural-language questions like
*"Can I invest in Nvidia?"*. The app answers using a two-stage AI agent pipeline: one agent
analyzes the question against the user's actual holdings (retrieved via RAG vector search over
their uploaded PDF), and a second agent turns that analysis into a balanced recommendation with a
risk disclaimer.

## Agentic AI / ADK concepts

ADK ([google/adk-java](https://github.com/google/adk-java)) is Google's framework for building
LLM-powered "agents" — an agent being more than a single prompt-in/text-out call: it can call
tools, remember conversation history, and be composed with other agents into a pipeline. Every
class name below is a real class in this codebase or in `com.google.adk`.

### `LlmAgent` — a single LLM "persona"

The basic building block. A model, a system instruction, and the tools it's allowed to call.
It's stateless by itself — conversation history lives elsewhere (see `Session` below).

```java
LlmAgent.builder()
    .name("portfolio_analysis")
    .model("gemini-2.5-flash")
    .instruction("You are a portfolio analyst. Use the searchPortfolio tool ...")
    .tools(FunctionTool.create(StockLookupTool.class, "getStockPrice"))
    .build();
```

See `AgentConfig.java` for this app's two agents.

### `FunctionTool` — giving an agent a Java method to call

A "tool" is just a Java method the model can decide to invoke mid-conversation. ADK reflects over
the method's parameters to build a JSON schema it hands to Gemini describing what arguments it
takes; when the model decides it needs the tool, ADK invokes the method for you and feeds the
return value back into the conversation.

```java
public static Map<String, Object> getStockPrice(
    @Schema(name = "ticker", description = "Stock ticker symbol, e.g. NVDA") String ticker) { ... }

// registered on the agent:
FunctionTool.create(StockLookupTool.class, "getStockPrice")   // static method
FunctionTool.create(portfolioSearchTool, "searchPortfolio")   // instance method (Spring bean)
```

Two tool styles are used in this app:
- **`StockLookupTool`** — a plain `static` method, no dependencies, registered via the
  `FunctionTool.create(Class, methodName)` overload.
- **`PortfolioSearchTool`** — a Spring-managed `@Component` with real dependencies (Firestore, an
  embedding client), registered via the `FunctionTool.create(instance, methodName)` overload
  instead, so it can be constructor-injected like any other Spring bean.

### `ToolContext` — how a tool learns *who's asking*

A tool method can declare a parameter literally named `toolContext` of type
`com.google.adk.tools.ToolContext`; ADK recognizes that parameter name and injects the current
turn's context automatically — it's invisible to the model (not part of its tool schema). This is
how `PortfolioSearchTool` gets the real user id (`toolContext.userId()`) to scope its Firestore
query, rather than trusting the model to supply a `userId` argument (never trust the model with
tenant-scoping) or reaching for Spring Security's `SecurityContextHolder` (unsafe here — ADK's
execution is async/reactive, so a tool call isn't guaranteed to run on the original servlet
thread that a `ThreadLocal`-based security context lives on).

```java
public Map<String, Object> searchPortfolio(
    @Schema(name = "query", description = "...") String query,
    ToolContext toolContext) {          // <- auto-injected, not model-supplied
  String userId = toolContext.userId();
  ...
}
```

**Gotcha:** this match is by literal Java parameter name via reflection, which only survives
compilation if `-parameters` is enabled on the compiler (see `pom.xml`) — otherwise the parameter
name resolves to `arg1` and the match silently fails.

### `Session` / `SessionService` — conversation memory

A `Session` is one ongoing conversation: an id, a user id, a list of `Event`s (every message,
tool call, and tool response so far), and a small state map. `SessionService` is the storage
backend for sessions — this app uses `FirestoreSessionService`, so conversations survive app
restarts (see [Firestore schema](#firestore-schema) for exactly what that looks like on disk).

### `Runner` — actually executing a turn

`Runner` ties an agent, a `SessionService`, and (unused here) an artifact/memory service
together. You hand it a user id, a session id, and a new message; it returns a stream
(`Flowable<Event>`, RxJava) of every event that happens during that turn — tool calls, tool
responses, and the agent's text reply.

```java
runner.runAsync(userId, sessionId, Content.fromParts(Part.fromText(message)))
    .toList()
    .blockingGet();   // collect the whole turn instead of streaming it
```

See `AgentChatService.chat()` for how this app uses it.

### `SequentialAgent` — chaining agents

Runs a fixed list of sub-agents one after another against the *same* session, in the *same*
order, every single time — no branching, no LLM-driven decision to skip a stage. It doesn't pass
data between sub-agents directly; the hand-off is via session **state**:

```java
// Agent 1 writes its answer into session state under a key:
LlmAgent.builder().outputKey("portfolio_analysis")...

// Agent 2's instruction references that key with a {placeholder}, substituted before the
// model ever sees the prompt:
LlmAgent.builder().instruction("A colleague reported back: {portfolio_analysis} ...")...

SequentialAgent.builder().subAgents(agent1, agent2).build();
```

This app's `wealthAdvisorPipeline` (in `AgentConfig`) is exactly this: `portfolio_analysis` →
`stock_recommendation`. Both agents' events land in the same session, so `AgentChatService`
deliberately returns only the *last* text-bearing event to the user — agent 1's intermediate
analysis is consumed by agent 2 via the placeholder, not shown directly.

### RAG (Retrieval-Augmented Generation), in this app specifically

"RAG" here means: chunk an uploaded PDF, embed each chunk into a vector, store the vectors, and
at query time embed the *question* and ask Firestore for the nearest-matching chunks — those
chunks get stuffed into the prompt as context so the model can answer using real data it was
never trained on. See `PortfolioIngestionService` (writing) and `PortfolioSearchTool` (reading).
Both sides **must** use the same embedding model (`text-embedding-004` here) — otherwise the
vector distances are meaningless.

## Architecture diagram

```
                         Browser                         
                            |                            
         (JSESSIONID cookie / chat + upload REST)        
                            v                            
+-------------------------------------------------------+
| SPRING BOOT APP (advisor)                             |
|                                                       |
| SecurityConfig (form login, JSESSIONID cookie)        |
| FirestoreUserDetailsService  <-- users collection     |
|                                                       |
| ChatController -> AgentChatService -> Runner          |
|   -> SequentialAgent (wealthAdvisorPipeline)          |
|        portfolio_analysis -> stock_recommendation     |
|        tools: getStockPrice, searchPortfolio (RAG)    |
|                                                       |
| PortfolioController -> PortfolioIngestionService      |
|   (PDFBox parse -> TextChunker -> EmbeddingService)   |
|                                                       |
| AgentAuditRecorder (beforeModel/afterModel callbacks, |
|   wired onto both agents in AgentConfig)              |
+-------------------------------------------------------+
                            |                            
                            v                            

     v                    v                    v                    v
Firestore: users   Firestore:            Firestore: adk-session/*   BigQuery:
(auth + profile)   portfolio_vectors     (ADK conversation           wealth_agent
                    (RAG chunks +        history, via                .agent_audit_log
                    embeddings)          FirestoreSessionService)    (1 row/turn)

                            |                            
                            v                            
          Gemini 2.5 Flash + text-embedding-004          
                   (Vertex AI, via ADC)                  
```

*(`portfolio_analysis` and `stock_recommendation` are the two `SequentialAgent` stages; only
`portfolio_analysis` has tools — `stock_recommendation` has none, it just reads
`{portfolio_analysis}`'s output. See [Agentic AI / ADK concepts](#agentic-ai--adk-concepts) above
for what each box/arrow means. `AgentAuditRecorder` sits outside the request path proper — it's
model-call callbacks fired by ADK itself, not something `AgentChatService` calls directly except
once, at the very end of a turn, to flush the accumulated row. See [BigQuery
audit log](#bigquery-audit-log).)*

## End-to-end request flows

### 1. Signup + login

1. (Optional, new users only) Browser POSTs JSON to `/api/auth/register` from `signup.html`.
   `RegistrationController` → `UserRegistrationService` validates the username/password, checks
   `users/{username}` doesn't already exist, and writes the doc (bcrypt `passwordHash`, same
   shape `DemoUserSeeder` writes). This endpoint only creates the account — it doesn't log you
   in — so the client redirects to `/login.html` on success.
2. Browser POSTs `username`/`password` to `/api/auth/login` (a plain HTML form in `login.html`,
   handled entirely by Spring Security's form login filter — there's no custom controller for
   this endpoint).
3. `FirestoreUserDetailsService.loadUserByUsername` looks up `users/{username}` in Firestore
   directly by document id (not a query) and compares the bcrypt `passwordHash`.
4. On success, Spring Security issues an `HttpOnly`, `SameSite=Lax` `JSESSIONID` cookie and
   redirects to `index.html`.

### 2. Portfolio upload (RAG ingestion)

1. Authenticated `POST /api/portfolio/upload` (multipart) hits `PortfolioController`.
2. `PortfolioIngestionService.ingest`:
   - Extracts text from the PDF (PDFBox's `Loader.loadPDF` + `PDFTextStripper`).
   - Splits it into ~250-word chunks (`TextChunker`).
   - Embeds each chunk (`EmbeddingService`, Gemini `text-embedding-004`, 768 dimensions).
   - Writes each chunk + its vector to Firestore's `portfolio_vectors` collection.
   - Sets `users/{userId}.hasUploadedPortfolio = true`.

### 3. Chat (the two-agent RAG pipeline)

1. Authenticated `POST /api/chat` hits `ChatController`, which calls
   `AgentChatService.chat(authenticatedUsername, message)` — note the userId comes from the
   *authenticated principal*, never from the request body.
2. `AgentChatService` ensures a Firestore-backed ADK session exists for this user (session id =
   user id — one continuous conversation per user, no need to juggle multiple session ids), then
   calls `runner.runAsync(...)`.
3. **Stage 1 — `portfolio_analysis`**: the model reads the conversation, and can call:
   - `searchPortfolio` — embeds the user's question, runs a Firestore vector `findNearest` query
     scoped to `whereEqualTo("userId", toolContext.userId())`, returns the closest chunks.
   - `getStockPrice` — looks up a (currently mocked) stock price by ticker.
   It writes a short analysis into session state under `portfolio_analysis`.
4. **Stage 2 — `stock_recommendation`**: its instruction has `{portfolio_analysis}` substituted
   in, so it "reads" stage 1's analysis as plain text, and writes the final, user-facing reply
   (with a risk disclaimer, per its instruction).
5. `AgentChatService` walks the turn's events backwards and returns the last non-empty text
   found — stage 2's reply, not stage 1's intermediate analysis.
6. Independently of all that, every real call *either* agent makes to Gemini during the turn is
   captured by `AgentAuditRecorder`'s `beforeModelCallbackSync`/`afterModelCallbackSync` (wired
   onto both agents in `AgentConfig`) — request JSON, response JSON, latency, token counts. Once
   the turn ends (success or thrown exception — `AgentChatService.chat` finalizes from a `finally`
   block), those accumulated calls are written as one row to BigQuery. See [BigQuery audit
   log](#bigquery-audit-log).

## Firestore schema

### `users` — app-level auth & profile (this app's own collection)

| Field                | Type      | Notes                                                    |
|----------------------|-----------|-----------------------------------------------------------|
| *(document id)*      | string    | the username, e.g. `demo`                                  |
| `email`               | string    | seeded as `{username}@example.com` by `DemoUserSeeder`      |
| `passwordHash`        | string    | bcrypt — **not** in the original design doc's schema, added because real auth needs a password |
| `createdAt`           | timestamp |                                                             |
| `hasUploadedPortfolio`| boolean   | set `true` after a successful upload                        |

### `portfolio_vectors` — RAG chunks (this app's own collection)

| Field       | Type              | Notes                                              |
|-------------|-------------------|-----------------------------------------------------|
| *(doc id)*  | auto-generated    |                                                      |
| `userId`    | string            | indexed — pre-filter before the vector search        |
| `filename`  | string            | original uploaded filename                           |
| `chunkText` | string            | the ~250-word chunk, returned as RAG context         |
| `embedding` | vector (768 floats)| from `text-embedding-004`                            |
| `createdAt` | timestamp         |                                                       |

**Requires a composite index** (equality filter on `userId` + vector index on `embedding`) — see
[Gotchas](#gotchas-discovered-the-hard-way).

### `market_data_cache` — real stock data, cached across all users (this app's own collection)

| Field                  | Type      | Notes                                                    |
|-------------------------|-----------|-------------------------------------------------------------|
| *(document id)*          | string    | the ticker symbol, uppercased, e.g. `AAPL` — direct get, no query |
| `found`                   | boolean   | `false` if the ticker doesn't exist / FMP has nothing on it |
| `price`                   | number    | nullable — absent if that one FMP call failed                |
| `peRatio` / `pegRatio`      | number    | nullable                                                       |
| `forwardPeRatio`             | number    | nullable — computed by this app, not returned by FMP directly  |
| `epsGrowth`                   | number    | nullable                                                       |
| `freeCashFlowPerShare`          | number    | nullable                                                       |
| `asOf`                            | string (ISO instant) | when this doc was fetched — checked against a 24h TTL in code |

See [Real market data](#real-market-data) for the full design (why Firestore, why a 24h TTL, how
partial FMP failures are handled).

### ADK's own collections (managed by `FirestoreSessionService`, not our code)

ADK's `FirestoreSessionService` picks its own schema, rooted at a collection literally called
**`adk-session`** by default (`FirestoreProperties`'s hardcoded default — overridable via an
`adk-firestore.properties` file on the classpath, which this app doesn't have):

```
adk-session (collection)
  {userId} (document)                         <- our userId, e.g. "demo"
    sessions (subcollection)
      {sessionId} (document)                  <- == userId in this app (see AgentChatService)
        id, appName, userId, updateTime, state (fields)
        user-event (subcollection)
          {eventId} (document)
            author, timestamp, appName, content{parts:[...]}, keywords[] (fields)

app-state (collection)
  {appName} (document)                        <- state keys prefixed "_app_" in EventActions

user-state (collection)
  {appName} (document)
    users (subcollection)
      {userId} (document)                     <- state keys prefixed "_user_"
```

We don't write to any of this directly — it's entirely managed by
`com.google.adk.sessions.FirestoreSessionService`, constructed once in `AgentChatService`.

## Real market data

`StockLookupTool`/`getStockData` originally returned prices from a hardcoded mock map. It now
calls [Financial Modeling Prep](https://financialmodelingprep.com)'s free tier for real price,
P/E, PEG, forward P/E, EPS growth, and free cash flow per share — fronted by the
[`market_data_cache`](#firestore-schema) Firestore collection with a 24h TTL, since this data is
the same for every user asking about a given ticker (no reason to refetch per-user or per-message).

**Split across two classes**: `StockLookupTool` (`agent/tool/StockLookupTool.java`) is the thin
ADK-facing surface — the `@Schema`-annotated method the model actually calls. `MarketDataService`
(`agent/tool/MarketDataService.java`) does the real work: check the cache, call FMP on a miss,
combine, write back. Split out because the FMP integration (4 parallel HTTP calls, per-call
failure handling, forward-P/E computation) is meaningfully more logic than `PortfolioSearchTool`
has in one method — keeping it in its own class keeps `StockLookupTool` a one-line delegate, easy
to read as "this is the tool's contract with the model," with the mechanics elsewhere.

**Why FMP specifically, and what's actually free**: verified live against a real free-tier key
(not just docs, which undersold it) — `/stable/quote`, `/stable/ratios`, `/stable/financial-growth`,
and `/stable/analyst-estimates` all responded with real data, no paywall errors. No credit card
required, 250 requests/day. That budget is per-app, not per-user, specifically *because* of the
cache — with a 24h TTL, the daily cap is really "~60 distinct tickers/day across every user
combined" (4 calls per cache-miss ticker), not "60 chat messages/day."

**Why 4 parallel calls via `CompletableFuture`, not sequential or Spring `@Async`**: `getStockData`
needs price (`quote`), P/E + PEG + FCF/share (`ratios`), EPS growth (`financial-growth`), and a
forward P/E computed from consensus EPS estimates (`analyst-estimates`) — four independent FMP
endpoints. `java.net.http.HttpClient.sendAsync(...)` + `CompletableFuture.allOf(...)` fires all
four concurrently with zero new dependencies (`HttpClient` has shipped in the JDK since 11) and
ties up no thread while waiting on the network, since `sendAsync` is non-blocking under the hood.
Spring's `@Async` was considered and rejected: it would need `@EnableAsync` plus an explicit
`TaskExecutor` bean (Spring Boot's default creates an unpooled thread per call), a second bean
class to dodge the self-invocation proxy gotcha, and — since it only pays off if the wrapped call
is itself synchronous — would tie up a real pooled thread for each call's full round-trip, which
is strictly less efficient than `sendAsync`'s already-non-blocking I/O. More ceremony *and* worse
resource usage for this specific shape of work (a handful of near-identical outbound GETs, fully
synchronous call site).

**Forward P/E needed real care, not `array[0]`**: forward P/E = current price ÷ the *nearest
upcoming* fiscal year's consensus EPS. Live testing showed FMP's `analyst-estimates` array is not
reliably ordered nearest-year-first (a real response returned FY2030 before FY2029) — so
`MarketDataService.computeForwardPe` scans every entry's own `date` field for the closest one
that hasn't already passed, rather than trusting array order. Getting this silently wrong (e.g.
dividing today's price by a 4-years-out EPS estimate) would produce a confidently-stated, wrong
number from a tool whose whole job is giving a "wealth advisor" agent real figures — worse than
just not having the field.

**Two-tier failure handling** (a deliberate distinction, not blanket try/catch):
- *Predictable failures* — one FMP endpoint times out, rate-limits, or returns a bad ticker — are
  caught **per endpoint** inside `fetchEndpoint`'s `.exceptionally(...)`, logged as a warning, and
  that field just comes back `null` in the combined result rather than failing the whole call.
  `quote` coming back empty is treated as `found: false` immediately (every FMP endpoint
  independently reports an unknown ticker as an empty array, so there's no need to inspect the
  other 3 to know the ticker doesn't exist).
- *Genuinely unexpected failures* (a bug in this app's own code, not an external service being
  flaky) are deliberately **not** caught here — they're left to propagate so
  `ToolUsageRecorder.onToolError` still captures them into `tool_invocations` with `status=ERROR`
  for real, rather than a blanket catch silently defeating the auditing already built for exactly
  this.
- Firestore cache reads/writes fail the same "warn and continue" way — a cache miss on a read
  failure just means a live fetch instead of a stale answer; a write failure costs nothing beyond
  the next request re-fetching, since the caller already has the freshly fetched data regardless.

**The tool waits for the cache write to finish** before returning (`writeCache(...)` blocks on
Firestore's `ApiFuture#get()`) rather than firing it in the background — the write is fast (tens
of ms) next to the 4 parallel HTTP calls that dominate the tool's latency, and blocking on it
means "the tool returned" and "the next user's request can hit the cache" happen together, with
no window where a background write could silently fail unobserved.

**Options considered and rejected:**

1. **Redis/Memcached (GCP Memorystore) instead of Firestore for the cache.** Rejected —
   Memorystore has no public IP (VPC-only; this app runs locally today and isn't on Cloud Run yet,
   so there's no VPC to put it in without standing up a tunnel/bastion purely for a cache), its
   data-plane isn't ADC-authenticated the way every other GCP service this app uses is (it speaks
   plain Redis protocol, a different credential story), and it has no perpetual free tier — it's
   an always-on billed instance, which conflicts with this project's explicit "free tier only, no
   credit card" constraint. Worth revisiting once this app is actually on Cloud Run (Serverless
   VPC Access makes Memorystore reachable then), not before.
2. **A plain in-memory cache (`ConcurrentHashMap`/Caffeine) instead of Firestore.** Considered —
   genuinely simpler, and would still solve "don't refetch per user." Rejected in favor of
   Firestore specifically because this app already restarts often during local dev (Spring Boot
   DevTools auto-restarts on every recompile), which would evict an in-memory cache constantly and
   defeat a large fraction of its purpose; Firestore survives restarts and costs nothing new
   (ADC's already set up, zero additional dependencies).
3. **Sequential FMP calls instead of parallel.** Considered as the simpler option, since this only
   matters on a cache miss and even 4 sequential calls at ~300-500ms each adds maybe 1-2s to a
   turn that's already 7-10s dominated by two Gemini calls. Went with parallel anyway since
   `HttpClient.sendAsync` made it no more complex than sequential code would have been — not a
   case of complexity traded for a marginal win.
4. **Blanket try/catch around the whole FMP fetch, log and return `found: false` on any failure.**
   Rejected in favor of the two-tier approach above — a blanket catch would mean one flaky
   endpoint (say, `analyst-estimates` timing out) throws away the 3 calls that *did* succeed, and
   would swallow real bugs that the audit trail is specifically built to catch.

## BigQuery audit log

Every real call this app makes to Gemini gets recorded — this is the "see exactly what happened
internally for a given user turn" tool, queryable with real SQL rather than grepping logs. One row
per user chat turn in `wealth_agent.agent_audit_log`, created on startup if missing by
`AuditSchemaInitializer` (same "seed what's missing, warn and keep going if unreachable" pattern
as `DemoUserSeeder`).

**Why BigQuery and not Cloud SQL or just log lines:** serverless (billed per query/storage, not
per hour like an always-on Cloud SQL instance), the free tier (10GB storage + 1TB queries/month)
comfortably covers this project's scale, and its native `JSON` column type means the raw
request/response payloads stay queryable (`JSON_VALUE`, `JSON_QUERY`, ...) instead of being an
opaque blob.

**How a turn is captured** (`AgentAuditRecorder`, wired onto both agents in `AgentConfig` via
`.beforeModelCallbackSync(agentAuditRecorder::beforeModel)` /
`.afterModelCallbackSync(agentAuditRecorder::afterModel)`):

- `beforeModel` fires immediately before every actual Gemini call, for *either* agent; `afterModel`
  immediately after. A single user turn is correlated across both agents (and across however many
  times each agent calls the model) using ADK's own `invocationId` — generated once per incoming
  user message by `Runner` and threaded through every sub-agent in the `SequentialAgent`.
- One agent can call Gemini more than once per turn: if `portfolio_analysis` decides to call
  `searchPortfolio`, that's a first call where the model asks to invoke the tool, then a second
  call where it reacts to the tool's result. Both are captured, in order, in that agent's
  `*_llm_calls` JSON array — this is genuinely a list per agent, not a single request/response
  pair.
- `AgentChatService.chat` calls `AgentAuditRecorder.finalizeTurn` exactly once per turn, from a
  `finally` block so it fires on both success and a thrown exception, at which point the
  accumulated row (plus `input_to_agent2` — specifically agent 1's output text, found by filtering
  turn events by author — and the final reply) is written with `bigQuery.insertAll(...)`.

**Table schema** (`AuditSchemaInitializer.schema()`):

| Field                    | Type      | Notes                                                        |
|---------------------------|-----------|---------------------------------------------------------------|
| `user_prompt_id`           | string    | ADK's `invocationId` — one per user message                   |
| `user_id`                  | string    | the authenticated username                                    |
| `session_id`                | string    | == `user_id` in this app (see [Firestore schema](#firestore-schema)) |
| `user_prompt_text`          | string    | the raw user message                                          |
| `agent1_name` / `agent2_name` | string  | `portfolio_analysis` / `stock_recommendation`                 |
| `agent1_llm_calls` / `agent2_llm_calls` | JSON | array of `{requestJson, responseJson, latencyMs, promptTokens, completionTokens}`, one element per real Gemini call that agent made |
| `agent1_latency_ms` / `agent2_latency_ms` | int64 | sum of that agent's own call latencies |
| `agent1_prompt_tokens` / `agent1_completion_tokens` (and `agent2_*`) | int64 | summed across that agent's calls |
| `input_to_agent2`          | string    | agent 1's output text specifically (not just "whatever text preceded agent 2"), via author-filtered event lookup |
| `final_response_to_user`    | string    | what `AgentChatService.chat` actually returned                |
| `total_latency_ms`          | int64     | whole-turn wall-clock time                                     |
| `status` / `error_message`  | string    | `SUCCESS` or `ERROR` (+ the exception message)                 |
| `created_at`                | timestamp |                                                                 |

**Gotcha:** `@JsonRawValue` on `RoundTrip.requestJson`/`responseJson` matters — those fields are
already-serialized JSON strings (from ADK's own `LlmRequest.toJson()`/`LlmResponse.toJson()`), and
without `@JsonRawValue` Jackson would double-escape them into a JSON-string-inside-a-JSON-string
when the whole `RoundTrip` list gets serialized for the `*_llm_calls` column, breaking BigQuery's
`JSON` column functions.

### Tool usage tracking (`tool_invocations` / `tool_registry`)

**The problem this solves:** `agent_audit_log`'s `agent1_llm_calls`/`agent2_llm_calls` JSON arrays
already contain every tool call ADK made (see the request/response JSON above — a tool call shows
up as a `functionCall` part), but answering "which tools are actually being used, by which agent,
how often, how slow, how often do they error" from that requires `JSON_QUERY_ARRAY` gymnastics
across every row. That doesn't scale as the number of registered tools and agents grows — this
project has 2 tools today, but the whole point of tracking usage is to support a future with
hundreds of tools across many agents, where you actually need to answer "which of these are dead
weight" from real data rather than institutional memory. So tool calls also get written to two
dedicated, normalized tables, in addition to (not instead of) staying inside `agent_audit_log`'s
JSON blobs — that JSON is still the right place for "replay exactly what happened in this one
turn," these tables are for "aggregate across many turns."

**`tool_invocations`** — one row per real tool call, written by `ToolUsageRecorder.finalizeTurn`:

| Field             | Type      | Notes                                                              |
|--------------------|-----------|----------------------------------------------------------------------|
| `invocation_id`     | string    | primary key — ADK's own `functionCallId` (see rationale below)       |
| `user_prompt_id`     | string    | `NOT ENFORCED` foreign key → `agent_audit_log.user_prompt_id`        |
| `agent_name`         | string    | which agent made the call                                            |
| `tool_name`           | string    | e.g. `getStockPrice`, `searchPortfolio`                              |
| `tool_input`           | JSON      | the tool's arguments, as the model supplied them                     |
| `tool_output`          | JSON      | the tool's return value (absent on error)                            |
| `status`               | string    | `SUCCESS` or `ERROR`                                                 |
| `error_message`        | string    | populated only on `ERROR`                                            |
| `latency_ms`           | int64     | wall-clock time of just that one tool call                           |
| `called_at`            | timestamp | partitioning key                                                     |

Partitioned by day on `called_at`, clustered by `(tool_name, agent_name)` — "tool X's usage over
the last 30 days" and "everything agent Y called" are the queries this is actually for, and both
prune to a fraction of the table instead of a full scan as row count grows.

**`tool_registry`** — one row per distinct tool name that has ever existed in code, kept in sync
by `ToolUsageSchemaInitializer.syncToolRegistry` from the live `LlmAgent` bean list on every
startup (a `MERGE` upsert, never deletes — a tool that gets removed from code stays in the
registry with a stale `last_seen_at`, which is itself useful signal):

| Field                  | Type      | Notes                                          |
|-------------------------|-----------|--------------------------------------------------|
| `tool_name`              | string    | primary key                                        |
| `description`             | string    | from the tool's own `@Schema`/description         |
| `first_registered_at`      | timestamp |                                                     |
| `last_seen_at`             | timestamp | bumped every app startup the tool is still present |

Deliberately does **not** store which agent(s) own a tool — that's usage data, derivable by
joining `tool_invocations` grouped by `tool_name`/`agent_name`, and storing it redundantly here
would just be a second copy that could drift from the truth.

**How a call gets captured:** ADK exposes `beforeToolCallbackSync`/`afterToolCallbackSync`/
`onToolErrorCallbackSync` on `LlmAgent.builder()` — the exact same shape as the model callbacks
`AgentAuditRecorder` already uses, just one level down (around the actual tool invocation rather
than the Gemini call that requested it). `ToolContext` (passed to all three) already carries a
`functionCallId()` that ADK assigns once per real tool call — `ToolUsageRecorder` reuses that
directly as `invocation_id` rather than generating a second id that would just duplicate it.
Rows are buffered per turn (keyed by ADK's `invocationId`, same pattern as `AgentAuditRecorder`)
and flushed as **one batched `insertAll`** from `finalizeTurn` — a turn that calls five tools does
one BigQuery write, not five. That's what keeps this cheap and within streaming-insert throughput
limits as tool/agent count grows, not just at today's scale.

**Options considered and rejected:**

1. **Keep tool-call analytics inside `agent_audit_log`'s JSON only, no new tables.** Rejected —
   works for "replay one turn," falls over for "aggregate across tools/agents," which is the
   actual goal (find unused tools, spot slow/error-prone ones).
2. **Write one `insertAll` per tool call, immediately in `afterTool`/`onToolError`.** Rejected in
   favor of batching per turn — N tool calls in a turn would mean N separate API round-trips
   instead of one, which is strictly worse on both latency and (at high tool-call volume)
   streaming-insert throughput, for no benefit; nothing needs the row visible mid-turn.
3. **Generate our own UUID per tool call instead of reusing ADK's `functionCallId`.** Rejected —
   ADK already assigns a unique id per real tool invocation; inventing a second id space would
   just be redundant bookkeeping. (Still falls back to a generated UUID if `functionCallId()` is
   ever absent, defensively.)
4. **Store `owning_agent` as a column on `tool_registry`.** Rejected — a tool can in principle be
   used by more than one agent, and which agents use which tools is usage history, not the tool's
   own static identity; that question is already answerable by joining against `tool_invocations`
   without maintaining a second, potentially-stale copy of the same fact.
5. **Hand-maintain `tool_registry` (manual `INSERT`s when a tool is added).** Rejected — drifts
   from reality the first time someone adds a tool and forgets the second step. Auto-deriving it
   from the actually-configured `LlmAgent` beans at startup means it can never be wrong about
   what's registered, at the cost of a small `MERGE` DML statement per tool on every app startup —
   fine at this project's scale and dev-iteration cadence; if that ever becomes a real concern
   (BigQuery DML has historically had per-table daily-statement quotas), the fix is to only run
   this sync on deploy rather than on every process start, not to hand-maintain the table.
6. **Enforce the `user_prompt_id` foreign key for real.** Not possible — BigQuery has no runtime
   FK/PK enforcement, only `NOT ENFORCED` declarations. Declared anyway because it costs nothing,
   documents the relationship in the schema itself, and BI tools/query generators that read
   BigQuery's constraint metadata pick it up automatically for join suggestions. This is also why
   `agent_audit_log` needed a `user_prompt_id` primary key retrofitted onto it (see
   `AuditSchemaInitializer`) — a `NOT ENFORCED` FK still has to reference a declared PK/unique
   constraint on the other table, even though nothing actually checks it at insert time.
7. **Retrofit partitioning onto the existing `agent_audit_log` table while touching this area
   anyway.** Not done here — out of scope for what was asked, and BigQuery can't add partitioning
   to an already-created table without recreating it (a bigger, riskier change than adding a
   constraint). Flagged as a reasonable follow-up, not done as a drive-by.

## Environment variables

| Variable                  | Purpose                                                              |
|----------------------------|-----------------------------------------------------------------------|
| `GOOGLE_GENAI_USE_VERTEXAI`| `true` — routes Gemini calls through Vertex AI + ADC instead of an API key |
| `GOOGLE_CLOUD_PROJECT`     | GCP project for Vertex AI calls                                       |
| `GOOGLE_CLOUD_LOCATION`    | Vertex AI region, e.g. `us-central1`                                  |
| `GCP_PROJECT_ID`           | GCP project for the Firestore *and* BigQuery clients (`gcp.project-id` property) |
| `DEMO_USER_USERNAME`/`DEMO_USER_PASSWORD` | overrides the seeded demo login (defaults `demo`/`changeme`) |
| `FIRESTORE_EMULATOR_HOST`  | if set, Firestore client auto-detects it and uses the local emulator instead of real GCP (no code involved — the client library checks this env var itself) |
| `FMP_API_KEY`               | Financial Modeling Prep key for `MarketDataService` (`fmp.api-key` property) — free tier, no credit card, get one at financialmodelingprep.com. **The one non-ADC credential in this app** — see [Real market data](#real-market-data) for why (a third-party API, not a GCP service). If unset, stock lookups degrade to `found: false` instead of failing the chat turn. |

No `GOOGLE_API_KEY`/`GEMINI_API_KEY` is used — this project deliberately uses ADC via Vertex AI
instead (one credential covers Gemini, Firestore, and now BigQuery too, and it's what Cloud Run will use
automatically later via its attached service account). `FMP_API_KEY` is the sole exception, since
Financial Modeling Prep is a third-party SaaS API, not a GCP service — ADC has no meaning there.

## Gotchas discovered the hard way

These aren't documented anywhere obvious — found by actually running the app, not by reading docs:

1. **Firestore vector search needs a composite index.** `findNearest` combined with a
   `whereEqualTo` pre-filter throws `FAILED_PRECONDITION` at *query* time (not write time) until
   you create one:
   ```
   gcloud firestore indexes composite create --project=<project> \
     --collection-group=portfolio_vectors --query-scope=COLLECTION \
     --field-config=order=ASCENDING,field-path=userId \
     --field-config='vector-config={"dimension":"768","flat":"{}"},field-path=embedding'
   ```
   Index creation is asynchronous (a few minutes) — poll `gcloud firestore indexes composite
   list` for `state=READY`.

2. **`FirestoreSessionService` + `RunConfig.autoCreateSession` don't actually work together.**
   `Runner.runAsync`'s auto-create logic only fires when the session lookup completes *empty*,
   but `FirestoreSessionService.getSession` signals "not found" by *erroring* with
   `SessionNotFoundException` instead. `AgentChatService.ensureSessionExists` works around this
   with an explicit get-or-create check before every turn.

3. **`google-adk-dev` must stay out of this app's dependencies.** It bundles its own embedded
   Spring Boot server (`AdkWebServer`) for ADK's standalone dev UI, which would conflict with
   this app's own `@SpringBootApplication`/embedded Tomcat if both were on the classpath.

4. **The real Maven coordinate is `com.google.adk:google-adk`** — an earlier draft of the design
   doc referenced `google-adk-spring-boot-starter`, which doesn't exist.

## Milestone status

1. ✅ Single agent + one tool + `/api/chat`, in-memory sessions.
2. ✅ Spring Security login backed by Firestore, per-user session isolation.
3. ✅ Two-agent `SequentialAgent` pipeline (`portfolio_analysis` → `stock_recommendation`).
4. ✅ PDF upload → RAG (chunk/embed/store/search) + Firestore-backed ADK sessions.
5. ✅ Self-service signup (`signup.html` → `POST /api/auth/register` → `UserRegistrationService`),
   so accounts aren't limited to the one seeded demo user.
6. ✅ BigQuery audit trail of every Gemini call and every tool call (`AgentAuditRecorder` +
   `ToolUsageRecorder`, see [BigQuery audit log](#bigquery-audit-log) and its "Tool usage
   tracking" subsection) — covers the "see what the agents actually did, and which tools are
   actually earning their keep" need that full OpenTelemetry tracing was originally on hold for;
   a proper OTel/tracing setup is still undone and would be complementary (spans/traces vs. these
   tables' one-row-per-turn / one-row-per-tool-call records), not a replacement.
7. ⏳ Cloud Run deployment — not started.
