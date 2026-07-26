# Wealth Advisor — Q&A

Practical questions that came up while reading through the codebase, answered in more depth than
fits as a code comment. See [ARCHITECTURE.md](ARCHITECTURE.md) for the full concept glossary,
diagram, and Firestore schema this FAQ assumes you've skimmed.

## Does the app present a UI on startup? What's the local URL?

Yes. Spring Boot auto-serves everything in `src/main/resources/static/` as static files, and
since `index.html` lives there, it's registered as the app's "welcome page" (you'll see this in
the boot logs: `Adding welcome page: class path resource [static/index.html]`).

- **`http://localhost:8080/`** or **`http://localhost:8080/login.html`** — since `SecurityConfig`
  requires authentication for everything except `/login.html` and `/api/auth/login`, hitting `/`
  unauthenticated redirects you straight to the login page.
- Log in with `demo` / `changeme` (or whatever `DEMO_USER_USERNAME`/`DEMO_USER_PASSWORD` you've
  set — seeded by `DemoUserSeeder` on boot).
- On success you land on **`http://localhost:8080/index.html`** — the chat box + PDF upload form.

## How do I actually run and test this locally?

```bash
export GOOGLE_GENAI_USE_VERTEXAI=true
export GOOGLE_CLOUD_PROJECT=agentic-ai-503223
export GOOGLE_CLOUD_LOCATION=us-central1
export GCP_PROJECT_ID=agentic-ai-503223
mvn spring-boot:run
```

Then open `http://localhost:8080` in a browser, log in, optionally upload a PDF, and chat. This
is exactly what gets exercised via `curl` when testing changes from the CLI — a browser just
gives you the same thing visually.

(No `GOOGLE_API_KEY`/`GEMINI_API_KEY` needed or wanted here — this project deliberately uses ADC
via Vertex AI instead; see ARCHITECTURE.md's [Environment variables](ARCHITECTURE.md#environment-variables) section.)

## How does the DB search actually work — for user auth vs. for vector search? What's the filter?

These are two very different patterns, and mixing them up is an easy way to misread the code.

### Auth: a direct **document lookup**, not a query

`FirestoreUserDetailsService.loadUserByUsername`:

```java
firestore.collection("users").document(username).get().get();
```

There's no "filter" here at all — the username **is** the document ID. Logging in as `demo`
fetches the exact document `users/demo` directly, the same way you'd do `map.get("demo")`. That's
why it's fast (a Firestore document-by-id read is its cheapest, most direct operation) and why it
can never accidentally match the wrong user. Spring Security then takes the `passwordHash` field
from that one document and compares it (via bcrypt) against what was typed into the login form —
`FirestoreUserDetailsService` never compares passwords itself, it just hands the hash back.

### Vector search: an **equality pre-filter + a nearest-neighbor rank**

`PortfolioSearchTool.searchPortfolio` does two things in one Firestore query:

```java
firestore
    .collection("portfolio_vectors")
    .whereEqualTo("userId", userId)                                      // <- the filter
    .findNearest("embedding", queryVector, MAX_RESULTS, DistanceMeasure.COSINE);
```

**Step by step, with real values** — say `userId = "demo"` and the question is *"What's my
technology sector exposure?"*:

1. **Embed the query.** `EmbeddingService.embed(query)` calls Gemini's `text-embedding-004` and
   turns that sentence into a 768-number vector (`queryVector`) — a numeric representation of its
   *meaning*.
2. **Filter first.** `whereEqualTo("userId", "demo")` narrows the entire `portfolio_vectors`
   collection down to only documents belonging to `demo` — this is the multi-tenant isolation:
   user `alice` can never search into `demo`'s chunks, because her query would filter to
   `userId == "alice"` instead. This *is* a real equality `WHERE` clause, unlike the auth lookup
   above.
3. **Then rank by vector distance.** Among *only those filtered documents*, `findNearest(...,
   COSINE)` computes the cosine distance between `queryVector` and each stored chunk's
   `embedding` field, and returns the `MAX_RESULTS` (3) closest ones — i.e. the 3 chunks from
   `demo`'s uploaded PDF whose *meaning* is closest to "technology sector exposure" (in testing,
   this correctly pulled back the chunk containing "65% Technology equities...").
4. Their `chunkText` fields get concatenated and returned to the model as `context`, which
   `portfolio_analysis` then reasons over to write its answer.

**Why both parts are required together:** `whereEqualTo` + `findNearest` in the same query is
exactly the combination that needs the composite index documented in ARCHITECTURE.md's
[Gotchas](ARCHITECTURE.md#gotchas-discovered-the-hard-way) section — Firestore can't efficiently
filter by equality *and* rank the remainder by vector distance without a matching index prebuilt
for that exact field combination.

**One-line contrast:** auth = "give me the document whose *ID* is exactly this" (no filter, no
ranking); vector search = "give me the top-3 documents matching this *equality filter*, ranked by
*closest meaning*" (a filter and a similarity rank, computed together, in a single query).
