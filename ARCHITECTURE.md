# Wealth Advisor — Architecture & Concept Guide

This is a learning project: a small, "production-shaped" financial advisor built with **Google's
Agent Development Kit (ADK) for Java**, Spring Boot, Spring Security, and Firestore. The goal is
to learn agentic AI practically — this doc is the map for reading the code, written assuming you
know roughly what RAG and LLM tool-calling are, but haven't used ADK specifically before.

If you only read one section, read [Agentic AI / ADK concepts](#agentic-ai--adk-concepts) first —
everything else builds on it.

## Table of contents

- [What this app does](#what-this-app-does)
- [Agentic AI / ADK concepts](#agentic-ai--adk-concepts)
- [Architecture diagram](#architecture-diagram)
- [End-to-end request flows](#end-to-end-request-flows)
- [Firestore schema](#firestore-schema)
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
+-------------------------------------------------------+
                            |                            
                            v                            

             v                              v                              v
Firestore: users               Firestore:                     Firestore: adk-session/*
(auth + profile)               portfolio_vectors              (ADK conversation history,
                               (RAG chunks + embeddings)      via FirestoreSessionService)

                            |                            
                            v                            
          Gemini 2.5 Flash + text-embedding-004          
                   (Vertex AI, via ADC)                  
```

*(`portfolio_analysis` and `stock_recommendation` are the two `SequentialAgent` stages; only
`portfolio_analysis` has tools — `stock_recommendation` has none, it just reads
`{portfolio_analysis}`'s output. See [Agentic AI / ADK concepts](#agentic-ai--adk-concepts) above
for what each box/arrow means.)*

## End-to-end request flows

### 1. Login

1. Browser POSTs `username`/`password` to `/api/auth/login` (a plain HTML form in `login.html`,
   handled entirely by Spring Security's form login filter — there's no custom controller for
   this endpoint).
2. `FirestoreUserDetailsService.loadUserByUsername` looks up `users/{username}` in Firestore
   directly by document id (not a query) and compares the bcrypt `passwordHash`.
3. On success, Spring Security issues an `HttpOnly`, `SameSite=Lax` `JSESSIONID` cookie and
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

## Environment variables

| Variable                  | Purpose                                                              |
|----------------------------|-----------------------------------------------------------------------|
| `GOOGLE_GENAI_USE_VERTEXAI`| `true` — routes Gemini calls through Vertex AI + ADC instead of an API key |
| `GOOGLE_CLOUD_PROJECT`     | GCP project for Vertex AI calls                                       |
| `GOOGLE_CLOUD_LOCATION`    | Vertex AI region, e.g. `us-central1`                                  |
| `GCP_PROJECT_ID`           | GCP project for the Firestore client (`gcp.project-id` property)      |
| `DEMO_USER_USERNAME`/`DEMO_USER_PASSWORD` | overrides the seeded demo login (defaults `demo`/`changeme`) |
| `FIRESTORE_EMULATOR_HOST`  | if set, Firestore client auto-detects it and uses the local emulator instead of real GCP (no code involved — the client library checks this env var itself) |

No `GOOGLE_API_KEY`/`GEMINI_API_KEY` is used — this project deliberately uses ADC via Vertex AI
instead (one credential covers both Gemini and Firestore, and it's what Cloud Run will use
automatically later via its attached service account).

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
5. ⏳ OpenTelemetry observability — not started (see chat history / project memory for the plan).
6. ⏳ Cloud Run deployment — not started.
