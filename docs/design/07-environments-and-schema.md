# 07 — Environments and schema changes

Status: **built 2026-10-20.** Written after the owner pointed out that local development was changing the
production BigQuery dataset.

## 1. What we want to tackle

Treat the service on Cloud Run as production. Local development and tests must not be able to change
production data, and changes to the production database schema must be a separate, deliberate step, not
a side effect of starting the app.

## 2. What we had (verified 2026-10-19)

- The app creates and evolves its own BigQuery schema on startup (`AuditSchemaInitializer`,
  `ToolUsageSchemaInitializer`, `TokenUsageViewInitializer`): tables, new columns, views, and even rows in
  `tool_registry_events`.
- The dataset name was a constant, `wealth_agent`, and there is no BigQuery emulator.
- A local run used the **real** project: real Firestore and the production BigQuery dataset. The token
  columns and views were added to the production dataset on 2026-10-16 simply because the app was started
  locally after that code change.
- The deployed revision (2026-07-30) did not even use the `cloud` profile.
- Firestore could be pointed at an emulator, but only if `FIRESTORE_EMULATOR_HOST` happened to be set;
  nothing stopped a local run from using the real Firestore.

## 3. Options considered

| Option | Verdict |
|---|---|
| A. A separate GCP project for dev (own Firestore, BigQuery, Vertex) | Cleanest isolation, including IAM; needs a new project and billing. **Later**, when the GKE practice needs it |
| B. Same project, separate resources: a dev BigQuery dataset, the Firestore emulator for local | **Chosen now.** Immediate, free, no new project |
| C. A second named Firestore database in the same project | Possible, but the emulator already covers local needs |
| D. A migration tool (Flyway-style versioned scripts) | More machinery than a handful of additive changes needs; revisit if schema changes become frequent |
| E. Let the deployed service keep altering its own schema | Rejected: a new revision starting (even a no-traffic canary) would change production before anyone decided to |

## 4. Decisions

1. **The dataset is configuration** (`advisor.bigquery.dataset`). The default, used by local runs and tests,
   is `wealth_agent_dev`. The `cloud` profile sets `wealth_agent`.
2. **`advisor.bigquery.schema-mode`**: `apply` (create and evolve, additive only) or `verify` (look, report,
   never change). Default `apply`; the `cloud` profile sets `verify`. In `verify` mode the initializers also
   skip the startup writes (registry rows, view re-creation).
3. **Local never reaches production Firestore by accident.** Outside the `cloud` profile the app refuses to
   start unless `FIRESTORE_EMULATOR_HOST` is set or `advisor.safety.allow-real-firestore` is turned on
   deliberately.
4. **The `cloud` profile is production.** It requires a non-default demo password (the app refuses to start
   with `changeme`). Deploying must therefore set `SPRING_PROFILES_ACTIVE=cloud`.
5. **Schema changes are applied as their own step**, before the deploy, by running the app once in `apply`
   mode against the production dataset (with the Firestore emulator, so nothing else is touched). Changes
   are **additive only** (new nullable columns, new tables, new or replaced views), so the revision that is
   still serving traffic keeps working while the new one is checked. A destructive change takes two
   releases: stop using it, then remove it.
6. **Deploy order:** apply schema, deploy a no-traffic canary, test it, shift traffic. Rollback is a traffic
   change back to the previous revision.

## 5. Consequences and limits

- Local audit rows now go to `wealth_agent_dev`, so production analytics no longer contain test traffic.
  Rows written before this change are still in `wealth_agent`: 27 rows of `eval-…` users (earlier
  `PortfolioEvalTest` runs, which assert on BigQuery on purpose) and the `demo` rows of local use, among them
  about 17 from the probe chats made while the guardrails were being designed (they cannot be told apart from
  other `demo` rows by user). Deleting them is a decision for the owner.
- The guard checks the Firestore only; Vertex AI calls from a local run are real and billed (cents). BigQuery
  is protected by the dataset name, not by a guard: pointing `ADVISOR_BIGQUERY_DATASET` at `wealth_agent` on
  purpose is how the schema is applied, and it also lets a local run write audit rows there, so do it only for
  the schema step.
- A separate dev project (option A) remains the stronger isolation and is the natural next step.
