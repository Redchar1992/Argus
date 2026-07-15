# Design: Pluggable Wallet-Screening Providers

Status: **design** (not yet built). Scope: `screening-tools-service`.

## Why

Today `sanctions_screen` matches an address against a **seeded fixture** list
(`SanctionedAddress` in H2/Postgres). That is enough to drive the agent loop, but a
production compliance system does not screen against one hand-made list — it **federates
several sources**:

- **Authoritative sanctions lists** — OFAC SDN, EU/UN consolidated, UK OFSI. These are
  the legal ground truth for a **direct** sanctions hit (→ BLOCK).
- **Chain-analytics vendors** — Chainalysis, TRM Labs, Elliptic. These add **behavioural
  risk** an address list can't: mixer/darknet/scam/stolen-funds *exposure*, direct vs
  indirect, cluster attribution, a normalised risk score.

This design introduces a `ScreeningProvider` abstraction so screening sources are swappable
and composable — **exactly mirroring the existing swappable `LlmProvider`** (`local` vs
`anthropic`) pattern. It also closes the README's honest gap *"addresses are illustrative
(no real OFAC entries)"* by shipping a provider that ingests the **real, public OFAC SDN
digital-currency addresses** (no API key required).

The point is not vendor lock-in — it's to demonstrate (a) a real sanctions-list ingest and
(b) fluency with how the major KYT/screening APIs are actually shaped and how their differing
risk taxonomies get normalised into one internal model.

## The abstraction

The hard part is **normalisation**: OFAC gives a binary listing; Chainalysis gives
`Severe/High/Medium/Low` + exposure categories; TRM gives per-category risk levels +
`riskType` (ownership/counterparty/indirect). One internal model absorbs all of them.

```java
public interface ScreeningProvider {
    String id();                          // "ofac", "chainalysis", "trm", "local"
    ScreeningResult screen(String address, Chain chain);
}
```

```java
public record ScreeningResult(
        String address,
        String source,               // provider id that produced this
        boolean sanctioned,          // TRUE only for a DIRECT authoritative-list hit → BLOCK
        int riskScore,               // 0..100, normalised across providers
        RiskBand band,               // LOW | MEDIUM | HIGH | SEVERE
        List<RiskSignal> signals,    // the evidence the agent/risk_rules reason over
        boolean evidenceComplete,    // false ⇒ provider errored/timed out (fail-closed)
        JsonNode raw)                // the vendor's raw verdict, persisted for audit
{ }

public record RiskSignal(
        RiskCategory category,       // SANCTIONS, DARKNET_MARKET, MIXER, SCAM,
                                     // STOLEN_FUNDS, TERRORISM_FINANCING, CHILD_ABUSE,
                                     // GAMBLING, HIGH_RISK_EXCHANGE, ...
        Exposure exposure,           // DIRECT | INDIRECT
        int hopsAway,                // 0 = the address itself; N = N-hop counterparty
        int severity,                // 0..100
        String entity,               // named entity if attributed (e.g. "Lazarus Group")
        String detail) { }
```

