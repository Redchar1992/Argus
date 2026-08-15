# Argus Identity BFF

Same-origin Node/Fastify boundary between the React analyst console and the Java services.
The BFF exchanges a password or an OIDC Authorization Code + PKCE result for an Argus JWT,
keeps that JWT in a server-side session, and returns only an opaque `HttpOnly` cookie.

## Run

```bash
npm ci
npm run dev                 # http://127.0.0.1:3001
```

Defaults expect `auth-service` on `:8081`, the orchestrator on `:8082`, and the browser on
`:5173`. Copy values from `.env.example` into your process environment when changing them;
the service intentionally does not read a committed secret file.

```bash
npm run build
npm test                    # 46 deterministic tests; 4 Redis integration tests skip without a URL
npm start                   # run compiled dist/server.js
```

Run the real shared-store integration suite against an isolated Redis database:

```bash
BFF_TEST_REDIS_URL=redis://127.0.0.1:6379/15 npm test   # 50/50
```

For the complete real-upstream browser demo, run `../scripts/demo-up.sh` from the repository root.
It enables a loopback-only mock IdP for the external account source; the BFF still performs the
real code + PKCE exchange and validates state, nonce and the signed ID Token. HTTP issuers are
accepted only on loopback in development, and production continues to require HTTPS.

## Browser contract

- `GET /bff/auth/session` — bootstrap the CSRF cookie and restore a session.
- `POST /bff/auth/login` — validate Origin + CSRF, call Java auth, create/rotate session.
- `GET /bff/auth/oidc/start` — generate state, nonce and PKCE, then redirect to the provider.
- `GET /bff/auth/oidc/callback` — atomically consume the transaction, validate the provider
  response and create a normal Argus session without exposing either token to JavaScript.
- `GET /bff/auth/mfa/challenge` + `POST /bff/auth/mfa/verify` — restore/complete a
  pre-authentication challenge while its backend token stays encrypted and server-side.
- `/bff/auth/mfa/totp/*` — session-guarded TOTP setup, confirmation and disable proxy.
- `POST /bff/auth/recovery/complete` — rate-limited offline-code password reset; the current
  Session and pre-authentication challenge are cleared after success.
- `/bff/auth/recovery*` — session-guarded remaining-code status and TOTP-gated regeneration.
- `POST /bff/auth/logout` — delete the server session and clear/rotate cookies.
- `POST /bff/api/investigations` and `GET /bff/api/investigations/:id` — guarded proxy;
  Bearer JWT is added only on the BFF-to-Java request.

All `/bff/*` responses are non-cacheable and use a stable `{ error: { code, message },
requestId }` error shape. Login is rate-limited per client.

## Security boundary and limitations

- Session cookie: random opaque ID, `HttpOnly`, `SameSite=Strict`, `Path=/bff`, production
  `Secure` required.
- Mutation defense: exact Origin allowlist + `Sec-Fetch-Site` + double-submit CSRF token.
- Upstream timeout/401: normalized failure; 401 destroys the local session.
- `NODE_ENV=production` refuses insecure cookies and the deterministic mock upstream.
- OIDC transactions are one-time, expiry-bounded and encrypted in Redis; the callback-only
  `HttpOnly`, `SameSite=Lax` cookie binds the browser redirect to its server-side transaction.
- MFA challenges use a separate `HttpOnly`, `SameSite=Strict` opaque cookie. The browser sees
  allowed methods and expiry, never the Java challenge token or an Argus JWT.
- Recovery codes carry 120 bits of random material, are HMAC-hashed at rest, returned once,
  consumed atomically, and work either as an MFA fallback or an offline password-reset proof.
- Redis sessions maintain a hashed per-user index. Successful account recovery removes every
  indexed browser session for that user across BFF replicas, not only the current cookie.

Development defaults to an in-process `Map` and process-local limiter for a zero-infrastructure
demo. `BFF_SESSION_STORE=redis` switches both the Session repository and login limiter to Redis:
replicas share login/logout state, records retain the upstream lifetime cap, and bearer tokens
are encrypted with AES-256-GCM before storage. The random Session ID is authenticated as AAD,
so ciphertext copied to another Redis key fails closed.

Production refuses the memory store and requires `BFF_REDIS_URL` plus a versioned AES-256-GCM key
ring (`BFF_ENCRYPTION_PRIMARY_KEY_ID` + `BFF_ENCRYPTION_KEYS`). Production Redis must use
`rediss://` plus authentication; optional client certificates support mTLS. Redis connection
failure fails startup, while limiter errors fail the login request rather than bypassing the
control. Rolling application-key rotation, metrics/readiness, authenticated BFF→auth transport and
the local regional failure drill are implemented. A real deployment still needs managed
certificate/secret lifecycles, TLS ingress and a restrictive CSP on the separately hosted SPA
document.

`BFF_MOCK_UPSTREAM=true` exists only for deterministic Playwright/local UI runs. It is not an
authentication bypass available in production—the configuration loader refuses that combination.
