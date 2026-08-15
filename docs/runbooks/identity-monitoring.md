# Identity monitoring and alert response

Argus exports low-cardinality Prometheus metrics from both identity tiers. Labels contain only
bounded values such as region, route template, flow, outcome and dependency. Usernames, email,
Session IDs, credential IDs, provider tokens, recovery codes and JWTs are never labels or metric
values.

## Endpoints

| Component | Liveness | Readiness | Metrics |
|---|---|---|---|
| Node identity BFF | `GET /health` | `GET /ready` (pings Redis in shared mode) | `GET /metrics` |
| Java auth-service | `GET /actuator/health/liveness` | `GET /actuator/health/readiness` | `GET /actuator/prometheus` |

Production BFF startup requires `BFF_METRICS_TOKEN` when metrics are enabled. Configure the
scraper with `bearer_token_file`; never put the token in the Prometheus YAML or a command line.
The auth-service listener already requires mTLS in production, so its scraper must use a distinct
workload client certificate. Keep all three endpoints on an internal network; they are not public
application routes.

## Local proof

Start the BFF and auth-service on their default host ports, then run:

```bash
docker compose --profile monitoring up -d prometheus
docker compose exec prometheus promtool check config /etc/prometheus/prometheus.yml
docker compose exec prometheus promtool check rules /etc/prometheus/identity-alerts.yml
```

Prometheus is available at `http://localhost:9090`. The local scrape file deliberately uses HTTP
and no bearer token; production must replace it with HTTPS/mTLS, a token file, durable storage,
service discovery and an Alertmanager receiver.

## Triage order

1. **Confirm scope:** compare `region`, BFF target health, auth target health and Redis dependency
   health. Do not assume a rejected credential is a platform outage.
2. **Preserve fail-closed behavior:** never bypass Redis, mTLS, signature checks, MFA or WebAuthn
   verification to restore traffic.
3. **System errors:** inspect normalized upstream outcome and latency, then correlate request IDs
   in application logs. Metrics contain no request IDs by design.
4. **Rejected-record alert:** stop key retirement, restore the retained old key if a rotation is
   in progress, and inspect only envelope metadata (`version`, `kid`, TTL)—never plaintext.
5. **Certificate alert:** issue a new identity, deploy trust overlap, verify mTLS from every region,
   then remove the old certificate. Do not reuse a private key.
6. **Regional failure:** follow the multi-region drill runbook and capture detection time, traffic
   shift time, Session continuity and fail-closed evidence.

## SLO starting point

- Login/passkey system-error ratio below 1% over 30 minutes (credential rejection is excluded).
- Identity BFF and auth-service availability at least 99.9% monthly.
- p95 BFF-to-auth latency below one second.
- Certificate warning at seven days, critical escalation at 72 hours in the production alerting
  overlay.
- Zero encrypted-record rejection events and zero plaintext identity material in telemetry.

These are initial engineering targets, not claims about production performance. Tune them from
real traffic after establishing an error budget and expected login volume.
