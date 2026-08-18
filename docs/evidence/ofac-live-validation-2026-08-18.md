# OFAC full-feed local validation — 2026-08-18

This record is reproducible engineering evidence for one point in time, not a production SLA or a
claim that the committed two-address snapshot is the complete list.

## Source and integrity

- Official source: `https://sanctionslistservice.ofac.treas.gov/api/PublicationPreview/exports/SDN_ADVANCED.XML`
- Retrieved: 2026-08-18 (Asia/Shanghai workstation)
- HTTP: `200 OK`, `Content-Type: text/xml`
- Server `Last-Modified`: `Fri, 07 Aug 2026 18:36:51 GMT`
- XML `DateOfIssue`: `2026-08-07`
- Bytes: `125,868,278`
- SHA-256: `f4c4a77b065ec4081b789917298ffdf879127245a6a356fee2fcc5b77858d9cd`
- Server Digest and locally computed SHA-256 matched.

## Parser result

Command:

```bash
mvn -B -q -f backend/pom.xml -pl screening-tools-service -am \
  -Dtest=OfacSdnParserLiveFileTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dargus.ofac.live-file=/tmp/argus-sdn-advanced.xml test
```

Result:

- 1 test, 0 failures/errors/skips;
- 976 deduplicated `(asset, normalized address, SDN profile)` records;
- ETH and TRX feature families present;
- test elapsed time reported by the final Surefire run: 0.980 seconds.

The optional test file is never committed. Normal CI uses the official-format two-address excerpt
and mocked HTTP transport, so it is deterministic and does not make OFAC availability a build input.

## Production-profile Compose smoke

The same day, `compose.production.yml` was built from clean application images and started with
fresh Postgres/Mongo/Redis volumes, generated 3072-bit auth/workload RSA rings and the `prod`
profile. All ten runtime containers became healthy. The screening service downloaded the official
HTTPS source itself, verified the response Digest against the parsed bytes and committed:

```text
ofac|official-2026-08-07-f4c4a77b065ec408|976|2026-08-07|f4c4a77b065ec4081b789917298ffdf879127245a6a356fee2fcc5b77858d9cd
rows|976
```

The five Java images ran as UID/GID `10001`, the BFF as `node` and the analyst-console image as
UID `101`. The analyst static image was reachable on the disposable host port.

This was a **local production-profile smoke test**, not a deployed production environment. The
Anthropic credential was an explicit non-secret placeholder and no investigation/model request was
made; the smoke validates startup wiring, storage, migrations, authenticated transport and the real
OFAC ingest. Public TLS ingress, managed secrets/databases/PKI, external LLM availability and
multi-region infrastructure were not simulated as real.
