# ADR-032 — Navigation state model and degraded-state UX

- Status: Accepted
- Date: 2026-09-03

## Context

PLAN §16.5 says an unavailable feature is "shown disabled with the reason". The UI research
found that a disabled link has nowhere to show `since`, a retry or "what still works", and
that Kafbat's hide-when-unconfigured behaviour must not be conflated with health.

## Decision

- Per feature and current cluster the shell derives
  `FeatureState = Ready | Degraded(reason) | Unavailable(reason, since) | Forbidden | NotConfigured`
  from the capability registry (`Available | Degraded | Unavailable | NotConfigured`) and the
  RBAC decision (`Forbidden`).
- Rendering rules:
  - `NotConfigured`: entry hidden (the cluster has no such upstream).
  - `Forbidden`: entry shown, disabled, tooltip "You do not have permission to view X";
    a global `kui.ui.hideForbidden` switch hides them for deployments that consider existence
    sensitive.
  - `Unavailable`: entry shown dimmed and **clickable**; the route renders the feature's
    fallback panel (reason, `since`, "Retry now" → `POST /capabilities/{service}/probe`,
    "what still works"). This amends PLAN §16.5.
  - `Degraded`: amber dot; page fully usable with an inline banner.
  - Stale data from the session stays on screen greyed with its timestamp; actions disabled.
- The `Degraded` reason is structured (`code`, `message`, `suggestedPollInterval?`, `p95?`) so
  lag and metrics screens can adapt polling.
- The capability SSE stream sends a full snapshot on connect and deltas afterwards; the
  Available → Unavailable transition raises one deduplicated toast per feature and cluster.
- Write actions are gated twice (RBAC and capability) through one `ActionPermissionWrapper`
  with a single merged tooltip.
- Cross-feature panels render through the kernel `FeaturePanel` slot (ADR-012).

## Evidence

- `research/kafbat/ui-analysis.md` IA.2, IA.3, DC-H1, DC-H2, DC-H3, DC-H5, DC-H6, DC-H7, open
  questions (hide forbidden; snapshot vs delta).
- `research/kouncil/ui-analysis.md` DC-H10 (last-seen assignments greyed).
- PLAN §2.1, §16.

## Consequences

- Every feature implements `unavailableView(reason)` and keeps its last successful response
  with a timestamp in feature state.
- The capability DTO in `libs/contracts-core` carries the structured reason from M0.

## Alternatives rejected

- Disabled links (PLAN §16.5 wording): no place for the reason and retry.
- Hiding unavailable features (Kafbat): users cannot tell "misconfigured" from "down".

## Reversibility

High before M1 (contract), medium after.
