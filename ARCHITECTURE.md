# Wealth Advisor — Architecture & Concept Guide

This is a learning project: a small, "production-shaped" financial advisor built with **Google's
Agent Development Kit (ADK) for Java**, Spring Boot, Spring Security, and Firestore. The goal is
to learn agentic AI practically — this doc is the map for reading the code, written assuming you
know roughly what RAG and LLM tool-calling are, but haven't used ADK specifically before.

If you only read one section, read [Agentic AI / ADK concepts](#agentic-ai--adk-concepts) first —
everything else builds on it. See also [FAQ.md](FAQ.md) for practical questions answered along the
way (how to run it locally, worked examples of how the DB queries actually work, etc.) — that
file grows as new questions come up.

> **Update (2026-10-16): embeddings and vector search were removed.** Statements are tables of
> positions, and the numbers come from structured holdings, so the chunk/embed/`searchPortfolio`
> path (the least-used tool, and an extra failure point on every upload) is gone. Uploads are now
> portfolio statements only, parsed straight into `portfolio_holdings`. Sections below describe the
> **current** behavior except where marked *historical* (the RAG concept note, the
> `portfolio_vectors` schema, the vector-index gotcha, and design-decision / milestone entries
> from before the change). See [Routing.md](Routing.md) for the current request routing.

## Table of contents

- [What this app does](#what-this-app-does)
- [Agentic AI / ADK concepts](#agentic-ai--adk-concepts)
- [Architecture diagram](#architecture-diagram)
- [End-to-end request flows](#end-to-end-request-flows)
- [Firestore schema](#firestore-schema)
- [Portfolio holdings](#portfolio-holdings)
- [Real market data](#real-market-data)
- [BigQuery audit log](#bigquery-audit-log)
- [Logging and traceability](#logging-and-traceability)
- [Evals](#evals)
- [Environment variables](#environment-variables)
- [GCP deployment (Cloud Run)](#gcp-deployment-cloud-run)
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
    .tools(FunctionTool.create(stockLookupTool, "getStockData"))
    .build();
```

See `AgentConfig.java` for this app's two agents.

### `FunctionTool` — giving an agent a Java method to call

A "tool" is just a Java method the model can decide to invoke mid-conversation. ADK reflects over
the method's parameters to build a JSON schema it hands to Gemini describing what arguments it
takes; when the model decides it needs the tool, ADK invokes the method for you and feeds the
return value back into the conversation.

```java
public Map<String, Object> searchPortfolio(
    @Schema(name = "query", description = "...") String query, ToolContext toolContext) { ... }

// registered on the agent:
FunctionTool.create(portfolioSearchTool, "searchPortfolio")   // instance method (Spring bean)
```

Two registration overloads exist: `FunctionTool.create(Class<?>, methodName)` for a plain
`static` method with no dependencies, or `FunctionTool.create(instance, methodName)` for a
Spring-managed `@Component`, constructor-injected like any other bean.

Both of this app's tools are registered the instance way today — **`PortfolioSearchTool`**
(Firestore + an embedding client) and **`StockLookupTool`** (delegates to `MarketDataService`,
which itself needs Firestore + an HTTP client). `StockLookupTool` actually *started* as a plain
`static` method — a hardcoded mock price lookup with zero dependencies — and migrated to the
instance-based pattern the moment it needed something injected (see [Real market
data](#real-market-data)). That migration is a good illustration of exactly when you'd need to
switch styles: the static overload works fine right up until a tool needs a real collaborator (a
database client, an HTTP client, anything Spring manages), at which point it has to become a
constructor-injected bean instead.

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

### RAG (Retrieval-Augmented Generation) — *historical*

This app used to chunk an uploaded PDF, embed each chunk, store the vectors, and at query time
embed the *question* and fetch the nearest chunks into the prompt. That pipeline was removed
(2026-10-16): the structured holdings tools answer every portfolio question exactly, and the
retrieval path was the least-used tool. RAG may return for a shared knowledge corpus (for example
investing principles); if so, both the writing and reading sides must use the same embedding model,
or the vector distances are meaningless.

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
|        tools: getStockData, getPortfolioHoldings,      |
|               getPortfolioAllocation, loadMemory      |
|                                                       |
| PortfolioController -> PortfolioService               |
|   (PdfTextReader -> HoldingsExtractionService         |
|    -> PortfolioStatementStore)                        |
|                                                       |
| AgentAuditRecorder + ToolUsageRecorder (ADK global    |
|   plugins, registered on the App in RunnerConfig)     |
+-------------------------------------------------------+
                            |
          +-----------------+------------------+
          v                                     v                          v
   Firestore                           BigQuery: wealth_agent      Financial Modeling Prep
   users, portfolio_holdings,          .agent_audit_log            (getStockData's real
   portfolio_documents,                .tool_invocations            price/fundamentals —
   market_data_cache, adk-session/*    .tool_registry                the one non-ADC
   (auth, exact holdings, upload       (1 row/turn, 1 row/tool call) credential, FMP_API_KEY)
    metadata, 24h stock cache,
    ADK sessions)
                            |
                            v
                 Gemini 2.5 Flash
                   (Vertex AI, via ADC)
```

*(`portfolio_analysis` and `stock_recommendation` are the two `SequentialAgent` stages; only
`portfolio_analysis` has tools — `stock_recommendation` has none, it just reads
`{portfolio_analysis}`'s output. See [Agentic AI / ADK concepts](#agentic-ai--adk-concepts) above
for what each box/arrow means. `AgentAuditRecorder`/`ToolUsageRecorder` sit outside the request
path proper — they're ADK global plugins (model-call and tool-call callbacks ADK fires for every
agent, registered once on the `App` in `RunnerConfig`), not something
`AgentChatService` calls directly except once, at the very end of a turn, to flush each
recorder's accumulated rows. See [BigQuery audit log](#bigquery-audit-log). Financial Modeling
Prep is the one external, non-GCP call this app makes — see [Real market
data](#real-market-data) for why it's fronted by a Firestore cache instead of being called
per-message.)*

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

### 2. Document upload (statement to holdings)

Upload is optional (chat works without it — the holdings and allocation tools just report
`found: false`). A user can upload any number of **portfolio statements**, typically one per
brokerage account; anything else (a resume, a bank statement) is rejected. See [Portfolio
holdings](#portfolio-holdings) for the design.

1. Authenticated `POST /api/portfolio/upload` (multipart, `file` only) hits
   `PortfolioController`, once per file — the frontend loops over however many files were selected.
2. `PortfolioService.ingest` runs three small steps, each in its own class:
   - `PdfTextReader` reads the PDF's text. A file that can't be read as a PDF, or has no text (a
     scanned image), is rejected with **HTTP 422**.
   - `HoldingsExtractionService` asks Gemini for the holdings (structured output: ticker, shares,
     cost basis, account) as typed `Holding` records. A model failure is a server error; **no
     holdings found means it is not a portfolio statement: HTTP 422, and nothing is stored.** This
     is how "statements only" is enforced — by outcome, since a client-supplied type label proves
     nothing.
   - `PortfolioStatementStore` saves the holdings to `portfolio_holdings` and one
     `portfolio_documents` record in a single atomic batch (the record goes last, so a statement
     only appears once all its holdings are stored). Statements are listed and deleted as a unit —
     see `GET`/`DELETE /api/portfolio/documents`.

### 3. Chat (the two-agent pipeline)

1. Authenticated `POST /api/chat` hits `ChatController`, which calls
   `AgentChatService.chat(authenticatedUsername, message)` — note the userId comes from the
   *authenticated principal*, never from the request body.
2. `AgentChatService` resolves the conversation: a new one (UUID session, titled from the first
   message) or one of the user's existing sessions (anyone else's id is a 404), then calls
   `runner.runAsync(...)`. See [Sessions and memory](#sessions-and-memory).
3. **Stage 1 — `portfolio_analysis`**: the model reads the conversation, and can call:
   - `getPortfolioHoldings` — exact share totals per ticker across all the user's statements.
   - `getPortfolioAllocation` — each position's current value and % weight, ranked.
   - `getStockData` — real price + fundamentals (P/E, PEG, forward P/E, EPS growth, free cash
     flow per share) by ticker, from Financial Modeling Prep behind a 24h Firestore cache; see
     [Real market data](#real-market-data).
   - `loadMemory` — keyword search over the user's earlier conversations (guarded, capped).
   It writes a short analysis into session state under `portfolio_analysis`.
4. **Stage 2 — `stock_recommendation`**: its instruction has `{portfolio_analysis}` substituted
   in, so it "reads" stage 1's analysis as plain text, and writes the final, user-facing reply
   (with a risk disclaimer, per its instruction).
5. `AgentChatService` walks the turn's events backwards and returns the last non-empty text
   found — stage 2's reply, not stage 1's intermediate analysis.
6. Independently of all that, every real call *either* agent makes to Gemini during the turn is
   captured by `AgentAuditRecorder`'s `beforeModelCallback`/`afterModelCallback` (an ADK global
   plugin registered on the `App` in `RunnerConfig`, so it sees both agents) — request JSON, response JSON, latency, token counts. Once
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

### `portfolio_vectors` — *removed* (2026-10-16)

No longer written or read. Existing documents in this collection (and the Firestore vector index
created for it) are orphaned and can be deleted; nothing in the app depends on them.

### `portfolio_holdings` — exact per-account holdings (this app's own collection)

| Field       | Type      | Notes                                                          |
|-------------|-----------|-------------------------------------------------------------------|
| *(doc id)*  | auto-generated |                                                                |
| `userId`    | string    | indexed — pre-filter, same isolation pattern as `portfolio_vectors` |
| `filename`  | string    | which uploaded document this holding was extracted from            |
| `ticker`    | string    | uppercased                                                          |
| `shares`    | number    |                                                                      |
| `costBasis` | number    | nullable — per-share, if the document stated one                   |
| `account`   | string    | nullable — brokerage/account name, if the document stated one      |
| `extractedAt` | timestamp |                                                                  |

One document per holding-per-account — a user with GOOG in 3 brokerage accounts has 3 separate
docs here, summed in Java by `PortfolioHoldingsTool`, not derived from prose chunks. See
[Portfolio holdings](#portfolio-holdings).

### `portfolio_documents` — one record per upload (this app's own collection)

| Field           | Type      | Notes                                                       |
|------------------|-----------|-----------------------------------------------------------------|
| *(doc id)*        | auto-generated |                                                              |
| `userId`           | string    | indexed                                                          |
| `filename`          | string    |                                                                   |
| `holdingsCount`         | number    | holdings extracted from this statement                           |
| `uploadedAt`             | timestamp |                                                                   |

Exists so a user's uploads can be listed and deleted as a unit (`GET`/`DELETE
/api/portfolio/documents/...`) without deriving that list from scattered holding rows. Documents written before 2026-10-16 may
also carry `documentType` and `chunkCount`; they are ignored.

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

## Portfolio holdings

Originally a user could upload exactly one portfolio PDF. Now uploads are optional and a user can
upload any number of **portfolio statements** (Vanguard, Fidelity, Robinhood, ... — one per
account); other document kinds (a resume, a bank statement) are rejected, and each statement can
be deleted individually. See `PortfolioController`'s `GET`/`DELETE
/api/portfolio/documents/...` and the multi-file upload UI in `index.html`.

**The problem this solves — RAG chunk retrieval alone can't answer aggregation questions
reliably.** `searchPortfolio`'s vector search returns the top 3 chunks most semantically similar
to the question — fine for qualitative questions ("what are my risk notes"), but structurally
wrong for a question like "how much GOOG do I hold across all my accounts": if GOOG appears in a
Vanguard chunk, a Fidelity chunk, and a Robinhood chunk, there is no guarantee all three make it
into a fixed top-3 retrieval, and the model would then confidently sum whatever partial subset it
happened to see — a wrong number stated with full confidence, which is worse than no number at
all for a financial-advice app. Increasing K reduces the odds but doesn't fix the structural
issue: vector similarity search has no notion of "return every chunk that mentions X," only "the
K closest matches," and completeness isn't something a bigger K guarantees as accounts grow.

**The fix: extract structured facts once at upload time, aggregate them exactly at query time.**
`HoldingsExtractionService` calls Gemini once per uploaded document with a JSON `responseSchema`
(not free-form text) to pull out every holding as `{ticker, shares, costBasis, account}` —
structured fields, not prose. These are stored in `portfolio_holdings`, one document per
holding-per-account. `PortfolioHoldingsTool.getPortfolioHoldings` then answers aggregation
questions with a **real, exhaustive Firestore query** (`whereEqualTo("userId", ...)`, optionally
`whereEqualTo("ticker", ...)`) summed in plain Java — not an LLM adding up numbers it happened to
retrieve. This is the standard split for RAG-adjacent systems: chunk-and-embed for qualitative,
fuzzy-match retrieval; structured-extract-and-query for anything that needs an exact, complete
number. `searchPortfolio` didn't change and still exists for the qualitative case — this is a
second, complementary tool, not a replacement.

**Two tools, two very different reliability guarantees, and the agent's instruction says so
explicitly** (`AgentConfig`): `searchPortfolio` for narrative context, `getPortfolioHoldings` for
"how much of X do I hold" or "what are all my holdings" — the model is told directly not to use
`searchPortfolio` for the latter, since that's exactly the failure mode this was built to avoid.

**Verified live**, not just in theory: three synthetic brokerage statements (Vanguard 40 GOOG,
Fidelity 25 GOOG, Robinhood 15 GOOG, each at a different cost basis) were uploaded, and
`getPortfolioHoldings("GOOG")` returned exactly 80 shares with a weighted-average cost basis of
$106.25 — matching the hand-computed value exactly. Deleting the Robinhood document correctly
dropped the total to 65 shares and recalculated the weighted average, confirming the delete
cascade actually removes `portfolio_holdings` rows, not just the metadata record.

**Options considered and rejected:**

1. **Just retrieve more chunks (raise `MAX_RESULTS`) instead of adding structured extraction.**
   Rejected — reduces the odds of missing a relevant chunk but doesn't guarantee completeness, and
   doesn't scale: a user with 10 accounts needs all 10 represented, not "probably most of them."
2. **Ask the LLM to do the arithmetic over retrieved chunks, more carefully (better prompting).**
   Rejected — the ceiling on this approach is retrieval completeness, not prompt quality; no
   amount of prompt engineering fixes "the relevant chunk was never retrieved in the first place."
3. **Structured extraction with regex/heuristic parsing instead of an LLM call.** Considered, since
   it would avoid a second Gemini call per upload — rejected because brokerage statement formats
   vary enough between providers (Vanguard vs. Merrill vs. Fidelity vs. Robinhood) that a
   general-purpose LLM extraction call is far more robust than hand-written per-provider parsers,
   and this is a one-time cost at upload, not something that runs per chat message.
4. **Skip the `portfolio_documents` metadata collection; derive a document list from
   `portfolio_vectors`/`portfolio_holdings` directly.** Rejected — Firestore doesn't cheaply do
   "distinct filenames for this user," and deriving delete targets from scattered chunk rows is
   both slower and more error-prone than one purpose-built metadata record per upload.
5. **A user-chosen `documentType` at upload.** *(Superseded.)* This was metadata-only and
   client-controlled, so it could not enforce anything. Uploads are now statements-only and the
   check is by outcome: the holdings extraction that already runs must find at least one holding,
   otherwise the file is rejected (HTTP 422) before anything is stored. No extra LLM call was
   needed — it reuses the existing extraction.

### Portfolio allocation (value + weight, not just share count)

Share count alone can't answer risk/concentration questions — "80 shares of GOOG" means nothing
without knowing what that's *worth* and what fraction of the whole portfolio it represents. This
surfaced directly while designing an eval case for `getPortfolioHoldings`: trying to write down
"the correct answer" for "am I overexposed to GOOG" forced the realization that share count isn't
the right unit for that question at all — value and weight are, and the existing tool doesn't
compute either.

`PortfolioAllocationTool.getPortfolioAllocation` (optional `topN` param) closes this gap:

1. Groups `portfolio_holdings` by ticker — reusing `PortfolioHoldingsTool.summarize` (package-
   private, shared rather than duplicated) for the share-count/cost-basis part of each position.
2. Fetches current price **per distinct ticker** (not per holding-per-account row) via
   `MarketDataService` — in parallel via `CompletableFuture`, the same "many independent lookups,
   combine results" shape `MarketDataService` itself uses internally for its 4 FMP endpoints, for
   the same reason: sequential blocking price lookups across several tickers would sum their
   latency instead of overlapping it.
3. Computes `value = shares × price` per ticker, sums to `totalValue`, computes `weightPercent =
   value ÷ totalValue`, sorts descending.
4. A ticker whose price lookup fails is **excluded** from the ranking and from `totalValue` —
   not treated as $0, which would silently understate every other position's weight — and
   surfaced via `pricingUnavailableFor` so the agent says so rather than presenting a quietly-
   incomplete picture as complete.

**Why keep both `getPortfolioHoldings` and `getPortfolioAllocation` instead of merging them**:
they answer genuinely different questions with different cost/reliability profiles.
`getPortfolioHoldings` is a pure Firestore query — fast, cheap, and can never fail due to FMP
being down, because it never calls FMP. `getPortfolioAllocation` needs a live price for every
distinct ticker held, so it's inherently heavier and inherits FMP's availability. Routing a
plain "how many shares of X" question through the pricier tool would make it pay for a dependency
it never needed. The agent's instruction in `AgentConfig` states explicitly which tool to use for
which question shape, the same pattern already used for `searchPortfolio` vs.
`getPortfolioHoldings`.

**Verified live**, hand-checked against real prices, not just inspected: a 7-ticker portfolio
(GOOGL split across 3 accounts, plus JPM/AAPL/KO/AMZN/MSFT/NVDA) produced a `totalValue` and
per-ticker `weightPercent` that matched hand computation (`shares × price`, summed, divided)
exactly to the fraction of a cent — e.g. GOOGL's 100 shares × $327.89999 = $32,789.999,
19.944% of the $164,409.349 total, both matching the tool's own output precisely.

**A real discovery from that live test, not a bug**: FMP's free tier returns HTTP 402 for ETF/
mutual-fund tickers (`VTI`, `BND`, `FXAIX` all failed) and specifically doesn't recognize `GOOG`
(Alphabet's Class C shares) — only `GOOGL` (Class A) works. Confirmed directly against FMP's API,
not inferred. The graceful-degradation design (exclude, don't zero) handled this correctly
without any code change — but it's a real, practical limitation worth knowing: a real user's
statement that literally says "GOOG" (as many brokerages print it) or holds ETFs will get partial
`pricingUnavailableFor` results on this free tier. Noted in [Gotchas](#gotchas-discovered-the-hard-way)
rather than silently fixed — a `GOOG`→`GOOGL` alias could be added later if it's worth the special
case, but wasn't done reflexively here.

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

**Log forging (CWE-117)**: the ticker logged throughout this class is ultimately model-supplied
(indirectly, from the user's message), and an exception message can in principle echo back part
of a malformed external response — neither is trusted to be free of characters that could forge or
split a log line. Both are run through `com.wealth.advisor.guardrails.ContentSanitizer`'s
`sanitizeForLog` before hitting any `log.warn`/`log.debug` call in this class. That package is
deliberately scoped narrowly today (log-safety only) but is where future validation of user
prompts, LLM responses, or tool responses — e.g. against a rogue or compromised MCP server
returning content crafted to manipulate the agent — should land, rather than being scattered
inline across `MarketDataService`, `AgentChatService`, etc.

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

> **Data-model decision (2026-10-16):** the wide row per turn is kept as is. It is about 98 KB per turn
> (14 MB for 143 rows) because every model call stores its full request again; that is accepted at
> the current volume. The measured numbers, the rejected options, small fixes that stay inside this
> design and the conditions for revisiting it are in `docs/design/02-analytics-data-model.md`.

**Why BigQuery and not Cloud SQL or just log lines:** serverless (billed per query/storage, not
per hour like an always-on Cloud SQL instance), the free tier (10GB storage + 1TB queries/month)
comfortably covers this project's scale, and its native `JSON` column type means the raw
request/response payloads stay queryable (`JSON_VALUE`, `JSON_QUERY`, ...) instead of being an
opaque blob.

**How a turn is captured** (`AgentAuditRecorder` implements ADK's `Plugin` interface and is
registered once on the `App` in `RunnerConfig` via `.plugins(agentAuditRecorder, toolUsageRecorder)` —
no per-agent wiring, so a new agent is audited automatically). Gotcha: with a `Runner` built from an
`App`, plugins must be registered on the **`App`** — `Runner.Builder.plugins(...)` throws once an app
is set:

- `beforeModelCallback` fires immediately before every actual Gemini call, for *any* agent;
  `afterModelCallback` immediately after. A single user turn is correlated across both agents (and across however many
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
| `tool_name`           | string    | e.g. `getStockData`, `searchPortfolio`                               |
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

**How a call gets captured:** `ToolUsageRecorder` is also an ADK global `Plugin`, implementing
`beforeToolCallback`/`afterToolCallback`/`onToolErrorCallback` — the same mechanism
`AgentAuditRecorder` uses for model calls, one level down (around the actual tool invocation rather
than the Gemini call that requested it). `RunnerConfigTest` pins that both plugins are registered:
if they were not, the app would still chat normally but silently stop writing audit rows. `ToolContext` (passed to all three) already carries a
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

## Evals

"Evals" here means: a small, versioned, automatically-runnable suite (`PortfolioEvalTest`) that
answers "did the agent pipeline do the right thing" — a different question from unit/integration
tests, which answer "does the code run without throwing." ADK's own eval support (Python-only,
minimal) isn't usable from Java, so this is hand-built on top of the existing test stack rather
than a framework feature.

**Three layers of assertion, cheapest/most-deterministic first:**

1. **Trajectory** — exact-match against `tool_invocations`: was the right tool called at all (e.g.
   `getPortfolioAllocation` for a concentration question, not just `getStockData`)? No LLM judge
   needed — either the row is there or it isn't.
2. **Hand-off** — exact-match/substring against `agent_audit_log.input_to_agent2`: did
   `portfolio_analysis`'s summary actually mention the thing the question was about, before
   `stock_recommendation` ever saw it?
3. **Response quality** — the one layer that's inherently fuzzy (natural-language reasoning, not a
   fixed value), graded by `JudgeService`: a second Gemini call, given the response plus explicit
   *ground-truth facts* (never left for the judge to infer — e.g. "JPM is ~43% of portfolio value,
   the largest position"), returns a structured `{answer: boolean, reasoning: string}` verdict.
   Judge prompts are written as narrow yes/no questions ("does this response cite the JPM position
   as a specific reason for caution"), not "is this a good response" — narrow questions are both
   more reliable and, when they fail, tell you *what* was wrong via `reasoning`. Hard exact-match
   negatives are preferred over judge calls wherever the fact is checkable without one (see Case 5
   below) — a judge call is a second LLM in the loop, so it's used only where a fixed check
   genuinely can't substitute.

**The 5 cases** (fixture: `src/test/resources/eval-fixtures/`, 3 synthetic brokerage PDFs —
Vanguard/Fidelity/Merrill — encoding a portfolio where JPM is ~43% of value, the single largest
position, and GOOGL+AAPL+MSFT+NVDA together are a comparable second concentration; see that
directory's `README.md` for the full ground truth and which tickers are safe against FMP's free
tier):

1. **`getPortfolioHoldings_noTicker_doesNotCrash`** — regression guard for the ADK
   `@Schema(optional)` crash (Gotcha #5): "what are all my holdings" must call the tool with no
   ticker and succeed, not error.
2. **`concentrationWarning_jpm`** — "should I buy more JPM" must cite the user's *existing* JPM
   concentration as the reason for caution, not a generic disclaimer.
3. **`diversificationOpportunity_jnj`** — paired with Case 2 deliberately: "should I buy JNJ" must
   be framed as improving diversification, not warned against. An agent that reflexively hedges on
   everything would pass Case 2 alone; passing both proves the reasoning tracks the real data.
4. **`portfolioRiskAnalysis`** — open-ended risk analysis. The hallucination check (inverted judge
   verdict — must come back `false`) is the most important assertion in this suite: the response
   must not invent exposure (healthcare/bonds/international) that doesn't exist in the fixture.
5. **`trimAddSuggestion`** — "what should I trim/add" must name JPM as a trim candidate and suggest
   an underrepresented area, and must **never** recommend adding to JPM or the tech names — this
   hard negative is exact-match/judge-as-negative rather than a positive judge call, since a
   response that contradicts its own prior analysis is unambiguous and doesn't need interpretation.

**System-level, not component-level, deliberately.** Every case goes through real HTTP
(`TestRestTemplate`), a real Gemini call, and real ADK tool-argument binding — not a direct Java
call to a tool class. This isn't just "more realistic": the `@Schema(optional)` crash Case 1 guards
against lived inside ADK's own argument-binding layer, which a direct-call test would never
exercise (Java doesn't care that a parameter is null; ADK's model-facing binding does). Only a test
that goes through the real model → real function-call decision → real ADK binding could catch it,
and did, live, before this suite existed. `JudgeService` itself is tested separately and at the
component level (`JudgeServiceTest`) — nothing about grading a fixed pair of strings needs the real
chat pipeline.

**Bugs this suite found on its first live runs, before a single line of it was trusted:**

- `portfolio_analysis` could answer "should I buy more JPM" by asking the user whether they'd like
  it to fetch data, instead of just fetching it — leaving `stock_recommendation` with nothing to
  reason about and no error anywhere to signal it. Fixed by making the instruction explicit: never
  ask permission to look something up, and any buy/trim/evaluate question always requires calling
  both `getPortfolioAllocation` and `getStockData` before summarizing, regardless of whether the
  user mentioned "portfolio" at all.
- Risk-analysis responses reported JPM's concentration but treated a same-sized group of
  same-sector holdings as unrelated numbers, since `getPortfolioAllocation` has no sector field to
  hand the model — fixed by telling the agent explicitly to do that grouping itself from its own
  knowledge, not just report the single largest position.
- The judge rubric for the sector-grouping check originally hard-coded this project's own fixture
  taxonomy (excluding AMZN from "tech"). A response that reasonably classified AMZN as tech too
  (a genuinely ambiguous, defensible call — AMZN is commonly grouped with "big tech") failed a
  taxonomy-matching test that had nothing to do with the actual reasoning quality being checked.
  Loosened to ask whether *a* comparably-sized second concentration was identified and named at
  all, not whether it used this project's exact ticker grouping — a reminder that an eval judge
  question should test the behavior that matters, not incidentally re-test an arbitrary authoring
  choice baked into the fixture.

**Run with:** `./mvnw -Dtest=PortfolioEvalTest test` (tagged `eval`, not part of the default `mvn
test` run — these make real, non-free Gemini/FMP calls and take ~100s for the full suite, not
milliseconds). Verified stable across two independent full live runs (5/5 both times) — LLM output
is not literally deterministic, so a single green run isn't itself proof a fix holds.

## Sessions and memory

A user can have many conversations. Three ADK concepts are in play, and they are easy to blur:

| Layer | What it is | Class | Scope |
|---|---|---|---|
| Short-term | One conversation's events + state | `FirestoreSessionService` / `Session` | one `(app, userId, sessionId)` |
| Long-term | Keyword search over a user's past events | `FirestoreMemoryService` (`BaseMemoryService`) | all of one user's sessions |
| Agent access | The model decides to search | `LoadMemoryTool` | called by `portfolio_analysis` |

**Multi-session.** `POST /api/chat` takes an optional `sessionId`; omitted means "start a new
conversation" and the server mints a UUID (title = first message) and returns it. Ownership is
ADK's own `(app, userId, sessionId)` key: another user's id resolves to nothing, surfaced as a 404
(`ChatSessionNotFoundException`) rather than get-or-create. One turn at a time per session — a
second concurrent message gets a 409 (`ChatSessionBusyException`). Sessions created before this
change used `sessionId == userId`; they still resolve and appear in the list as "Conversation".

**Audit correlation.** `AgentAuditRecorder` / `ToolUsageRecorder` index in-flight turns by
`TurnKey(userId, sessionId)`, not `userId`. Keyed by user alone, two tabs would overwrite each
other's invocation id (`AgentAuditRecorderTest` pins this).

**What `FirestoreMemoryService` really does** (read from the 1.7.0 sources, not the docs):
- `addSessionToMemory` is a **no-op**. `FirestoreSessionService` already writes a `keywords` array
  on every appended event, so memory is just a query over those events — no ingest step.
- `searchMemory` runs a collection-group query on `user-event` filtered by `appName`, `userId`,
  and `keywords array-contains-any <query words minus stop words>`. Keyword match only — no
  embeddings, no ranking, **no result limit**, and it includes the current session and the agent's
  own replies. A broad query can return a lot of text into the model's context.
- It needs a Firestore collection-group index on `user-event` (`appName`, `userId`, `keywords`).
  The first query fails with an error containing a console link to create it.

**`GuardedMemoryService`** wraps the real service because two things were found running it live:
a backend error (e.g. the missing index above) surfaced through `LoadMemoryTool` as a tool
exception, which failed the whole turn with a 500; and results are unbounded. The wrapper turns an
error into "no memories" (logged at WARN) and keeps only the 8 most recent entries at 500 chars
each. Recency is a stand-in ranking, since the backend gives no relevance score.

**`FirestoreSessionService` 1.7.0 quirks** (read from source, confirmed live):
- `listSessions` returns every session with *empty* state and events — titles need a `getSession`.
- A user's own events are stored with `author == userId`, not the literal `"user"`.
- State scoping looks for `_user_` / `_app_` key prefixes while ADK core uses `user:` / `app:`, and
  nothing reads the `user-state` collection back into a new session — so cross-session
  preferences via `user:` state do **not** work with this session service.

**Not in the Java SDK (1.7.0):** Vertex AI Memory Bank (LLM-extracted, consolidated memories) and
a `PreloadMemoryTool`. Only `VertexAiSessionService` exists. Extracting durable facts would be our
own code.

## Logging and traceability

Every log line carries `[requestId userId sessionId]` (Spring Boot's `logging.pattern.correlation`,
set in `application.yml`). The values come from SLF4J's MDC, which is per thread, and a chat turn hops
threads (request thread, then ADK's gRPC executor threads, then virtual threads for the allocation
tool's parallel price lookups). So the context is opened where the work happens and **restored on
exit** by `logging/LogContext`: the request interceptor sets request id, user and Cloud Trace id,
`AgentChatService` adds the session for the turn, and each tool opens its own scope from its
`ToolContext`. It is deliberately **not** set from ADK plugin callbacks: a value left behind on a
pooled thread would attribute one user's activity to another.

**On Cloud Run, logs are JSON.** With the `cloud` profile (`SPRING_PROFILES_ACTIVE=cloud`) each line is
one JSON object from `logging/GoogleCloudLogFormatter` — `severity`, `timestamp`, `message`, `logger`,
`thread`, plus `userId`, `sessionId`, `requestId` (and `invocationId`, `agent` inside tools) as fields,
and `logging.googleapis.com/trace` so Cloud Logging groups the app's lines under the platform's request
log. A custom formatter is needed because Spring Boot's built-in JSON formats name the level `level` /
`log.level`, but Cloud Logging only reads `severity`. Useful Logs Explorer queries:

```
resource.type="cloud_run_revision" AND jsonPayload.userId="demo"
jsonPayload.sessionId="<session id>"
jsonPayload.requestId="<request id>"
severity>=WARNING AND jsonPayload.userId="demo"
jsonPayload.invocationId="<turn id>"        # tool lines; the same id is agent_audit_log.user_prompt_id
```

ADK-internal and Firestore library lines cannot carry the context. The rationale, the rejected
options and the queries are in `docs/design/01-log-traceability.md`.

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

## GCP deployment (Cloud Run)

Deployed live on 2026-07-30 (milestone 11 is now ✅). Originally written as a forward-looking
runbook before execution; now updated with what actually happened, the same way every other
section in this doc was written after the fact. The steps below matched reality except for one gap
— step 3 (build-time IAM) wasn't anticipated by the original design and was found live, the same
way the [Gotchas](#gotchas-discovered-the-hard-way) list generally gets built.

**Why Cloud Run Service** (not Jobs, not GKE): this app is a plain request/response HTTP service
with no background/batch work, so Jobs doesn't fit. GKE would mean managing a cluster for a single
service — pure overhead here. Cloud Run Service scales to zero, needs no cluster ops, and — the
detail that actually matters for this app specifically — its metadata-server ADC is exactly what
`GenAiClientConfig`/`FirestoreConfig`/the BigQuery config bean already expect with zero code
changes; see FAQ.md's ["Where's our ADC in this project?"](FAQ.md#wheres-our-adc-in-this-project-how-does-it-differ-between-local-and-cloud-run)
for the local-vs-Cloud-Run ADC comparison this whole deployment rides on.

### 1. One-time GCP project setup

Assuming deployment targets the same project already used for local dev (`agentic-ai-503223`) —
Firestore (Native mode) and the vector composite index (Gotcha #1 below) already exist there from
local development, so nothing to do for those. For a *different* project, do both first.

Enable the APIs a fresh project wouldn't have on by default:

```bash
gcloud services enable run.googleapis.com aiplatform.googleapis.com \
  firestore.googleapis.com bigquery.googleapis.com \
  cloudbuild.googleapis.com artifactregistry.googleapis.com \
  secretmanager.googleapis.com --project=<PROJECT_ID>
```

BigQuery's `agent_audit_log`/`tool_invocations`/`tool_registry` dataset and tables are **not**
created manually — `AuditSchemaInitializer`/`ToolUsageSchemaInitializer` create them on first
successful app startup, same as local dev. The runtime service account just needs the IAM to do
that (next section) — don't pre-create the dataset by hand, it'll just be redundant with what the
app already does idempotently.

### 2. Runtime service account & IAM roles

Cloud Run's attached service account is what ADC resolves to in production (no key file — see the
FAQ.md table linked above). Create one dedicated to this app rather than using the project's
default compute service account:

```bash
gcloud iam service-accounts create wealth-advisor-run \
  --display-name="Wealth Advisor Cloud Run runtime identity" --project=<PROJECT_ID>
```

| Role | Why |
|---|---|
| `roles/datastore.user` | Firestore reads/writes — `users`, `portfolio_vectors`, `portfolio_holdings`, `portfolio_documents`, `market_data_cache`, and ADK's own `adk-session` collection. |
| `roles/aiplatform.user` | Gemini calls via Vertex AI (`GOOGLE_GENAI_USE_VERTEXAI=true`) — both agents' LLM calls and `HoldingsExtractionService`/`JudgeService`'s structured-output calls. |
| `roles/bigquery.dataEditor` **at the project level** (not scoped to one dataset — the dataset doesn't exist yet on first deploy) | Insert/query rows, and — per Google's IAM docs, this role grants dataset creation specifically when bound at project scope — lets `AuditSchemaInitializer` create `agent_audit_log`'s dataset on first startup. |
| `roles/bigquery.jobUser` | Separate from `dataEditor` — needed to actually run query jobs (`PortfolioEvalTest`-style polling queries, BigQuery client `bigQuery.query(...)` calls), which `dataEditor` alone doesn't grant. |
| `roles/secretmanager.secretAccessor` | Only if `FMP_API_KEY` is stored in Secret Manager (recommended below) rather than a plain `--set-env-vars` value. |

If a permission error shows up on first deploy that isn't obviously one of the above, `roles/bigquery.admin` is the blunt fallback for BigQuery specifically (over-permissioned but fine to unblock a first deploy of a learning project, then tighten back down) — not a substitute for figuring out which specific permission was actually missing.

```bash
for role in roles/datastore.user roles/aiplatform.user roles/bigquery.dataEditor roles/bigquery.jobUser roles/secretmanager.secretAccessor; do
  gcloud projects add-iam-policy-binding <PROJECT_ID> \
    --member="serviceAccount:wealth-advisor-run@<PROJECT_ID>.iam.gserviceaccount.com" \
    --role="$role"
done
```

### 3. Build-time (Cloud Build) service account permissions

**Concept:** `gcloud run deploy --source .` doesn't build the container itself — it hands the
source to Cloud Build, which runs as a *separate* identity from the runtime service account
configured in step 2. On projects created after mid-2024, GCP no longer auto-grants `Editor` to the
default Compute Engine service account (`<PROJECT_NUMBER>-compute@developer.gserviceaccount.com`),
which is what `gcloud run deploy --source .` uses for the build unless told otherwise. So step 2's
runtime IAM being correct says nothing about whether the *build* can even run — this is a second,
independent IAM surface the deploy command doesn't surface, and the errors it throws don't
obviously point at IAM as the cause. Found live, in this order:

1. `gcloud run deploy` fails with a generic "Build failed; check build logs for details", and even
   `gcloud builds log <BUILD_ID>` itself fails with "No logs were found... service account ... does
   not have permission to write the logs." Root cause: the build SA lacked `roles/logging.logWriter`.
2. After granting that, the real build error surfaces: buildpacks' `ANALYZING` phase fails with
   `DENIED: Permission 'artifactregistry.repositories.downloadArtifacts' denied` against the
   auto-created `cloud-run-source-deploy` Artifact Registry repo. Root cause: the build SA lacked
   `roles/artifactregistry.writer` (needed for both the pull-for-cache-check on `ANALYZING` and the
   final image push).

**Action** — grant both to the default compute service account before the first deploy:

```bash
gcloud projects add-iam-policy-binding <PROJECT_ID> \
  --member="serviceAccount:<PROJECT_NUMBER>-compute@developer.gserviceaccount.com" \
  --role="roles/logging.logWriter"

gcloud projects add-iam-policy-binding <PROJECT_ID> \
  --member="serviceAccount:<PROJECT_NUMBER>-compute@developer.gserviceaccount.com" \
  --role="roles/artifactregistry.writer"
```

`roles/storage.objectViewer` (to read the uploaded source tarball from Cloud Build's GCS staging
bucket) was also present on this project's build SA — not traced back to a specific failure here,
but standard for source-based builds and worth granting explicitly rather than assuming it's there.

One-time setup per project, same as step 2's runtime roles — not something `deploy.sh` or the app
does at runtime, and easy to miss because the deploy command gives no hint this identity even exists
separately from the one configured with `--service-account`.

### 4. `FMP_API_KEY` via Secret Manager

Per [Environment variables](#environment-variables), this is the one non-ADC credential in the
app — worth keeping out of plain `--set-env-vars` (which shows up in `gcloud run services
describe` output and revision metadata) the way the GCP-native credentials never have to be:

```bash
printf '%s' "$FMP_API_KEY" | gcloud secrets create fmp-api-key --data-file=- --project=<PROJECT_ID>
```

(the IAM binding for `secretmanager.secretAccessor` above already covers the runtime service
account reading it; `--set-secrets` in the deploy command below wires it to the `FMP_API_KEY` env
var the app already reads.)

### 4. Code prerequisite not yet in place

`application.properties` has no `server.port` entry, so the app always listens on **8080**
regardless of environment. Locally that's fine — it's also Spring Boot's own default. Cloud Run
injects a `PORT` env var (usually 8080, but not guaranteed) and healthchecks whatever port that
is, so before the first deploy, add:

```properties
server.port=${PORT:8080}
```

Harmless for local dev (`PORT` is never set there, so it falls back to the same 8080 as today) —
purely additive, not yet done since nothing has required it until now.

### 5. Deploy

No `Dockerfile` — `--source .` uses Google Cloud Buildpacks (via Cloud Build), which auto-detects
this is a Maven project from `pom.xml` and builds a runnable container without one, the same
"don't add tooling this project doesn't need yet" call already made for the UI (Bootstrap via CDN,
no bundler).

```bash
gcloud run deploy wealth-advisor \
  --source . \
  --project=<PROJECT_ID> \
  --region=us-central1 \
  --service-account=wealth-advisor-run@<PROJECT_ID>.iam.gserviceaccount.com \
  --allow-unauthenticated \
  --set-env-vars=GOOGLE_GENAI_USE_VERTEXAI=true,GOOGLE_CLOUD_PROJECT=<PROJECT_ID>,GOOGLE_CLOUD_LOCATION=us-central1,GCP_PROJECT_ID=<PROJECT_ID>,SPRING_PROFILES_ACTIVE=cloud \
  --set-secrets=FMP_API_KEY=fmp-api-key:latest \
  --memory=1Gi \
  --cpu=1
```

**`--allow-unauthenticated` is not optional here, and is a different layer than this app's own
login.** Cloud Run has its own IAM-based access control that sits in front of the container
entirely — without this flag, Cloud Run rejects anonymous requests before they ever reach Spring
Security, so nobody could even load `/login.html`. This app's real authentication is
`SecurityConfig`'s form login (see [End-to-end request flows](#end-to-end-request-flows)) — Cloud
Run's own layer should stay open so that logic is the only gate a user actually hits.

**`SPRING_PROFILES_ACTIVE=cloud`** turns on the Cloud Logging JSON log format (`application-cloud.yml`,
see [Logging and traceability](#logging-and-traceability)). Without it the service still works, but
logs are plain text, so they cannot be filtered by user or session and `severity` is not set. It
applies from the next deploy; the already-deployed revision keeps the text format.

`us-central1` matches `GOOGLE_CLOUD_LOCATION` deliberately — keeps Cloud Run and the Vertex AI
calls it makes in the same region, not required but avoids unnecessary cross-region latency.

Consider also setting `DEMO_USER_USERNAME`/`DEMO_USER_PASSWORD` (via `--set-env-vars` or another
secret) rather than leaving the seeded account at its `demo`/`changeme` default once this is
reachable on the public internet — self-service signup exists as the real path in, but the seeded
account still exists too.

### 6. Post-deploy verification

Don't consider this done until each of these has actually been checked, not assumed:

1. Visiting the Cloud Run URL redirects to `/login.html` (unauthenticated root is correctly gated).
2. Log in (demo user or a fresh signup), confirm `/index.html` loads with the new Bootstrap UI and
   its `/css/**`/`/js/**` assets actually load (these are the paths added to `SecurityConfig`'s
   `permitAll` — worth specifically confirming they don't 302-redirect instead of serving).
3. Upload a real PDF, confirm it shows up via `GET /api/portfolio/documents`.
4. Send a chat message, confirm a real reply (not a generic error).
5. Query BigQuery for a row in `agent_audit_log`/`tool_invocations` from that turn — confirms the
   `bigquery.dataEditor`/`bigquery.jobUser` IAM actually worked end to end, not just that the app
   started without throwing.
6. Check the Firestore console for the new documents/vectors/holdings — confirms
   `datastore.user` end to end the same way.

### 7. Redeploys

Re-running the same `gcloud run deploy --source .` command creates a new revision and Cloud Run
shifts 100% of traffic to it automatically (no manual traffic-split step needed for a project at
this stage).

### Cost/scaling notes (a tradeoff to decide at deploy time, not a recommendation)

Default `min-instances=0` scales to zero between requests — cheapest, but the first request after
idle pays a cold start (JVM start + Spring context init + ADK/Firestore/BigQuery client setup,
likely several seconds, not measured yet). `min-instances=1` removes that at the cost of one
always-on instance. Fine to start at the scale-to-zero default and revisit only if cold starts turn
out to matter for how this gets used.

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

5. **ADK tool parameters are required by default — `@Schema(optional = true)` must be set
   explicitly**, or ADK rejects the model's call in its own argument-binding layer *before your
   tool method ever runs*, surfacing as a generic "internal error" with no other detail. See
   `PortfolioHoldingsTool`'s javadoc for the full story — found live when "analyze all my
   holdings" (no specific ticker) reliably crashed until this was set.

6. **FMP's free tier returns HTTP 402 for ETF/mutual-fund tickers, and doesn't recognize `GOOG`
   specifically** — only `GOOGL` (Alphabet's Class A shares). Confirmed directly against the API:
   `VTI`/`BND`/`FXAIX` all 402, `GOOGL` 200, `GOOG` 402. A real brokerage statement that prints
   "GOOG" (common) or holds ETFs will get partial `pricingUnavailableFor` results from
   `getPortfolioAllocation` on this plan — see the "Portfolio allocation" subsection under
   [Portfolio holdings](#portfolio-holdings) for how that's handled (excluded, not silently
   zeroed) and why no `GOOG`→`GOOGL` alias was added reflexively.

7. **`QueryParameterValue.timestamp(String)` does not accept `Instant.toString()`.** Java's
   ISO-8601 (`2026-07-29T10:06:50.492570Z` — `T` separator, 6-digit microseconds) fails BigQuery's
   client-side format check, which expects BigQuery's own timestamp literal format instead. Use the
   `timestamp(Long epochMicros)` overload (`instant.getEpochSecond() * 1_000_000L +
   instant.getNano() / 1_000L`) and skip string formatting entirely — found live via
   `PortfolioEvalTest`'s BigQuery polling helper.

8. **An agent can "solve" a missing-tool-call problem by asking the user for permission instead of
   just calling the tool** — a plausible-looking, grammatically fine response that leaves the next
   agent in a `SequentialAgent` pipeline with nothing real to act on, and no exception anywhere to
   signal it. Only visible by inspecting the actual `agent_audit_log`/`tool_invocations` rows for a
   turn, not from the reply text alone. See [Evals](#evals) for the fix (an explicit
   never-ask-just-fetch instruction) and how `PortfolioEvalTest` catches it via a trajectory
   assertion.

## Milestone status

1. ✅ Single agent + one tool + `/api/chat`, in-memory sessions.
2. ✅ Spring Security login backed by Firestore, per-user session isolation.
3. ✅ Two-agent `SequentialAgent` pipeline (`portfolio_analysis` → `stock_recommendation`).
4. ✅ PDF upload → RAG (chunk/embed/store/search) + Firestore-backed ADK sessions. (Later
   extended to optional, multi-file, multi-type uploads — see milestone 8.)
5. ✅ Self-service signup (`signup.html` → `POST /api/auth/register` → `UserRegistrationService`),
   so accounts aren't limited to the one seeded demo user.
6. ✅ BigQuery audit trail of every Gemini call and every tool call (`AgentAuditRecorder` +
   `ToolUsageRecorder`, see [BigQuery audit log](#bigquery-audit-log) and its "Tool usage
   tracking" subsection) — covers the "see what the agents actually did, and which tools are
   actually earning their keep" need that full OpenTelemetry tracing was originally on hold for;
   a proper OTel/tracing setup is still undone and would be complementary (spans/traces vs. these
   tables' one-row-per-turn / one-row-per-tool-call records), not a replacement.
7. ✅ Real market data (`getStockData`/`MarketDataService`, see [Real market
   data](#real-market-data)) — replaced the hardcoded mock price map with real price/P/E/PEG/
   forward-P/E/EPS-growth/free-cash-flow from Financial Modeling Prep's free tier, fronted by a
   24h Firestore cache shared across every user. The one non-ADC credential in this app
   (`FMP_API_KEY`); log-forging guardrails (`com.wealth.advisor.guardrails.ContentSanitizer`)
   added alongside it.
8. ✅ Optional, multi-file portfolio-statement uploads + exact holdings aggregation (see
   [Portfolio holdings](#portfolio-holdings)) — upload is no longer required or limited to one
   file; any number of brokerage statements can be uploaded and individually deleted (resumes and
   bank statements are rejected). `HoldingsExtractionService` + `portfolio_holdings` +
   `PortfolioHoldingsTool.getPortfolioHoldings` solve exact cross-account aggregation (e.g. total
   GOOG shares across 3 brokerages) in a way chunk-based RAG retrieval structurally can't
   guarantee. Per-user isolation is unchanged — every new collection/query still scopes by
   `userId` the same way `portfolio_vectors` always has.
9. ✅ Portfolio allocation by value/weight (`PortfolioAllocationTool.getPortfolioAllocation`, see
   the "Portfolio allocation" subsection under [Portfolio holdings](#portfolio-holdings)) —
   share-count aggregation alone couldn't answer concentration/risk questions; this layers live
   price × shares = value, weight% of total, and ranking on top, reusing
   `PortfolioHoldingsTool`'s share-summing logic rather than duplicating it. Verified live and
   hand-checked exact against real prices; also surfaced a real FMP free-tier limitation (ETFs and
   `GOOG` specifically return HTTP 402) now documented in Gotchas.
10. ✅ Eval suite (`PortfolioEvalTest`, see [Evals](#evals)) — 5 system-level cases covering the
    ADK optional-schema crash regression, concentration/diversification reasoning (paired to rule
    out reflexive hedging), open-ended risk analysis with a hallucination check, and a hard-negative
    trim/add check. Found and fixed two real agent-behavior gaps (asking permission instead of
    calling a tool; not grouping same-sector positions) before this suite existed to catch them.
    Verified stable across two independent live runs.
11. ⏳ Cloud Run deployment — not started. Runbook written in [GCP deployment (Cloud
    Run)](#gcp-deployment-cloud-run) ahead of actually doing it, including one still-outstanding
    code prerequisite (`server.port`) called out there.
