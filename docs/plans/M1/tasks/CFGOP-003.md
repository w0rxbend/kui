# CFGOP-003 — `checkArchitecture` rules A9 and A10, with their build tests

- **ID:** CFGOP-003
- **Title:** `checkArchitecture` rules A9 and A10, with their build tests
- **Milestone / Feature:** M1 / OT-003
- **Owner role:** Infrastructure Lead
- **Context / service:** `build-tests`, `build.mill`
- **Size:** S
- **Dependencies / blocked by:** CLADP-001

## Goal (user value)

The first Kafka client in the codebase does not become the last one that is in the right place.
A developer who adds `libs.kafka` to an `api` module, or points a service's `application` at its
own `infrastructure`, is told in the same minute — by the build, naming the rule and the reason —
rather than in a review six months later that has to argue the point from first principles.

## Scope

Two rules in `kui.build.ArchitectureRules`, in the same shape as A1–A8, plus their unit tests.

- **A9** — a service's `application`, `contract` or `api` module may not depend on that service's
  `infrastructure` module.
- **A10** — `libs.kafka`, `libs.kafkaAuth`, `org.typelevel:fs2-kafka`, `com.github.fd4s:fs2-kafka`
  and `org.apache.kafka:kafka-clients` may appear only on an explicit allow-list of modules.

Both are DEVPLAN §10 decision D3. Neither rule is new *thinking*: A8 already does exactly what
A10 does, for exactly one module, and A3 already does part of what A9 does, for exactly one
layer of exactly one kind of service. This task generalises both, now that
`services/cluster/infrastructure` exists and makes the gap reachable.

## Non-goals

- **No new checking mechanism.** The rules are still checked against *declared* dependencies, not
  bytecode (ADR-041 §3). No classpath scanning, no `jdeps`, no compile plugin.
- **No rule about what an `infrastructure` module may depend on.** A9 constrains who may point
  *at* it. What it may point at is A1's and A5's business plus ordinary review, and inventing a
  rule for it before a second `infrastructure` module exists would be guessing.
- **No renumbering, rewording or refactoring of A1–A8.** Their tests are the record that they
  behave as written; a diff that touches them makes this task unreviewable.
- **No A7.** There is no rule A7 in `ArchitectureRules` today and this task does not invent one.
  ADR-041's numbering has a hole in it; leave it. (Noted for CFGOP-008 to record in the ADR-041
  amendment as a known cosmetic inconsistency, not to fix here.)

## Design references

ADR-041 (layering rules, machine-enforced — read both amendments), ADR-004 §3 (why the gateway
holds no Kafka client, which is A10's precedent), ADR-042 §5 (why `libs/config` is on the
allow-list), M1 DEVPLAN §5.2 and §10 decision D3, and the existing
`build-tests/src/kui/build/ArchitectureRules.scala` — the new rules must read like the ones
already there, including the `why` strings, which are what a developer sees when the build fails.

## Files to change

```
build-tests/src/kui/build/ArchitectureRules.scala        (a9, a10, the allow-list, check())
build-tests/test/src/kui/build/ArchitectureSuite.scala   (the cases below)
docs/adr/ADR-041-layering-rules-machine-enforced.md      (amendment: A9 and A10)
ARCHITECTURE.md §3                                       (the rule table gains two rows)
```

`build.mill` is **not** changed by this task: `checkArchitecture` already calls
`ArchitectureRules.check` over the whole module graph, so a new rule inside `check` is picked up
with no wiring. DEVPLAN §6.5 names CFGOP-003 as the only task that may edit the architecture rule
table, and that table is `ArchitectureRules.scala`.

## Public Scala signatures to implement

```scala
package kui.build

object ArchitectureRules {

  /** Modules that may hold a Kafka client, as roots: a module id equal to one of these, or
    * beneath it (so `libs.kafka.test` and `services.cluster.infrastructure.test` are covered
    * without being listed).
    *
    * Five entries, and a sixth has to be argued in the commit that adds it. That is the whole
    * mechanism: the rule is the list, the list is short, and `KafkaAllowListSuite` asserts each
    * entry individually so deleting one silently is not possible either.
    */
  private val KafkaAllowedRoots: Set[String]

  /** True for `services.<s>.infrastructure` and anything beneath it. */
  private def isInfrastructureModule(id: String): Boolean

  private def a9(module: ModuleFacts): List[Violation]
  private def a10(module: ModuleFacts): List[Violation]
}
```

