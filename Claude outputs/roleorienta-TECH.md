# roleorienta — Technical Overview

> Engineering companion to the product overview. This document is for technical
> reviewers (CTOs, tech leads, platform recruiters) and summarizes the architecture,
> stack, and engineering decisions behind the project. For the product/feature
> description see the client-facing overview; for the full specification see
> [`docs/technical-design.md`](docs/technical-design.md) and the slice-by-slice record in
> [`docs/project-notes.md`](docs/project-notes.md).

**Status:** the MVP is complete (2026-11-12). It runs end to end on one machine for
Slovakia, the first market: employer discovery, continuous collection from the state job
portal, profesia.sk and ten recruiting-system formats, deduplication, profession
recognition, hourly delivery to users, a React SPA for users and an admin page. The next
stage is moving to a server: a 14-day continuous run on live sources, container images
for both applications, Compose profiles, outbound mail, then hosting. Later stages
(Kubernetes, observability stack, wider extraction) are on the roadmap below.

---

## What it is (one line)

A job-collection and delivery backend that discovers a country's employers, finds every
channel each of them publishes jobs in, reads those channels politely and continuously,
keeps one record per real job, classifies it by profession (ISCO-08 / ESCO), and delivers
matching jobs to users every hour — with data honesty (unknown is not shown, never guessed;
a failed read never closes a job) as a design rule.

## Architecture

Multi-module Maven monorepo: two deployable Spring Boot applications and a SPA, sharing one
PostgreSQL database; RabbitMQ carries jobs to the worker's handlers, S3-compatible storage
keeps raw response snapshots:

- **job-api** — REST layer: accounts and sessions, search conditions (versioned), results,
  job details and statuses, applications, companies and user-added career pages, admin
  endpoints. Owns the DB schema (applies Flyway migrations).
- **job-worker** — background processor: scheduler, outbox publication, company intake and
  site discovery, career-page and recruiting-system discovery, collection, deduplication,
  profession recognition, matching, hourly delivery passes, metrics, digest mail.
- **web** — React + TypeScript SPA for users (`/`) and the administrator (`/admin`), built
  by the same Maven build.

```
   SPA (web) ──HTTP──▶ ┌─────────────┐
                       │   job-api   │
                       │ REST, auth, │
                       │ owns schema │
                       └──────┬──────┘
                              │      shared PostgreSQL
                              ▼      (data, tasks, outbox, sessions)
                        ┌────────────┐
                        │ PostgreSQL │◀──────────────────────────────┐
                        └────────────┘                               │
                                                                     │
   ┌──────────────────────────── job-worker ─────────────────────────┴───┐
   │ scheduler (leader-lock) ─▶ outbox publisher ─▶ RabbitMQ ─▶ handlers │──▶ state portal,
   │                                                         (adapters) │    profesia.sk,
   └──────────────────────────────────────────────────────┬─────────────┘    recruiting systems,
                                                          │                  career pages
                                         ┌────────────────┴──────┐
                                         ▼                       ▼
                                  Ollama (bge-m3)       SeaweedFS (S3 snapshots)
```

Collection and delivery are decoupled: collection never sees users' conditions — only the
list of collection countries — and a delivery pass only reads what was already collected
and matched. The API and the worker are connected only through the shared database, so
request latency is independent of crawling time and the worker scales separately.

## Tech stack

