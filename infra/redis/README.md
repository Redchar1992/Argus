# Redis

Redis is provisioned by `docker-compose.yml` and is now an implemented optional
runtime for the Node identity BFF:

- shared opaque Session records across BFF replicas;
- AES-256-GCM encryption of the upstream bearer token before storage;
- distributed login rate limiting through `@fastify/rate-limit`;
- TTL bounded by both the upstream token lifetime and BFF policy;
- cross-instance logout and fail-closed behavior when Redis is unavailable.

Local development still defaults to the in-memory store so the demo runs with
no infrastructure. Exercise the real path with an isolated key and database:

```bash
docker compose up -d redis
export BFF_SESSION_STORE=redis
export BFF_REDIS_URL=redis://127.0.0.1:6379
export BFF_SESSION_ENCRYPTION_KEY="$(openssl rand -base64 32)"
cd bff && npm run dev
```

CI runs `test/redis-integration.test.ts` against a Redis 7 service and verifies
that two BFF instances share Session restore, logout and rate-limit state while
the Redis value does not contain the plaintext bearer token.

The Compose service is development-only and has no password. Production must
use a private authenticated TLS endpoint (`rediss://`), managed secret/key
rotation, monitoring, backups appropriate to the Session policy, and restricted
network access.
