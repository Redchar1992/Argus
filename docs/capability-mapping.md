# Capability Mapping — Frontend-Oriented Full Stack Identity & Security

This document maps common identity-product capabilities to **reviewable evidence in this repository**.
Status is intentionally strict: design knowledge is not labelled as an implemented feature.

## Identity/frontend requirements

| Target capability | Concrete evidence | Status |
|---|---|---|
| **React 18 + TypeScript product UI** | `frontend/analyst-console`: React 18, strict TypeScript, accessible labelled login form, protected analyst console, bilingual UI and live investigation timeline | **Built** |
| **Explicit complex-flow state modelling** | `src/auth/authMachine.ts`: discriminated union + reducer for session checking, password/Passkey authentication, MFA verification, authenticated, sign-out, expiry and errors; impossible combinations cannot be represented | **Built + unit tested** |
| **Route/session guard** | `App.tsx` mounts `InvestigatePage` only when a BFF session is authenticated; protected API 401 dispatches the one `SESSION_EXPIRED` transition | **Built + RTL/Playwright tested** |
| **Node.js server-side/BFF work** | `bff`: Node 20 + Fastify service with password/OIDC/MFA/recovery/Passkey routes, Session lifecycle and guarded investigation proxy routes | **BFF built**; no SSR |
| **Session validation** | Opaque 256-bit session ID in an `HttpOnly`, `SameSite=Strict` cookie; JWT and role are server-side; expiry is bounded by upstream JWT; upstream 401 deletes the session | **Built + tested** |
| **API aggregation / error boundary** | BFF coordinates `auth-service` and `agent-orchestrator`, attaches the JWT server-side, adds request IDs/timeouts and exposes one normalized error envelope | **Built for two upstreams**; not a general aggregation platform |
| **Caching judgement** | Identity/BFF responses deliberately send `Cache-Control: no-store`; session data is never browser/CDN cached. Existing investigation polling is preserved | **Security choice built**; no read-through application cache |
| **CSRF and browser security** | Exact Origin allowlist, `Sec-Fetch-Site`, constant-time double-submit CSRF token, session/CSRF rotation, Helmet and production `Secure`-cookie fail-fast | **Built + negative tested** |
| **XSS awareness** | React output escaping; no `dangerouslySetInnerHTML`; JWT is not JS-readable. Architecture documents why HttpOnly does not stop an injected script acting as the user and assigns SPA CSP to the hosting layer | **Code + threat model**; deployment CSP not in repo |
| **Credential-abuse controls** | Uniform invalid-credential response plus configurable per-client login rate limit; Redis mode shares counters across replicas and fails closed on store errors | **Built + two-instance tested** |
| **Authenticated service transport** | Production BFF requires CA-validated mTLS to auth-service; Spring requires the BFF client certificate; Redis requires `rediss://` + ACL auth and supports client-cert mTLS; short-lived local PKI/secure-Redis fixture is executable | **Built + smoke/Redis tested** |
| **Online key rotation** | Versioned BFF envelope key ring, old-key reads, lazy live-Session rewrite, real Redis rolling-rotation test; Java TOTP lazy rewrite plus bounded ADMIN drain for dormant accounts | **Built + tested** |
| **Identity observability** | Region-labelled Node/Java Prometheus metrics, Redis-aware readiness, auth/upstream latency and outcomes, key/decrypt/certificate signals, 11 validated alert rules and response runbook; telemetry explicitly excludes PII and credentials | **Built + tested** |
| **Regional resilience** | Two independent BFF regions, TLS Redis primary/replica, fail-closed shared-store behavior, replica promotion, RTO/RPO evidence and a production-gap runbook | **Local 11-check drill built + CI-wired**; not a production deployment |
| **Resilient UX** | Loading, invalid-login, service-error, expired-session, authenticated and sign-out views; stale protected data is unmounted on auth loss or the server-declared expiry deadline | **Built + tested** |
| **Frontend unit tests** | Vitest + React Testing Library + user-event: reducer, guard, password/MFA/recovery/Passkey flows, deadline expiry and logout | **13 tests passing** |
| **Browser/E2E coverage** | Playwright Chromium: anonymous guard, password login/HttpOnly cookie/no Web Storage token, logout and virtual-authenticator Passkey registration/passwordless login | **4 journeys passing** |
| **Performance fundamentals** | Production build is approximately 189 kB JS / 61 kB gzip including the WebAuthn client; auth boot performs one session check; polling stops on terminal result/unmount | **Measured baseline**; no production RUM/Core Web Vitals yet |
| **Responsive/accessibility fundamentals** | Mobile layout, semantic form labels, button disabled states, alert roles, reduced-motion handling and keyboard-friendly controls | **Built**; no formal WCAG audit |

## Identity protocol coverage

| Protocol/topic | Repository evidence | Honest status |
|---|---|---|
| **JWT** | Spring auth-service issues HS256 JWT; every Java business service validates it; BFF keeps it off the browser | **Built** |
| **Cookie sessions** | Development memory store plus production-required Redis store; AES-256-GCM-encrypted bearer material, opaque cookie, capped TTL and cross-instance restore/logout | **Built + two-instance tested** |
| **OAuth 2.0 / OIDC** | BFF Authorization Code + PKCE with discovery, state and nonce; encrypted one-time transaction store; Java re-verifies JWKS signature, issuer, audience, expiry and nonce and maps only by issuer + subject | **Built + negative tested** |
| **WebAuthn / FIDO2 / Passkey** | Discoverable registration and usernameless authentication; required user verification; exact origin/RP/challenge/signature checks in Node; Java credential inventory plus atomic counter compare-and-update; Redis one-time ceremony state; React management UX | **Built + unit/Redis/Chromium tested** |
| **MFA / step-up** | Encrypted TOTP enrollment, confirmation and disable; password/OIDC primary auth yields an expiry-bounded, attempt-limited challenge; BFF keeps the challenge token server-side; frontend has explicit MFA states | **Built + replay/lockout tested** |
| **Account recovery** | Ten 120-bit offline codes returned once, HMAC-hashed at rest and atomically consumed; usable for MFA fallback or password reset; regeneration requires fresh TOTP | **Built + one-time/reuse tested** |
| **Face/liveness KYC** | Argus has compliance workflows and human-review semantics, but no biometric SDK/model integration | **Not implemented** |

