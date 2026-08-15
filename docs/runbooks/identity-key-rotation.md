# Identity encryption-key rotation

This runbook rotates application-level AES-256-GCM keys without placing plaintext identity
material in Redis, logs, command output or the browser. It covers the BFF key ring and the Java
TOTP key ring; JWT signing-key rotation is a separate provider/service migration.

## Preconditions

- Generate a 32-byte key in the secret manager (`openssl rand -base64 32` is suitable input).
- Choose an immutable key ID such as `identity-2026-08`; never reuse a key ID with new bytes.
- Confirm every region is healthy and uses the same old key ring before starting.
- Back up the old secret-manager version. Do not delete it during the overlap window.

## BFF records

Assume `old-v1` is primary and `new-v2` is the new key.

1. **Add, do not promote:** deploy all regions with
   `BFF_ENCRYPTION_KEYS=old-v1:<old>,new-v2:<new>` and
   `BFF_ENCRYPTION_PRIMARY_KEY_ID=old-v1`.
2. Confirm all regions can read Sessions and report `old-v1` as primary.
3. **Promote:** change only `BFF_ENCRYPTION_PRIMARY_KEY_ID=new-v2` and roll every region.
   New records use `new-v2`; active old Sessions are lazily re-encrypted with their remaining TTL.
4. Wait at least the configured maximum Session TTL after the last region promoted. OIDC, MFA
   and WebAuthn records have shorter TTLs and are consumed once.
5. **Retire:** remove `old-v1` from `BFF_ENCRYPTION_KEYS`, roll every region, and verify login,
   cross-region Session restore and logout.

Rollback before step 5 is configuration-only: restore `old-v1` as primary while retaining both
keys. After step 5, restore the retained old secret before rolling back application configuration.
Removing the old bytes too early deliberately fails closed and invalidates unreadable records.

## Java TOTP seeds

1. Deploy every auth-service instance with
   `ARGUS_IDENTITY_KEYS=old-v1:<old>,new-v2:<new>` while `old-v1` remains primary.
2. Promote `ARGUS_IDENTITY_PRIMARY_KEY_ID=new-v2` across every region.
3. Successful TOTP verification now rewrites that user's seed under `new-v2`. Drain dormant and
   pending records in bounded transactions using an ADMIN token over the authenticated mTLS hop:

   ```bash
   curl --fail-with-body -X POST \
     'https://auth.internal.example/api/auth/admin/identity-keys/rotate?limit=100' \
     -H "Authorization: Bearer $ADMIN_TOKEN" \
     --cacert /run/secrets/workload-ca.crt \
     --cert /run/secrets/operator-client.crt \
     --key /run/secrets/operator-client.key
   ```

4. Repeat until both `scanned` and `rotated` are zero. Investigate rather than skipping any
   malformed/unreadable envelope.
5. Confirm every region reports the new primary, then remove `old-v1` and roll once more.

## Evidence to retain

- Change/deployment IDs and region completion times.
- Old/new key IDs only (never key bytes).
- Bounded-drain responses and final zero result.
- Cross-region Session test, MFA login test and rollback decision.
- Confirmation that the retired secret-manager version is disabled according to policy.
