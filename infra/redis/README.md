# Redis

Redis is provisioned in `docker-compose.yml` for future use (rate-limiting at the
gateway, short-lived investigation status caching, idempotency keys).

**Honesty note:** the current services do NOT yet read/write Redis — it is wired
into infra but not into application code. Listed here so the compose file is
self-documenting rather than implying a feature that does not exist.
