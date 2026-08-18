# Wallet-screening providers and OFAC SDN ingestion

Status: **provider core and OFAC provider built**. Chainalysis/TRM/Elliptic adapters remain
explicit extension work, not claimed capabilities.

## Product boundary

OFAC's Sanctions List Service (SLS) distributes raw sanctions-list files; Argus performs the
application-side download, validation, parsing, indexing and exact wallet match. The implementation
uses the official SDN Advanced XML because it contains typed `Digital Currency Address - <asset>`
features and rich entity/program relationships. OFAC states that the advanced file contains the
entire SDN list and is updated with its other list products:

- [OFAC Sanctions List Service](https://ofac.treasury.gov/sanctions-list-service)
- [OFAC advanced-format FAQ](https://ofac.treasury.gov/sdn-list-data-formats-data-schemas/frequently-asked-questions-on-advanced-sanctions-list-standard)
- [OFAC 2024 namespace notice](https://ofac.treasury.gov/recent-actions/20240507_44)
- official machine source: `https://sanctionslistservice.ofac.treas.gov/api/PublicationPreview/exports/SDN_ADVANCED.XML`

This is exact address screening, not fuzzy person/name matching, transaction monitoring or legal
advice. A direct official-list hit is authoritative input to Argus's deterministic BLOCK rule.

## Provider model

```java
public interface ScreeningProvider {
    String id();
    boolean required();
    ScreeningResult screen(String address);
}
```

Every provider maps into the same internal result:

- direct `sanctioned` flag and normalized 0–100 score/band;
- named `ScreeningMatch` evidence;
- normalized category/exposure `RiskSignal`s;
- dataset version and provider error code; and
- `evidenceComplete`, used by the agent's deterministic fail-closed guard.

`CompositeScreeningProvider` loads the configured provider IDs in order, fans out for each address,
uses the worst score/band, lets any authoritative hit win and unions evidence. A failed **required**
provider makes the aggregate incomplete. Both the local and Anthropic agent paths reject CLEAR when
`sanctions_screen.evidenceComplete=false`; this rule is code-enforced rather than left to a prompt.

### Built providers

| ID | Purpose | Local mode | Production mode |
|---|---|---|---|
| `local` | deterministic transaction-graph/watchlist scenarios | enabled and labelled `LOCAL-DEMO-WATCHLIST` | disabled |
| `ofac` | exact match over SDN digital-currency features | two-address, real-public-data **snapshot**, version starts `snapshot-` | complete official HTTPS feed, version starts `official-` |

The response retains the old `directHit`/`hits` contract and adds provider evidence, signals,
normalized risk, freshness outcome and dataset version, so the investigation trace itself preserves
which source produced the verdict.

## OFAC ingest lifecycle

1. `OfacSourceLoader` accepts only the configured classpath snapshot or HTTP source, sends an
   identifiable User-Agent, refuses redirects, checks status/content type and enforces a 200 MB cap.
2. `OfacSdnParser` uses XXE/DTD-disabled StAX. It streams the 100+ MB publication rather than building
   a DOM, resolves feature-type IDs, primary entity names, sanctions programs and typed addresses,
   and preserves Base58 case while canonicalizing EVM/Bech32 forms.
3. The ingestion computes SHA-256 over the bytes actually parsed, compares the source's SHA-256
   `Digest` header when present, and rejects invalid dates, empty feature sets, future publications
   or a result below the configured address-count floor.
4. `OfacDatasetStore` replaces address rows and provenance metadata in one database transaction.
   A failed download/parse never deletes the last-known-good dataset.
5. Startup refreshes a missing/old dataset; a UTC daily scheduler maintains it. A fresh retained
   dataset survives a transient refresh outage. Once older than `max-age`, screening returns
   `dataset_stale` and fails closed.
6. ADMIN can trigger `POST /api/tools/catalog/ofac/refresh`; authenticated users can inspect
   `GET /api/tools/catalog/ofac/status` without receiving raw source bytes.

The reference Compose runs one screening replica. A horizontally scaled deployment should elect a
single refresh leader (or add a database/distributed lock); each replica may safely read the shared
last-known-good tables, but cross-process scheduler coordination is deployment work, not mocked here.

Flyway V2 owns `screening_dataset` and `ofac_sdn_address`, including content hash, publication date,
fetch time, source URI, version, entry count and an indexed normalized address.

## Honest local versus production behavior

The committed excerpt contains one real Lazarus ETH address and one real Central Bank of Iran TRX
address from the official 2026-08-07 publication. It exists so CI and a disconnected interview demo
exercise the real parser/provider/UI without pretending a two-row snapshot is current or complete.
The UI labels it **official OFAC snapshot**, and the dataset version starts `snapshot-`.

The `prod` profile instead:

- enables only `ofac`;
- fixes the source to the official SLS HTTPS host/path;
- requires startup refresh and at least 100 parsed digital-currency addresses; and
- refuses the classpath snapshot or a non-official host.

A point-in-time full-source validation is recorded in
[`evidence/ofac-live-validation-2026-08-18.md`](evidence/ofac-live-validation-2026-08-18.md).
This is local engineering evidence, not an availability claim about a deployed feed.

## Configuration

```yaml
argus:
  screening:
    providers: local,ofac
    ofac:
      source-uri: classpath:fixtures/ofac-sdn-advanced-excerpt.xml
      required: true
      refresh-on-startup: true
      refresh-cron: "0 0 3 * * *"
      refresh-if-older-than: PT6H
      max-age: PT48H
      request-timeout: PT180S
      max-bytes: 200000000
      minimum-address-count: 1
```

Production overrides providers/source/integrity floor in the profile, not through hopeful operator
convention. `ARGUS_OFAC_USER_AGENT` should identify the operating system/team but contain no secret.

## Reviewable tests

- `OfacSdnParserTest`: ETH/TRX/entity/program extraction and XXE rejection.
- `OfacSdnParserLiveFileTest`: opt-in complete-feed parse; hermetic CI skips without a file.
- `OfacSdnIngestionServiceTest`: mocked HTTP success/hash/atomic handoff and failure retention.
- `OfacSdnProviderTest`: hit, missing and stale behavior.
- `CompositeScreeningProviderTest`: authoritative hit, worst-case merge, optional/required failure.
- `ScreeningToolsTests`: Spring/H2 proof for the labelled OFAC ETH and TRX snapshot entries.
- `AgentLoopTest`: incomplete provider evidence forces REVIEW instead of CLEAR.

## Extension point, not current implementation

Chainalysis, TRM Labs and Elliptic adapters can implement the same interface and normalize their
mixer/darknet/scam/stolen-funds taxonomies into `RiskSignal`. They are **not implemented** because
no licensed API/key or response contract is present in this repository. A production addition must
include recorded-contract tests, bounded timeouts, raw-verdict retention/redaction policy, provider
SLA/freshness semantics and the same required-provider fail-closed behavior.
