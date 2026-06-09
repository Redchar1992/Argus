# JD Mapping

How each requirement from the Binance "Tech Compliance — Full Stack Engineer" JD maps to
concrete code in this repo. Kept honest: where something is partial, it says so.

| JD requirement | Where it's demonstrated | Status |
|---|---|---|
| **React frontend** | `frontend/analyst-console` — React 18 + Vite + TS + Ant Design. Submits a wallet, renders the **live** agent reasoning + tool-call timeline and the final decision. | Built, builds green |
| **Vue frontend** | `frontend/admin-console` — Vue 3 + Vite + TS + Element Plus. Manage screening policies, enable/disable tools, view audit log + cases. | Built, builds green |
| **Java / Spring Cloud microservices** | `backend/` Maven reactor: `api-gateway` (Spring Cloud Gateway), `auth-service`, `agent-orchestrator-service`, `screening-tools-service`, `case-service`. Java 17, Spring Boot 3.2, Spring Cloud 2023.0.x. | Built, builds + tests green |
| **RESTful APIs** | Controllers across all services (`/api/auth`, `/api/investigations`, `/api/tools`, `/api/cases`, `/api/policies`, `/api/audit`). DTOs as records; constructor injection; clean controller/service/dto/config layering. | Built |
| **Integration with AI model APIs** | `agent-orchestrator-service/.../llm/AnthropicLlmProvider.java` — real Anthropic Messages API tool-use (correct request/response shape, `input_schema` tools, env-supplied key, conversation replay). | Built + wired; runs when a key is supplied |
| **"Agentic flows … from scratch"** | `AgentOrchestrator` — a real bounded plan→act→observe loop with persisted, auditable steps. Two `LlmProvider` impls (local rule agent + Anthropic) behind `@ConditionalOnProperty`. Verified end-to-end. | **Centerpiece**, built + tested |
| **Compliance systems from scratch** | `screening-tools-service` tools: `sanctions_screen`, `trace_transactions` (real BFS over a tx graph), `address_profile`, `risk_rules` (transparent AML rule engine). Decisions are CLEAR/REVIEW/BLOCK with risk factors. | Built + tested |
| **SQL** | JPA entities in auth/tools/case services (Postgres in prod profile, H2 default). `infra/postgres/*.sql` documents the canonical schema + seed. | Built |
| **NoSQL** | `agent-orchestrator-service` persists investigation traces in **MongoDB** (`investigations` collection) via `MongoInvestigationStore`; in-memory default for zero-infra demos. `infra/mongo/01-init.js`. | Built (Mongo impl + memory default) |
| **Auth / RBAC** | bcrypt (cost 12) user store, JWT (HS256) with role claim. RBAC is enforced **per service**: auth-service plus orchestrator, screening-tools and case-service are all OAuth2 resource servers validating the same shared-secret JWT with `@EnableMethodSecurity` + `@PreAuthorize`. Self-registration is fixed at the lowest privilege; role elevation is an admin-only endpoint. Tested per service (401/200/403 with real signed tokens). | Built + tested |
| **CI** | `.github/workflows/ci.yml` — builds the backend (`mvn package`, runs tests) and both frontends (`npm run build`). | Built |
| **Containerised infra** | `docker-compose.yml` — Postgres + Mongo + Redis with seed mounts and healthchecks. | Built (DBs; service Dockerfiles are a TODO) |
| **Auditability** (compliance-critical) | Every agent step persisted; `audit_log` append-only table; admin console audit view. | Built |

## Honest gaps (also in the README)

- Gateway does routing + CORS only; it does **not** centrally validate JWTs. Enforcement is
  **per-service** (each business service validates the JWT and applies `@PreAuthorize`) —
  this is the real enforcement boundary, not a placeholder. A gateway-level JWT filter would
  add defence-in-depth.
- Internal service-to-service calls propagate the **caller's** bearer token (authorised as the
  originating analyst/admin). A dedicated machine/service credential would be a cleaner
  production design.
- On-chain data is **seeded/synthetic** fixtures, not a live chain indexer; addresses are
  illustrative (no real OFAC entries).
- Redis is provisioned but not yet used by application code.
- No per-service Dockerfiles yet (compose covers the databases).

## Compliance-correctness notes

- **Fail-closed decisioning.** A wallet is CLEARED only when both required tools
  (`sanctions_screen`, `risk_rules`) returned valid observations. If a required tool is
  missing, errored, or disabled, the agent escalates to REVIEW and names the missing
  evidence — it never silently CLEARs on incomplete evidence. The Anthropic provider gets
  the same guarantee via a system-prompt instruction plus a deterministic override.
- **Policy drives the agent.** The admin-editable `screening_policy` block/review thresholds
  are read by the orchestrator (`PolicyClient`) and used for the BLOCK/REVIEW/CLEAR bands, so
  editing the policy in the admin console actually changes agent behaviour (no hardcoded cutoffs).

These are deliberately listed so reviewers see exactly the boundary between what runs and
what is scaffolded — the project does not oversell.