`check` gains `a9(module) ++ a10(module)` in the **unconditional** group, beside A4, A5, A6 and
A8 — not in the `isTestModule` guarded group. The reason is the one already written above
`isTestModule`: A1–A3 are about what a *layer* may know and a suite is not its layer, but A9 and
A10 are about a component reaching somewhere it must never reach, and a suite that reaches there
is telling you something true about the production code beside it. A test that needs a Kafka
client is a test of a module that is allowed one.

## The rules, exactly

### A9

Fires when `module.id` is `services.<s>.<layer>` (or a `.jvm` / `.js` / `.test` child of one)
with `layer ∈ {application, contract, api}`, and `module.moduleDeps` contains any module under
`services.<s>.infrastructure`.

`why`:

> a service's application, contract and api layers are stated in terms of ports, and only its app
> module wires an adapter in; a layer that can see an adapter will eventually call one and the
> port becomes decoration (ADR-041 A9)

`domain` is deliberately absent from the layer list: A1 already forbids a domain module every
dependency except `libs.kernel`, so including it here would report the same edge twice with two
different reasons, and a developer would have to fix it twice to find out they were the same
problem.

### A10

Fires when `module.id` is **not** under any of `KafkaAllowedRoots`, and `module.moduleDeps`
contains `libs.kafka` or `libs.kafkaAuth` (after `coreModuleOf`, so `.jvm` children count), or
`module.mvnDeps` contains one of the three `KafkaArtifacts`.

`KafkaAllowedRoots` is exactly:

| Root | Why it is on the list |
| --- | --- |
| `libs.kafka` | it *is* the Kafka adapter layer |
| `libs.kafkaAuth` | it renders Kafka client properties; it holds `kafka-clients` for the constant names, not a client |
| `libs.config` | the Kafka `ConfigStore` adapter of ADR-042 §5 — the store is a Kafka client, so this edge is the point of the ADR and not a leak |
| `libs.testkit` | the Testcontainers Kafka topology (CFGOP-004); a test fixture that starts a broker has to be able to talk to it |
| `apps.allinone` | a composition root, which is where adapters are constructed |

plus, matched structurally rather than listed, **any `services.<s>.infrastructure`** and **any
`services.<s>.app`** — because the rule has to hold for the nine services M2–M8 add without
someone remembering to extend a list, and both of those are composition or adaptation by
definition.

`why`:

> org.apache.kafka must be importable in exactly the places that adapt it: a service's
> infrastructure module, libs/kafka and libs/kafka-auth themselves, libs/config for the metadata
> store adapter (ADR-042 §5), libs/testkit for the container fixtures, and a composition root
> (ADR-041 A10, generalising A8)

