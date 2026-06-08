# Architecture

## Services

| Service | Port | Stack | Responsibility |
|---|---|---|---|
| `api-gateway` | 8080 | Spring Cloud Gateway | Single ingress, path routing, CORS |
| `auth-service` | 8081 | Spring Boot + Security + JPA | bcrypt user store, JWT issue/parse, RBAC |
| `agent-orchestrator-service` | 8082 | Spring Boot + WebFlux + (Mongo opt) | **The agent loop**; trace store |
| `screening-tools-service` | 8083 | Spring Boot + JPA | The agent's tools + tool catalog |
| `case-service` | 8084 | Spring Boot + JPA | Cases, audit log, screening policies |

All are modules of one Maven reactor (`backend/pom.xml`), Java 17, Spring Boot 3.2,
Spring Cloud 2023.0.x.

## Request flow — an investigation

```
analyst-console
  └─POST /api/investigations {address}──▶ orchestrator
                                            │ create Investigation(status=RUNNING)
                                            │ run loop:
                                            │   PLAN  llmProvider.nextAction(ctx)
                                            │   ACT   POST /api/tools/{tool} ──▶ screening-tools
                                            │   OBSERVE ctx.record(...)
                                            │   persist step to trace store
                                            │ on FINISH: persist decision
                                            │            mirror case ──▶ case-service (SQL + audit)
  └─GET /api/investigations/{id} (poll)──▶ orchestrator (live steps + decision)
```

The frontend polls `GET /api/investigations/{id}` (~700 ms) and re-renders the timeline,
so the "live agent" effect needs no websockets.

## Data stores (SQL + NoSQL)

- **SQL (Postgres / H2):**
  - `auth-service`: `user_account`
  - `screening-tools-service`: `sanctioned_address`, `transaction_edge`, `tool_status`
  - `case-service`: `case_record`, `audit_log`, `screening_policy`
- **NoSQL (MongoDB):**
  - `agent-orchestrator-service`: `investigations` collection — the variable-length,
    semi-structured step-by-step trace. Chosen for NoSQL because each investigation is a
    nested document whose shape (number of steps, tool observations) varies per run.

**Profiles / zero-infra default.** Every SQL service defaults to in-memory **H2**
(`MODE=PostgreSQL`); activate `SPRING_PROFILES_ACTIVE=postgres` for real Postgres.
The orchestrator defaults to an **in-memory** trace store (Mongo auto-config excluded);
activate `SPRING_PROFILES_ACTIVE=mongo` + `ARGUS_TRACE_STORE=mongo` for real Mongo. This
keeps the demo runnable with `java -jar` and no Docker, while the "real SQL + NoSQL" path
is one flag away.

## Inter-service contracts

- Orchestrator → tools: `POST /api/tools/{sanctions_screen|address_profile|trace_transactions|risk_rules}`,
  JSON in/out (see `screening-tools-service` `ToolDtos`).
- Orchestrator → case: `POST /api/cases` (best-effort mirror; failure logged, not fatal).
- Admin console → tools: `GET/PUT /api/tools/catalog[/{id}]`.
- Admin console → case: `GET/PUT /api/policies`, `GET /api/audit`, `GET /api/cases`.

## Security

- bcrypt (cost 12) password hashing; JWT (HS256) carrying `sub` + `role`.
- `auth-service` enforces RBAC with `@EnableMethodSecurity` + `@PreAuthorize`.
- Secrets (JWT secret, Anthropic key) come from env; nothing is committed (`.env.example`
  documents the variables; `.gitignore` excludes `.env`).
- **Known gap:** the gateway forwards without validating the JWT centrally; per-service
  enforcement is the current line of defence. Centralising at the gateway is the next step.
