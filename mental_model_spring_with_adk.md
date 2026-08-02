# Mental Model: Spring Boot + Google ADK

Purpose: one place to build interview-solid Spring fundamentals *and* see exactly how they apply
to a real agentic system — this app, not a hypothetical. Every code reference below points at a
real file in this repo. Where a Spring concept isn't actually used here (e.g., prototype scope,
Spring Data `@Repository`), that's stated explicitly, with the real reason, rather than invented.

This is a living document — tell me which section to go deeper on and we'll walk the real code
together.

---

## Part 1 — Spring Core, the way an interviewer actually probes it

### 1.1 IoC and the `ApplicationContext`

**The concept:** without a framework, your code decides when and how to construct its
dependencies (`new FooService(new BarRepository())`, wired by hand, everywhere it's needed). IoC
inverts that: you declare *what* you need (a constructor parameter, a field), and a container —
Spring's `ApplicationContext` — decides *when* and *how* to build it, once, and hands it to you.
The container reads metadata (`@Component`, `@Service`, `@Configuration`, `@Bean`), builds a
dependency graph, resolves construction order, and instantiates everything before your app is
ready to serve a request.

**Real example — the whole dependency graph for one chat turn**, all wired without a single `new`
in application code:

```
AgentChatService(SequentialAgent, Firestore, AgentAuditRecorder, ToolUsageRecorder)
  ← wealthAdvisorPipeline() [AgentConfig] needs → LlmAgent × 2
      ← portfolioAnalysisAgent() needs → PortfolioSearchTool, PortfolioHoldingsTool,
                                          PortfolioAllocationTool, StockLookupTool,
                                          AgentAuditRecorder, ToolUsageRecorder
          ← PortfolioAllocationTool(Firestore, MarketDataService)
              ← MarketDataService(Firestore, @Value fmp.api-key, ContentSanitizer)
  ← Firestore [FirestoreConfig.firestore(@Value gcp.project-id)]
```

Nobody in this codebase ever writes `new PortfolioAllocationTool(...)`. Spring resolves this whole
graph at startup, in dependency order, and injects the finished objects. That's IoC, concretely.

**Interview trap to nail**: *"Are singleton beans thread-safe?"* — Only if they're stateless (no
mutable instance fields). Every bean in this app *is* stateless by design — `AgentConfig`'s agents
hold only immutable config (name, model, instruction, tool list); `PortfolioAllocationTool` holds
only its two injected collaborators, never per-request data. The actual per-user state (a
conversation's history) is deliberately kept *out* of any bean and pushed into Firestore via
`FirestoreSessionService` — this is precisely why one shared singleton `Runner` can safely serve
every user concurrently. If a bean needs to be a singleton *and* hold per-request state, that's a
concurrency bug waiting to happen — the fix is always "externalize the state," never "make the
bean not-a-singleton."

### 1.2 Bean scopes: singleton vs. prototype (and why you'll say "singleton" almost every time here)

- **Singleton** (Spring's default): one instance per `ApplicationContext`, created once, reused for
  the app's lifetime. Every bean in this codebase is a singleton — `AgentConfig`'s `@Bean` methods,
  every `@Component`/`@Service` tool class, `AgentChatService` itself.
- **Prototype**: a new instance every time the bean is requested. **Not used anywhere in this app**,
  and that's a deliberate, correct choice, not an oversight — there is no per-request *object*
  identity that needs to differ between users; what differs between users is *data* (their
  `userId`, their session), which is a method parameter / `ToolContext` value, not a different bean
  instance. Interview-ready phrasing: "we don't need prototype scope because we never encode
  per-user identity into the bean itself — it flows through as data, at call time."

### 1.3 Constructor injection vs. field injection (and why this repo has zero `@Autowired` fields)

Every single class in this app uses constructor injection — grep the codebase, there's no
`@Autowired` on a field anywhere. Example, `MarketDataService`:

```java
public MarketDataService(Firestore firestore, @Value("${fmp.api-key:}") String apiKey, ContentSanitizer contentSanitizer) {
    this.firestore = firestore;
    this.apiKey = apiKey;
    this.contentSanitizer = contentSanitizer;
}
```

**Why this wins, concretely, not just as a rule to recite:**
- **Immutability** — `firestore`/`apiKey`/`contentSanitizer` are all `final` fields; once
  constructed, this object can never point at a different Firestore client. Nothing can reassign
  it later, accidentally or otherwise.
- **Fail-fast** — if `ContentSanitizer` were ever removed from the `ApplicationContext` (e.g., its
  `@Component` annotation deleted by mistake), the app fails to *start*, with a clear "no bean of
  type ContentSanitizer" error. Field injection would instead let the app start with a `null` field,
  and you'd get a `NullPointerException` deep inside a live request, with a far worse stack trace to
  debug.
- **Testability without the container** — `new MarketDataService(mockFirestore, "test-key",
  new ContentSanitizer())` works in a plain JUnit test, no Spring context needed at all. This
  matters directly for your interview answer on "how do you unit test a Spring service": you don't
  spin up `@SpringBootTest` for a class you can just `new` up with mocks.
- One nuance you should know since Spring 4.3: with exactly **one** constructor, you don't even need
  `@Autowired` on it — Spring infers it automatically. That's why none of this repo's classes have
  `@Autowired` anywhere; it's implicit.

**Note where Gemini's example code diverges from this:** its `AccountTools` example does use
constructor injection correctly for `BankAccountRepository` — but then registers the tool with
`FunctionTool.create(AccountTools.class, "getBalance")`, the **static/no-dependency overload**
(see §2.3 below — this is a real, live bug in that example, not nitpicking).

### 1.4 `@Component` / `@Service` / `@Repository` / `@Configuration` — what each actually signals

All four are `@Component` under the hood (Spring treats them as stereotypes it scans for and
registers as beans) — the differences are about *communicating intent* and, for `@Repository`,
one real technical behavior:

| Annotation | Signals | In this repo |
|---|---|---|
| `@Component` | "generic managed bean" | `ContentSanitizer`, the tool classes (`PortfolioSearchTool`, etc.) |
| `@Service` | "business logic lives here" | none currently — `MarketDataService`/tool classes use `@Component` even though they hold real logic; a defensible naming nitpick, not a bug |
| `@Repository` | data-access layer; Spring also wraps it with automatic exception translation (DB-specific exceptions → Spring's `DataAccessException` hierarchy) | **not used anywhere in this app** |
| `@Configuration` | "this class produces beans via `@Bean` methods, and Spring proxies it (CGLIB) so calling one `@Bean` method from another still returns the *same* singleton instance, not a fresh one" | `AgentConfig`, `FirestoreConfig`, `GenAiClientConfig`, `BigQueryConfig`, `SecurityConfig` |

**A genuinely interesting, honest gap worth knowing for interviews**: this app has *zero*
`@Repository` beans, because it doesn't use Spring Data at all — no JPA, no relational DB. Data
access is direct GCP SDK calls (`firestore.collection("portfolio_holdings").whereEqualTo(...)`,
right inside `PortfolioAllocationTool`/`PortfolioHoldingsTool` themselves) — there's no Spring Data
Firestore module to abstract this behind a repository interface the way `JpaRepository` would for
Postgres. This is a legitimate, explainable architecture choice, not a shortcut: "we call the GCP
client directly because Spring Data doesn't have first-class Firestore support the way it does for
JPA — the repository *pattern* (data access abstraction) still conceptually applies, it's just not
expressed via Spring's `@Repository` stereotype here."

### 1.5 Spring AOP — the concept, and a real example of *not* reaching for it

**The concept:** AOP (Aspect-Oriented Programming) lets you inject cross-cutting behavior (logging,
timing, auth checks, retries) *around* a method call without modifying the method itself. Spring
implements this via proxies — Spring wraps your bean in a dynamic proxy (JDK proxy or CGLIB), and
an `@Aspect` class declares a pointcut (which methods to intercept) plus advice (what to run
before/after/around the call).

```java
@Aspect
@Component
public class TimingAspect {
    @Around("execution(* com.wealth.advisor.web.ChatController.chat(..))")
    public Object timeIt(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.currentTimeMillis();
        Object result = pjp.proceed();
        log.info("took {}ms", System.currentTimeMillis() - start);
        return result;
    }
}
```

**Where this app deliberately does *not* use AOP, and why that's the correct call, not an
oversight**: cross-cutting concerns around Gemini calls and tool calls (latency, tokens, audit
trail) are handled instead by `AgentAuditRecorder`/`ToolUsageRecorder`, wired as **ADK's own native
callbacks** directly on `LlmAgent.builder()` in `AgentConfig`:

```java
.beforeModelCallbackSync(agentAuditRecorder::beforeModel)
.afterModelCallbackSync(agentAuditRecorder::afterModel)
.beforeToolCallbackSync(toolUsageRecorder::beforeTool)
.afterToolCallbackSync(toolUsageRecorder::afterTool)
.onToolErrorCallbackSync(toolUsageRecorder::onToolError)
```

A Spring `@Aspect` around `ChatController.chat()` or `AgentChatService.chat()` can only ever see
"one whole HTTP request/method call happened, it took X ms total." It has no visibility into what
happens *inside* that one method call — and a single turn can trigger **multiple** real Gemini
calls (a tool-call request, then a second call reacting to the tool's result) and multiple tool
calls, each needing its *own* latency/token/status row. AOP's interception point (a Spring bean's
public method boundary) is structurally the wrong altitude for this — it's one level too coarse.

There's a second, sharper reason: ADK's execution is **async/reactive** (`Flowable<Event>`, RxJava)
— a tool call isn't guaranteed to execute on the thread that entered `chat()`. This is the exact
reason `ToolContext.userId()` is used inside tools instead of Spring Security's
`SecurityContextHolder` (a `ThreadLocal`) — the tool might not run on the original servlet thread.
Classic AOP-based instrumentation patterns often lean on thread-local correlation (e.g., a
`ThreadLocal` timer started in `@Before` and read in `@After`) — that breaks the same way
`SecurityContextHolder` would here.

**The general, interview-quotable lesson**: *when the framework you're wrapping already exposes
lifecycle hooks at the exact granularity you need, use those — don't bolt Spring AOP on from
outside just because "AOP is how Spring does cross-cutting concerns."* AOP is still the right
answer for cross-cutting concerns on *your own* synchronous, Spring-managed call graph (e.g., "log
every `@RestController` method's latency" would be a legitimate AOP use here, and isn't done today
only because it isn't needed yet — nothing wrong with adding it for that specific, coarser purpose).

### 1.6 `@Configuration` classes as the "ADK-object factory" pattern

Every ADK object in this app — `LlmAgent` × 2, `SequentialAgent` — is built inside an `@Bean`
method in `AgentConfig`, never `new`'d inside a service or controller. This is the general Spring
pattern for wrapping a third-party builder API (ADK's, in this case) as first-class Spring objects:
`@Configuration` classes are where "translate a library's own builder/factory API into a Spring
bean" belongs, keeping that translation in one place instead of scattered across every class that
needs one.

**Update (fixed 2026-08-01, was `Optimization.md` P6-1):** `Runner` used to be the one exception to
this — built inline inside `AgentChatService`'s own constructor instead of as an `AgentConfig`
`@Bean`, the one place this codebase's own convention wasn't followed consistently. Now it's a
`@Bean Runner runner(SequentialAgent wealthAdvisorPipeline, Firestore firestore)` method in
`AgentConfig`, same as every other ADK object. Good example of *why* the consistency mattered: once
you notice one ADK object is built differently from its siblings for no reason, the fix is
almost always this small.

**Taken a step further, same day:** `AgentChatService`'s constructor still wasn't *pure* field
assignment — it derived `agent1Name`/`agent2Name` by indexing into `wealthAdvisorPipeline.subAgents()`
(`.get(0).name()`, `.get(1).name()`). That's a real "not clear" bug, not just a style nitpick: it
requires the reader to already know that sub-agent list order happens to match "stage 1, stage 2"
— an implicit fact you'd have to go read `AgentConfig`'s `wealthAdvisorPipeline` bean to confirm.
Fixed by exposing `agent1Name`/`agent2Name` as their own `String` beans in `AgentConfig`, each wired
directly off the actual named `LlmAgent` bean (`agent1Name(LlmAgent portfolioAnalysisAgent)`), not
off the pipeline's list position. See §2.6 for the full reasoning — this is the same
"don't make a consumer reverse-engineer an implementation detail" principle, applied one level
deeper. `AgentChatService`'s constructor is now genuinely pure assignment — five fields in, five
fields out, nothing else.

**A tradeoff worth naming honestly, not hiding:** this introduces two raw `String`-typed beans into
the `ApplicationContext`. Spring resolves which is which by matching the constructor parameter name
(`agent1Name`, `agent2Name`) to each `@Bean` method's name — standard Spring behavior for
disambiguating multiple beans of the same type, and it requires the same `-parameters` compiler
flag ADK's own `ToolContext` matching already depends on (see §2.3). It's a legitimate pattern, but
raw primitive/`String` beans are less common than typed objects — if this app ever adds an
unrelated `String` bean elsewhere, that's a name collision to be aware of, not something the
compiler will catch for you.

---

## Part 2 — ADK core concepts, through the Spring lens

### 2.1 `LlmAgent` — a bean, not a request-scoped object

```java
@Bean
public LlmAgent portfolioAnalysisAgent(
        PortfolioSearchTool portfolioSearchTool, /* ...other tools... */
        AgentAuditRecorder agentAuditRecorder, ToolUsageRecorder toolUsageRecorder) {
    return LlmAgent.builder()   // note: static factory method, not `new LlmAgent.Builder()`
            .name("portfolio_analysis")
            .model("gemini-2.5-flash")
            .instruction("...")
            .tools(FunctionTool.create(portfolioSearchTool, "searchPortfolio"), ...)
            .outputKey("portfolio_analysis")
            .beforeModelCallbackSync(agentAuditRecorder::beforeModel)
            .build();
}
```

**Correction to Gemini's example**: ADK's actual builder entry point is the static
`LlmAgent.builder()`, not `new LlmAgent.Builder()` — a small but real API detail; typing the wrong
one won't compile.

An `LlmAgent` is a **stateless persona**: a model name, a system instruction, a fixed tool list.
It holds no conversation history and no per-user anything — which is exactly why it's safe as a
singleton bean serving every user concurrently. All the per-user, per-turn variability lives
outside it, in the `Session`.

### 2.2 `FunctionTool` — two registration styles, and a real gotcha in choosing between them

```java
FunctionTool.create(portfolioSearchTool, "searchPortfolio")   // instance-based
```

Two overloads exist: `FunctionTool.create(Class<?>, methodName)` for a plain **static** method with
**zero dependencies**, or `FunctionTool.create(instance, methodName)` for a Spring-managed,
constructor-injected bean. `StockLookupTool` in this app actually *started* as the static,
dependency-free form (a hardcoded mock price lookup) and was migrated to the instance-based form
the moment it needed something injected (`MarketDataService`, which itself needs `Firestore` + an
`HttpClient`).

**Gemini's example gets exactly this wrong**: it constructs `AccountTools` with a real injected
`BankAccountRepository` dependency (correct, constructor injection), then registers it with
`FunctionTool.create(AccountTools.class, "getBalance")` — the **static overload**, which is for
methods with no dependencies. That combination doesn't work: a method that needs an injected
repository can't be called as if it were a static, standalone function. This is precisely the
lesson your own codebase already learned live (see `ARCHITECTURE.md`'s `FunctionTool` section) —
worth having ready as a specific, concrete "here's a subtle bug I'd catch" interview answer.

### 2.3 `ToolContext` — how a tool learns *who's calling*, without trusting the model

```java
public Map<String, Object> searchPortfolio(
    @Schema(name = "query", description = "...") String query,
    ToolContext toolContext) {          // auto-injected by ADK via reflection on the parameter name
  String userId = toolContext.userId();
  ...
}
```

ADK recognizes a parameter literally named `toolContext` by reflection and injects the current
turn's context — invisible to the model, not part of its tool schema. This is the mechanism that
lets `PortfolioSearchTool`/`PortfolioAllocationTool`/`PortfolioHoldingsTool` scope every Firestore
query to the real authenticated user, without ever trusting the model to supply a `userId`
argument (never let an LLM supply your tenant-scoping key) and without reaching for
`SecurityContextHolder` (unsafe here, per §1.5's reactive-execution point).

**Gotcha worth knowing**: this match is by literal Java parameter name via reflection, which only
survives compilation if `-parameters` is enabled on `javac` (see `pom.xml`) — otherwise the
parameter resolves to `arg1` and the injection silently fails.

### 2.4 `Session` / `SessionService` — Spring doesn't need to solve this; ADK already did

This is the direct rebuttal to "persist sessions via Spring Data JPA": ADK ships its own
`SessionService` abstraction specifically for conversation durability. This app uses
`FirestoreSessionService`, wired into the `Runner` bean in `AgentConfig`:

```java
@Bean
public Runner runner(SequentialAgent wealthAdvisorPipeline, Firestore firestore) {
    return Runner.builder()
            .agent(wealthAdvisorPipeline)
            .appName(wealthAdvisorPipeline.name())
            .sessionService(new FirestoreSessionService(firestore))
            .build();
}
```

A `Session` is an id, a user id, a list of `Event`s (every message/tool-call/tool-response so far),
and a small state map. Adding a parallel JPA-based "chat trajectory" table on top of this (as
Gemini's answer suggests) would create **two sources of truth for the same conversation** that can
drift apart — the standard distributed-state bug. The correct instinct is "does the framework I'm
already using own this problem" before reaching for a new persistence layer.

### 2.5 `Runner` — the actual execution engine, and what's inside one call

```java
List<Event> events = runner.runAsync(userId, userId, Content.fromParts(Part.fromText(message)))
        .toList()
        .blockingGet();
```

`Runner` ties an agent, a `SessionService`, and (unused here) an artifact/memory service together.
`runAsync` returns `Flowable<Event>` (RxJava) — a stream of every event in the turn: tool calls,
tool responses, each agent's text reply. `AgentChatService` collects the whole stream
(`.toList().blockingGet()`) since this is a plain request/response endpoint, not a streaming one.

**Under the hood, verified directly from ADK's own source** (not assumed): every real turn re-reads
the *entire* session's event history and rebuilds it as the model's context (`Contents.java`'s
`RequestProcessor`, `com.google.adk.flows.llmflows.Contents`) — this is the mechanism behind the
context-window/cost discussion in `Optimization.md` P1-1.

**Update (fixed 2026-08-01):** that item is now closed — `AgentConfig`'s `runner` bean wires up
ADK's tail-retention compaction (`EventsCompactionConfig`, `tokenThreshold=50,000`,
`eventRetentionSize=20`), so once a turn's prompt tokens cross the threshold, everything older than
the most recent 20 events gets summarized into one rolling event instead of resent raw forever.
Two things worth internalizing from actually wiring this up, not just knowing it exists:

1. **Enabling it required switching how `Runner` gets built.** `EventsCompactionConfig` only
   attaches to `App` (`com.google.adk.apps.App`), not to `Runner.Builder` directly — and
   `Runner.Builder` throws if you set both `.agent(...)` and `.app(...)`. So this wasn't "add one
   config line," it was "replace the way the root agent gets handed to `Runner` entirely."
   Library APIs often have exactly this shape: a feature that *looks* additive from the outside
   turns out to require swapping the object you were building through.
2. **A gotcha specific to this app's own architecture, found only by reading ADK's source, not any
   doc:** leaving `summarizer` unset makes `Runner` try to build a default one from the root
   agent's own model — which only works if the root agent is an `LlmAgent` directly. This app's
   root agent is `SequentialAgent` (composition of two `LlmAgent`s, no model of its own), so relying
   on that default throws `IllegalArgumentException: "No BaseLlm model available for event
   compaction"` at startup. The fix: construct the summarizer's `Gemini` model explicitly
   (`new LlmEventSummarizer(new Gemini("gemini-2.5-flash", genAiClient))`), reusing the same
   `genAiClient` bean already used for embeddings elsewhere in this app — no new credential. The
   general lesson: a framework's "just works" defaults are usually derived from an assumption about
   your object graph's shape (here: "the root agent has a model") — worth checking that assumption
   holds for *your* architecture specifically before trusting a default, especially once you've
   composed agents rather than using a single one.

### 2.6 `SequentialAgent` — composition without an orchestration framework of its own

```java
SequentialAgent.builder().subAgents(portfolioAnalysisAgent, stockRecommendationAgent).build();
```

Runs sub-agents one after another against the *same* session, every time, no branching. Hand-off
between them is via session **state**, not direct data passing: agent 1 writes its answer under a
key (`.outputKey("portfolio_analysis")`), agent 2's instruction references it with a
`{portfolio_analysis}` placeholder, substituted by ADK before the model ever sees the prompt. This
*is* ADK's orchestration primitive — the thing Spring AI doesn't have a first-class equivalent of
(see our earlier framework discussion) — worth having as your concrete answer to "what does ADK
give you that a plain `ChatClient` wrapper wouldn't."

**A subtle consequence worth knowing**: `subAgents()` returns them as an ordered `List`, but *list
position* isn't a stable, self-documenting way to refer to "stage 1" vs. "stage 2" from outside
this class — a consumer would have to already know that index 0 happens to be
`portfolio_analysis`. That's exactly the bug fixed in §1.6's "taken a step further" update: instead
of a consumer indexing into the pipeline's sub-agent list, `AgentConfig` now exposes each stage's
name as its own bean, wired directly off the named `LlmAgent` it belongs to. General lesson: when
a collection's *order* is secretly load-bearing (here, order == pipeline stage), don't make callers
decode that order — expose the thing they actually need (a name, tied to its real source) directly.

---

## Part 3 — One real request, traced end to end

```
Browser: POST /api/chat  {"message": "should I buy more JPM"}
   (JSESSIONID cookie — NOT userId in the body; see §3a)
        │
        ▼
ChatController.chat(ChatRequest, Authentication)      [web/ChatController.java]
   String userId = authentication.getName();          ← from Spring Security, never the payload
        │
        ▼
AgentChatService.chat(userId, message)                [agent/AgentChatService.java]
   ensureSessionExists(userId)                         ← get-or-create against FirestoreSessionService
   runner.runAsync(userId, userId, Content...)          ← session id == user id (one session/user)
        │
        ▼
Runner (ADK)                                          — invocationId generated once for this turn
   Stage 1: portfolio_analysis (LlmAgent)
      beforeModelCallbackSync → AgentAuditRecorder.beforeModel   [captured to BigQuery later]
      model call → decides to call a tool
      beforeToolCallbackSync  → ToolUsageRecorder.beforeTool
      PortfolioAllocationTool.getPortfolioAllocation(topN, ToolContext)
         toolContext.userId() → scopes the Firestore query, never model-supplied
         Firestore: portfolio_holdings WHERE userId = ...
         MarketDataService.getStockData(ticker) per distinct ticker (cache → FMP on miss)
      afterToolCallbackSync   → ToolUsageRecorder.afterTool
      model call reacts to tool result → writes text to session state under "portfolio_analysis"
      afterModelCallbackSync  → AgentAuditRecorder.afterModel
   Stage 2: stock_recommendation (LlmAgent)
      instruction has {portfolio_analysis} substituted in
      model call → final user-facing reply
        │
        ▼
AgentChatService: walk events backwards → last non-empty text = the reply
   finally { agentAuditRecorder.finalizeTurn(...); toolUsageRecorder.finalizeTurn(...); }
        │
        ▼
ChatResponse { "reply": "..." }  →  Browser
```

**§3a — the security detail Gemini's example got backwards**: `userId` is never in the request
body. It comes from `authentication.getName()`, resolved by Spring Security from the `JSESSIONID`
cookie against `FirestoreUserDetailsService`. A payload shaped like Gemini's
(`{"userId": "123", "sessionId": "abc", "message": "..."}`) would let any client claim to be any
user — exactly the multi-tenant isolation bug this design avoids.

---

## Part 4 — Interview cheat sheet

Quick-fire answers, each tagged with whether this repo demonstrates it live or it's general
knowledge worth having ready anyway.

| Question | Answer | In this repo? |
|---|---|---|
| What is IoC? | Container builds and owns your objects; you declare dependencies, don't construct them | Yes — whole app |
| Constructor vs. field injection? | Constructor: immutable, fail-fast, testable without Spring context | Yes — zero `@Autowired` fields anywhere |
| Singleton vs. prototype scope? | Singleton = one shared instance; prototype = new instance per lookup | Only singleton used; state externalized instead |
| `@Component` vs `@Service` vs `@Repository`? | Same mechanism, different intent signal; `@Repository` also adds exception translation | No `@Repository` here — no Spring Data, direct GCP SDK calls |
| Why does `@Configuration` need CGLIB proxying? | So one `@Bean` method calling another returns the same singleton, not a fresh instance | `AgentConfig`'s `wealthAdvisorPipeline(...)` receiving the *same* agent beans built elsewhere in the class |
| What is AOP, and when would you *not* use it? | Proxy-based cross-cutting concerns on your own sync call graph; wrong tool when the thing you need to observe lives inside a third-party async engine's internals at finer granularity than your own method boundary | Not used — ADK's own model/tool callbacks used instead (see §1.5) |
| How do you avoid trusting client input for identity? | Derive identity from the authenticated principal (`Authentication.getName()`), never a request body field | `ChatController.java:43` |
| How do you unit test a constructor-injected service? | `new MyService(mockA, mockB)` in plain JUnit, no Spring context | Any tool class in `agent/tool/` |
| Bean lifecycle — what runs at startup, in what order? | `@Configuration` classes resolved, `@Bean` methods called in dependency order, then `ApplicationRunner`/`CommandLineRunner` beans fire post-context | `AuditSchemaInitializer` (`@Order(1)`) then `ToolUsageSchemaInitializer` (`@Order(2)`), both `ApplicationRunner`s |
| Circular dependency — what happens? | Constructor injection fails fast at startup with a clear error (can't resolve order); field injection can mask it via a 3-phase creation Spring does for setter/field cases | Not present in this repo (clean DAG) — worth knowing as a "why constructor injection is stricter, and that strictness is good" talking point |

---

## What's next

Tell me which part to go deeper on with the real code — e.g., walk `AgentAuditRecorder`'s callback
wiring line-by-line, trace exactly how `ApplicationRunner` ordering works via
`AuditSchemaInitializer`/`ToolUsageSchemaInitializer`, or pull up ADK's own `Contents.java` again
and read the compaction logic together. Real repo, real line numbers, same as this doc.
