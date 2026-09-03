# ADR-041 — Layering rules are machine-enforced; a domain-owning `application` never depends on the wire

- Status: Accepted (amended 2026-09-03, Amendments 1 and 2 — see below)
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
The sample service had already met the same question and answered it by moving the DTO out of
`application` (task SVC-001). This ADR first generalised that answer to every module, then —
in Amendment 1, after review — scoped it to the modules the rule was designed for: those that
own a `domain`. The gateway owns none, and §1a explains why that changes the answer.

## Decision

### 1. A domain-owning `application` owns its own types and never depends on the wire

No `application` module **of a service that owns a `domain`** depends on `libs/contracts-core`,
on `libs/http`, on Tapir, or on Circe. When a use case needs to hand something outward, it
returns a type it owns, and the `api` module maps that type to the DTO (Chimney, ADR-033).
Task SVC-001 is the worked example: `CapabilityReportUseCase` returns an application-owned
`CapabilityReport`, and `services/cluster/api` maps it to the `ServiceCapabilities` DTO.

The rule protects business rules from transport churn. It therefore presupposes that the
module *has* business rules to protect — that a `domain` module exists behind it.

### 1a. The gateway is outside this rule, because it owns no domain

`services/gateway` has no `domain` module and never will: ADR-004 §3 decides that "the gateway
is application code only … no domain rules, no Kafka client", and `ARCHITECTURE.md` §3 lists
its modules as `application`, `api` and `app` only. Its subject matter *is* the composition of
other services' published contracts. `CapabilityState`, `DegradedReason`, `Section[A]` and
`ErrorEnvelope` are not an incidental encoding of some gateway domain concept that could be
expressed another way — they are the gateway's vocabulary, and `ARCHITECTURE.md` §4.5 and §6
define them in exactly those terms.

So `services.gateway.application` **may** depend on `libs/contracts-core` and on `libs/http`,
and the capability registry uses the contract types directly.

The original version of this ADR ruled the other way, reasoning that the seam would let the
wire shape change without touching the fold that decides whether a feature is usable. On
review that trade does not hold here:

- **The isolation is nominal.** A duplicate `CapabilityState` in `application` plus a mapper in
  `api` does not decouple anything, because the two types are the same type by construction.
  A field renamed for a client is then a rename in three places instead of one. A genuinely
  different v2 capability shape would change what the fold *means*, so it would have to reach
  the fold regardless — a mapper cannot absorb a semantic change, only a cosmetic one.
- **The cost lands in the worst place.** `CapabilityFoldSuite` is meant to be the executable
  specification of KU-001 (`DEVPLAN` §7); a reviewer should be able to read it against ADR-032
  and ADR-039 line by line. Interposing a duplicate type set between the fold and the wire
  makes the specification harder to read, which is the opposite of what the plan needs from
  that module.
- **It contradicted the architecture it was enforcing.** `ARCHITECTURE.md` §4.5 places
  `CapabilityRegistry` in `kui.gateway.capability` with its DTOs in `contracts-core`; §6 has
  the gateway "fold[ing] readiness polling … into `CapabilityState`". The rule as first written
  required an implementation the architecture document did not describe.

Generalised: **a module may depend on the wire when the wire is its subject matter.** The
gateway is the only such module in KUI, and it is one by explicit decision (ADR-004), not by
convenience — so this is a scoped rule, not an exception anyone may claim.

What still holds for the gateway, and is what actually protects the architecture:

- It sees each service only through that service's `contract` module (rule A4). This is
  ADR-004's central constraint and is unchanged.
- It holds no Kafka client (new rule A8).
- The `ServiceClient[F]` port stays declared in `application` with the sttp implementation in
  `api` (task GW-002). This split is now a design choice rather than a forced one, and it is
  kept because it is independently right: the Tapir client interpreter and the Netty binding
  belong at the edge, and `apps/allinone` supplies a second implementation of the same port
  (ADR-005). Allowing the edge does not license dragging the server interpreter into the fold.

### 2. The rule set, checked by the build

`./mill checkArchitecture` reads each module's declared `moduleDeps` and `mvnDeps` and fails
the build on any of these edges:

