# Authenticated transport

Argus uses two independent production transport controls:

1. the identity BFF validates `auth-service` and presents a client certificate; Spring requires
   that certificate (`client-auth=need`);
2. Redis uses `rediss://`, server-certificate validation and ACL credentials, with optional
   client-certificate authentication.

## Local proof

Generate a short-lived local CA and server/client identities:

```bash
./infra/tls/generate-dev-pki.sh
set -a; source infra/tls/generated/.env.mtls; set +a
docker compose --profile security up -d redis-secure
```

The generated directory is gitignored. Private keys are mode `0600`, server certificates expire
after 14 days, and the CA expires after 30 days. The script refuses to overwrite an existing PKI;
delete it deliberately before regeneration.

Build and start `auth-service` with the sourced environment. With `SPRING_PROFILES_ACTIVE=prod`,
also replace every shipped development application secret. A request without a client certificate
must fail during the TLS handshake:

```bash
curl --cacert infra/tls/generated/ca.crt https://localhost:8081/actuator/health
```

The same request with the BFF identity reaches Spring:

```bash
curl --cacert infra/tls/generated/ca.crt \
  --cert infra/tls/generated/bff-auth-client.crt \
  --key infra/tls/generated/bff-auth-client.key \
  https://localhost:8081/actuator/health
```

Run the two-replica Redis integration suite through TLS + password + client certificate:

```bash
set -a; source infra/tls/generated/.env.mtls; set +a
cd bff
BFF_TEST_REDIS_URL="$BFF_REDIS_URL" npm test
```

## Production ownership

- Issue certificates from the workload PKI, not this development CA.
- Keep server and client identities separate and restrict SANs/EKUs.
- Mount private keys read-only with owner-only permissions.
- Rotate certificates before expiry and alert on remaining lifetime.
- Store Redis ACL and PKCS12 passwords in a secret manager; never embed them in source or logs.
- Revoke the BFF client identity independently of end-user Sessions.
