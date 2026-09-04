# ADR-039 — Capability fold: inputs, precedence, debounce and what must not feed it

- Status: Accepted
- Date: 2026-09-03

## Context

Fault isolation is KUI's first non-negotiable product property: the UI stays
usable when any single capability is down, and it tells the user *which* thing is down and
*why*. The project's architecture rules require the gateway to keep a capability registry that records, for each
`(service, cluster)` pair, one of `Available | Degraded(reason) | Unavailable(reason, since)`,
and ADR-032 defines how the frontend renders each of those.

Neither document says how the state is computed. That turns out to matter, because the
registry has four independent inputs that can disagree with each other at the same instant:

1. readiness polling of each upstream (`GET /health/ready`, every 10 s, ADR-004);
2. the per-upstream circuit-breaker state from `libs/http` (ADR-037);
3. the service's own `GET /capabilities` report, which says per cluster whether the upstream
   is configured at all and whether the service considers itself degraded;
4. observed p95 latency of the readiness calls.

A term for it: the **fold** is the pure function that takes those four inputs plus the
previous state and returns the new state. It is the single place where "is this feature
usable?" is decided, and every dimmed sidebar entry and every fallback panel in the product is
downstream of it. Getting it wrong is not a cosmetic bug: a fold that flaps turns the
navigation into a strobe light, and a fold that reports `Unavailable` when it merely has not
looked yet trains operators to ignore the one signal that is supposed to mean something.

Task GW-003 had to settle the rules in order to be implementable, and settled them well. This
ADR records them so that later milestones, which add ten more services to the same registry,
inherit a decision rather than re-derive one per service.

## Decision

### 1. Four inputs, one pure function

`CapabilityFold.fold(previous, inputs, now)` is pure and total. It lives in the gateway's
`application` layer, takes `now: Instant` as a parameter rather than reading a clock, and
holds no state of its own. Purity is what makes it testable as a table — one assertion per
input combination — and that table is the specification. The registry that calls it must never
fail: an input combination the fold does not expect yields the previous state and a logged
warning, never an exception, because the registry going down defeats the entire mechanism it
exists to support.

### 2. Precedence

When several inputs apply at once:

```
NotConfigured  >  Unavailable  >  Degraded  >  Available
```

Read this as "the most specific truth wins, and among health states the worst wins".
`NotConfigured` sits above `Unavailable` because it is not a health verdict at all: a cluster
with no schema registry attached does not have a *broken* schema registry, and showing it as
down would send an operator hunting for an outage that does not exist. ADR-032 hides
`NotConfigured` entries; showing them as `Unavailable` would put a permanent red mark in every
sidebar for every optional upstream a deployment chose not to configure.

Within the health states, the worse state wins, because a feature that is partly broken should
never be advertised as fine.

### 3. `since` is sticky

`since` is stamped on the transition *into* `Unavailable` and preserved for as long as the
state stays `Unavailable`, even when the reason code changes underneath it (a connection
refused that becomes a circuit-open, say). The field answers "how long has this been broken?",
which is the first question anyone asks. Recomputing it on every reason change would answer a
different and much less useful question ("how long has it been broken *in this particular
way*?") while looking identical in the UI.

### 4. Asymmetric debounce: slow to fail, instant to recover

A transition from `Available` to `Unavailable` must persist for `debounce` (default one
readiness interval, 10 s) before it is published. A transition from `Unavailable` to
`Available` is published immediately.

The asymmetry is deliberate and is the whole point. A single dropped readiness poll is not an
outage, and publishing it as one makes the sidebar flicker; waiting one interval costs at most
ten seconds of notice on a real outage, which nobody will notice against the outage itself.
Recovery is the opposite: a user staring at a fallback panel and waiting for a service to come
back wants the page to light up the instant it does. Debouncing recovery would make the
product feel broken *after* it had been fixed, which is the worst moment to feel broken.

### 5. Unknown is `Degraded(Starting)`, not `Unavailable`

Before the first readiness poll completes, the gateway has no information about an upstream.
`ReadinessSignal.Unknown` therefore folds to `Degraded` with `ReasonCode.Starting`. See
ADR-032 amendment 2 for the reasoning and for the matching rule in the browser.

### 6. Business errors must not dim capabilities

Only transport-level failures feed the registry. Concretely: every `InfrastructureError` from
a proxied call is reported to the registry; every `ApplicationError` is not.

A user requesting a topic that does not exist gets a 404 — and that 404 says something about
the request, not about the topic service, which answered correctly and promptly. Feeding it
into the registry would let any user dim a feature for every other user by typing a bad URL,
and would make the sidebar a display of recent user mistakes rather than of system health. The
inverse case matters just as much and is easy to get wrong in the other direction: an upstream
that is unreachable *must* be reported, so that a user who sees an error in the page also sees
the sidebar entry dim, instead of an error with no explanation anywhere on screen.

One exception, and it is not really one: a service with no configured URL yields
`ApplicationError.Unsupported`, which the registry maps to `NotConfigured`. That is a
statement about deployment configuration, known without asking anyone, not about a failure.

## Consequences

- `CapabilityFold` is the single decision point. A future input (an explicit maintenance flag,
  a health signal pushed by a service) is added to `CapabilityInputs` and to the table, not
  handled at a call site.
- The fold's table test is the specification and must be extended before behaviour changes.
- Services must classify their own errors correctly, because the `ApplicationError` /
  `InfrastructureError` split in `libs/kernel` (ADR-034) is what this decision keys on. A
  service that returns `InfrastructureError` for a business failure will dim itself.
- The debounce default (10 s) is configurable per deployment via `RegistryConfig`; the
  asymmetry is not.

## Alternatives rejected

- **Symmetric debounce.** Simpler to describe, but it delays the recovery the user is actively
  waiting for in order to smooth a transition nobody is watching.
- **Publishing every raw input to the frontend and folding in the browser.** Every client would
  reimplement the precedence table, they would drift, and the SSE payload would grow without
  making anything more truthful.
- **Treating `NotConfigured` as a kind of `Unavailable`.** Collapses "you did not set this up"
  into "this is broken" — the exact confusion ADR-032 was written to remove.
- **Feeding all upstream errors, including 4xx, into the registry.** Would make capability
  state a function of user input.

## Reversibility

High. The fold is one pure function behind `CapabilityRegistry[F]`; changing the precedence or
the debounce policy changes that function and its table test, and nothing else. The wire
contract (`CapabilityState` in `libs/contracts-core`) is unaffected.

## References

ADR-004 (health and capability endpoints), ADR-032 (rendering, and
its amendments), ADR-034 (the error hierarchy this keys on), ADR-037 (circuit breaker);
`ARCHITECTURE.md` §4.5, §6; tasks GW-003 (the fold), GW-004 (poller and circuit feed),
GW-006 (the report-on-proxy-failure rule), UI-008 (the browser-side derivation).