| Area              | Technology                                                                    |
|-------------------|-------------------------------------------------------------------------------|
| Language          | Java 21 (LTS): records, sealed interfaces, pattern matching, text blocks       |
| Framework         | Spring Boot 4.1.1                                                              |
| Persistence       | PostgreSQL 16; Flyway (105 migrations, schema-first, Hibernate only validates); Spring Data JPA for aggregates, JdbcTemplate for read-heavy lists and search |
| Search            | PostgreSQL: word search without case and diacritics (`translate` + `LIKE` over an `unnest` word array), regex matching of dictionary terms |
| Messaging         | RabbitMQ 4 via Spring AMQP; transactional outbox with publisher confirms       |
| Object storage    | SeaweedFS (S3 API) for raw snapshots, 30-day retention                         |
| Auth / sessions   | Spring Security + Spring Session JDBC; bcrypt; CSRF for SPA; USER / ADMIN roles |
| HTTP / parsing    | Apache HttpClient 5 behind one vetted client; Jsoup; Playwright (headless Chromium) as a fallback for script-built pages |
| Classification    | ISCO-08 / ESCO catalogue in the DB (436 unit groups, 3,039 occupations, 66,335 labels); Ollama with the bge-m3 embedding model, local and free |
| Frontend          | React 19, TypeScript, Vite; built by frontend-maven-plugin                     |
| Build             | Maven (multi-module, BOMs); Checkstyle and ArchUnit fail the build             |
| Testing           | JUnit 5, Testcontainers (PostgreSQL, RabbitMQ, S3), stub sources on the JDK HTTP server, saved real career pages |
| Infrastructure    | Docker Compose: PostgreSQL, RabbitMQ, SeaweedFS, scheduled PostgreSQL backup   |
| CI                | GitHub Actions on a pinned Ubuntu 24.04 runner: build, all tests               |
| Planned           | Container images for both apps, Compose profiles (prod / dev), Brevo SMTP; later Kubernetes + KEDA, Helm, Argo CD (GitOps), Gateway API + Envoy, cert-manager + Sealed Secrets, Prometheus/Grafana/Tempo, SBOM + Trivy |

## Key engineering decisions

The design is recorded in `docs/technical-design.md`; every decision has a slice entry in
`docs/project-notes.md`. The most notable:

- **Reliable messaging from day one.** A task and the outbox event announcing it are
  written in one DB transaction, then relayed to RabbitMQ with publisher confirms and
  routing checks (`mandatory` + `basic.return`); an event is marked published only after
  both. No dual-write race; at-least-once delivery to idempotent handlers.
- **The task table is the source of truth, the broker is transport.** Each task carries
  state, attempts and a lease. A claim gets a claim number; only the claimant can write the
  result, so a stalled executor can't overwrite someone else's outcome. The lease is renewed
  every third of its term; an expired lease is a failed attempt and the task is requeued.
  Retries back off exponentially (5 min doubling to 6 h, +10 % jitter, 8 attempts); a
  consumer exception goes to a DLQ without requeue.
- **Leader-lock in PostgreSQL, no extra infra.** A transactional advisory lock
  (`pg_try_advisory_xact_lock`) keeps one scheduler and one publisher across replicas;
  outbox capture uses `SELECT … FOR UPDATE SKIP LOCKED`; unique keys per schedule window
  make planning idempotent. Ticks run on 8 threads with a 2-minute `lock_timeout`, so one
  stuck tick can't stop the others — a fix after a real three-day stall.
