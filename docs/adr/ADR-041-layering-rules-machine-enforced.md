# ADR-041 — Layering rules are machine-enforced, and `application` never depends on the wire

- Status: Accepted
- Date: 2026-09-03

## Context

PLAN §18 and `ARCHITECTURE.md` §3 lay out a hexagonal module structure per service —
`domain`, `application`, `infrastructure`, `contract`, `api`, `app` — with a dependency
direction that is the whole point of the structure: business rules must not depend on the
transport, the JSON library, the HTTP server or the wiring framework, so that any of those can
be replaced without touching the rules.

Rules like that decay quietly. Nobody sets out to make `application` depend on Tapir; someone
needs one DTO, adds one `moduleDeps` edge, and the edge is invisible in every subsequent code
review because reviewers read diffs, not dependency graphs. By the time it is noticed there
are thirty call sites and the rule is gone. PLAN §3 anticipates this by saying service
boundaries are "enforced by module dependencies", but it does not say what enforces the module
dependencies.

M0 grooming turned the rules into a build task, `./mill checkArchitecture` (task BUILD-005),
and in doing so hit a case the prose had not resolved: the gateway's `application` module
holds the capability registry, and the capability types (`CapabilityState`, `ReasonCode`,
`DegradedReason`) live in `libs/contracts-core`, which is a wire module built on Tapir and
Circe. Either the rule bends for the gateway, or the registry does not use the wire types.
The same question had already been answered, in the other direction, for the sample service:
task SVC-001 found `CapabilityReportUseCase` returning a contracts-core DTO and moved the DTO
out of `application` rather than moving the boundary. This ADR makes that answer general and
records the enforcement mechanism.

## Decision

### 1. `application` owns its own types and never depends on a wire module

No `application` module — of any service, gateway included — depends on `libs/contracts-core`,
on `libs/http`, on Tapir, or on Circe. When a use case needs to hand something outward, it
returns a type it owns, and the `api` module maps that type to the DTO (Chimney, ADR-033).

The gateway is not an exception. Its `application` layer defines its own capability types; the
`api` layer maps them to the `libs/contracts-core` DTOs that go on the wire. The duplication
this creates is small, mechanical, and exactly the seam that lets the wire representation
change — a field renamed for a client, a version-2 shape added beside version 1 — without a
change reaching the fold that decides whether a feature is usable. Collapsing the two means
the JSON shape and the decision logic are the same type, and every wire change becomes a
change to business logic. That is the coupling the hexagon exists to prevent, and the gateway
is the module most exposed to wire churn, so it needs the seam most.

`ServiceClient[F]` follows the same rule and shows what it looks like in practice: the port is
declared in `application`, and the sttp implementation that needs the Tapir client interpreter
lives in `api` (task GW-002).

### 2. The rule set, checked by the build

`./mill checkArchitecture` reads each module's declared `moduleDeps` and `mvnDeps` and fails
the build on any of these edges:

| Rule | Forbidden edge |
| --- | --- |
| A1 | `services/*/domain` → anything except `libs.kernel` and cats-core |
| A2 | `services/*/contract` → `services/*/domain` or `services/*/application` |
| A3 | `services/*/application` → `libs.http`, `libs.contractsCore`, tapir, circe, or any `infrastructure` module |
| A4 | `services.gateway.*` → any `services.<other>.{domain,application,infrastructure,api,app}` (only `contract` is allowed) |
| A5 | `libs/*` → any `services/*` or `frontend/*` module |
| A6 | `libs.kernel`, `libs.contractsCore`, `libs.securityCore` → any JVM-only dependency in their shared source set |
| A7 | `frontend/ui-shell` → a *static* reference to a `kui.ui.<feature>` class (checked by the bundle-shape assertion, not by this task) |

A3 names `libs.contractsCore` explicitly; that is the clarification this ADR adds. A4 is
ADR-004's central rule — the gateway holds no domain logic and sees each service only through
its published contract — expressed as a graph edge.

### 3. Enforcement is a build failure, not a review convention

The check runs in CI on every change (task BUILD-004) and locally as a Mill task, and it
reports the rule, the module, the offending dependency and why the rule exists — so the person
who trips it learns the reason in the same minute, rather than being told "no" by a reviewer a
day later. Each rule is proven by deliberately introducing a violating edge, observing the
failure, and reverting: a check nobody has watched fail is a check nobody knows works.

Reading `moduleDeps` metadata rather than scanning bytecode is deliberate. It is fast enough
to run on every build, it has no false positives, and it catches the mistake at the moment it
is made — when someone adds a dependency — which is the moment it is cheapest to undo.

## Consequences

- Adding a legitimate new edge means changing this rule table, in a commit that has to explain
  itself. That friction is the feature.
- Every service pays a small mapping cost at the `api` boundary. Chimney (ADR-033) makes the
  common case one line.
- The check is structural only. It does not see what a module imports inside its own source,
  which is why scalafix `DisableSyntax` covers the in-module cases that matter, and why A7 is
  delegated to the bundle-shape assertion, which inspects linker output.
- `libs/http` must not depend on `libs/config`: configuration reaches it as already-parsed
  case classes, so the HTTP library stays usable by anything that can build a config value.

## Alternatives rejected

- **Prose in `ARCHITECTURE.md` plus review discipline.** The status quo the project started
  from, and the mechanism that has failed in every codebase that relied on it.
- **Letting `application` use contracts-core, since it is "just DTOs".** contracts-core is not
  just DTOs; it is Tapir schemas, Circe codecs and Iron refinements, which is precisely the
  transport concern the layering exists to keep out. And "just DTOs" is how the edge always
  starts.
- **ArchUnit-style bytecode analysis.** Catches more, but runs after compilation, is slower,
  and is a heavier dependency than the mistake warrants. Revisit if module-level checking
  proves to miss real violations.

## Reversibility

High. The rule table is data in one build file; relaxing a rule is a one-line change with a
commit message that has to justify it. Undoing the *consequences* of a relaxation, after code
has grown against it, is not reversible — which is the argument for keeping the check strict
by default.

## References

PLAN §3, §18, §19; ADR-004 (the gateway rule), ADR-033 (mapping at the boundary),
ADR-039 (the fold that lives in gateway `application`); `ARCHITECTURE.md` §3;
`docs/domain/context-map.md` ("No context imports another context's `domain` module");
tasks BUILD-005 (the check), BUILD-004 (CI), SVC-001 and GW-002 (the rule applied),
DEVPLAN §5 (the module map the check validates).
