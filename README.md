# Argus — Agentic Crypto-Compliance: Transaction Monitoring, Sanctions Screening & Case Management

> Named after the hundred-eyed guardian. Give it a wallet and an AI **agent** runs the
> compliance investigation end-to-end — **sanctions screening**, **transaction-graph tracing
> (transaction monitoring)**, an AML **risk-rule** engine, and a **CLEAR / REVIEW / BLOCK**
> decision — with every reasoning step and tool call persisted to an **audit trail** and the
> completed case mirrored into **case management**.
>
> The design principle is the one crypto compliance-engineering roles keep asking for: a
> **probabilistic AI agent kept behind deterministic, fail-closed compliance logic**. The agent
> *plans* the investigation, but a wallet is never silently CLEARed and the BLOCK/REVIEW bands
> come from admin-editable policy — not from the model.

Portfolio project — **Java 17 · Spring Cloud · React + Vue**. The README is deliberately
**honest**: it claims only what is built and runs. [`docs/jd-mapping.md`](docs/jd-mapping.md)
maps each JD requirement to code; a pluggable third-party-screening design (real OFAC SDN +
Chainalysis/TRM/Elliptic adapters) is in [`docs/wallet-screening-providers.md`](docs/wallet-screening-providers.md);
and the "What's real vs scaffolded" section below draws the line precisely.

---

## The centerpiece: a real agentic loop

The differentiator is a genuine **plan → act → observe** loop (not a canned script):

```
loop (bounded by maxSteps):
  action     = llmProvider.nextAction(context)   # PLAN  — ask the brain what to do next
  if FINISH:   record decision; break            #          brain decides it has enough
  observation = toolClient.invoke(action.tool)   # ACT   — run the chosen tool over REST
  context.record(tool, args, observation)        # OBSERVE— feed the result back in
  persist(step)                                  #          every step is auditable
```

The "brain" is a swappable `LlmProvider`:

- **`local`** (default, no API key): a real **rule-based tool-selecting loop**. On each
  turn it inspects everything observed so far and chooses the next sensible action.
  It is **not** a single canned string — different wallets take genuinely different
  paths (e.g. a tiny clean wallet *skips* graph tracing; a sanctioned wallet runs the
  full chain). See [`docs/agent-design.md`](docs/agent-design.md).
- **`anthropic`**: real Claude **tool-use** via the Messages API (correct
  request/response shape, tools rendered as `input_schema`, API key from env). The same
  orchestrator loop drives it — only the brain swaps.

Demo wallets (seeded) produce four distinct outcomes, verified end-to-end:

| Wallet (prefix) | Agent path | Decision |
|---|---|---|
| `0xbadc0de…` | screen → profile → trace → rules → finish | **BLOCK** (score 60, directly sanctioned) |
| `0xc0ffee…`  | screen → profile → trace → rules → finish | **REVIEW** (1-hop mixer exposure, score 35) |
| `0xdeadbeef…`| screen → profile → trace → rules → finish | **REVIEW** (2-hop exposure + structuring, 35) |
| `0xc1ean…`   | screen → profile → rules → finish (**skips trace**) | **CLEAR** (score 0) |

---

## Architecture (one screen)

```
analyst-console (React/AntD)        admin-console (Vue/Element Plus)
        │                                   │
        ▼                                   ▼
                     api-gateway (Spring Cloud Gateway, :8080)
        │                │                  │                │
        ▼                ▼                  ▼                ▼
   auth-service   agent-orchestrator   screening-tools    case-service
   (:8081)        (:8082) ◀── tools ──▶ (:8083)           (:8084)
   JWT issue +    THE AGENT LOOP        sanctions_screen   cases + audit
   RBAC, bcrypt   trace store (NoSQL)   trace_transactions + policies (SQL)
                  ── mirror case ─────▶ risk_rules
   (every business service below is also JWT-validated + @PreAuthorize role-gated)
```

Full detail: [`docs/architecture.md`](docs/architecture.md).

---

## Run it

### Backend (Java 17, Spring Boot 3.2)

```bash
cd backend
mvn -q -DskipTests package      # build all 5 modules
mvn -q test                     # 10 tests (auth, tools, agent loop)
```

Run the two services needed for an agent demo (they default to in-memory stores —
**no Docker required**):

```bash
java -jar screening-tools-service/target/screening-tools-service-0.1.0.jar   # :8083
java -jar agent-orchestrator-service/target/agent-orchestrator-service-0.1.0.jar  # :8082
```