| Rule | Forbidden edge |
| --- | --- |
| A1 | `services/*/domain` → anything except `libs.kernel` and cats-core |
| A2 | `services/*/contract` → `services/*/domain` or `services/*/application` |
| A3 | `services/<name>/application` **where the service owns a `domain`** → `libs.http`, `libs.contractsCore`, tapir, circe, or any `infrastructure` module. `services.gateway.application` is outside this rule (§1a) |
| A4 | `services.gateway.*` → any `services.<other>.{domain,application,infrastructure,api,app}` (only `contract` is allowed) |
| A5 | `libs/*` → any `services/*` or `frontend/*` module |
| A6 | `libs.kernel`, `libs.contractsCore`, `libs.securityCore` → any JVM-only dependency, checked on the shared and `.js` halves; the `.jvm` half is exempt (Amendment 2) |
| A7 | `frontend/ui-shell` → a *static* reference to a `kui.ui.<feature>` class (checked by the bundle-shape assertion, not by this task) |
| A8 | `services.gateway.*` → `libs.kafka`, `libs.kafka-auth`, `fs2-kafka` or `kafka-clients` (ADR-004 §3: the gateway holds no Kafka client) |

A3 names `libs.contractsCore` explicitly, and scopes itself to services that own a domain;
that pair is the clarification this ADR adds. A4 and A8 together are ADR-004's central rule —
the gateway holds no domain logic and no Kafka client, and sees each service only through its
published contract — expressed as graph edges. A8 exists because §1a widens what the gateway's
`application` may reach; the constraint that actually matters is stated positively rather than
left implied.

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
- **Letting any `application` use contracts-core, since it is "just DTOs".** Still rejected for
  domain-owning services: contracts-core is not just DTOs; it is Tapir schemas, Circe codecs and
  Iron refinements, which is precisely the transport concern the layering exists to keep out,
  and "just DTOs" is how the edge always starts. The gateway is admitted by §1a on a different
  argument — that it owns no domain for the rule to protect — not on this one.
- **Extracting the pure resilience types (`CircuitState`, `CircuitEvent`) out of `libs/http`
  into a small shared module,** so the gateway's `application` could consume them without the
  Netty-carrying module on its classpath. Rejected as premature: it adds a module to avoid a
  classpath breadth that has caused no problem, and §1a's argument admits `libs/http` on its
  merits anyway. Reconsider if the gateway's `application` ever needs to be reused somewhere
  that must not carry a server.
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

## Amendment 1 — 2026-09-03

**What changed.** §1 is scoped to services that own a `domain`; new §1a admits
`services.gateway.application → {libs.contractsCore, libs.http}`; rule A3 is scoped to match;
new rule A8 forbids any Kafka client in the gateway.

**Why.** The rule as first written applied a constraint designed to protect business rules to
the one module in KUI that has none, and in doing so required an implementation that
`ARCHITECTURE.md` §4.5 and §6 do not describe. The duplication it forced bought no real
isolation and made the capability fold — the executable specification of KU-001 — harder to
read.

**What did not change.** Every other rule, the enforcement mechanism, and the answer for
domain-owning services: `services/cluster/application` still owns its types and still maps at
the `api` boundary (SVC-001). The gateway's real constraints are now stated positively as A4
and A8 rather than left implied by A3.

**Tasks updated:** BUILD-005 (rule table), GW-002, GW-003, `DEVPLAN` §5.3 and §10.


## Amendment 2 — 2026-09-03

**What changed.** Rule A6 no longer fires on the `.jvm` half of a cross-compiled core module. It
still fires on the shared and `.js` halves.

**Why.** A6 exists so that a library which only exists on the JVM cannot make the browser build
impossible. What it actually checked was every module whose id reduced to `libs.kernel`,
`libs.contractsCore` or `libs.securityCore` — including the `.jvm` platform module, which is
compiled for the JVM and for nothing else. That made a shape the architecture explicitly calls
for illegal: ADR-020 puts the nimbus JOSE library "in the JVM adapter so the core stays
cross-platform", and task KERN-006 requires exactly that (`libs/security-core/src-jvm/` holds
`JwsPrincipalCodec`, and `libs.securityCore.js` must link without nimbus). Under the rule as
written, implementing the ADR broke the check.

The exemption is narrow and does not weaken what the rule protects. A dependency declared on the
`.jvm` module is on the classpath of the `src-jvm` source set alone; the browser build never sees
it. A dependency declared on the shared trait reaches both platforms and is still caught, because
it appears on the `.js` module too, and `ArchitectureSuite` now has a test for each direction: the
JVM half of `libs.securityCore` may hold nimbus, the browser half may not.

The alternative — splitting a JVM-only adapter into its own top-level module — was rejected. It
would mean a `libs/security-jws` whose only reason to exist is to satisfy a check, published and
versioned separately from the vocabulary it implements, when Mill's platform source sets already
express "this file is compiled for one platform" precisely.

**Tasks updated:** BUILD-005 (rule table), KERN-006 (which records the same reasoning as a
deviation).
