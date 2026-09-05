# ADR-041 — Layering rules are machine-enforced; a domain-owning `application` never depends on the wire

- Status: Accepted (amended 2026-09-03, Amendments 1, 2 and 3 — see below)
- Date: 2026-09-03

## Context

The project's architecture rules and `ARCHITECTURE.md` §3 lay out a hexagonal module structure per service —
`domain`, `application`, `infrastructure`, `contract`, `api`, `app` — with a dependency
direction that is the whole point of the structure: business rules must not depend on the
transport, the JSON library, the HTTP server or the wiring framework, so that any of those can
be replaced without touching the rules.

Rules like that decay quietly. Nobody sets out to make `application` depend on Tapir; someone
needs one DTO, adds one `moduleDeps` edge, and the edge is invisible in every subsequent code
review because reviewers read diffs, not dependency graphs. By the time it is noticed there
are thirty call sites and the rule is gone. The project's architecture rules anticipate this by
saying service boundaries are "enforced by module dependencies", but that alone does not say
what enforces the module dependencies.

The M0 architecture review turned the rules into a build task, `./mill checkArchitecture` (task BUILD-005),
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
| A7 | *(withdrawn by ADR-048)* The shell must not statically reference a feature's implementation. The rule is unchanged as a property but no longer belongs here: the frontend is not a Mill module, so `checkArchitecture` cannot see it. It is checked on the frontend's own side, against the Vite build manifest's module graph — ADR-012 as amended by ADR-048 §4 |
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

ADR-004 (the gateway rule), ADR-033 (mapping at the boundary),
ADR-039 (the fold that lives in gateway `application`); `ARCHITECTURE.md` §3;
`docs/domain/context-map.md` ("No context imports another context's `domain` module");
tasks BUILD-005 (the check), BUILD-004 (CI), SVC-001 and GW-002 (the rule applied).

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

**Tasks updated:** BUILD-005 (rule table), GW-002, GW-003.


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


## Amendment 3 — 2026-09-03 (M1 architecture review)

**What changed.** Two rules are added, and one existing rule is confirmed rather than widened.

- **A9** — a service's `application`, `contract` or `api` module may not depend on that
  service's `infrastructure` module. Only `app` may. The dependency rule points inward; an
  `api` that can see an adapter will eventually call one, and the port becomes decoration.
- **A10** — `libs.kafka`, `libs.kafkaAuth`, `org.typelevel:fs2-kafka` and
  `org.apache.kafka:kafka-clients` may appear only on the classpath of a service's
  `infrastructure` module, `libs/kafka*` itself, `libs/config`, `libs/testkit`, or an `app`.
  This is A8 generalised from the gateway to everyone. `libs/config` is on the list because the
  Kafka `ConfigStore` adapter lives there (ADR-042 §5); the rule names the exception so that a
  sixth one has to be argued in the commit that changes the rule.
- **A1 is not widened.** The M1 architecture review asked for `co.fs2:fs2-core` to be added to A1's allow-list,
  so that a domain port could expose `changes: fs2.Stream[F, List[ClusterProfile]]`. Refused.
  A1's allow-list is `libs.kernel` and cats-core, and its value comes from being short enough
  that adding to it is an event. A port stated in terms of an abstract `F[_]` needs no runtime
  dependency at all; `fs2.Stream` is a concrete type from a concrete runtime, and a domain that
  imports it can no longer be read, tested or moved without that runtime. Change notification
  belongs one layer out: `ClusterRegistry`, in `application`, owns the stream — `application`
  is already allowed fs2 — and the `ClusterConfigStore` port offers callback registration
  (`onChange(handler: List[ClusterProfile] => F[Unit]): F[F[Unit]]`, returning the
  deregistration) instead of a stream.

**Why this is worth a rule rather than a review note.** Both A9 and A10 constrain a module shape
that did not exist when A1–A8 were written: `services/cluster/infrastructure` is KUI's first
adapter module. The argument of this ADR — that nobody sets out to break layering, they add one
edge for one type — applies identically to a Kafka client and to an adapter edge.

