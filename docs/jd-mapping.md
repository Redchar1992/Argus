# JD Mapping — Binance Full Stack Engineer (Frontend Oriented), Identity & Security

This document maps the target role to **reviewable evidence in this repository**. Status is
intentionally strict: interview design knowledge is not labelled as an implemented feature.

## Identity/frontend requirements

| Target capability | Concrete evidence | Status |
|---|---|---|
| **React 18 + TypeScript product UI** | `frontend/analyst-console`: React 18, strict TypeScript, accessible labelled login form, protected analyst console, bilingual UI and live investigation timeline | **Built** |
| **Explicit complex-flow state modelling** | `src/auth/authMachine.ts`: discriminated union + reducer for `checking`, `anonymous`, `authenticating`, `authenticated`, `signingOut`, `expired`, `error`; impossible combinations cannot be represented | **Built + unit tested** |
| **Route/session guard** | `App.tsx` mounts `InvestigatePage` only when a BFF session is authenticated; protected API 401 dispatches the one `SESSION_EXPIRED` transition | **Built + RTL/Playwright tested** |
| **Node.js server-side/BFF work** | `bff`: Node 20 + Fastify service with `/bff/auth/login`, `/session`, `/logout` and guarded investigation proxy routes | **BFF built**; no SSR |
| **Session validation** | Opaque 256-bit session ID in an `HttpOnly`, `SameSite=Strict` cookie; JWT and role are server-side; expiry is bounded by upstream JWT; upstream 401 deletes the session | **Built + tested** |
| **API aggregation / error boundary** | BFF coordinates `auth-service` and `agent-orchestrator`, attaches the JWT server-side, adds request IDs/timeouts and exposes one normalized error envelope | **Built for two upstreams**; not a general aggregation platform |
| **Caching judgement** | Identity/BFF responses deliberately send `Cache-Control: no-store`; session data is never browser/CDN cached. Existing investigation polling is preserved | **Security choice built**; no read-through application cache |
| **CSRF and browser security** | Exact Origin allowlist, `Sec-Fetch-Site`, constant-time double-submit CSRF token, session/CSRF rotation, Helmet and production `Secure`-cookie fail-fast | **Built + negative tested** |
| **XSS awareness** | React output escaping; no `dangerouslySetInnerHTML`; JWT is not JS-readable. Architecture documents why HttpOnly does not stop an injected script acting as the user and assigns SPA CSP to the hosting layer | **Code + threat model**; deployment CSP not in repo |
| **Credential-abuse controls** | Uniform invalid-credential response plus configurable per-client login rate limit; Redis mode shares counters across replicas and fails closed on store errors | **Built + two-instance tested** |
| **Resilient UX** | Loading, invalid-login, service-error, expired-session, authenticated and sign-out views; stale protected data is unmounted on auth loss or the server-declared expiry deadline | **Built + tested** |
| **Frontend unit tests** | Vitest + React Testing Library + user-event: reducer lifecycle, guard, login success/failure, deadline expiry and logout | **7 tests passing** |
| **Browser/E2E coverage** | Playwright Chromium: anonymous guard, successful login/HttpOnly cookie/no Web Storage token, logout/cookie deletion | **3 journeys passing** |
| **Performance fundamentals** | Production build is approximately 169 kB JS / 56 kB gzip; auth boot performs one session check; no large UI framework; polling stops on terminal result/unmount | **Measured baseline**; no production RUM/Core Web Vitals yet |
| **Responsive/accessibility fundamentals** | Mobile layout, semantic form labels, button disabled states, alert roles, reduced-motion handling and keyboard-friendly controls | **Built**; no formal WCAG audit |

## Identity protocol coverage

| Protocol/topic | Repository evidence | Honest status |
|---|---|---|
| **JWT** | Spring auth-service issues HS256 JWT; every Java business service validates it; BFF keeps it off the browser | **Built** |
| **Cookie sessions** | Development memory store plus production-required Redis store; AES-256-GCM-encrypted bearer material, opaque cookie, capped TTL and cross-instance restore/logout | **Built + two-instance tested** |
| **OAuth 2.0 / OIDC** | Covered in `docs/interview-prep/03-oauth-oidc-jwt-webauthn.md` and system-design material | **Understood/designed, not implemented** |
| **WebAuthn / FIDO2 / Passkey** | Registration/authentication/recovery design in interview-prep docs | **Not implemented** |
| **MFA / step-up / recovery** | Product/state/service design in `docs/interview-prep/04-binance-login-mfa-system-design.md` | **Not implemented** |
| **Face/liveness KYC** | Argus has compliance workflows and human-review semantics, but no biometric SDK/model integration | **Not implemented** |

