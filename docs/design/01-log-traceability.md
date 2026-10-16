# 01 — Log traceability

Status: **implemented in part.** Context on every log line is done; the Cloud Logging JSON format
is the next step. Written 2026-10-16 as the first piece of the observability topic.

## 1. What we want to tackle

Every log line should say **who** (user), **which conversation** (session) and **which request**, so
that "everything user X did", "everything in this conversation" or "everything for this one
request" is a single log query — locally and in Cloud Logging.

## 2. What we had

Plain text logs on stdout (Cloud Run ships them to Cloud Logging as unstructured text). Some
messages repeated the user id by hand (`userId='{}'`), most did not, and nothing carried a session
or request id. Finding one conversation meant grepping message text.

## 3. Gaps

- No consistent identifier on log lines; the hand-written ones drift.
- Cloud Logging cannot filter text by field; it can only search strings.
- The context is hard to carry: a chat turn does not stay on one thread. In the real log, our lines
  come from `nio-8081-exec-N` (request thread), `default-executor-N` (gRPC threads, where ADK runs
  tools) and `virtual-N` (the allocation tool's parallel price lookups). MDC is per thread, so
  setting it once on the request thread reaches almost none of the tool lines.
- ADK's own and Firestore's log lines run on gRPC threads we do not control.

## 4. Industry practice (terms)

- **Correlation id / request id**: one id on every line of a request.
- **MDC (Mapped Diagnostic Context)**: SLF4J's per-thread key-value map that the log layout prints.
- **Structured logging**: one JSON object per line, so fields are queryable
  (`jsonPayload.userId="demo"`), not substrings.
- **Trace-log correlation**: Cloud Logging groups app lines under the platform's request log when
  the line carries `logging.googleapis.com/trace`.
- **Context propagation**: carrying that context across thread hops (executors, reactive chains).
- **Cardinality and PII**: user and session ids are identifiers; keep them out of metric labels,
  and treat them as personal data in retention decisions.

## 5. Options considered

| Option | Verdict |
|---|---|
| Hand-write ids into every message | What we had; inconsistent and easy to forget |
| MDC on the request thread only | Misses tool, gRPC and virtual-thread lines (see gaps) |
| **Set MDC inside ADK plugin callbacks** | **Rejected.** Callbacks and the next step can run on different pooled threads; a value left behind is stamped onto an unrelated task, attributing one user's activity to another. Missing a tag is better than wrong attribution |
| RxJava scheduler hook | Does not help: the tool continuations run on gRPC threads triggered by Firestore callbacks, not on RxJava schedulers |
| OpenTelemetry context | The strongest long-term answer (ADK already emits spans), but needs an exporter and collector; planned, not needed for this step |
| **Open a scope where the work happens, restore on exit** | **Chosen** |

## 6. What was built

- `logging/LogContext`: `with(...)` opens a scope and **restores the previous values** on close;
  `forTool(ToolContext)` builds the context from the tool's own user/session/turn/agent;
  `propagating(Supplier)` carries it across an explicit thread hop.
- `constants/LogKeys`: the key names (also the future JSON field names).
- `web/RequestLogContextInterceptor`: sets `requestId`, `userId` and the Cloud Trace id for each HTTP
  request (registered in `config/WebConfig`; it runs after Spring Security so the user is known).
- `AgentChatService` opens a scope with user and session around each turn.
- Each tool opens `LogContext.forTool(toolContext)` around its body, and the allocation tool wraps
  its parallel price lookups with `propagating(...)`.
- `application.yml`: `logging.pattern.correlation` prints `[requestId userId sessionId]` on every
  line through Spring Boot's own default pattern.

Verified live: request-thread lines show all three ids, tool lines on `default-executor` threads and
price-lookup lines on virtual threads show user and session. `LogContextTest` pins the restore
behavior, including that a propagated task does not leak its context onto a pooled thread.

## 7. Known limits

- ADK-internal and Firestore library lines cannot carry the context (their messages usually include
  the session id themselves).
- The authentication lookup runs in the security filter chain before the interceptor, so it has no
  context.
- Lines written on a thread we do not control and do not wrap are untagged.

## 8. Next

1. **Cloud Logging JSON format.** Spring Boot ships ECS, GELF and Logstash JSON, but none sets
   Cloud Logging's `severity` field (they use `level` / `log.level`), so error filters would not
   work. A small custom `StructuredLogFormatter` for Google Cloud, enabled by a `cloud` profile.
2. Add the `traceId` as `logging.googleapis.com/trace` so lines group under the request log.
3. Example saved queries for the design note once the JSON format is live.