Drive an investigation (synchronous so the result is inline):

```bash
curl -s -X POST http://localhost:8082/api/investigations \
  -H 'Content-Type: application/json' \
  -d '{"address":"0xc0ffee00000000000000000000000000000c0ffee","runSync":true}'
# -> {"investigationId":"inv_...","status":"RUNNING"}

curl -s http://localhost:8082/api/investigations/inv_... | jq .
# -> full step-by-step trace + final decision
```

Optionally run `auth-service` (:8081) and `case-service` (:8084) too. With all four
up, the orchestrator mirrors each completed case into case-service.

### Frontends

```bash
cd frontend/analyst-console && npm install && npm run dev   # http://localhost:5173
cd frontend/admin-console   && npm install && npm run dev   # http://localhost:5174
```

The analyst console submits a wallet and renders the **live** agent timeline; the
admin console manages tools/policies and views the audit log + cases.

### Real databases + Anthropic (optional)

```bash
docker compose up -d postgres mongo redis
cp .env.example .env   # then edit
# run services with: SPRING_PROFILES_ACTIVE=postgres (auth/tools/case)
#                    SPRING_PROFILES_ACTIVE=mongo + ARGUS_TRACE_STORE=mongo (orchestrator)
# for real LLM:      ARGUS_LLM_PROVIDER=anthropic ARGUS_ANTHROPIC_API_KEY=sk-...
```

### Demo credentials (auth-service, real bcrypt hashing)

| Username | Password | Role |
|---|---|---|
| `admin` | `admin12345` | ADMIN |
| `analyst` | `analyst12345` | ANALYST |

These are seeded for local demo only and are hashed with bcrypt (cost 12) at boot.

---

## What's real vs scaffolded (honest)

**Real / working:**
- The agentic plan-act-observe loop with the **local** provider — end-to-end, persisted,
  verified by tests and live curl. Different wallets → different tool paths → different
  decisions.
- The **Anthropic** provider: correct Messages API tool-use shape, conversation replay,
  structured `finish_investigation` tool. (Compiles + wired; exercised only when a key
  is supplied — not run in CI.)
- Three+ real tools over REST: `sanctions_screen`, `trace_transactions` (a real BFS over
  the seeded graph with path reconstruction), `address_profile`, `risk_rules` (transparent
  points-based AML rules).
- Auth + per-service RBAC: bcrypt user store, JWT issue/parse, and **every** business
  service (orchestrator, screening-tools, case) is an OAuth2 resource server that validates
  the shared-secret JWT and enforces `@PreAuthorize` role gating — real, tested. Self-service
  registration is fixed at the lowest privilege; role elevation is an admin-only endpoint.
- **Deterministic compliance logic around a probabilistic agent** (fail-closed decisioning):
  a wallet is only CLEARED when the required tools (`sanctions_screen` + `risk_rules`) produced
  valid evidence; missing/failed evidence escalates to REVIEW (never a silent CLEAR). The
  admin-editable `screening_policy` thresholds — not the model — drive the BLOCK/REVIEW bands.
  This is the "boundary between probabilistic AI and deterministic compliance logic" made concrete.
- Persistence: cases + audit + policies in JPA (SQL); investigation traces in a store
  with a real MongoDB implementation (NoSQL) and an in-memory default for zero-infra demos.
- Both frontends build and render the real API shapes.

**Scaffolded / simplified / TODO (called out so nothing is oversold):**
- The gateway does **not** validate JWTs centrally — it is routing + CORS only. Enforcement
  is **per-service**: each service is an OAuth2 resource server validating the same
  shared-secret JWT and applying `@PreAuthorize`. (A JWT filter at the gateway would add
  defence-in-depth but is not the enforcement boundary — the services are.)
- Service-to-service calls (orchestrator → screening-tools / case) propagate the **caller's**
  bearer token, so internal calls are authorised as the originating analyst/admin. A dedicated
  service credential would be a cleaner production design; token propagation is the current,
  working approach.
- The on-chain data is **seeded/synthetic**, not a live chain indexer. The graph and
  sanctions list are illustrative fixtures (no real OFAC addresses).
- **Redis** is provisioned in compose but not yet used by application code (documented in
  `infra/redis/README.md`).
- No Dockerfiles for the services yet (compose covers the DBs); services run via `java -jar`.

See [`docs/agent-design.md`](docs/agent-design.md) for the prompt, tool schema, and the
local-vs-LLM tradeoff in detail.