No screen or API pretends that MFA, Passkey or OIDC already works. This avoids turning a
portfolio feature into a misleading security claim.

## Full-stack and product evidence

| Capability | Concrete evidence | Status |
|---|---|---|
| **Java / Spring microservices** | Five-module Maven reactor: gateway, auth, agent orchestrator, screening tools and case service; Java 17, Spring Boot 3.2, Spring Cloud | **Built + tested** |
| **REST API design** | Typed DTOs/controllers across `/api/auth`, `/api/investigations`, `/api/tools`, `/api/cases`, `/api/policies`, `/api/audit`; BFF exposes a browser-specific contract | **Built** |
| **SQL + NoSQL judgement** | JPA/H2/Postgres for relational identity/policy/case data; Mongo implementation for variable investigation traces; zero-infra memory defaults | **Built** |
| **AI/agent workflows** | Real bounded plan → act → observe loop; local tool-selecting provider plus Anthropic tool-use provider; every step persisted for audit | **Built; Anthropic requires a key** |
| **Compliance/security product thinking** | Fail-closed CLEAR decision, deterministic policy bands around probabilistic AI, role-gated operations and case/audit trail | **Centerpiece, built** |
| **Ambiguous end-to-end ownership** | Browser UX → Node security boundary → Java auth/resource servers → data stores → CI/test evidence, with explicit trade-offs and limitations | **Demonstrated** |
| **Remote/async communication** | Architecture, threat model, runbook, test commands and code-to-JD traceability live alongside the code | **Documented** |

## CI evidence

`.github/workflows/ci.yml` uses reproducible lockfile installs:

1. Maven `package` for all backend modules.
2. BFF `npm ci`, dependency audit, TypeScript build and 20 Vitest tests against a Redis 7 CI service.
3. Analyst console `npm ci`, dependency audit, type/build and Vitest/RTL tests.
4. Playwright Chromium install and three browser identity journeys.
5. Vue admin-console `npm ci`, dependency audit and build.

## Most important interview walkthrough

A concise code tour should follow this order:

1. `frontend/analyst-console/src/auth/authMachine.ts` — make invalid UI states impossible.
2. `frontend/analyst-console/src/auth/AuthContext.tsx` — boot/session/expiry transitions.
3. `frontend/analyst-console/src/api/bff.ts` — same-origin credentials and CSRF header; no JWT.
4. `bff/src/app.ts` — login/session/cookie/CSRF/rate-limit and guarded proxy boundary.
5. `bff/src/redis-session-store.ts` — encrypted shared JWT storage and bounded TTL.
6. `bff/test/redis-integration.test.ts` — two-instance Session/logout/rate-limit proof.
7. `bff/test/app.test.ts` — adversarial/expiry/upstream evidence.
8. `frontend/analyst-console/e2e/auth.spec.ts` — browser proof of the contract.
9. `docs/architecture.md` — production trade-offs and the MFA/Passkey extension seam.

## Remaining gaps and the correct production answer

- Deploy the implemented Redis path on a private authenticated TLS endpoint; add encryption-key
  rotation, eviction/availability metrics, regional outage drills and an edge/WAF limiter.
- Add real OIDC Authorization Code + PKCE, token rotation/revocation and IdP key discovery.
- Add WebAuthn/passkey registration, authentication, credential inventory and recovery; never
  ship a cosmetic mock.
- Add risk-based step-up, MFA factor orchestration and resumable/replay-safe multi-step flows.
- Add edge/ingress TLS, restrictive SPA CSP, HSTS and asset integrity/deployment controls.
- Add OpenTelemetry traces, login funnel/RUM/Core Web Vitals, SLOs and alerting.
- Add a live KYC/liveness provider only behind explicit consent, privacy/retention controls,
  vendor fallback and human review.

The implementation is deliberately the smallest credible evidence slice for this frontend-oriented
identity role: **real login + encrypted shared Session + guarded product page + Node BFF +
security controls + test pyramid**, without claiming unbuilt authentication factors.
