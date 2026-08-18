# Production packaging and schema migrations

Argus now has a fail-fast `prod` profile, non-root multi-stage images and a reference
Compose topology. The Compose file is an executable deployment contract, not a substitute for
managed ingress, a secret manager, managed databases or an orchestrator such as Kubernetes.

## What the production profile guarantees

| Service | Production behavior |
|---|---|
| `auth-service` | Requires Postgres, mTLS key/trust stores, a non-demo RS256 auth signing ring and non-development identity secrets; disables demo users; Flyway migrates the `auth` schema and Hibernate uses `validate` only |
| `screening-tools-service` | Requires Postgres plus auth/workload public rings; disables illustrative fixtures; Flyway migrates the `tools` schema and Hibernate uses `validate` only |
| `case-service` | Requires Postgres plus auth/workload public rings; Flyway migrates the `cases` schema and Hibernate uses `validate` only |
| `agent-orchestrator-service` | Requires Mongo, auth public keys, a non-demo workload signing ring, the external Anthropic provider and an API key; rejects memory traces and the local rule provider |
| `api-gateway` | Requires an explicit public Origin and does not publish the BFF-only auth route |
| Node BFF | Retains its existing production checks for Secure cookies, encrypted Redis, Redis authentication/TLS, auth-service mTLS and metrics authentication |

The default profile remains deliberately convenient: H2, memory traces and demo data keep the
one-command review environment fast. Production behavior is not inferred from hostnames; it is
activated explicitly with `SPRING_PROFILES_ACTIVE=prod` / `NODE_ENV=production`.

## Images

The shared backend Dockerfile accepts one Maven module and copies only its executable JAR into a
Java 17 runtime image:

```bash
docker build -f backend/Dockerfile --build-arg MODULE=auth-service \
  -t argus/auth-service:local backend
docker build -t argus/identity-bff:local bff
docker build -t argus/analyst-console:local frontend/analyst-console
```

Java processes run as UID/GID `10001`; Node runs as the image's unprivileged `node` user; both
frontends use unprivileged Nginx on port 8080. The SPA image supplies a restrictive CSP and proxies
same-origin `/bff` traffic. A real deployment must still terminate public HTTPS and add HSTS at the
ingress/CDN.

## Flyway ownership and rollout

Flyway migrations live with the service that owns the tables:

- `auth-service/src/main/resources/db/migration`
- `screening-tools-service/src/main/resources/db/migration`
- `case-service/src/main/resources/db/migration`

The three histories are isolated in Postgres schemas `auth`, `tools` and `cases`. The local
`postgres` profile may create missing schemas for developer convenience. The `prod` profile sets
`create-schemas=false`; infrastructure must create the schemas first, and application startup
fails if a migration or Hibernate validation fails.

For a real rollout:

1. back up the database and verify restore before the change window;
2. create the three schemas with the migration principal;
3. run the new image as a one-shot migration job or allow exactly one controlled instance to run
   Flyway before scaling out;
4. verify `flyway_schema_history` and application readiness;
5. deploy the remaining replicas; and
6. never edit an applied migration—add a new version.

The former prototype used Hibernate-created tables in the `public` schema. An existing populated
prototype database therefore needs a deliberate export/transform/import or a reviewed Flyway
baseline; the application does not silently claim that legacy schema as migrated.

## Reference Compose validation

Generate only short-lived **local** PKI, then provide disposable validation secrets:

```bash
./infra/tls/generate-dev-pki.sh             # skip if a still-valid local PKI already exists
cp .env.production.example .env.production  # replace every placeholder
./scripts/generate-jwt-key-rings.sh .env.jwt.generated
# copy the six generated ARGUS_*_JWT_* values into .env.production

docker compose --env-file .env.production -f compose.production.yml config -q
docker compose --env-file .env.production -f compose.production.yml \
  up -d --build --wait --wait-timeout 300
docker compose --env-file .env.production -f compose.production.yml ps
```

The reference stack runs Postgres, Mongo, TLS/password/client-certificate Redis, all Java services,
the production Node BFF and the analyst static image. Init containers copy local TLS material into
least-readable per-workload volumes so non-root processes do not need access to the host key
directory.

The exposed HTTP port is for static/container smoke checks only. Browser authentication assumes
the configured HTTPS public origin and an external TLS ingress. Use `./scripts/demo-up.sh` for the
fully interactive localhost review flow.

## Secret and infrastructure boundaries

- `.env.production` is ignored and is only a local Compose input; production injects credentials
  from its secret manager.
- Auth private keys are mounted only into `auth-service`; workload private keys only into the
  orchestrator. Tools/case services receive public rings only. Keep a retired public key through
  the maximum token TTL before removing it.
- `infra/tls/generated` contains development certificates and is ignored. Never ship its CA or
  private keys.
- Database, Mongo and Redis volumes in the reference file are single-host fixtures, not HA.
- Backups, point-in-time recovery, managed PKI, image registry signing/SBOM policy, ingress/WAF,
  multi-region traffic control and infrastructure-as-code remain deployment work.
