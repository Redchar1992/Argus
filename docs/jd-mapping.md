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
| **Auth / RBAC** | bcrypt (cost 12) user store, JWT (HS256) with role claim, `@PreAuthorize` role-gated endpoint, `JwtAuthFilter`. Tested in `AuthServiceApplicationTests`. | Built + tested |
| **CI** | `.github/workflows/ci.yml` — builds the backend (`mvn package`, runs tests) and both frontends (`npm run build`). | Built |
| **Containerised infra** | `docker-compose.yml` — Postgres + Mongo + Redis with seed mounts and healthchecks. | Built (DBs; service Dockerfiles are a TODO) |
| **Auditability** (compliance-critical) | Every agent step persisted; `audit_log` append-only table; admin console audit view. | Built |

## Honest gaps (also in the README)

- Gateway does routing + CORS only; it does **not** centrally validate JWTs yet (per-service
  RBAC is the current enforcement).
- On-chain data is **seeded/synthetic** fixtures, not a live chain indexer; addresses are
  illustrative (no real OFAC entries).
- Redis is provisioned but not yet used by application code.
- Editable `screening_policy` thresholds are surfaced in the admin UI but the local agent
  uses fixed 60/30 cutoffs rather than reading them live.
- No per-service Dockerfiles yet (compose covers the databases).

These are deliberately listed so reviewers see exactly the boundary between what runs and
what is scaffolded — the project does not oversell.