**A8 stays.** It is now a special case of A10, and it stays for two reasons: its message is
better for the case it covers (it says *why the gateway in particular* holds no client, which is
ADR-004's central argument), and a gateway module that acquired a Kafka dependency should fail
two rules rather than one. Two violations for one edge is acceptable here and not in A9's case,
because here they say genuinely different things.

## Decisions taken here

**D-1 — `e2e` is not on A10's allow-list.** The end-to-end suite drives Docker Compose through
the `docker compose` command line and asserts through HTTP; it has no reason to hold a Kafka
client, and CFGOP-007's broker is a Compose service rather than a library. If a later milestone
needs one there, adding the sixth entry is the argument the rule is designed to force.

**D-2 — the allow-list is asserted entry by entry, not as a set.** `KafkaAllowListSuite` has one
test per entry, each of which constructs a module with that id holding `libs.kafka` and asserts
*no* violation. A single assertion over the whole set would still pass if somebody replaced the
list wholesale, and the point of R-7 in the DEVPLAN's risk register is that the list must be hard
to widen quietly.

**D-3 — the real graph is checked too, but not in this suite.** `ArchitectureSuite` is unit tests
over `check` with hand-built `ModuleFacts`, exactly as its header comment says, and it stays that
way. The real module graph is checked by `./mill checkArchitecture`, which is already in the
build's definition of done (DEVPLAN §9.3). Both are required by this task's acceptance criteria.

## Library coordinates

None. `build-tests` already has MUnit.

## Acceptance criteria

```
$ ./mill build-tests.test
kui.build.ArchitectureSuite:
  + A9: an application module may not depend on its own service's infrastructure
  + A9: a contract module may not depend on its own service's infrastructure
  + A9: an api module may not depend on its own service's infrastructure
  + A9: an app module may depend on its own service's infrastructure
  + A9: a module may not be reported twice for one edge that A1 also forbids
  + A10: a module outside the allow-list may not depend on libs.kafka
  + A10: a module outside the allow-list may not pull in kafka-clients or fs2-kafka
  + A10: a gateway module with a Kafka dependency fails both A8 and A10
kui.build.KafkaAllowListSuite:
  + libs.kafka may hold a Kafka client
  + libs.kafkaAuth may hold a Kafka client
  + libs.config may hold a Kafka client
  + libs.testkit may hold a Kafka client
  + apps.allinone may hold a Kafka client
  + any service's infrastructure module may hold a Kafka client
  + any service's app module may hold a Kafka client
  + a test module inherits its parent's permission

$ ./mill checkArchitecture
checkArchitecture: <n> modules, no layering violations
```

The second command is the one that matters: it must pass against the real graph **after**
CLADP-001 has created `services/cluster/infrastructure`, which is why this task depends on it.
If it does not pass, the finding is a genuine layering defect in the module wiring and it is
fixed in the module that declares the edge — never by widening a rule.

## Tests required

The two suites above. Three properties of every case, so they read like the existing ones:

1. Each violation case uses `expectOneViolation(rule, module, dep)`, the helper already in
   `ArchitectureSuite`, so a rule that fires twice or fires on the wrong edge fails.
2. Each rule has at least one **negative** case — a legal graph that must produce nothing.
   A rule with only positive tests is a rule that could be `always fail` and still be green.
3. The A9/A1 overlap case asserts the violation count is exactly one for
   `services.cluster.domain -> services.cluster.infrastructure` and that its rule is `A1`.

## Observability

`checkArchitecture` already prints the module count and every violation with rule, module,
offending dependency and reason. Nothing is added: a build rule's observability is its failure
message, and A9's and A10's `why` strings are written to be read by whoever tripped them.

## Degraded behavior

None. A layering violation fails the build. There is no warning mode and there must not be one:
a warning that can be ignored is ignored, which is the argument `-Werror` is already built on.

## Docs to update

`docs/adr/ADR-041-layering-rules-machine-enforced.md`: an amendment adding A9 and A10 with the
`why` text above and the allow-list table, dated, in the same style as the existing amendments.
`ARCHITECTURE.md` §3: two rows in the rule table.

## Deviations

Recorded during implementation, 2026-09-04.

**D-A — three existing cases now report two rules for one edge, and their assertions say so.** A9
overlaps A3 on `services.<s>.application -> services.<s>.infrastructure`, and A10 overlaps A8 on
every gateway Kafka edge. The spec anticipates the second (and argues it is right, because A8 and
A10 answer different questions) and not the first. Removing the `infrastructure` clause from A3
would have meant rewriting a rule this task is told not to touch, so both fire, and
`ArchitectureSuite` gained an `expectViolations(edge)(rules*)` helper beside `expectOneViolation`.
The rules themselves are unchanged.

**D-B — `KafkaAllowListSuite` has a tenth case the spec does not list.** D-1 argues that `e2e` is
deliberately off the allow-list; that argument is now a test, so removing the boundary breaks
something with a name that says what was removed.

**D-C — the ADR-041 amendment was already written.** The M1 gate review wrote Amendment 3 with A9,
A10 and the refusal to widen A1 (finding F-13). This task added the two rows to `ARCHITECTURE.md`
§3 and the paragraph explaining the allow-list, and wrote no new ADR text.
