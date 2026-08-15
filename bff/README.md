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
npm test                    # 28 deterministic tests; Redis integration skips without its URL
npm start                   # run compiled dist/server.js
```

Run the real shared-store integration suite against an isolated Redis database:

```bash
BFF_TEST_REDIS_URL=redis://127.0.0.1:6379/15 npm test   # 29/29
```

## Browser contract

- `GET /bff/auth/session` — bootstrap the CSRF cookie and restore a session.
- `POST /bff/auth/login` — validate Origin + CSRF, call Java auth, create/rotate session.
- `GET /bff/auth/oidc/start` — generate state, nonce and PKCE, then redirect to the provider.
- `GET /bff/auth/oidc/callback` — atomically consume the transaction, validate the provider
  response and create a normal Argus session without exposing either token to JavaScript.
- `GET /bff/auth/mfa/challenge` + `POST /bff/auth/mfa/verify` — restore/complete a
  pre-authentication challenge while its backend token stays encrypted and server-side.
- `/bff/auth/mfa/totp/*` — session-guarded TOTP setup, confirmation and disable proxy.
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

Development defaults to an in-process `Map` and process-local limiter for a zero-infrastructure
demo. `BFF_SESSION_STORE=redis` switches both the Session repository and login limiter to Redis:
replicas share login/logout state, records retain the upstream lifetime cap, and bearer tokens
are encrypted with AES-256-GCM before storage. The random Session ID is authenticated as AAD,
so ciphertext copied to another Redis key fails closed.

Production refuses the memory store and requires `BFF_REDIS_URL` plus a base64-encoded 32-byte
`BFF_SESSION_ENCRYPTION_KEY`. Redis connection failure also fails startup, while limiter errors
fail the login request rather than bypassing the control. A real deployment still needs TLS/
authenticated Redis, encryption-key rotation, monitoring, revocation policy, TLS ingress, and a
restrictive CSP on the separately hosted SPA document.

`BFF_MOCK_UPSTREAM=true` exists only for deterministic Playwright/local UI runs. It is not an
authentication bypass available in production—the configuration loader refuses that combination.
