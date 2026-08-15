# Argus local demo

This runbook starts a reviewable local system rather than a disconnected set of mocks. The
default profile uses the real Java services, real Node BFF, real browser UI, authenticated TLS,
encrypted Redis Sessions and Prometheus. Only boundaries that require an external organization,
chain-data vendor, physical authenticator or cloud geography are replaced or simulated, and each
replacement is labelled below.

## Start and stop

Prerequisites: Java 17, Maven 3.9+, Node 20+, npm, Docker Desktop, OpenSSL, `lsof` and Chromium for
the automated walkthrough.

```bash
./scripts/demo-up.sh
```

The first run builds the Java reactor and both browser-facing Node workspaces. Later runs can reuse
verified build output:

```bash
./scripts/demo-up.sh --skip-build
```

If Docker is unavailable, the lite profile keeps the product flows but uses an in-process BFF
Session store, plain HTTP to auth-service and no Prometheus container:

```bash
./scripts/demo-up.sh --lite
```

Useful lifecycle commands:

```bash
./scripts/demo-status.sh
./scripts/demo-verify.sh       # 4 Playwright journeys against the real running stack
./scripts/demo-down.sh
```

Runtime PIDs, logs and the generated local Session-encryption key live under ignored `.demo/`.
Short-lived PKI material lives under ignored `infra/tls/generated/`. `demo-down.sh` kills only the
PIDs it recorded and stops only containers that this invocation started; it does not remove data
volumes or broadly match unrelated processes.

## URLs and accounts

| Surface | URL / credentials |
|---|---|
| Analyst console | <http://localhost:5173> |
| Password account | `analyst / analyst12345` |
| Admin account | `admin / admin12345` |
| Node health / readiness | <http://localhost:3001/health>, <http://localhost:3001/ready> |
| Node metrics | <http://localhost:3001/metrics> |
| Prometheus (full profile) | <http://localhost:9090> |
| Local mock IdP | <http://localhost:9091/health> (enter through the OIDC button) |

The accounts are seeded into the ephemeral H2 auth database and their passwords are stored with
bcrypt. Restarting auth-service restores fresh seed-account state. The real-stack Playwright suite
creates unique temporary users for MFA and Passkey journeys, so it does not enroll MFA on the two
seed accounts.

## Ten-minute product walkthrough

### 1. Password, Session and protected route

1. Open the analyst console and sign in as `analyst`.
2. In browser storage/devtools, show `argus_session`: it is opaque, `HttpOnly`, `SameSite=Strict`
   and scoped to `/bff`. There is no JWT in local/session storage.
3. Refresh the page. Redis-backed Session restoration keeps the protected console open while the
   upstream Java JWT remains encrypted inside the BFF state store.
4. Sign out and refresh. The protected investigation UI is no longer mounted.

### 2. Real agent loop over deterministic local evidence

Sign in and select two seeded wallets:

- `0xc1ean…` shows sanctions → profile → rules → **CLEAR**, deliberately skipping graph tracing;
- `0xbadc0de…` shows the longer tool path and ends **BLOCK** for a direct sanctions fixture.

The planner, REST tool calls, policy gates, JWT propagation, trace persistence, audit timeline and
case mirroring are real local code. The wallet graph, balances and sanctions entries are synthetic
fixtures, not live Chainalysis/TRM/Elliptic/OFAC data.

### 3. OIDC Authorization Code + PKCE

1. Sign out and click **Continue with OIDC**.
2. The amber page says **LOCAL MOCK IdP — NOT A REAL IDENTITY**. Continue as Gray Demo.
3. The browser returns to the protected console with an `oidc-*` account and the normal opaque
   Session cookie.

The external directory/account source and consent decision are mocked. The Authorization Code
exchange, one-time code, PKCE S256, state, nonce, signed RS256 ID Token, discovery/JWKS, issuer,
audience and expiry validation are real. Both `openid-client` in the BFF and Spring/Nimbus in
auth-service validate the result independently. HTTP is accepted only for a loopback issuer in
development; production configuration still requires HTTPS.

### 4. TOTP MFA and offline account recovery

1. Sign in, open **Manage MFA & recovery**, and start authenticator setup.
2. Use an authenticator app with the provisioning URI/secret. For a phone-free local demo, run the
   shown command from the repository root:

   ```bash
   node scripts/totp-code.mjs <BASE32_SECRET>
   ```

   This helper computes a real RFC 6238 HMAC-SHA1 code; it does not bypass verification. If you
   confirm and immediately sign out, wait for the next 30-second window before generating the
   login code because successful TOTP counters cannot be replayed.
