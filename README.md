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

Portfolio project — **React 18 + TypeScript · Node/Fastify BFF · Java 17/Spring Cloud · Vue**.
The analyst console now has real password, OIDC Authorization Code + PKCE, TOTP/recovery and
phishing-resistant Passkey identity flows: the browser receives only an
opaque `HttpOnly` session cookie while the upstream JWT stays server-side in the BFF. The
README is deliberately
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
Browser: analyst-console (React 18 + TypeScript, :5173)
        │ same-origin /bff/* + opaque cookies
        ▼
identity-bff (Node 20 + Fastify, :3001)
        │ server-side session → attaches Bearer JWT (never returned to browser)
        ├── password/OIDC/WebAuthn ─────────────▶ auth-service (:8081)
        └── /api/investigations/* ─────────────▶ agent-orchestrator (:8082)
                                                      │
                                                      ├── tools ──▶ screening-tools (:8083)
                                                      └── case ───▶ case-service (:8084)

admin-console (Vue, :5174) ──▶ api-gateway (Spring Cloud Gateway, :8080)

Every Java business service validates the JWT and enforces @PreAuthorize role gates.
```

Full detail: [`docs/architecture.md`](docs/architecture.md).

---

## Run it

### Backend (Java 17, Spring Boot 3.2)

```bash
cd backend
mvn -q -DskipTests package      # build all 5 modules
mvn -q test                     # 52 tests (identity/RBAC, tools, agent loop, security)
```

Run the three Java services needed for the authenticated analyst demo (they default to in-memory stores —
**no Docker required**):

```bash
java -jar auth-service/target/auth-service-0.1.0.jar                         # :8081
java -jar screening-tools-service/target/screening-tools-service-0.1.0.jar   # :8083
java -jar agent-orchestrator-service/target/agent-orchestrator-service-0.1.0.jar  # :8082
```

The Java APIs remain independently secured. To drive them directly, first mint a development
JWT (the browser application never does this—it uses the BFF):

```bash
TOKEN=$(curl -s -X POST http://localhost:8081/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"analyst","password":"analyst12345"}' | jq -r .token)

curl -s -X POST http://localhost:8082/api/investigations \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"address":"0xc0ffee00000000000000000000000000000c0ffee","runSync":true}'
# -> {"investigationId":"inv_...","status":"RUNNING"}

curl -s http://localhost:8082/api/investigations/inv_... \
  -H "Authorization: Bearer $TOKEN" | jq .
# -> full step-by-step trace + final decision
```

Optionally run `case-service` (:8084) too. With all four business services up, the
orchestrator mirrors each completed case into case-service.

### Protected analyst console (Node BFF + React)

```bash
# terminal 1 — same-origin BFF; connects to auth-service and orchestrator above
cd bff && npm ci && npm run dev                              # http://localhost:3001

# terminal 2 — Vite proxies /bff to :3001
cd frontend/analyst-console && npm ci && npm run dev         # http://localhost:5173
```

Open `http://localhost:5173` and sign in with one of the local accounts below. The
investigation console is not rendered until `GET /bff/auth/session` succeeds. No token is
written to `localStorage`, `sessionStorage`, JavaScript state, or a build-time `VITE_*` variable.

Development uses the zero-infrastructure memory Session store. To exercise the implemented
multi-instance path, start Redis and configure a shared encrypted store:

```bash
docker compose up -d redis
export BFF_SESSION_STORE=redis
export BFF_REDIS_URL=redis://127.0.0.1:6379
export BFF_ENCRYPTION_PRIMARY_KEY_ID=local-v1
export BFF_ENCRYPTION_KEYS="local-v1:$(openssl rand -base64 32)"
cd bff && npm run dev
```

To exercise authenticated transport locally, generate a short-lived development CA, start
the opt-in Redis TLS/ACL/mTLS fixture and source the generated environment values:

```bash
./infra/tls/generate-dev-pki.sh
docker compose --profile security up -d redis-secure
set -a; source infra/tls/generated/.env.mtls; set +a
export BFF_SESSION_STORE=redis
# Start auth-service with the sourced PKCS12 settings, then start the BFF.
```

The auth service requires a trusted BFF client certificate; Redis requires both TLS client
authentication and an ACL password. Generated keys live under an ignored directory and expire
after 14 days. Plain HTTP/passwordless Redis remain development-only conveniences.

The admin console remains separately runnable:

```bash
cd frontend/admin-console && npm ci && npm run dev           # http://localhost:5174
```

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

### Verification

```bash
cd bff
npm ci && npm run build && npm test                   # 42 pass; 4 real-Redis tests skip
BFF_TEST_REDIS_URL=redis://127.0.0.1:6379/15 npm test  # 46/46 incl. replicas + key rotation

cd ../frontend/analyst-console
npm ci && npm run build && npm run test:unit        # 13 reducer/RTL lifecycle tests
npx playwright install chromium                     # once per machine
npm run test:e2e                                    # 4 journeys incl. a virtual WebAuthn authenticator

cd ../admin-console
npm ci && npm audit && npm run build                # 0 vulnerabilities; Vue/Vite build
```

CI uses `npm ci`, runs the BFF suite against a Redis 7 service, runs both frontend test
layers, and installs Chromium for four Playwright journeys. Playwright starts the BFF
with its explicit test-only deterministic upstream; production startup refuses mock mode,
insecure cookies and the memory Session store.

### Identity monitoring

The BFF exports token-protected Prometheus metrics at `/metrics`, liveness at `/health` and
Redis-aware readiness at `/ready`. Auth-service exposes Micrometer metrics at
`/actuator/prometheus` plus liveness/readiness probes; production reaches that listener only
through mTLS. Metrics use bounded route/flow/outcome/region labels and never user, Session,
credential or token values. To run the local Prometheus fixture:

```bash
docker compose --profile monitoring up -d prometheus
open http://localhost:9090
```

The fixture includes 11 alert rules for target/dependency outages, authentication error ratio,
rejection spikes, upstream p95 latency, encrypted-record failures and certificate expiry. See
[`docs/runbooks/identity-monitoring.md`](docs/runbooks/identity-monitoring.md) for production
scrape controls and triage.

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
- **Browser-safe identity BFF:** a real Fastify service calls `POST /api/auth/login`, stores
  the returned JWT only in a server-side session, rotates a 256-bit opaque session ID, and
  gives the browser an `HttpOnly` + `SameSite=Strict` cookie. Protected investigation routes
  are session-guarded and attach the Bearer token only on the server-to-server hop.
- **CSRF/session hardening:** exact Origin allowlist + `Sec-Fetch-Site` check + double-submit
  CSRF token on mutations, login rate limiting, upstream timeouts, normalized errors,
  `Cache-Control: no-store`, Helmet headers, session invalidation when an upstream returns
  401, and fail-fast production configuration for insecure cookies or mock mode.
- **Shared encrypted Session path:** `BFF_SESSION_STORE=redis` shares Session restore, logout
  and login-rate-limit state across BFF replicas. Bearer tokens are encrypted with AES-256-GCM
  before Redis storage, bound to the opaque Session ID as authenticated data and lifetime-capped.
  Redis outage fails startup/login closed. Versioned AES-GCM key rings write only with the
  primary key, retain old keys for reads and lazily re-encrypt live Sessions. The real
  two-instance and rolling-key-rotation integration tests run in CI.
- **Authenticated internal transport:** production BFF startup requires HTTPS mTLS to
  `auth-service` (private CA verification plus BFF client certificate). Production Redis
  requires `rediss://` and ACL authentication; optional Redis client certificates support
  mTLS. Private-key permission checks, TLS 1.2+ and an executable short-lived local PKI fixture
  make the contract testable instead of documentation-only.
- **Online identity-key rotation:** BFF Session/OIDC/MFA/WebAuthn envelopes carry a key ID;
  a rolling deployment can add, promote and later retire a key without signing out active
  users. Java TOTP envelopes rotate after successful use or through a bounded ADMIN drain
  endpoint, so dormant accounts do not pin retired keys forever. See
  [`docs/runbooks/identity-key-rotation.md`](docs/runbooks/identity-key-rotation.md).
- **Identity observability:** Node and Java export auth outcomes, route/upstream latency,
  dependency state, key migration and workload-certificate expiry with region labels but no PII.
  Separate liveness/readiness endpoints, a pinned Prometheus fixture and executable alert rules
  cover outage, latency, decrypt-failure and expiry signals.
- **Explicit frontend state model:** a TypeScript discriminated union/reducer models
  `checking → anonymous → authenticating/authenticating_passkey → authenticated → signingOut/expired/error`.
  The route guard never renders investigation data for an anonymous/expired state, and a
  client-side deadline unmounts protected data at the server-declared Session expiry.
- **Passkey/WebAuthn:** authenticated users can register, list and remove discoverable
  credentials; passwordless login requires user verification. The Node BFF verifies origin,
  RP ID, challenge and signature, Java persists the COSE public key and atomically advances
  authenticator counters, and the browser receives neither key material nor the issued JWT.
  Encrypted, one-time ceremonies work across Redis-backed BFF replicas.
- **Identity test pyramid:** Fastify injection tests cover cookies, CSRF, expiry, upstream
  timeout/401, rate limiting and Passkey replay; Vitest/RTL covers reducer/login/guard/logout
  and WebAuthn UX; Playwright uses a Chromium virtual authenticator and asserts the session
  cookie is `HttpOnly`.
- **Deterministic compliance logic around a probabilistic agent** (fail-closed decisioning):
  a wallet is only CLEARED when the required tools (`sanctions_screen` + `risk_rules`) produced
  valid evidence; missing/failed evidence escalates to REVIEW (never a silent CLEAR). The
  admin-editable `screening_policy` thresholds — not the model — drive the BLOCK/REVIEW bands.
  This is the "boundary between probabilistic AI and deterministic compliance logic" made concrete.
- Persistence: cases + audit + policies in JPA (SQL); investigation traces in a store
  with a real MongoDB implementation (NoSQL) and an in-memory default for zero-infra demos.
- Both frontends build and render the real API shapes.

**Scaffolded / simplified / TODO (called out so nothing is oversold):**
- Memory Session/rate-limit state remains the convenient development default. Production now
  refuses it and requires the implemented authenticated `rediss://` path. Online encryption-key
  rotation and live dependency/availability metrics are implemented, while a real deployment
  still needs managed certificate/ACL lifecycle, backup policy and a tested regional outage strategy.
- OIDC Authorization Code + PKCE, TOTP MFA, one-time offline recovery codes and Passkeys are implemented,
  including encrypted server-side pre-authentication state, replay counters, attempt lockout,
  MFA fallback, password reset and discoverable passwordless credentials. Refresh-token
  rotation and provider-specific account linking remain unbuilt.
- Browser HTTPS is expected to terminate at the deployment ingress; production mode refuses a
  non-`Secure` session cookie. BFF→auth-service mTLS is implemented independently. Helmet
  protects the JSON BFF responses, while the SPA document's CSP/HSTS/asset-integrity policy
  must be configured by the CDN or web server that hosts it.
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
- No Dockerfiles for the services yet (compose covers the DBs); services run via `java -jar`.

See [`docs/agent-design.md`](docs/agent-design.md) for the prompt, tool schema, and the
local-vs-LLM tradeoff in detail.