**Tasks updated:** CFGOP-003 (rule table and its build tests), CLDOM-003, CLDOM-004, CLADP-003,
CLADP-005.


## Amendment 4 — 2026-09-04 (M2/M3/M4 architecture review)

**What changed.** Four rules are added, and their numbers are allocated centrally because three
milestones planned in parallel each proposed a different rule under the number A11.

| Rule | Forbidden edge | Owning milestone |
| --- | --- | --- |
| A11 | `services.<a>.*` → any module of `services.<b>` other than `services.<b>.contract.*` and `services.<b>.client` | M2 (task TOP-010) |
| A12 | `libs.serdeConfluent`, `io.confluent.*`, Jackson or Guava on the classpath of any module other than `libs/serde-confluent` itself, a service's `infrastructure`, `libs/testkit` or an `app` | M3 (task MSG-047) |
| A13 | `libs.filter`, `dev.cel.*` or `re2j` on the classpath of any module other than `libs/filter`, a service's `application`, `libs/testkit` or an `app` | M3 (task MSG-047) |
| A14 | declaring a **wire vocabulary** — an enum or set of string constants serialised across a process boundary — anywhere but `libs/kernel` or `libs/contracts-core` | M4 (task GRP-040) |

**Why the numbers are allocated here.** M2's plan, M3's plan and M4's plan each defined
an "A11", and the three definitions are unrelated. Whichever milestone landed second would have
either renamed the other's rule or silently redefined it, and a rule table whose numbers mean
different things in different commits is worse than no table: `checkArchitecture`'s failure
message names the rule, and the reason a developer reads is the reason attached to that number.
Rule numbers are therefore allocated in this ADR and nowhere else. A milestone that wants a new
rule takes the next free number here, in the commit that adds the check.

**A11 is the load-bearing one.** M2 creates KUI's first service-to-service dependency
(ADR-046). A4 said this for the gateway; nothing said it for services, because until M2 there
were none. Without A11 a service could call another service's use case in process in the
all-in-one build and over HTTP in the distributed one, and the two shapes would diverge without
either being wrong at its own call site. `client` is named explicitly in the allow-list so that
a second such module has to be argued in the commit that adds it, exactly as A10 names
`libs/config`.

**A12 and A13 are classpath confinement, not layering.** ADR-014 makes the Confluent stack an
optional *runtime* dependency; the moment an `application` module can see a Confluent class, the
deployment that runs without a Schema Registry stops compiling in someone's head and starts
failing in production. CEL (ADR-017) is user-supplied code, and the set of modules that can
evaluate it must be small enough to audit and must never include the gateway or a `contract`
module. `services/*/application` is on A13's allow-list because the filter port is a pure port
consumed by a use case; that is the one exception and MSG-047's build test names it.

**A14 is the M0 review's second process finding, mechanised.** Six consumer-group state names
appear in a Kafka enum, a domain enum, a DTO, a query parameter and a CSS class. A documented
rule that nothing enforces gets violated.

**A4 becomes an allow-list when A11 lands.** A4 was written as a deny-list of the five layers a
service had when it was written (`domain`, `application`, `infrastructure`, `api`, `app`). ADR-046
creates a sixth, `client`, which that deny-list would therefore have admitted to the gateway
silently, by a rule that was never asked about it — while A4's own failure message says "only
through that service's published contract module". TOP-010 restates A4 as the allow-list its
message already claimed: `contract`, and nothing else. This is a tightening, not a relaxation, and
it is behaviourally identical for every layer that existed before. `client` remains available to
the services that need it, under A11.

**Standing requirement.** Each of A11–A14 is proven the same way: deliberately introduce an edge
that violates it, check that the build fails with a message naming the offending edge, and revert.
A rule nobody has watched fail is a rule nobody knows is wired up.