Passkey scope is intentionally precise: registration, inventory, deletion and passwordless
authentication are built. Authenticator attestation trust policy and Passkey-specific recovery
policy remain deployment/product decisions rather than implied features.

## Full-stack and product evidence

| Capability | Concrete evidence | Status |
|---|---|---|
| **Java / Spring microservices** | Five-module Maven reactor: gateway, auth, agent orchestrator, screening tools and case service; Java 17, Spring Boot 3.5, Spring Cloud 2025.0 | **Built + 53 tests** |
| **REST API design** | Typed DTOs/controllers across `/api/auth`, `/api/investigations`, `/api/tools`, `/api/cases`, `/api/policies`, `/api/audit`; BFF exposes a browser-specific contract | **Built** |
| **SQL + NoSQL judgement** | JPA/H2/Postgres for relational identity/policy/case data; Mongo implementation for variable investigation traces; zero-infra memory defaults | **Built** |
| **Production packaging + migrations** | Non-root multi-stage images; isolated Flyway `auth`/`tools`/`cases` schemas; production Hibernate validation; demo-seed suppression; executable reference Compose health graph | **Built + real Postgres/container smoke-tested** |
| **AI/agent workflows** | Real bounded plan → act → observe loop; local tool-selecting provider plus Anthropic tool-use provider; every step persisted for audit | **Built; Anthropic requires a key** |
| **Compliance/security product thinking** | Fail-closed CLEAR decision, deterministic policy bands around probabilistic AI, role-gated operations and case/audit trail | **Centerpiece, built** |
| **Ambiguous end-to-end ownership** | Browser UX → Node security boundary → Java auth/resource servers → data stores → CI/test evidence, with explicit trade-offs and limitations | **Demonstrated** |
| **Remote/async communication** | Architecture, threat model, runbook, test commands and code-to-JD traceability live alongside the code | **Documented** |

## CI evidence

`.github/workflows/ci.yml` uses reproducible lockfile installs:

1. Maven `package` for all backend modules.
2. BFF `npm ci`, dependency audit, TypeScript build and 47 Vitest tests against a Redis 7 CI service.
3. Analyst console `npm ci`, dependency audit, type/build and Vitest/RTL tests.
4. Playwright Chromium install and four browser identity journeys, including virtual WebAuthn.
5. Vue admin-console `npm ci`, dependency audit and build.
6. Disposable local-PKI drill with two BFF regions and a TLS Redis primary/replica, including
   state-store loss, manual promotion, Session RPO and post-failover logout checks.
7. Dependabot security alerts/updates plus weekly grouped maintenance for every npm lockfile,
   Maven and Actions. Spring release-train patches are grouped, while minor train changes stay
   manual so Boot/Cloud compatibility is verified together; the recorded 2026-08-15 baseline
   has zero open alerts.

## Most important review walkthrough

A concise code tour should follow this order:

1. `frontend/analyst-console/src/auth/authMachine.ts` — make invalid UI states impossible.
2. `frontend/analyst-console/src/auth/AuthContext.tsx` — boot/session/expiry transitions.
3. `frontend/analyst-console/src/api/passkeys.ts` — browser WebAuthn handoff without JWT/key storage.
4. `bff/src/passkeys.ts` — RP/challenge/origin/signature verification.
5. `bff/src/app.ts` — login/session/cookie/CSRF/rate-limit and guarded proxy boundary.
6. `bff/src/webauthn-ceremony-store.ts` — encrypted shared one-time ceremony state.
7. `backend/auth-service/.../PasskeyService.java` — credential ownership and counter race defense.
8. `bff/test/redis-integration.test.ts` — two-instance Session/ceremony/rate-limit proof.
9. `frontend/analyst-console/e2e/auth.spec.ts` — browser proof with a virtual authenticator.

## Remaining gaps and the correct production answer

- Deploy the implemented authenticated TLS/Redis path with managed certificate and ACL lifecycle;
  add managed/quorum cross-region failover, global traffic steering, WAN/backup drills and an
  edge/WAF limiter. Encryption-key rotation, availability metrics and the local regional drill exist.
- Add refresh-token rotation/revocation; OIDC code + PKCE and IdP key discovery are built.
- Define authenticator-attestation trust and enterprise enrollment policy where managed-device
  assurance is required; the current `attestation=none` consumer-style flow is deliberate.
- Extend the built TOTP challenge into risk-based step-up and richer factor orchestration.
- Add edge/ingress TLS, restrictive SPA CSP, HSTS and asset integrity/deployment controls.
- Add OpenTelemetry traces, login funnel/RUM/Core Web Vitals and production SLO/error-budget routing;
  Prometheus metrics and 11 alert rules are implemented locally.
- Add a live KYC/liveness provider only behind explicit consent, privacy/retention controls,
  vendor fallback and human review.

The implementation is deliberately the smallest credible evidence slice for this frontend-oriented
identity role: **password/OIDC/MFA/recovery/Passkey + encrypted shared Session + guarded product
page + Node BFF + security controls + test pyramid**, without claiming unbuilt production controls.