3. Confirm setup and show the ten recovery codes. Plaintext is returned only once; the database
   stores keyed hashes.
4. Sign out, enter the next TOTP, then optionally sign out again and use one code through
   **Recover account** to reset the password. The code is consumed and indexed BFF Sessions for the
   account are invalidated.

### 5. Passkey

1. Sign in and open **Manage passkeys**.
2. Register a label and complete the browser/platform WebAuthn prompt.
3. Sign out and use **Sign in with a passkey**.

On a supported Mac/browser, the platform authenticator and user verification are real. The BFF
verifies challenge, origin, RP ID, signature and counter; Java stores the COSE public key. The
automated demo uses Chromium's virtual authenticator so CI does not require a physical Touch ID or
security key. Only that hardware boundary is simulated; the WebAuthn ceremony and verification
code paths are unchanged.

### 6. Authenticated TLS and monitoring

The full profile starts auth-service with mandatory client certificates. Show the boundary:

```bash
# Expected to fail during TLS client authentication:
curl --fail --cacert infra/tls/generated/ca.crt \
  https://localhost:8081/actuator/health

# Expected to return {"status":"UP"}:
curl --fail --cacert infra/tls/generated/ca.crt \
  --cert infra/tls/generated/bff-auth-client.crt \
  --key infra/tls/generated/bff-auth-client.key \
  https://localhost:8081/actuator/health/readiness
```

Prometheus uses its own client identity for the auth-service scrape. Open **Status → Target
health** at <http://localhost:9090/targets>; both `argus-identity-bff` and
`argus-auth-service` should be up. Alert rules cover availability, rejection/error ratios,
latency, encrypted-record rejection, dependency errors and certificate expiry.

### 7. Key rotation and regional fault drill

The local key-rotation proof is executable and uses real encrypted Redis records:

```bash
set -a; source infra/tls/generated/.env.mtls; set +a
cd bff
BFF_TEST_REDIS_URL="${BFF_REDIS_URL%/}/15" npm test -- \
  --run test/redis-integration.test.ts \
  -t 'rotates active Session ciphertext across replicas'

cd ../backend
mvn -q -pl auth-service -Dtest=IdentityKeyRotationTest test
```

Those tests prove lazy BFF Session re-encryption across two runtimes and bounded Java TOTP seed
drain without exposing plaintext. The production add → promote → overlap → retire procedure is in
[`runbooks/identity-key-rotation.md`](runbooks/identity-key-rotation.md).

Run the isolated failure exercise separately because it deliberately stops its Redis primary:

```bash
./infra/drills/run-multi-region-auth-drill.sh
```

It proves cross-instance Session restoration, app-region continuity, fail-closed store outage,
manual TLS replica promotion, zero observed Session loss and global logout. Both “regions” and the
Redis replica are local processes/containers on one machine; this is fault-injection evidence, not
a claim of deployed geographic infrastructure.

## Real versus mocked/simulated

| Capability | Local demo status | Boundary not claimed |
|---|---|---|
| Password, bcrypt, JWT HS256 and RBAC | Real | Seed credentials are development-only |
| Node BFF Session, cookies, CSRF and rate limit | Real; encrypted TLS Redis in full profile | No managed Redis/secret manager |
| OIDC protocol and token verification | Real | Account directory, corporate policy and KYC are mock |
| TOTP, counter replay defense and recovery codes | Real | No SMS/email delivery or help-desk workflow |
| WebAuthn/Passkey | Real; virtual authenticator only in automation | No device-attestation policy or physical CI key |
| Agent loop, tools, deterministic policy and audit | Real | Chain/provider data is seeded; external LLM is optional and not called |
| BFF→auth mTLS and Redis TLS/ACL/mTLS | Real short-lived local CA | No managed certificate issuance/revocation |
| Metrics and alert evaluation | Real local Prometheus | No PagerDuty/Sentry/on-call delivery |
| Multi-region drill | Real fault injection | Single machine, no WAN/LB/DNS/cloud control plane |

## Troubleshooting

- Check ownership and readiness with `./scripts/demo-status.sh`.
- Read `.demo/logs/<service>.log`; startup failures print the last lines automatically.
- The launcher refuses to kill a foreign listener. Free ports `3001`, `5173`, `8081`–`8084`,
  `9090`, `9091` and `6380`, then retry.
- Development certificates expire after 14 days. The generator refuses to overwrite private keys;
  remove/regenerate `infra/tls/generated/` deliberately when expired.
- `./scripts/demo-verify.sh` expects the demo to be running and tests the real stack, not
  `BFF_MOCK_UPSTREAM`.
