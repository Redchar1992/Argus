# Multi-region identity failover drill

This local fault-injection drill proves three narrow identity properties: a Session created by
one BFF instance can be restored by another, an unavailable shared store fails authorization
closed, and a TLS Redis replica can be promoted without losing the observed Session. It is not a
claim that a production multi-region platform has been deployed.

## Objectives

| Signal | Local objective | Why it matters |
|---|---:|---|
| Application-region RTO | <= 5 seconds | A second BFF instance can immediately restore a shared Session |
| Redis failover RTO | <= 30 seconds | The controlled promotion and BFF reconnect finish within the exercise window |
| Session RPO | 0 lost Sessions | The authenticated Session exists on the replica before fault injection |

These are test thresholds for a single-machine Docker topology, not production SLOs. Production
objectives must include load-balancer detection, DNS/routing, WAN replication and managed-service
control-plane latency.

## Run the drill

Prerequisites are Docker, Node 20+, OpenSSL and a JDK containing `keytool`.

```bash
./infra/drills/run-multi-region-auth-drill.sh
```

The runner:

1. generates or extends the ignored, 14-day local PKI;
2. starts only `redis-drill-primary` and `redis-drill-replica` from the Compose `drill` profile;
3. starts two independent in-process BFF regions against the TLS primary;
4. creates a Session in Region A and restores it in Region B;
5. stops Region A and verifies Region B continues authorization;
6. stops the Redis primary and verifies readiness becomes unhealthy and Session access returns
   the normalized `IDENTITY_STORE_UNAVAILABLE` 503 instead of using stale process memory;
7. promotes the TLS replica, reconnects Region B, verifies Session RPO, then logs out and proves
   global revocation on the promoted store; and
8. removes the two dedicated drill containers in an exit trap, even after failure.

Raw timestamped results are written mode `0600` under `infra/drills/results/` and are gitignored.
The reviewed, secrets-free evidence from 2026-08-15 is committed at
[`../evidence/multi-region-auth-drill-2026-08-15.json`](../evidence/multi-region-auth-drill-2026-08-15.json).
It passed 11/11 checks with a 3 ms application-instance transition, a 433 ms Redis promotion and
reconnect, and zero observed Session loss.

## Safety and abort

- The drill uses fixed, dedicated containers and host ports 6391/6392. It does not stop the normal
  `redis` or `redis-secure` service and mounts no production or shared data volume.
- The default password and generated CA are local fixtures only. Never reuse them outside this
  exercise.
- Press `Ctrl-C` to abort. The trap removes both drill containers. If the process was killed before
  the trap ran, clean up with:

  ```bash
  docker compose --profile drill rm -sf redis-drill-primary redis-drill-replica
  ```

- A failed check returns non-zero and still writes a timestamped result. Do not delete that failure
  evidence while investigating it.

## Production gaps before calling this multi-region ready

1. Put BFF and auth-service instances behind regional health checks and tested global traffic
   steering; measure detection and connection-draining time.
2. Replace manual Redis promotion with a managed or quorum-controlled cross-region service, and
   document consistency, fencing and split-brain behavior.
3. Run the same sequence with realistic WAN latency, packet loss, regional secret stores, separate
   workload PKI issuers and certificate revocation.
4. Add durable backups, point-in-time restore exercises and explicit Session-loss policy. Replication
   is not a backup.
5. Route alerts through the production on-call system and correlate regional BFF, auth-service and
   state-store telemetry without adding PII.
6. Rehearse rollback: restore traffic only after the old primary is fenced, the promoted store is
   writable and every identity instance reports ready.

## Evidence to retain

For each production exercise, retain the change/incident ID, operators, topology and versions,
fault-injection timestamp, alert detection time, traffic-shift completion, measured RTO/RPO,
readiness and authorization responses, Session continuity/logout proof, rollback result and every
deviation from the runbook. Never capture cookies, JWTs, recovery codes or identity attributes.