`RiskCategory` is the standard KYT category set shared by Chainalysis/TRM/Elliptic — using
their vocabulary is deliberate (it's the language a compliance reviewer expects).

## Providers

| Provider | Source | Key needed | Notes |
|---|---|---|---|
| `LocalFixtureProvider` | seeded `sanctioned_address` table | no | current behaviour; default, zero-config demo |
| `OfacSdnProvider` | **real OFAC SDN** digital-currency addresses | **no** | public data — turns the honest gap into a real capability |
| `ChainalysisProvider` | Chainalysis Address Screening / KYT | yes | key-gated, mockable |
| `TrmProvider` | TRM `/public/v2/screening` | yes | key-gated, mockable |
| `EllipticProvider` | Elliptic Wallet Screening | yes (HMAC) | key-gated, mockable |
| `CompositeScreeningProvider` | fan-out + merge | — | the production shape |

### `OfacSdnProvider` (real, buildable now)

OFAC publishes the SDN list with machine-readable **"Digital Currency Address"** features
(`SDN.CSV` / `sdn_advanced.xml`, and the newer Sanctions List Service). Each crypto entry is
a feature like `Digital Currency Address - XBT <addr>` / `- ETH <addr>` on an SDN entity
(e.g. Lazarus Group, Garantex, Tornado Cash contracts).

- A scheduled ingest (`@Scheduled`, daily) downloads the SDN source, parses the digital-currency
  features, upserts them into `sanctioned_address` with `listSource="OFAC-SDN"`, `program`,
  `entity`, and `severity=100`.
- `screen()` then does a direct lookup → `sanctioned=true`, `SANCTIONS/DIRECT/hops=0` on a hit.
- Ships with a **checked-in SDN excerpt fixture** so CI + the offline demo have real (public)
  sanctioned addresses without a network call.

This alone is the highest-credibility piece: "real OFAC SDN ingest + screening" is a concrete,
verifiable compliance capability, not scaffolding.

### Vendor adapters (`Chainalysis` / `TRM` / `Elliptic`)

Each adapter is a thin REST client that maps the vendor response into `ScreeningResult`,
modelled on the vendors' documented API shapes:

- **Chainalysis** — register + `GET` the address entity; map `risk` (Severe/High/Medium/Low)
  → `band`, `exposures[]` → `RiskSignal`s (category + direct/indirect).
- **TRM** — `POST /public/v2/screening` `[{address, chain}]`; map `addressRiskIndicators[]`
  (`category`, `categoryRiskScoreLevel`, `riskType`) → signals; `entities[]` → attribution.
- **Elliptic** — HMAC-signed wallet screening; map `risk_score` + `contributions[]` by category.

All three are **key-gated** (`@ConditionalOnProperty` + env key) and **mocked in tests** via a
recorded response fixture — no live key in CI, same discipline as the Anthropic provider today.

### `CompositeScreeningProvider` (the production shape)

Fan-out to the configured providers, then merge **fail-closed** (consistent with Argus's
existing decisioning):

1. Run OFAC (authoritative) **always**; any direct hit ⇒ `sanctioned=true` wins → BLOCK.
2. Run the configured vendor(s) for behavioural exposure; union their signals.
3. `riskScore` = max across providers; `band` = worst.
4. If a **configured** provider errors/times out ⇒ `evidenceComplete=false` ⇒ the agent must
   escalate to REVIEW, never silently CLEAR. (Mirrors the existing "required tool missing →
   REVIEW" rule.)

## Config (mirrors `LlmProvider`)

```yaml
argus:
  screening:
    providers: local            # csv: e.g. "ofac,trm"  (default: local)
    ofac:
      source-url: https://.../sdn_advanced.xml
      refresh-cron: "0 0 3 * * *"
    chainalysis: { base-url: ..., api-key: ${ARGUS_CHAINALYSIS_API_KEY:} }
    trm:         { base-url: https://api.trmlabs.com, api-key: ${ARGUS_TRM_API_KEY:} }
```

Beans are `@ConditionalOnProperty`; `CompositeScreeningProvider` wires whichever are enabled.
`SanctionsScreenService` becomes a thin orchestrator over the active provider(s) instead of
querying the repository directly.

## Integration with the tool + agent

- `SanctionsScreenResponse` gains `riskScore`, `band`, and `signals[]` (categories + exposure)
  alongside the existing `directHit`/`hits`. Backward compatible — existing fields stay.
- `risk_rules` already consumes `sanctionsDirectHit` and `minHopsToFlagged`; extend it to fire
  rules on **categories** (e.g. `MIXER` exposure ≤1 hop → +points → REVIEW; `SANCTIONS/DIRECT`
  → BLOCK). This makes the vendor signals actually drive the decision, not just decorate it.
- **Auditability**: persist each provider's `raw` verdict with the investigation step. A
  compliance audit must be able to show *which source said what* — storing the vendor's raw
  response is a compliance requirement, not a nicety.

## Testing

- `OfacSdnParserTest` — parse the checked-in SDN excerpt → expected addresses/entities.
- `CompositeScreeningProviderTest` — OFAC hit wins; vendor error ⇒ `evidenceComplete=false`
  ⇒ REVIEW; score/band merge = worst-case.
- Vendor adapters — map a recorded JSON fixture → `ScreeningResult` (no live key).
- Existing `sanctions_screen` tests keep passing (fixture provider is the default).

## Build order

1. `ScreeningProvider` interface + `ScreeningResult`/`RiskSignal` model; refactor the current
   service into `LocalFixtureProvider` behind it (no behaviour change — pure structure).
2. `OfacSdnProvider` + scheduled ingest + SDN fixture + parser test. **← real capability lands here.**
3. `CompositeScreeningProvider` + fail-closed merge + config wiring.
4. `TrmProvider` (cleanest public API) → `ChainalysisProvider` → `EllipticProvider`, each with a
   recorded-fixture test.
5. Extend `risk_rules` to consume categories; update `docs/jd-mapping.md` +
   README ("real vs scaffolded": OFAC moves from gap → real).

## What this demonstrates (for a compliance-eng JD)

Real sanctions-list ingestion (OFAC SDN); fluency with **Chainalysis / TRM / Elliptic** API
shapes and their risk-category taxonomy; provider **federation + normalisation** across
disagreeing sources; **fail-closed** compliance semantics; raw-verdict **audit** retention;
and config-driven pluggability — the exact "hands-on with KYT/screening tooling" signal a
crypto compliance team screens for.
