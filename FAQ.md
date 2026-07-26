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

## Does uploading a PDF store it in GCS? What's the bucket? Can I upload more than one?

**No GCS at all.** There's no bucket, no GCS dependency anywhere in this project.
`PortfolioController.upload` reads the multipart file's bytes into memory,
`PortfolioIngestionService.ingest` extracts text (PDFBox), chunks it, embeds each chunk, and
writes only the **extracted text + embeddings** to Firestore's `portfolio_vectors` collection.
The original PDF bytes are never persisted anywhere — once the request finishes, they're gone;
only the derived text chunks remain.

**Not limited to one PDF — but not by design, by absence of a check.** Every chunk write is:

```java
firestore.collection("portfolio_vectors").add(Map.of("userId", userId, "filename", filename, ...));
```

`.add()` always creates a **new** document with an auto-generated id — there's no "delete this
user's existing chunks first" step.

- **Multiple providers (Vanguard, Fidelity, Merrill) works today**, incidentally: upload each
  statement separately, and every chunk gets tagged with the same `userId` (plus its own
  `filename`). `PortfolioSearchTool`'s vector search filters only by `userId`, not by `filename`,
  so it searches across *all* uploaded documents together and returns whichever chunks are
  semantically closest, regardless of provider.
- **The real gap: re-uploading doesn't replace anything.** Upload the same (or an updated)
  statement again and the old chunks aren't deleted — stale and current data coexist
  indefinitely, both equally eligible to be returned by search. There's also no tracking of
  *which* filenames a user has uploaded — `users/{userId}.hasUploadedPortfolio` is just a
  boolean, not a list.

So: fine for a one-time upload from several providers; not yet fine for "re-upload my updated
Vanguard statement each month" — that needs an explicit delete-by-`userId`(+`filename`) step
before re-ingesting, which doesn't exist yet.

## Where's our ADC in this project? How does it differ between local and Cloud Run?

**Nowhere in the repo — that's the point.** There's no ADC file, no credentials, no key
committed or referenced anywhere in this codebase. ADC lives entirely outside the project, on
whatever machine runs it:

```
~/.config/gcloud/application_default_credentials.json   # created by `gcloud auth application-default login`
```

Neither `GenAiClientConfig.genAiClient()` (`Client.builder().build()`) nor
`FirestoreConfig.firestore(...)` (`FirestoreOptions.newBuilder().setProjectId(projectId).build().getService()`)
mentions credentials at all. Both internally call Google's standard
`GoogleCredentials.getApplicationDefault()`, which is a *search*, not a fixed path:

1. `GOOGLE_APPLICATION_CREDENTIALS` env var (path to a service account key file) — not set here.
2. → the `gcloud` ADC file above — **this is what fires when you run locally**.
3. → (only on GCP infra) the metadata server's attached service account.

|                        | Local (now)                                                 | Cloud Run (later)                                              |
|------------------------|--------------------------------------------------------------|-------------------------------------------------------------------|
| ADC resolves from      | `~/.config/gcloud/application_default_credentials.json`      | Cloud Run's metadata server — no file at all                      |
| Whose identity         | **You** (your own Google account)                             | A **service account** attached to the Cloud Run service            |
| Permissions come from  | IAM roles granted to your user on the project                | IAM roles granted to that service account                          |
| Setup needed           | `gcloud auth application-default login` (already done)       | Grant the service account `roles/datastore.user` + `roles/aiplatform.user` (or equivalent) — part of the not-yet-done Cloud Run milestone |

The code doesn't know or care which of these it's running under — same two lines of Java either
way. That portability is the whole reason this project uses ADC instead of an API key.

## Does running locally actually hit real Firestore/Gemini, or is anything mocked?

Nothing is mocked at the infrastructure level (the only mocked thing in this app is
`StockLookupTool`'s price data). Running locally with the env vars above talks to the *real*
`agentic-ai-503223` project: real Firestore (visible in the
[Firestore Console](https://console.cloud.google.com/firestore/databases) under `users`,
`portfolio_vectors`, and ADK's own `adk-session` collection) and real Gemini via Vertex AI
(`aiplatform.googleapis.com`, not the public Gemini API key endpoint). This was confirmed by
actually logging in, chatting, and uploading a PDF end to end earlier in this project's history.

## Is there a signup page, or only the one seeded demo user?

Both — a self-service signup exists now (it didn't originally; `DemoUserSeeder` seeding one user
was a deliberate initial shortcut to avoid building registration). Go to
**`http://localhost:8080/signup.html`** (linked from `login.html` too): pick a username
(3-30 chars, letters/numbers/`_`/`-` only — it becomes the Firestore document id, same convention
`FirestoreUserDetailsService` already uses), an email, and a password (min 6 chars).

`RegistrationController` (`POST /api/auth/register`, unauthenticated) hands this to
`UserRegistrationService`, which:

1. Validates the username/password against those rules — rejects with `409` + a message
   otherwise.
2. Checks `users/{username}` doesn't already exist in Firestore — `409` if it's taken.
3. Writes `users/{username}` with a bcrypt `passwordHash` (same `PasswordEncoder` bean
   `FirestoreUserDetailsService` checks against), `email`, `createdAt`, and
   `hasUploadedPortfolio: false` — the exact same shape `DemoUserSeeder` writes for the demo user.

Registering does **not** log you in — the signup page's JS redirects to `/login.html` on success,
where you authenticate with the credentials you just picked, same as any other user. From that
point on a self-registered user behaves identically to the demo one: own Firestore user doc, own
`portfolio_vectors` chunks, own ADK session under `adk-session/{username}/...`.

One known simplification: the "does this username already exist" check and the write aren't in a
Firestore transaction, so two simultaneous signups for the same exact username could theoretically
both slip past the check. Not worth guarding against at this project's scale.
