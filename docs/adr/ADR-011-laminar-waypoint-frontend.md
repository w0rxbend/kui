# ADR-011 — Laminar 17.2.1, Airstream 17.2.1, Waypoint 9.0.0 frontend

- Status: **Superseded by [ADR-048](ADR-048-solidjs-typescript-vite-frontend.md)** (2026-09-05)
- Date: 2026-09-03

> **Superseded.** The frontend is no longer Scala.js. ADR-048 replaces Laminar, Airstream and
> Waypoint with SolidJS 2, TypeScript and Vite, and explains what that costs — chiefly
> compile-time contract sharing, which ADR-048 §3 replaces with types generated from the
> committed OpenAPI documents. This ADR is kept because the behaviour it specifies (state
> ownership, the SSE split, the error-handling rules) was ported rather than redesigned, and
> because it records why the original choice was made.

## Context

The frontend is Scala.js with no application TypeScript. Kafbat's React stack
(react-query, zustand, react-table, styled-components, react-hook-form) must be mapped to
Laminar idioms with explicit state ownership rules.

## Decision

- **Laminar 17.2.1 / Airstream 17.2.1 / Waypoint 9.0.0 / url-dsl 0.7.0 / scala-js-dom 2.8.1 /
  scala-java-time 2.7.0** on Scala.js 1.22.0. Upgrade to the Laminar 18 / Waypoint 10 pair is a
  scheduled task after their final releases; routes are written with explicit `endOfSegments`
  now for forward compatibility.
- State: kernel-owned `Var`s only for `AuthState`, `CapabilityState`, `CurrentCluster`,
  `NotificationBus`, `Theme`; feature-local `State` classes; server state through a kernel
  `QueryCache[K, A]` built on Airstream `Status` with key-prefix invalidation; persisted UI
  preferences via `WebStorageVar`; page switching with `splitMatchOne`.
- Routing: a sealed `Page` ADT per feature, route lists concatenated in the shell into one
  `Router[Page]`; `Page` serialized with Circe; the gateway serves `index.html` for `/ui/**`.
- Forms: `Var[Model]` + controlled inputs + Iron validation with `Validated` accumulation;
  schema-aware validation of produced messages is server-side (message-service endpoint) and
  surfaced through editor lint.
- HTTP: Tapir clients from cross-compiled contracts over sttp `FetchBackend`; a kernel
  `ApiClient` adds the CSRF header, correlation id and 401/403 interception.
- SSE: kernel `Sse.eventSource(url)` (native, reconnecting, for capabilities) and
  `Sse.fetchStream(request)` (abortable fetch + SSE parser, for message browsing).
- Errors: `AirstreamError.registerUnhandledErrorCallback` in the shell; every feature route
  rendered inside a `Try` so a throwing page shows the feature fallback, never a blank app.

## Evidence

- `research/scala/frontend-research.md` §3 (versions, idioms, Kafbat→Airstream mapping table,
  routing, forms, SSE/HTTP), §1 (Kafbat inventory), ADR-011 candidate.
- `research/scala/ecosystem-mapping.md` F12 (Waypoint pinned to 9.0.0, not 10.0.0-Mx).

## Consequences

- No off-the-shelf table, virtualization or form library; the kernel owns them (ADR-025).
- Feature code is Airstream-idiomatic; a UI library switch would be a rewrite.

## Alternatives rejected

- Laminar 18 milestones: not final; draft release notes still change.
- Scala.js React facades (slinky/japgolly): reintroduce the React runtime and JS ecosystem
  dependencies the project's technology constraints exclude.

## Reversibility

Medium. Version upgrades within Laminar are mechanical; leaving Laminar is not.
