# Argus interview-readiness roadmap

This is a gap assessment of the code at `6e4f5a9` (the existing Pages demo and the
real Spring/BFF stack). It deliberately distinguishes shipped evidence from future
production work.

| Rank | Gap / opportunity | Interview value | Risk | Evidence and bounded next step |
|---|---|---:|---:|---|
| 1 | The investigation API exposes a timeline, but the demo does not show a review gate or an explicit intervention state. | High | Medium | **Shipped:** the Pages fixture exposes a local review gate; case-service persists `PENDING_REVIEW` / `NEEDS_INFO` / `RESOLVED`, restricts review actions to analysts/admins, and writes `CASE_REVIEWED` audit events. |
| 2 | Policy thresholds are implemented in the service, but policy/version provenance is not visible in the Pages walkthrough. | High | Low | Show policy id/version, enforcement mode, and the bounded step/cost budget alongside the verdict. |
| 3 | The trace is inspectable but there is no portable evidence/replay artifact. | High | Low | Add a secret-free JSON export containing the subject, policy metadata, steps, and decision; keep it deterministic and browser-only. |
| 4 | Web3 transaction-agent handoffs are not visible as a state model. | High | Medium | Add a conservative transaction state strip (`DETECTED → SCREENED → AWAITING_REVIEW → SETTLED`) to REVIEW fixtures only; do not claim signing or broadcasting. |
| 5 | Production gaps remain around managed deployment, refresh-token rotation, attestation policy, and richer step-up orchestration. | Medium | High | Keep these documented as gaps; do not simulate production guarantees in the demo. |

## Delivery slice

Ranks 1–4 are the bounded slice for this interview-prep pass. They improve the
reviewable evidence without changing the real agent loop or introducing an AI key.
The Pages implementation is a deterministic fixture, while the case-service review
queue is the source of truth for live human intervention.