- **Shared database, schema owned by the API.** Flyway is the single source of truth,
  including data migrations (merging accumulated duplicates while carrying users' marks).
- **Per-source adapters, not a universal parser.** Workday, SAP SuccessFactors, Oracle
  Taleo, Oracle Recruiting Cloud, Greenhouse, SmartRecruiters, Personio, Phenom, Nalgoo and
  schema.org `JobPosting` each implement one adapter contract; the result of a read is a
  sealed `SourceReadResult`, so every outcome (read, partial, unavailable, forbidden) must be
  handled. Own career pages without a system get a generic reader, kept as is in the MVP.
- **One job, one record.** A *posting* (an ad in one source) is separate from a *vacancy*
  (what the user sees). A new posting joins an open vacancy by a strong key — ad number,
  address without session parameters, provider + host + job id — otherwise by normalized
  title and place at the same employer. A vacancy closes only when all its postings are
  closed; every merge is journalled.
- **A failed read never closes a job.** A temporary failure or a partial read (page limit,
  a failed next page, a sudden drop in the number of jobs, vacancy pages that don't open)
  marks jobs *needs recheck*; a posting closes only after 3 complete reads without it.
- **Profession recognition from exact to approximate.** A job matches a condition if any
  path says so: the employer's ISCO code on the state portal, the profesia.sk position
  (mapped to ISCO, with admin overrides), the admin-maintained position dictionary, or
  embedding similarity of the title to titles with a known code (bge-m3, 5 nearest
  examples, thresholds 0.8 / 0.9). All paths meet in one database view. Every rule is
  measured on real data before it ships: a generative local LLM scored 14 % and was
  dropped; ESCO-label matching scored 55 % and stayed a measurement.
- **Honest coverage as a first-class state.** Each hiring company has a coverage state
  (site not found, career page not found, unreachable, format unsupported, use forbidden…);
  the admin works a loss queue ordered by the number of jobs at stake.
- **Company ≠ Source ≠ Provider.** A source (a board, a career page, a portal employer) is
  linked to a company with a role (employer, group, agency); the provider is only the
  integration type.

## Data pipeline

The worker runs bounded, idempotent task types in three queues — collection (reading
sources, matching, delivery passes), discovery (register intake, site and career-page
scans, the state portal) and name-based site search (multi-day chains) — each with its own
handlers, so slow discovery never delays reading jobs:

| Stage | Tasks (examples) | Responsibility |
|-------|------------------|----------------|
| Companies | `REGISTRY_INTAKE` | import legal entities from the national register (RPO, CC BY 4.0) |
| Sites | `SITE_WIKIDATA`, `SITE_NAME`, `SITE_SCAN` | find and verify a company's site (Wikidata, state portal, name, Common Crawl) |
| Channels | `CAREER_SCAN`, `BOARD_DISCOVERY`, `CHECK_CAREER_URL` | find the careers section or recruiting-system board; check user-added addresses |
| Collection | `PORTAL_LIST`, `PORTAL_RECORD`, `PROFESIA_OFFERS`, `READ_SOURCE` | read the state portal list, profesia.sk ads and every connected source |
| Recognition | `PROFESIA_POSITIONS`, `TITLE_CLASSIFY`, `MATCH_VACANCIES` | profession of each job; match to dictionary positions, country and format |
| Delivery | `RUN_PASS`, `SEND_DIGEST` | hourly passes into each user's accumulated list; the email digest |
| Operations | `COMPUTE_METRICS`, `SNAPSHOT_PURGE` | daily metrics; snapshot retention |

Politeness is part of the pipeline: hourly quotas per external host, one queue per host (a
slow host delays only itself), robots.txt (RFC 9309), a single User-Agent with contact,
every source read at least once a day. Access restrictions and CAPTCHAs are never bypassed.

## Data model & extraction

- **Vacancy and posting:** state (`ACTIVE`, `NEEDS_RECHECK`, `CLOSED`), first seen, last
  confirmed, employer's publication date, closure time; revisions of changed fields.
- **Location and country:** places resolved against GeoNames; a job without a determined
  country still matches; work format `OFFICE / HYBRID / REMOTE / CONFLICT`.
- **Languages:** requirements taken from the text where stated (language, required or not).
- **Profession:** ISCO-08 group from the paths above; group names in English, Slovak,
  German and Russian.
- **Users:** versioned search conditions, a delivered list per condition version, a current
  status per job plus a status history.
- **Raw snapshots** (S3) keep the responses behind each read for 30 days, for debugging and
  re-derivation.

## REST API design

- Versioned paths (`/api/v1/...`); errors as `application/problem+json` (RFC 9457) with
  JSON-Pointer field errors.
- Page-number pagination with totals; optimistic concurrency on search conditions
  (`ETag` / `If-Match`, `412`).
- Session cookie + CSRF token for the SPA; a 401 or 403 sends the SPA to its sign-in screen.

Representative endpoints: `PUT /api/v1/me/search-condition`,
`GET /api/v1/me/vacancies?position=&since=&q=`, `GET /api/v1/me/vacancies/{id}`,
`PUT /api/v1/me/vacancies/{id}/status`, `GET /api/v1/me/responses?group=&q=`,
`GET /api/v1/occupations`, `POST /api/v1/companies/{id}/career-page`,
`GET /api/v1/admin/losses/next`, `PUT /api/v1/admin/profesia-positions/{slug}`.

## Security

- **Session auth:** Spring Security + Spring Session JDBC, USER / ADMIN roles, bcrypt,
  self-service registration, CSRF for the SPA, admin credentials from external config.
- **SSRF control (implemented):** one vetted HTTP client, mandatory by an ArchUnit rule:
  http/https only; private, loopback, link-local, IPv4-mapped IPv6 and metadata addresses
  denied; the actual connection address is checked (DNS rebinding); every redirect
  revalidated; body size, redirect count and timeouts capped. User-entered addresses take
  the same path.
- **XXE control (implemented):** XML feeds parsed without DTD and external entities, with
  depth, size and time limits, through one parser (ArchUnit rule).
- **Privacy:** collection tasks carry no user identifiers; employers' contact persons are
  not stored; one user can't see another's conditions, lists or marks (acceptance test).

## Observability

Implemented: core metrics per day (`core_metric_daily`) and a live core status on the
admin page, coverage metrics, a loss queue with browser notifications, slow lock waits
logged by PostgreSQL. Planned with the observability stage: Prometheus metrics, Grafana
dashboards in the repo, alerts (non-empty DLQ, oldest task age, publish delay) and one
end-to-end trace (scheduler → outbox → RabbitMQ → worker).

## Testing

- **Acceptance scenarios from the business description** (15) are tests in the build:
  delivery of all matching jobs at once, new jobs between passes, condition changes, one job
  in two sources, closure, temporary failure and partial read, the confirmation term,
  restart during processing, matching, ownership, manual addition, *Not interested*,
  privacy, channels and duplicates.
- **Integration (Testcontainers PostgreSQL, RabbitMQ, S3):** outbox delivery, task leases,
  scheduling, recording reads, merging duplicates, the state portal cycle.
- **Adapters:** stub sources on the JDK HTTP server; the career-page reader is checked on 16
  saved real company pages.
- **API:** HTTP-level acceptance tests with a signed-in client (accounts, conditions,
  results and search, statuses, companies, dictionary, admin metrics).
- **Rules in the build:** Checkstyle (at most 5 parameters, naming, JavaDoc) and ArchUnit.
- **Independent audits** every 10 slices: a code audit and a logic-and-usability audit.

## Running locally

```bash
# Prerequisites: JDK 21, Maven, Docker (Ollama with bge-m3 for title recognition)

docker compose up -d                 # PostgreSQL :5433, RabbitMQ, SeaweedFS (S3), backup
mvn -B -ntp verify                   # Checkstyle, compile, all tests, SPA build
mvn -pl job-api spring-boot:run      # http://localhost:8080
mvn -pl job-worker spring-boot:run   # http://localhost:8081
mvn -pl web frontend:npm -Dfrontend.npm.arguments="run dev"   # SPA on :5173, /api proxied
```

## Deployment & CI/CD

Today CI builds and tests every push. Next (server stage): one image per app — `job-worker`
with Chromium and its system libraries for the headless browser — Compose profiles for prod
and dev, secrets from `.env`, then a server and domain. Later: image scanning and SBOM,
publish by commit tag, deploy by digest; expand → migrate → contract migrations keep old and
new workers compatible during rollout; Kubernetes with KEDA (RabbitMQ `QueueLength` scaler),
Helm, Argo CD, Gateway API + Envoy, cert-manager + Sealed Secrets.

## Roadmap

- **Stage 0** — market and source validation *(done)*: access, fields, rate limits, terms.
- **Stage 1** — core *(done)*: two apps, PostgreSQL + RabbitMQ + outbox, adapters,
  closure and history, snapshots, reliability, delivery passes, API.
- **MVP** — on the owner's machine *(done 2026-11-12)*: user interface and statuses, admin
  page, ISCO-08 / ESCO professions, state portal and profesia.sk at scale, one job — one
  record, profession recognition, coverage and loss queue.
- **Move to a server** *(next)*: 14-day continuous run on live sources, container images,
  Compose profiles, mail, server and domain.
- **Kubernetes & observability** — KEDA, Helm, GitOps, metrics, dashboards, alerts, tracing.
- **Delivery & evidence** — full CI (kind, scanning, Argo CD), DB recovery drills,
  experiment reports.
- **Extensions** — company monitoring, change history and salary for the user, skills and
  employer technology profiles, application copies, notes and interview stages, excluding
  recruitment agencies' jobs, more countries, optional LLM enrichment with verification,
  GDPR machinery.

## Engineering highlights

Built production-first rather than prototype-first: decoupled collection and delivery,
reliable messaging with a transactional outbox and leased, idempotent tasks, versioned
schema and data migrations, explainable profession matching with every rule measured before
it ships, SSRF and XXE controls enforced by architecture tests, and acceptance scenarios
from the business description running against real PostgreSQL, RabbitMQ and S3 on every
build.
