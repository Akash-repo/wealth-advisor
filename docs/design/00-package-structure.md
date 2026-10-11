# 00 — Package structure and design rules

Status: **decisions made, implementation not started.** Written after discussion on 2026-10-11.

## 1. What we want to tackle

How the codebase is organized and which design rules we hold it to, so the advisor can grow into a
production build without becoming hard to change. Scope is structure, configuration and
conventions — not features.

## 2. What we have today

Packages are by technical role, and dependencies already point one way
(`web -> agent -> agent.tool / audit`, `agent.tool -> guardrails / portfolio`) with no cycles.

| Package | Approx. lines | Depends on |
|---|---|---|
| `agent` | 730 | `agent.tool`, `audit` |
| `agent.tool` | 700 | `guardrails`, `portfolio` |
| `audit` | 790 | — |
| `portfolio` | 440 | — |
| `web`, `web.dto` | 420 | `agent`, `portfolio`, `security` |
| `security`, `infra` | 500 | — |
| `guardrails` | 44 | — |

## 3. Gaps (verified in the repo)

- `AgentChatService` (321 lines) does session lifecycle, turn orchestration, the in-flight guard,
  history replay, title derivation and audit finalization.
- `AgentConfig` (281 lines) mixes long prompt text, agent/tool wiring, and runner + compaction setup.
- `AgentChatService` depends on the concrete `AgentAuditRecorder` / `ToolUsageRecorder`.
- Text extraction from ADK content is written twice (`AgentChatService.textOf`,
  `AgentAuditRecorder.extractText`).
- Four independent `new ObjectMapper()` instances.
- About 20 tunables are `static final` across 12 files; the model name `gemini-2.5-flash` is
  hard-coded in two classes.
- Config classes live in three places (`infra`, `agent`, `security`).
- DTO records and exceptions sit inside `agent`; rate limiting is called inline in `ChatController`.
- Nothing enforces the dependency direction.

## 4. Industry practice

- Package-by-layer (controller / service / repository) and package-by-feature are both common;
  layers remain the default in many enterprise codebases.
- Externalized, typed configuration (`@ConfigurationProperties`) with environment overrides.
- Dependency inversion at boundaries that are likely to change; SOLID as a review rubric;
  architecture rules enforced by tests (ArchUnit) rather than convention.

## 5. Options considered

| Option | Verdict |
|---|---|
| A. Package-by-layer | **Chosen** — familiar to the team, fast to maintain at this size |
| B. Package-by-feature | Better encapsulation (package-private internals), not chosen now |
| C. Full hexagonal | More ceremony than this app needs |

## 6. Decisions

**Structure: A (layers), improved.** Target sketch:

```
config/        AdvisorProperties (typed, validated), RunnerConfig, AgentConfig, GCP clients,
               ExecutorConfig, SecurityConfig
controller/    Chat, Portfolio, Auth, Registration
service/       ChatService, ChatSessionService, portfolio and market-data services
agent/         agent definitions, tool/, prompt/, memory/
guardrails/    GuardrailService + checks
observability/ recorders and event model (today's audit/)
dto/  exception/  util/  security/
```

**Where a value lives**

| Kind | Examples | Home |
|---|---|---|
| True constants (change only with code) | Firestore field names, session-state keys, collection and table names | Constants classes grouped by area |
| Tunables (change per environment) | Model names, compaction threshold/retention, rate limit, max message length, memory caps | `application.yml` -> typed `@ConfigurationProperties`, validated at startup |
| Secrets | `FMP_API_KEY` | Env var / Secret Manager, never in YAML |

- **YAML, not `.properties`.** Convert `application.properties` to `application.yml`; do not keep
  both for the same keys. Nested `advisor.*` groups hold the tunables. Env vars override on Cloud
  Run through relaxed binding (`ADVISOR_COMPACTION_TOKENTHRESHOLD`).
- **Prompts live as files** under `resources/prompts/`, not in YAML or Java string blocks; the
  file hash can serve as the `prompt_version` tag (see Topic 1).
- **Runtime-toggleable switches** (e.g. a guardrail on/off) are a separate mechanism from YAML —
  YAML/env still needs a new revision. Deferred.
- **`util` rule:** stateless, no Spring dependencies, named by capability (e.g. `AdkContentText`).

**Design rules we hold to (proposed):** SRP per class; depend on an interface at boundaries that
will change (event recording, guardrails); one injected `ObjectMapper`; patterns only where they
earn their place — Strategy and Chain of Responsibility for guardrails, Decorator for
`GuardedMemoryService`-style wrappers, Facade for the chat entry point.

## 7. Status

Decisions confirmed 2026-10-11: prompts as resource files, repository layer later, order of work.

| Item | State |
|---|---|
| Layered structure (A) | Decided |
| YAML (`application.yml`) | **Done** — `c41e447` |
| Typed `AdvisorProperties` (yml is single source, validated, no code defaults) | **Done** — `34e33d4` |
| Constants classes (`constants/`) | **Done** — `3fe0cb6` |
| Prompts as resource files (`resources/prompts/`, `PromptLoader`, versioned by hash) | **Done** — `8f22b93` |
| Config classes into one `config/` package; runner split out as `RunnerConfig` | **Done** — `3b62dc8` |
| Util extraction (`util/AdkContentText`, one shared `ObjectMapper`) | **Done** — `7c71cbe` |
| Service split (`ChatService` / `ChatSessionService`), `dto/` + `exception/` packages, `@RestControllerAdvice` | Confirmed, **not started** (step 4) |
| Repository layer | Later — when Topic 5 (analytics tables) needs it |
| ArchUnit dependency rules | Proposed, not confirmed |

### Correction recorded during step 3

The earlier plan said to "inject Spring's `ObjectMapper`". On Spring Boot 4.1 Spring's JSON stack is
Jackson 3 (`tools.jackson`), a different type from the Jackson 2 (`com.fasterxml`) that ADK and our
audit/market-data/holdings classes use, so there is no Spring-provided Jackson 2 mapper to inject.
`config/JacksonConfig` therefore defines one shared Jackson 2 bean instead. Migrating those classes
to Jackson 3 is possible but is a separate, larger change and was not needed.

### Deliberately left as constants

- `ChatRequest.MAX_MESSAGE_LENGTH` — a bean-validation annotation value must be a compile-time
  constant, so it cannot be bound from YAML.
- Smaller single-use values (`TextChunker` words per chunk, `PortfolioSearchTool` result count,
  minimum password length) — can move into `AdvisorProperties` later if they need tuning.
