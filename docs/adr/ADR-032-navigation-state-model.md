# ADR-032 — Navigation state model and degraded-state UX

- Status: Accepted
- Date: 2026-09-03

## Context

The project's original UX rule says an unavailable feature is "shown disabled with the reason". The UI research
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
    "what still works"). This amends the project's original UX rule.
  - `Degraded`: amber dot; page fully usable with an inline banner.
  - Stale data from the session stays on screen greyed with its timestamp; actions disabled.
- The `Degraded` reason is structured (`code`, `message`, `suggestedPollInterval?`, `p95?`) so
  lag and metrics screens can adapt polling.
- The capability SSE stream sends a full snapshot on connect and deltas afterwards; the
  Available → Unavailable transition raises one deduplicated toast per feature and cluster.
- Write actions are gated twice (RBAC and capability) through one `ActionPermissionWrapper`
  with a single merged tooltip.
- Cross-feature panels render through the kernel `FeaturePanel` slot (ADR-012).

## Amendments

Settled and reviewed as part of the M0 architecture review, 2026-09-03. Both fill in cases
the original decision left to the reader; neither adds a state to the `FeatureState` ADT.

**Amendment 1 — `Forbidden` outranks every health state.**

When a user lacks permission for a feature *and* that feature's service is unhealthy, the
shell renders `Forbidden` and nothing else. Deriving `FeatureState` from two independent
inputs (the capability registry and the RBAC decision) means both can apply at once, and the
original decision did not say which wins. It has to be `Forbidden`, because the alternative
leaks information: a user who is not allowed to see the schema registry should not be able to
learn from the sidebar whether it is up, how long it has been down, or its upstream error
message. Concretely, in the derivation table the row "permitted = false" matches first,
whatever the capability says. Implemented by task UI-008.

**Amendment 2 — a not-yet-polled capability is `Degraded(Starting)`, never `Unavailable`.**

Between process start and the first readiness poll the gateway has no information about an
upstream. That absence of information is deliberately rendered as `Degraded` with
`ReasonCode.Starting`, not as `Unavailable`. Reporting `Unavailable` would be a claim the
gateway cannot support — it has not yet asked — and every operator who restarts the gateway
would see the whole sidebar go red and dim for one polling interval before recovering, which
trains people to ignore the colour that matters. `Degraded(Starting)` says the honest thing:
the feature is usable, and we do not know its health yet. The same rule applies in both
places the case can arise: the gateway's fold maps `ReadinessSignal.Unknown` to
`Degraded(Starting)` (task GW-003), and the browser's capability store maps a missing map
entry to `Degraded(Starting)` (task UI-008) so the sidebar renders identically during the
gap before the first snapshot arrives. `Starting` is already a member of `ReasonCode`
(`libs/contracts-core`, task KERN-005).

## Evidence

- `research/kafbat/ui-analysis.md` IA.2, IA.3, DC-H1, DC-H2, DC-H3, DC-H5, DC-H6, DC-H7, open
  questions (hide forbidden; snapshot vs delta).
- `research/kouncil/ui-analysis.md` DC-H10 (last-seen assignments greyed).

## Consequences

- Every feature implements `unavailableView(reason)` and keeps its last successful response
  with a timestamp in feature state.
- The capability DTO in `libs/contracts-core` carries the structured reason from M0.

## Alternatives rejected

- Disabled links (the original UX wording): no place for the reason and retry.
- Hiding unavailable features (Kafbat): users cannot tell "misconfigured" from "down".

## Reversibility

High before M1 (contract), medium after.
