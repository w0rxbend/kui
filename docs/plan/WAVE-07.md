# Wave 7 — the tenth service, the relay the ninth never got, and the forty-six rules the last wave shipped ungated

**Milestones covered:** **the rest of M8** in [ROADMAP.md](ROADMAP.md) — `services/alerts` is built,
tested, imaged and routed, and its **ADR-035 stream answers 404 through the gateway**, so the one
mechanism the milestone exists to provide is missing from the product — and **half of M9**,
`services/connect`. `services/ksql` and M10 are wave 8's, and the split is stated in the roadmap with
the numbers it was made on.

**Why this shape.** Wave 6 spent ten building packets and delivered one service that could not be
routed until integration and a stream that still cannot be reached. It also shipped **46 new ungated
rules**, 37 of them undisclosed, while its three adversaries closed **75** and its ten verification
passes closed **none**. Two things follow, and both are in this plan rather than in an instruction.
Every one of the 46 is an **owned rule** in the packet whose tree it lives in, quoted with the exact
mutation that leaves the suite green — because a named owned rule has been closed 10/10 in wave 4 and
10/10 in wave 6, and it is the only mechanism in this project with a perfect record. And the third
adversarial packet becomes a **closer**: it runs after the building packets freeze, owns their test
trees, takes the verification reports as its input, and lands a case per finding.

**Parallelism.** `Owns` is disjoint across every packet: no two tasks may edit the same file, and no
packet owns a directory containing another packet's file. Six dependencies are declared, all on a
*stated shape* and none on a diff: W7-03 on W7-01's contract module and on the `build.mill` edge
W7-01 lands first; W7-04 on W7-01's endpoints and on W7-05's widened `FeatureId`; W7-05 on W7-02's
golden documents; W7-06 on W7-05's kernel store; W7-09 on W7-01 and W7-03 having landed before the
merged documents are regenerated; W7-A3 on all ten building packets having frozen.

**The tree you start from is green, and every figure here was measured at integration on 2026-09-07.**
`./mill __.compile` 7020/7020; `./scripts/run-tests.sh` **69 modules, 66 with tests, 3,767 cases**;
`./mill checkArchitecture` 165 modules, 10 rules, no layering violations; `./mill __.openApiCheck`
2188/2188 over **56 paths, 67 operations, 154 schemas**; `./mill __.checkFormat` 216/216;
`./mill __.fix --check` 4572/4572; `pnpm -C frontend test` **71 files, 1,631 cases**;
`pnpm -C frontend typecheck` exit 0; `node frontend/scripts/boundaries.mjs` 391 files in 9 packages;
`pnpm -C frontend a11y` **762 stories × 2 themes, no violations**;
`./scripts/feature-matrix-check.sh` **216 claims checked, all true**; and
`./deployment/compose/smoke.sh` **three consecutive runs from a torn-down stack**, on images built
from the tree, with nine services routed. Anything red is yours.

**One thing is red and it is not yours to hide.** `pnpm -C frontend e2e` is 86 passed, 2 skipped and
**one failure**: `e2e/brokers.spec.ts:57`, *names the broker and its address*, which passes 9/9 when
its own spec runs alone and fails inside the full run. Three separate agents have now seen it and
nobody has run it in both directions. It is W7-A1's item 1 and house rule 9 applies: explain it
before patching it, or say you could not.

**The designed intermediate state.** W7-01 adds `services/connect` and regenerates its own
`services/connect/api/openapi.json`; W7-03 adds the routing. `./mill __.openApiCheck` will be **red on
`services.gateway.api` and green on the other eight** until W7-09 regenerates `docs/api/openapi.json`
and `docs/api/openapi.browser.json` — and `./scripts/feature-matrix-check.sh` will be red with it,
because those two documents are what its merged-document section counts. **That is not a theory this
time: it is exactly what happened in wave 6, where the check went red at integration with 12
disagreements and was repaired by the integrator rather than by the wave.** W7-09 turns both green
and nobody else edits a figure in a document they do not own.

**`stash@{0}` is still in this repository and this wave does not drop it.** It holds 46 files and
3,256 insertions of six packets' wave-5 mid-flight work, created by accident and unpoppable without
conflicts; its owners carried that work forward and the entry is stale. Wave 6's plan told its
integrator to drop it and its integrator did not, which was the right call: a document asserting that
data is disposable is not evidence that it is. Whoever wants it gone runs
`git stash show --stat stash@{0}` first, confirms nothing in it post-dates `8fc0c77`, and drops it
deliberately as its own act — not as a line item in a wave.

**House rules.** Read them before starting. The first fourteen are wave 6's, with **13 replaced**
because it did not execute; three are new and each is a wave-6 finding.

Backend: Scala 3 + Mill, ADR-041 layering (machine-enforced by `./mill checkArchitecture`), Tapir
endpoints, ADR-034 error envelope, ADR-039 capability fold, ADR-035 streaming, ADR-045
plan→token→confirm for destructive mutations. Frontend: TypeScript + SolidJS 2 + Vite under
`frontend/` (pnpm, not Mill), Storybook-first — a story per state — and browser types generated from
`docs/api/openapi.browser.json`. Comments explain **why**, not what, at roughly the 25% density of the
surrounding code. There is no ESLint or Prettier; the codebase is hand-written at 100 columns (Scala
at 110, per `.scalafmt.conf`). **Do not reformat a file you are not otherwise changing.**

1. **No new stylesheet files, with exactly one exception.** `build-tests`' `CssReferencesSuite`
   requires every stylesheet on disk to be named exactly once in
   `frontend/packages/kernel/styles/index.css`. The seventh feature package needs one stylesheet, in
   its own `styles/` directory, in the shape `feature-alerts/styles/80-alerts.css` has — **and the
   index that must name it is in W7-05's tree, not W7-04's.** One file, one line, one owner.
2. **No new custom properties in `frontend/packages/kernel/styles/10-tokens.css`.** A Scala mirror
   lives in `build-tests/src/kui/build/design/Tokens.scala`, which no packet owns.
3. **No new `ErrorCode`.** `./mill frontend.apiConstants --check` compares
   `frontend/packages/api/src/constants.generated.ts` byte for byte. The thirty-one that exist cover a
   Connect adapter: a worker that will not answer is `KUI-UPSTREAM-UNAVAILABLE`, a connector that is
   rebalancing is `KUI-CONNECT-REBALANCING`, which has been declared and unused since wave 1.
4. **A gate you cannot make fail is not a gate.** Every packet's acceptance list has a **mutation
   line**: name one change to the shipped code that reverses the packet's headline rule, apply it, run
   the acceptance suite, record which case went red, revert it.
5. **Report a mutation that stayed green.** Alongside the red one, apply at least one mutation to a
   rule you did *not* write the test for and record what happened.
6. **No honest-refusal-only acceptance.** No packet may satisfy its acceptance list entirely with
   assertions that something is absent, refused or not configured.
7. **A card may not be drawn from a figure the design did not name.**
8. **`./mill a.test b.test` runs nothing.** Mill parses the second task path as a vararg to the first,
   MUnit takes it as a test-name filter, it matches nothing, and every suite reports `0 failed,
   1 ignored, 0 total` while Mill exits `SUCCESS`. The separator is `+`.
9. **A root cause is not established until it has been run in both directions.** Wave 6 finally
   discharged the standing example — the `kui-metrics` `depends_on` was run *without*, the stack came
   up healthy in 25s and `smoke.sh` passed, so that line is headroom and not a repair and the compose
   file now says so. The wave-4 failure it was once thought to explain is still unexplained, and is
   recorded as unexplained. The new standing example is `brokers.spec.ts:57`.
10. **State the scope of a measurement, and expect the next reader to re-run it.**
11. **A comment that names a figure must name a figure something in the tree can check.**
12. **Two sides of one wire are one packet, and the binding case decodes the encoder's own output.**
    This is the rule that closed M7: ten golden documents, read by a Scala suite and by a browser suite
    over the same files, both red when one is moved. It is why W7-02 owns the alerts goldens and W7-05
    owns the reader that must decode them, with the file path stated on both sides.
13. **REPLACED. A verifier's finding is an owned rule or a case, never a paragraph.** Wave 6's rule
    said every verification pass owns the test tree of the packet it verifies and lands a case for
    every hole it finds. Forty-six holes were found and **zero** cases were landed, because a verifier
    runs while the packet it verifies still owns that tree. So: a verification pass reports, with the
    exact mutation and the shape of the case that would close it; **W7-A3 lands the cases**, after the
    building packets freeze; and any finding that is neither closed by A3 nor written into the next
    wave's plan as an owned rule is not filed.
14. **Adversarial packets run in a `git worktree`.** Revert by restoring bytes you saved yourself,
    never with `git checkout --`, `git restore --source=HEAD` or `git stash`.
15. **NEW: an edge has an owner.** `services/alerts` shipped complete and unroutable because the Mill
    module was in one packet, the gateway row in another, and the *dependency edge* between them —
    `alerts.contract.jvm` in `services.gateway.api`'s `moduleDeps` — was in neither packet's `Owns`.
    Whoever owns `build.mill` lands **every** module-dependency edge this plan declares **first, before
    any of its own work**, and its acceptance list includes compiling the consumer module that needs
    the edge. A partition over files does not partition the edges between them.
16. **NEW: a stream ships its relay.** `ContractRouting.derive` decodes and re-encodes JSON, so it
    cannot carry an event stream; every SSE endpoint needs a hand-written relay in the shape of
    `MessageStreamRoutes`. `services/alerts` shipped a stream with no relay and nobody noticed,
    because a contract entry and a green suite look exactly like a routed stream. **The criterion for a
    stream is `curl -N` through the gateway, in the report, with the frame quoted.**
17. **NEW: a claim about your own gate is measured, not asserted.** W6-09 reported that a green false
    figure now costs five edits across three files where it used to cost one; measured at integration,
    it costs **one**. If your packet claims a gate is now harder to defeat, defeat it — publish the
    cheapest attack you found and its cost, and if the cheapest attack is still one line, say so.

`pnpm` is not on the default PATH in a non-login shell; it lives at `~/.local/share/pnpm/bin/pnpm`,
with node at `~/.nvm/versions/node/v26.8.1/bin`.

**Running a browser suite.** `pnpm -C frontend e2e` drives a stack it does not start. Build both
images from the working tree first — `./mill deployment.docker.allinone.docker.build` and
`docker build --no-cache -f deployment/frontend/Dockerfile -t kui-frontend:0.1.0-SNAPSHOT .` — then
`deployment/quickstart/quickstart.sh`, and **say in the report which image ids you drove**; wave 6 had
three packets quote three different digests for one tag. The metrics and alerts buffers are in memory,
so a restarted KUI needs a minute before a card has anything to draw. To make the alerts screens show
anything at all, set `diskUsedWarningPercent: 1` in `deployment/quickstart/kui-quickstart.yaml`,
restart `kui-quickstart-kui`, and revert the file afterwards — that is how the seeded event in M8's
criterion was produced at integration, and it opens exactly one `storage` event.

**Running the a11y sweep** is three commands, not one — build Storybook, serve `storybook-static` on
`:6017`, then sweep. A single theme abort with no axe violation is not an a11y failure: re-run the
named story alone before reporting one. W7-05 owns the harness.

---

## The guard files

Everything below asserts a shape, a count or a roster that this wave's work can invalidate. None is
owned by the packet most likely to break it — that is the point of listing them. If your change makes
one red, it is your change that is unfinished, and the repair goes in the packet that owns the guard,
named through `needsOutsideOwnership` if that is not you.

| Guard | What it pins | Who breaks it |
| --- | --- | --- |
| `services/gateway/api/test/.../routing/ServiceContractsSuite.scala` | A hard-coded `Set` of **seven** service ids — `cluster, topic, consumer, message, schema, metrics, alerts` — plus an assertion that `ServiceContracts.of(alerts)` is `AlertsEndpoints.all` and **not** the stream endpoint. A tenth service moves both | **W7-03**, which owns the map and the suite |
| `build.mill` — `services.gateway.api`'s `moduleDeps` | The dependency edge that made the ninth service unroutable. `connect.contract.jvm` must be there **before** W7-03 can compile its row | **W7-01**, first, per house rule 15 |
| `apps/allinone/test/.../AllInOneWiringSuite.scala` | The startup-log string and the mounted-path set, which now require the alerts feed to be proxied by the one listener this process binds. A tenth service moves both, and the **alerts stream** joins them once W7-03's relay exists | **W7-10** |
| `docs/api/openapi.json`, `docs/api/openapi.browser.json` — `./mill services.gateway.api.openApiCheck` | A byte comparison against a fresh Tapir render of every service's endpoints. `build.mill` aims the *gateway* module's check at these, so the gateway module goes red for somebody else's endpoint | W7-01 and W7-03; repaired only by **W7-09** |
| `scripts/feature-matrix-check.sh` + the `<!-- checked: merged-document -->` regions in `docs/adr/ADR-048-*.md` and `frontend/packages/api/README.md` | **56 paths, 67 operations, 154 schemas, `X-Kui-Principal` on 52 operations over 41 paths, `X-Csrf-Token` on 21.** The script prints `216 claims checked, all true` over five sections. It went red at wave 6's integration and was repaired by hand, which is the failure mode this row exists to prevent | W7-01 and W7-03; repaired only by **W7-09** |
| `frontend/packages/api/src/schema.d.ts` + `ci.yml`'s regenerate-then-`git diff --exit-code` | The browser's types. **Nothing in Mill checks this**; that CI step is the only gate | **W7-09** |
| `frontend/scripts/bundle-shape.mjs` | Every `frontend/packages/feature-*` package present as a **dynamic** entry in the Vite manifest — six today, seven after `feature-connect`. Roster read from the filesystem, so it joins automatically and fails the build if it is statically imported | **W7-04** |
| `frontend/packages/kernel/styles/index.css` + `build-tests`' `CssReferencesSuite` | Every stylesheet on disk named exactly once | W7-04 adds the file, **W7-05 adds the line** — house rule 1 |
| `frontend/packages/shell/src/features/registry.ts` | *"The body of a `load` thunk must be a bare `import("@kui/feature-…")` and nothing else"* — the property `bundle-shape.mjs` measures | **W7-04**, both ends |
| `scripts/run-tests.sh` | **69 modules, 66 with tests, 3,767 cases**, and the three it names out loud as declaring a test module with no test sources: `services.identity.app.test`, `services.message.app.test`, `services.topic.app.test`. Derived from `./mill resolve __.test`, so a tenth service simply appears | W7-01; and **W7-A2**, whose subject is those three |
| `services/metrics/contract/test/resources/golden/*.json` + `frontend/.../overview/wire.golden.test.ts` | **Ten** documents read by a Scala suite and a browser suite over the same files. Moving one reddens both sides. This is the pattern W7-02 and W7-05 must repeat for the alerts wire | W7-02 (Scala side), W7-06 (browser side) |
| `libs/contracts-core/test/resources/golden/*.json` + their Scala twins | Cluster and topic DTOs in *two* places per document | nobody this wave — no shared DTO changes. If you are about to, stop |
| `services/gateway/api/test/.../openapi/OpenApiMergeSuite.scala` | A hard-coded sorted path list over `gatewayDoc + clusterDoc` only | W7-03 if it adds a *gateway* path (it must not; the alerts relay is a proxy route, in the shape `MessageStreamRoutes` already has) |
| `services/gateway/api/test/.../openapi/MergedDocumentShapeSuite.scala` | `writes.size` over `ServiceContracts.proxied(cluster)`; distinct operationIds across the merged document. Connect's pause/resume/restart are writes, so this moves | **W7-03** |
| `libs/config/test/src/kui/config/ShippedConfigurationSuite.scala` | A list of shipped configuration files reconciled against disk in both directions. **Its `notKuiConfiguration` partition can be widened to `.yml` with the suite green**, which is W7-10's owned rule | **W7-10**, both ends |
| `deployment/compose/smoke.sh` | The **contract** set scraped from `ServiceContracts.byService` against the containers; twelve exporter line shapes; four alerts assertions. A tenth contracted service that is not a container fails the preflight before a container starts | W7-10 owns the script; W7-03 owns the Scala it scrapes |
| `docs/FEATURE_MATRIX.md` rows vs. its own prose, and `README.md` | **189 rows; 70 COMPLETE; 178 in scope; 39% delivered**, all inside checked regions, and `DECISIONS.md` now machine-compared against `docs/adr/ADR-*.md` in both directions at **53 rows over 53 ADRs** | every packet that finishes a capability; repaired by **W7-09** |
| `build-tests/**` | The token mirror and the stylesheet roster. Unowned — **except two comment lines** named in W7-09's block, which claim a Mill task that does not exist | house rules 1 and 2 forbid the rest |
| `frontend/packages/api/src/constants.generated.ts` | 31 error codes, byte for byte | house rule 3 forbids it |
| `./mill __.checkFormat`, `./mill __.fix --check`, `./mill checkArchitecture`, `./scripts/run-tests.sh`, `./scripts/feature-matrix-check.sh`, `./deployment/compose/smoke.sh` | **All six are green at the start of this wave**, re-measured at integration. Anything red here is yours | whoever makes it so |

---

## W7-01 — `services/connect`: the tenth service, and the edge that made the ninth unreachable

**Owns**
```
services/connect/**                                          (new)
build.mill
docs/adr/ADR-054-connect-endpoints.md                        (new)
```

**Contract.** Everything outside the service shipped in wave 1 and is still unused: the whole Connect
RBAC closure in `libs/security-core/.../Vocabulary.scala`, `ConnectName`/`ConnectorName`/`TaskId` in
`libs/kernel`, `ErrorCode.ConnectRebalancing`, and `ConnectClusterSettings` per cluster in
`libs/config`. Do not widen any of them. The worked example is `services/metrics`, which is now
complete and closed, and whose contract module carries **ten** golden documents — copy that shape, not
the shape of a service that had none.

**Do**
1. **First, before any of your own work, land every dependency edge this wave declares in
   `build.mill`, and prove it compiles.** House rule 15 exists because `services/alerts` shipped
   complete and unroutable in wave 6: the module was in one packet, the gateway row in another, and
   `alerts.contract.jvm` in `services.gateway.api`'s `moduleDeps` was in neither. The edges this wave
   needs are: `connect.contract.jvm` on `services.gateway.api` (W7-03), and any test dependency named
   in an adversarial packet's `needsOutsideOwnership`. Your acceptance list includes
   `./mill services.gateway.api.compile` **with a stub contract object present**, so that W7-03 is
   never blocked on you.
2. The six ADR-041 layers over the Connect REST API: connector list with expanded status, per-task
   state, and the failure reason §7.7 asks for — a task's `trace`, rendered as a reason and never as a
   status word KUI invented.
3. Pause, resume and restart as **mutations**, audited. Copy `services/consumer`'s `MutationGuard`:
   a cancelled mutation is `MutationOutcome.Unknown`, never `Failed`. W6-A1 repaired the two services
   that had this wrong; do not create a third.
4. A worker that is rebalancing is `ErrorCode.ConnectRebalancing`, which is a **transient** state and
   not a failure: it must not dim the connect capability (ADR-039 §6), the way a refusal must not.
5. **The stream, if you ship one, ships its relay.** House rule 16. If per-task state is polled rather
   than streamed, say so in ADR-054 and say what an operator sees while a restart is in flight.
6. ADR-054: which Connect API version is assumed, what happens on a worker that answers a connector
   list and refuses status, why a task with no trace is not a task with no problem, and the retention
   (if any) of anything this service keeps.
7. **The golden documents, in your own contract module, from your own encoder.** House rule 12. Not a
   hand-written literal: render the DTO and commit what came out.

**Do not** seed a connector to make a screen look alive, and do not add a `kui.clusters.*.connect` key.

**Acceptance**
```
./mill services.connect.__.test
./mill services.gateway.api.compile        # the edge from item 1, before anything else
./mill checkArchitecture
./mill services.connect.__.checkFormat
./mill services.connect.api.openApi        # regenerate this module's own document, and commit it
./mill services.connect.api.openApiCheck
./scripts/run-tests.sh                     # the module count moves; no module may ship with no test sources
```
Required cases, by name: a worker that names three connectors and their task states reaches the wire
with each task's state; a connector whose worker is rebalancing is reported as rebalancing and does
not dim the capability; a restart a principal without `CONNECT:RESTART` asks for is refused before the
worker is called; a cancelled restart is audited as `Unknown`; a task that failed carries the worker's
own trace and never a word KUI chose; a deployment with no Connect address answers `not_configured`
with a 200.
**Mutation line:** make the rebalancing arm report `ReasonCode.UpstreamUnavailable`. Name the case
that goes red. **And a green one:** mutate whatever bound your list endpoint keeps and report whether
anything notices.

---

## W7-02 — The two services that shipped ungated: `alerts`, `metrics`, and a golden the browser can read

**Owns**
```
services/alerts/**
services/metrics/**
docs/adr/ADR-052-metrics-endpoints.md
docs/adr/ADR-053-alert-events.md
```

**Contract.** Both services are built, both are green, and **seven rules across them are ungated**,
every one found by verification and none disclosed by the packet that wrote it. They are quoted below
with the mutation that leaves the suite green today. Four of the seven are about the number the bell
and the card draw, which is the whole of M8.

**Do**
1. **`services/alerts`, the four. Each is an owned rule: close it with a case, then re-apply the
   mutation and watch it go red.**
   * `infrastructure/.../InMemoryAlertStore.scala`, `feed`: `openCount = ordered.count(_.isOpen)` →
     `ordered.take(limit).count(_.isOpen)` leaves all 142 cases green. The open count becomes a page
     count — and `AlertUseCases.acknowledge` and `AlertsRoutes.changes` both read the feed with
     `limit = 0`, so under this mutation every acknowledgement response and every SSE frame carries
     `openCount = 0`. The case that exists (`AlertFeedSuite:39`) asserts `AlertsRig.FakeStore`, a
     hand-written re-implementation of the method. **Assert the shipped store, with a page smaller
     than its contents.**
   * Same file, same method: moving the marker write ahead of the unread count makes `?markRead=true`
     answer `unreadCount = 0` for ever. Green. The existing case again asserts the fake.
   * Same file, `acknowledge`: dropping `updates.publish1(cluster)` leaves everything green, so the
     service's one write wakes no subscriber. `InMemoryAlertStoreSuite:197` gates `record`'s
     publication and there is no counterpart for the write.
   * `api/.../AlertsRoutes.scala`, `eventsRoute`: `markRead` can be dropped on the way to the store
     with every case green — decoded, validated, documented in the OpenAPI file and thrown away, and
     ADR-053 §6's whole argument is that this parameter *is* the read marker.
2. **`services/metrics`, the three.**
   * `application/.../MetricsUseCases.scala:171`: `MetricsReading.Measured(observed.value, observed.at)`
     → `(observed.value, now)` is green. The *stale* half of this rule is gated and the *fresh* half is
     not, so a current gauge may be stamped with the request instant rather than the scrape it came
     from — verbatim the defect the packet's own narrative was built on.
   * `infrastructure/.../PrometheusExposition.scala:259`: `dimensionsOf(sample) == Set(DelayedOperationLabel)`
     → `dimensionsOf(sample).nonEmpty` is green. The throughput reader's twin no-double-count rule has
     a named case; this one has nothing.
   * The third is in the browser and is **W7-06's**, stated here so neither packet writes it twice.
3. **The alerts golden documents, and they are the reason M8 is not closed by a suite.**
   `services/alerts/contract` is one of three contract modules with no golden. Render each of
   `AlertFeedResponse`, `AlertEventDto`, `AlertResolutionDto` and `AlertChangeDto` **from the encoder**
   and commit them at `services/alerts/contract/test/resources/golden/*.json`, read by a Scala suite
   that fails loudly when one is missing. W7-05 decodes the same files from the browser; the path is
   the contract and it is stated on both sides. This is what `ALERTS_EVENT_NAME = "alerts"` being a
   hand-copied mirror of `AlertChangeDto.EventName` costs today: rename the event server-side and the
   bell stops updating with every gate green, because `tools/error-codes` writes the five SSE names by
   hand and there is no `SseEventName.Alerts`.
4. **Three orphans, and each is either used or deleted.** `AlertsApi.scala:153`'s `GeneratedAt` has no
   reader at all; `AlertsCapabilities.Features` is asserted only against the expression it is defined
   as, so its case cannot fail; `AlertsCapabilities.EventsFeature`'s scaladoc says it is *"named once
   for the callers that only care about it"* and there is no such caller anywhere.
5. **ADR-053 and ADR-052 corrections.** ADR-053 gains the stream's routing status — the relay is
   W7-03's and the ADR must stop implying the stream is reachable. ADR-052 §11 and
   `deployment/metrics/kafka-jmx-exporter.yml` currently give **different reasons** for the same
   behaviour: the ADR argues the domain must not use `kui.topics.internalPrefix`, and the ruleset file
   explains the exclusion through exactly that config value. One of them is wrong; the ruleset file is
   W7-10's, so state the row.

**Acceptance**
```
./mill services.alerts.__.test + services.metrics.__.test
./mill checkArchitecture
./mill services.alerts.__.checkFormat + services.metrics.__.checkFormat
./mill services.alerts.api.openApiCheck + services.metrics.api.openApiCheck
```
Required cases, by name: the open count is the whole store's and not the page's, asserted against the
shipped store with a page smaller than its contents; `markRead` moves the marker after the unread
count is taken, against the shipped store; an acknowledgement publishes a frame; a `markRead=true`
query reaches the store; a fresh reading carries the instant of the scrape it came from and not the
instant of the request; a purgatory line carrying a second dimension is skipped; each golden document
decodes to the value its encoder rendered.
**Mutation line:** the `openCount` page mutation above. **And a green one:** mutate the retention the
alert store keeps and report whether anything notices.

---

## W7-03 — The gateway: a tenth service, and the relay the ninth never got

**Owns**
```
services/gateway/**
ARCHITECTURE.md
```
**depends on W7-01's contract module and on the `build.mill` edge it lands first.**

**Contract.** This is the packet M8 is waiting on. `GET /api/v1/clusters/{clusterId}/alerts/stream`
answers **404 `KUI-ROUTE-NOT-FOUND`** through a gateway built from this tree — measured at
integration on `kui-allinone` `sha256:9e36c66d5bf7`. `AlertsStreamEndpoint` is deliberately **not** in
`ServiceContracts.byService`, and that is correct: `ContractRouting.derive` decodes and re-encodes
JSON, which is the wrong thing to do to an event stream. What is missing is the hand-written relay,
and `MessageStreamRoutes.scala` in your own tree is the shape.

**Do**
1. **The alerts SSE relay.** Same shape as `MessageStreamRoutes`: pass the frames through without
   decoding them, carry the principal, propagate cancellation, and close when the upstream closes.
   The acceptance is `curl -N` through the gateway returning `200 text/event-stream` **and a frame**,
   quoted in the report (house rule 16). An acknowledgement is what produces one, once W7-02's
   `publish1` is gated.
2. `ServiceContracts` gains the tenth service and `ServiceContractsSuite`'s id `Set` gains it with
   an assertion of which endpoint list is in the map, in the shape the alerts row now has.
3. **Owned rule, and it is the SSRF guard's fail-safe default.**
   `api/src/kui/gateway/api/client/SttpServiceClient.scala`, `def resource`:
   `policy: UrlPolicy = UrlPolicy.Strict` → `UrlPolicy.Dev` leaves all 1345 cases green. The packet
   that shipped it disclosed it and gave the wrong reason: `ServiceClientFixture` does call
   `resource`, and the reason the mutation is invisible is that the fixture's base URL is a host
   *name*, which `SafeUrl` never resolves, so Strict and Dev behave identically. **The closing case
   builds a client through `resource` with no policy argument against a `127.0.0.1` stub and asserts
   it is refused.**
4. **`ARCHITECTURE.md` says two things that are not true of the tree, and it is gated by nothing.**
   Its §3 paragraph says `AlertsStreamEndpoint` *"is in `services/alerts/api/`, which the gateway may
   not depend on, so the relay cannot be written until it moves"* — it moved before wave 6 ended and
   the only copy is in `services/alerts/contract/src-jvm/`. And it names the alerts service's port as
   `ClusterFactsSource[F]`; the shipped port is `ClusterFactsPort[F]`. Re-read every claim in that
   paragraph against the tree before you rewrite it, and write down which ones you checked.
5. `checkArchitecture` must still report no layering violations with the relay in place: the gateway
   depends on `alerts.contract.jvm` and on nothing in `services/alerts/api`.

**Acceptance**
```
./mill services.gateway.__.test
./mill checkArchitecture
./mill services.gateway.__.checkFormat
./mill services.gateway.__.fix --check
# and, against a quickstart built from this tree:
curl -N -s http://localhost:8080/api/v1/clusters/quickstart/alerts/stream | head -5
```
Required cases, by name: the alerts stream is relayed byte for byte and not re-encoded; a client
cancelling the stream closes the upstream call; a principal without `ALERTS:VIEW` is refused the
stream before an upstream connection is made; the tenth service's contract is in the map and its
write moves `writes.size`; a client built through `resource` with no policy refuses a loopback
address.
**Mutation line:** delete the relay's route and show the case that goes red — and say what
`curl -N` answers. **And a green one:** mutate a timing constant in the readiness poller and report
whether anything notices.

---

## W7-04 — `feature-connect`, the seventh feature package, and the registration seam

**Owns**
```
frontend/packages/feature-connect/**                          (new)
frontend/packages/shell/src/features/**
frontend/packages/shell/package.json
frontend/tsconfig.json
frontend/e2e/connect.spec.ts                                  (new)
```
**depends on W7-01's endpoints and on W7-05's widened `FeatureId`.**

**Contract.** The sixth package landed in wave 6 and the seam worked exactly as designed: a bare
`import("@kui/feature-alerts")` in `registry.ts`, a filesystem-read roster in `bundle-shape.mjs`, and
a chunk of its own in the build with nobody editing a list. Do the same and change nothing about the
seam. `frontend/pnpm-lock.yaml` will move by the importer entry and the workspace link — that is the
mechanical consequence of the two `package.json` files you own, and CI's `--frozen-lockfile` needs
it; state it in `needsOutsideOwnership` as wave 6 did.

**Do**
1. The package: the connector list, per-task state, the failure reason, and pause/resume/restart with
   their permission gates. **A control gated on `kui.permits(action, subjectName)`, not on
   `kui.permits(action)`** — wave 6 shipped two consumer-group controls asking the weaker question and
   nothing could see it, because the test harness's `permits` helper discards the subject.
2. Stories for every state, including the two nobody draws: a worker that answered and named no
   connectors, and a connector whose tasks the worker refused to describe. Neither may render a zero.
3. `frontend/tsconfig.json`: add the seventh reference. **And note that wave 6 left a `/* … */` block
   comment inside that JSON file** — `tsc` accepts JSONC, but the file is a build seam several scripts
   read with strict parsers. Remove it or say why it stays.
4. `frontend/e2e/connect.spec.ts`: assert the capability's honest absence on the quickstart (which
   configures no Connect worker) **and** the working half against a worker you start, or say plainly
   that the working half was not run and why. House rule 6 forbids an all-refusal acceptance.

**Acceptance**
```
pnpm -C frontend test packages/feature-connect
pnpm -C frontend typecheck
node frontend/scripts/boundaries.mjs
node frontend/scripts/bundle-shape.mjs      # seven feature packages, all dynamic
pnpm -C frontend build-storybook && (serve) && node frontend/scripts/a11y-stories.mjs 'connect-|screens-connect'
pnpm -C frontend e2e e2e/connect.spec.ts
```
**Mutation line:** make one control's gate ask the weaker question — `kui.permits(action)` with no
subject — and name the case that goes red. If none does, your harness has wave 6's defect and the
harness is the finding. **And a green one:** mutate the empty-state sentence and report what happens.

---

## W7-05 — The kernel: the store the bell reads, and a click that navigates twice

**Owns**
```
frontend/packages/kernel/**
frontend/scripts/a11y-stories.mjs
```
**depends on W7-02's golden documents (path stated below).**

**Contract.** The kernel kept its owner for a second wave and shipped five ungated rules; it keeps it
for a third and this time the rules are named. Four of them are in the alerts store the packet wrote
itself, which is the same shape it was candid about in the files it inherited.

**Do**
1. **The four owned rules, all in `src/data/alerts/store.ts`.**
   * `applyRead()`: deleting `setStreamed(null)` leaves the whole frontend suite green — 71 files,
     1,631 cases. The comment two lines above states the rule it breaks: *"the read's own count
     supersedes whatever the frame carried … leaving both alive is how two screens come to draw two
     figures."*
   * `releaseHandle()`: moving `open?.close()` above `disposeWatch?.()` is green, and the comment
     there names the failure verbatim — a `closed by the client` read back as *"the server went
     away"* and shown to the user as an outage.
   * `read()`'s `if (asked !== episode || stopped)` and `connect()`'s `if (stopped) return;` are both
     green when weakened, and both are redundant depth rather than reachable gates. **Say which:
     either delete them, or construct the state that makes them reachable.** Wave 6's own complaint
     was that the capability store disclosed exactly this and the alerts store concealed its twin.
2. **The store's header states a rule the code does not implement.** It says *"a subscriber that sees
   a count it does not hold re-reads the feed"*; `subscriber.onEvent` calls `read(false)`
   unconditionally on every frame, so a busy cluster costs one full feed fetch per frame per open
   tab. Implement the comparison or delete the claim — and if you implement it, that is the case that
   proves the frame's count is used for something.
3. **`streamed` is never cleared on a failure path or on `stop()`.** `openCount()` can therefore
   answer a live number while `feed()` is `forbidden` or `failed`. Unreachable today only because
   `openCount()` has no product caller, which is item 4.
4. **Five of the store's eight accessors have no product caller** — `openCount`, `unreadCount`,
   `events`, `connection`, `lastReadAt` — and the shell re-derives the open count itself. Agree one
   answer with W7-06: the kernel's accessor is the number, or the accessor goes. Wave 4's dead export
   is the standing example of what happens if neither.
5. **The golden decode, and it is house rule 12 applied to the alerts wire.** Add a suite that reads
   `services/alerts/contract/test/resources/golden/*.json` **off disk** — the way
   `frontend/packages/shell/src/overview/wire.golden.test.ts` reads the metrics goldens — and decodes
   each through `data/alerts/events.ts`, failing loudly when a file is missing rather than skipping.
   That is what makes `ALERTS_EVENT_NAME` and every field name a checked mirror instead of a copied
   one.
6. **A live product defect, filed by W6-A3 and measured: `src/components/DataTable.tsx:308`.**
   `onClick={clickable() ? () => props.onRowClick?.(row()) : undefined}` takes no modifier into
   account, so a ⌘/ctrl/shift-click on a link inside a clickable row opens a new tab **and** navigates
   the current one. The exact edit is in W6-A3's report: take the event and short-circuit on
   `metaKey || ctrlKey || shiftKey || altKey || button !== 0`. Check `VirtualizedTable`'s row handler
   for the same shape while you are there.
7. `FeatureId` gains `"connect"`; `styles/index.css` gains the import naming `feature-connect`'s one
   stylesheet, at the number W7-04 states (house rule 1). And `src/feature/registration.ts:25` still
   names `frontend.checkBundleShape`, a Mill task that does not exist — the third surviving copy of a
   sentence two other files were corrected for.
8. Four scaladoc citations in `data/alerts/` point into the Scala contract about eleven lines above
   where the types now are. This packet's own argument is that citations are how a wire stops being
   guessed at.

**Acceptance**
```
pnpm -C frontend test packages/kernel
pnpm -C frontend test
pnpm -C frontend typecheck
pnpm -C frontend build-storybook && (serve) && node frontend/scripts/a11y-stories.mjs
node frontend/scripts/boundaries.mjs
```
**Mutation line:** delete `setStreamed(null)` from `applyRead()` and name the case that goes red.
**And a green one:** mutate a constant in the query cache's backoff and report whether anything
notices.

---

## W7-06 — The frame: five rules on the bell, and the card the dashboard never got

**Owns**
```
frontend/packages/shell/src/App.tsx  + app.render.test.tsx + shell.test.tsx
frontend/packages/shell/src/chrome/**   src/nav/**   src/data/**   src/routing/**
frontend/packages/shell/src/overview/**   src/pages/**   src/index.ts
frontend/packages/shell/styles/**
frontend/e2e/shell.spec.ts   dashboard.spec.ts   search.spec.ts   traffic.spec.ts
research/design/SCREENS-V4.md                          (the alerts-card paragraph and nothing else)
```
**depends on W7-05's kernel store.**

**Do**
1. **Five owned rules, every one on the bell or the address that reaches it, and every one green
   today across all 506 shell cases.**
   * `chrome/Notifications.tsx`, `badgeText`: `count > 9 ? "9+" : String(count)` → `String(count)` is
     green in the whole frontend suite. The only assertion of the cap anywhere is inside an e2e case,
     so a cluster with 147 open alerts draws a three-digit badge and nothing sees it.
   * `App.tsx`: the alerts store's `cluster: () => clusterForFrame()` → `() => undefined` **deletes**
     the cross-cluster guard rather than tightening it — the kernel reads
     `if (mine !== undefined && change.cluster !== mine)` — so every frame moves this bell's count
     whatever cluster it names.
   * `App.tsx`: deleting `if (chosen === undefined) return undefined;` from the per-cluster effect is
     green, and the comment block above it discusses at length which *other* line is safely deletable.
   * `routing/routes.tsx:156`: `{ path: "/alerts", component: gate("alerts") }` → `gate("topics")` is
     green. Deleting the route is caught; mis-binding it is not.
   * `App.tsx`'s `onRetryNotifications`: widening `alerts.feed().kind === "failed"` to
     `!== "loading"` is green, and puts a Try-again button under *"You do not have permission to see
     this cluster's alerts."* The packet that shipped it **disclosed this one as gated**; it is not,
     because the assertion it cited passes structurally under any props.
2. **The alerts card the dashboard has never had.** M8 says the tab, the card and the bell read one
   feed; the tab and the bell exist and `AlertsFeed`'s narrow layout has no production caller. The
   shell may not statically import a feature package (`bundle-shape.mjs` fails the build), so the
   dashboard card is drawn **by the shell from the kernel store**, beside the traffic cards, and reads
   the same `openCount` the bell reads. One number, three places, one source.
3. **The third of W7-02's metrics rules, because it is in this tree.**
   `src/overview/TrafficCards.tsx:382`: `if (state.kind !== "stale") return own;` → `|| true` is
   green. Request handlers, Top producers and Message size then draw hour-old figures with **no badge
   and no sentence**. The only assertion of that caption in the repository covers `ThroughputCard`.
4. **Agree the open count with W7-05 and delete the second derivation.** `src/data/alerts.ts`'s
   `openCountOf` re-derives the count under a different null rule from the kernel store's
   `openCount()`. Whichever survives, the other goes.
5. **Four exports in `src/data/alerts.ts` have no caller outside their own module** —
   `ALERTS_MARK_READ_PARAM` and `ALERTS_STREAM_SEGMENT` have none of any kind. The packet that wrote
   them reported that every export had a production caller. Use them or delete them.
6. `e2e/shell.spec.ts`'s bell case is the M8 criterion's browser half and it passes today with a
   seeded event. Extend it: **acknowledging that event moves the bell without a reload**, which is
   what W7-03's relay makes possible and what nothing has ever asserted.

**Acceptance**
```
pnpm -C frontend test packages/shell
pnpm -C frontend typecheck
node frontend/scripts/bundle-shape.mjs
pnpm -C frontend build-storybook && (serve) && node frontend/scripts/a11y-stories.mjs 'chrome-|shell-|screens-overview|screens-traffic'
pnpm -C frontend e2e e2e/shell.spec.ts e2e/dashboard.spec.ts e2e/search.spec.ts e2e/traffic.spec.ts
```
**Mutation line:** `gate("alerts")` → `gate("topics")` in `routes.tsx`, and name the case that goes
red after your work. **And a green one:** mutate the drawer's storage meter thresholds and report
whether anything notices.

---

## W7-07 — `feature-alerts`: eight rules, a fixture that contradicts itself, and a comment that is false

**Owns**
```
frontend/packages/feature-alerts/**
frontend/e2e/alerts.spec.ts
```

**Contract.** The package shipped 45 green cases and **eight ungated rules**, none disclosed. Every
one of them is a sentence the screen says about a cluster, which is the one thing this package exists
to get right.

**Do**
1. **The eight owned rules, with the mutations that are green today at 45/45.**
   * `model.ts`, `feedVoice`: the `evaluatedAt === undefined` arm can be made unreachable, after which
     a cluster the rules have never run on reads *"Nothing is open. The bell is quiet."* over an
     `openCount: 0` that measures nothing. The card's pill and body are gated for that document; the
     `PageHeader` voice above them is not, because no case mounts the route over an unevaluated feed.
     **This is the most serious of the forty-six.**
   * `AlertsFeed.tsx`, `message()`: `NO_MATCHES` → `NO_EVENTS` is green, so a page the reader's own
     filter emptied says the service is holding no events. `NO_MATCHES` and the whole `filtered` state
     are asserted by nothing — and the case named for it (`alerts.test.tsx:536`) leaves one row on
     screen, so its title promises what its body never tests.
   * `AlertsFeed.tsx`, `stateAction`: dropping `props.state.kind === "failed"` from the retry `Show`
     is green, so a `forbidden` read draws a Retry button that will be refused every time — against a
     rule the file's own header and a story's caption both state.
   * `model.ts`, `ruleRefusal`: dropping `|| report.status === "stale"` is green, so a stale
     evaluation is redrawn as *"KUI could not evaluate…"*, discarding a measurement. **And the
     mirror-image defect is live with no mutation at all**: `wire.ts` decodes `reason` for a stale
     rule and `RuleReports.tsx` renders it nowhere.
   * `RuleReports.tsx`, `unmeasured()`: dropping the `skipped === 0` guard puts *"0 subjects were
     skipped…"* on three of four rules in every fixture — a rendered zero, in the panel whose header
     is an argument against exactly that.
   * `model.ts`, `openPill`: swapping the first two guards is green, so a feed with neither figure
     draws no pill instead of saying the cluster has not been looked at.
   * `model.ts`: `SEVERITIES` can gain `"info"` with 45/45 green — a filter chip that can never match
     a row, against the file's own two-paragraph argument for exactly two.
   * `AlertsFeed.tsx`: the disabled acknowledge control's fallback reason can be emptied. Narrow
     today, and **it stops being narrow the moment W7-06 mounts the card**, which is this wave.
2. **A shipped comment asserts a behaviour the code does not have.** `data.ts:38` and
   `alertsRoute.test.tsx:156` both say *"This screen polls, so a `markRead=true` here would empty the
   bell of anybody who left the tab open on it."* The screen does not poll: `useQuery` has no
   interval and there is no `setInterval` anywhere in the package. Not sending `markRead` is still
   right, for the endpoint's own reason. Write that reason.
3. **A fixture contradicts itself and a shipped story draws the contradiction.**
   `documents/events-empty.json` carries `items: []`, `total: 0`, `openCount: 0` and four rule reports
   whose `openEvents` are 1, 0, 1, 1, so `AnsweredAndEmpty` draws *"holding no events for this
   cluster"* above a panel saying *"1 open event."* three times. **Regenerate the fixtures from
   W7-02's goldens** rather than by hand; that is what the goldens are for.
4. **One wire, one decoder.** `wire.ts` decodes the same document as the kernel's `events.ts`, with
   `undefined` where the kernel uses `null`. Collapse to the kernel's reader, and if the null/undefined
   boundary makes that more than mechanical — it does — say so and make the choice explicitly.
5. `e2e/alerts.spec.ts`: three of its four cases carry `test.skip` guards and one skips
   unconditionally on the quickstart. With the seeded event (see the running instructions above) the
   positive branches all fire, and they passed at integration. **Make the seeded run the asserted one**
   and say which cases can still skip and why.

**Acceptance**
```
pnpm -C frontend test packages/feature-alerts
pnpm -C frontend typecheck
node frontend/scripts/boundaries.mjs
pnpm -C frontend build-storybook && (serve) && node frontend/scripts/a11y-stories.mjs 'alerts-|screens-alert'
pnpm -C frontend e2e e2e/alerts.spec.ts     # against a stack with a seeded event
```
**Mutation line:** make `feedVoice`'s never-evaluated arm unreachable and name the case that goes red.
**And a green one:** mutate a fixture's `total` and report whether anything notices.

---

## W7-08 — The three feature packages that shipped nine ungated rules

**Owns**
```
frontend/packages/feature-topics/**
frontend/packages/feature-consumers/**
frontend/packages/feature-messages/**
frontend/e2e/topics.spec.ts   consumers.spec.ts   messages.spec.ts
```

**Contract.** Nine rules across two packages, quoted with their mutations. Two of them are permission
gates, which is what makes this packet a P1 rather than tidying.

**Do**
1. **`feature-consumers`, four.**
   * `GroupRoute.tsx:97-98`: `kui.permits(Actions.ConsumerGroupResetOffsets, props.groupId)` →
     `kui.permits(Actions.ConsumerGroupResetOffsets)` is green at 273/273, and so is the same edit on
     `mayDelete`. `kernel/src/state/session.ts:110-116` is explicit that the subjectless form asks
     *"the weaker question … the right answer for a list heading and the wrong one for a row's delete
     button."* An account granted the action on `analytics.*` gets a live Forget button on a group it
     may not touch. **The packet's own `permitsAllBut` helper compares `{resource, action}` and
     discards the subject, which is why no case can see it — fix the helper first.**
   * `GroupRoute.tsx:281-285`, `consequenceOfForget`: deleting the `held === 0` branch is green, so a
     destructive confirmation reads *"Removes this group's committed offsets on 0 partitions of
     orders.events"*. The function's own docstring states the rule.
   * `GroupDetail.tsx:198-200` and `ResetWizard.tsx:218`: both refusal fallbacks can be emptied with
     everything green — a disabled destructive control that will not say why. Both lines were
     rewritten in wave 6 **on the argument that their content is load-bearing**.
   * Disclosed and still open: `GroupRoute.tsx:77`'s `if (!cancelled)` guard. Closing it needs a
     router navigation to a second `groupId` mid-promise.
2. **`feature-topics`, five.**
   * The bulk confirmation's **title** can be forced to the Delete wording with 148/148 green: an
     Empty dialog headed *"Delete 2 topics?"* over a button reading *Empty topics*. The word, the
     label and the consequence are gated; the largest text on the dialog is not.
   * `TopicListPage.tsx:568` and `TopicsRoute.tsx:461`: both `disabledReason`s can be emptied, so a
     forbidden control is *hidden rather than explained* — the exact class the file's own header
     names, and §3.7's promise is *"disabled with its reason"*.
   * `TopicsRoute.tsx`'s bulk `onConfirm`: deleting `setSelected(new Set())` is green (the bar stays
     up over rows that no longer exist), and so is deleting `reload()` (the screen keeps showing the
     topics it just destroyed).
3. **And a product observation wave 6 left standing:** all seven `writeBlockedReason` calls in
   `TopicsRoute.tsx` hard-code `readOnly: false`, so a cluster's own read-only flag reaches none of
   the topic screens' write gates. The permission half was closed and the read-only half is wired to a
   constant on the screen that was being audited.
4. **Three more in `feature-topics` that wave 6 disclosed and did not close**, because a disclosed
   hole is still a hole: the bulk confirmation's glyph (`confirmIcon` forced to `"trash"`), the CSV
   export's per-cluster filename (`download("topics.csv", …)`), and the create poll's upper bound
   (`attempt < 6` → `attempt < 100`, so the *"it stops rather than spinning"* half of
   `settleAfterCreate`'s comment is carried by nothing). All three are green today at 148/148.
5. `feature-messages` needs no rule closed and is here so that its route test — the only file that
   knows the router-base doubling shape — is in the same packet as the two that will grow routes.

**Acceptance**
```
pnpm -C frontend test packages/feature-topics packages/feature-consumers packages/feature-messages
pnpm -C frontend typecheck
pnpm -C frontend build-storybook && (serve) && node frontend/scripts/a11y-stories.mjs 'screens-topic|topics-|screens-consumer|consumers-|messages-|screens-message'
pnpm -C frontend e2e e2e/topics.spec.ts e2e/consumers.spec.ts e2e/messages.spec.ts
```
**Mutation line:** drop the subject from `mayReset`'s `permits` call and name the case that goes red.
**And a green one:** report the cheapest single edit you found that still leaves the suite green, and
whether you closed it.

---

## W7-09 — The documents, and a gate whose own packet published the wrong price

**Owns**
```
docs/api/**
frontend/packages/api/src/schema.d.ts
frontend/packages/api/README.md
frontend/README.md
docs/FEATURE_MATRIX.md
README.md
TECH_DEBT.md
DECISIONS.md
docs/adr/ADR-012-microfrontend-loading-strategy.md
docs/adr/ADR-048-solidjs-typescript-vite-frontend.md
scripts/feature-matrix-check.sh
tools/error-codes/**
build-tests/src/kui/build/ArchitectureRules.scala          (line 161's comment, and nothing else)
build-tests/test/src/kui/build/ArchitectureSuite.scala     (line 378's comment, and nothing else)
```
**depends on W7-01 and W7-03.**

**Contract.** Two packets add endpoints, so the merged documents and the browser's generated types
move and both `./mill services.gateway.api.openApiCheck` and `./scripts/feature-matrix-check.sh` are
red on arrival. **In wave 6 that repair did not happen inside the wave** — the check went red at
integration with 12 disagreements and was fixed by the integrator. Today's figures, measured at
integration with `jq`: **56 paths, 67 operations, 154 schemas, `X-Kui-Principal` on 52 operations
over 41 paths, `X-Csrf-Token` on 21**, and the script prints `216 claims checked, all true` over five
sections.

**Do**
1. Regenerate the three documents and update both `<!-- checked: merged-document -->` regions from
   `jq`, not from a packet's note. Write no Scala and no TypeScript by hand.
2. **The owned rule, and it is your own packet's claim from last wave, measured and false.** W6-09
   reported that a green false figure now costs *"five edits across three files"* where it used to
   cost one. It costs **one**, re-measured here: change `carries("X-Csrf-Token")` to
   `carries("X-Kui-Principal")` in the `jq` tsv read at `scripts/feature-matrix-check.sh:559`, publish
   `X-Csrf-Token on 52 operations` in ADR-048 and in `frontend/packages/api/README.md`, and the run
   prints `216 claims checked, all true` and exits 0. The claim registry sees that a claim of that
   *kind* was made; the marker sees the region was checked; `close_section` counts; the residue check
   runs only over the two prose sections. **None of the four looks at what the claim was compared
   against.** The same one-line weakening of the dependency comparison
   (`[[ $row == *"$version"* ]]` → `[[ -n $row ]]`) lets `DEPENDENCY_MATRIX.md` record any version at
   all, and sections 3, 4 and 5 have **no residue backstop**. Publish what was compared — the pairs,
   registered or checksummed, with a fixture that pins them — and then, per house rule 17, **publish
   the cheapest attack you could still find and its cost.**
3. Two more of your own, both disclosed and both still open: `report_unclaimed_figures` can be
   replaced by `return 0` with the run green; and the rounding rule
   (`percent=$(( (complete * 200 + in_scope) / (in_scope * 2) ))` → truncation) is unpinned, so the
   first row count that lands on a `.5` boundary makes README and the matrix disagree with the
   sentence describing them. Also fix the latent indexing bug in the marker fixture: the
   `[[ -z ${text// /} ]] && continue` path does not increment `blocks`, so a file with an
   empty-but-marked region would pair every later region with the previous region's `claims:` list.
4. **`BrowserConstantsMain`'s exit status is one operator from silent.** With `if outcome.status != 0`
   changed to `< 0`, a genuinely stale committed `constants.generated.ts` passes
   `./mill frontend.apiConstants --check` at `234/234 SUCCESS` while printing *"is out of date"*.
   Close the seam, not the function. (W6-09 disclosed this and proved it with an example that proves
   nothing — an extra path argument that `main` never reads.)
5. **Two documents in one repository disagree about one fact.** `frontend/README.md:33` says of
   `@kui/api` *"Nothing hand-written mirrors a server type"* while `TD-024` records that 22 properties
   are typed `unknown` and `overview/metrics.ts` hand-writes every metrics wire shape. Correct the
   sentence, or land the sub-schemas with W7-02 and delete TD-024. Not both, and not neither.
6. **Three surviving copies of a sentence about a Mill task that does not exist.**
   `frontend.checkBundleShape` is named in `kernel/src/feature/registration.ts:25` (W7-05's, filed as
   an outside-ownership row) and in the two `build-tests` comments named in your `Owns`. Two files
   were corrected in wave 3 and these were missed twice.
7. **`MT-002`'s stale class names survive in a third place** — `services/metrics/app/test/.../MetricsWiringSuite.scala:44`
   still says `ThroughputScrapeLoop`. That file is W7-02's; state the row.
8. `TD-023`: record wave 6's numbers rather than a list — **46 new ungated rules shipped by ten
   building packets, 37 undisclosed; 75 found and 75 closed by three adversarial packets over 130
   scored mutations, a 58% rate on a targeted sample; ten of those closures re-applied at integration
   and every one red; zero cases landed by ten verification passes under a house rule requiring
   exactly that.** Then record what this wave's plan does with them: every one is an owned rule above.
9. And record the two live product defects wave 6 found and did not repair, both now owned:
   `DataTable`'s modifier-click (W7-05) and the topic screens' hard-coded `readOnly: false` (W7-08).

**Acceptance**
```
./mill __.openApiCheck                     # green, and it is this packet that makes it so
./mill __.fix --check
pnpm --filter @kui/api run generate        # twice; the second run must change nothing
rm -rf frontend/.tsbuild && pnpm -C frontend typecheck
./mill frontend.apiConstants --check
./mill tools.errorCodes.test
./scripts/feature-matrix-check.sh
```
**Mutation line:** weaken one comparison while leaving its claim, its marker and its count standing,
publish a false figure against it, and show the run failing. **And a green one:** delete a
`docs/adr/ADR-*.md` row from `DECISIONS.md` and report what happens — wave 6 made that one red, so
this is a regression check rather than a hunt.

---

## W7-10 — The tenth container, and four sentences measurement contradicts

**Owns**
```
deployment/**
.github/workflows/ci.yml
apps/allinone/**
libs/config/**
```
**depends on W7-01's Mill module and `deployment.docker.connect` image target.**

**Do**
1. The Connect container: its compose entry in both stacks, `depends_on`, healthcheck, configuration
   in `kui-service.yaml` and the quickstart's, its `AllInOneWiring` entry, and the `smoke.sh`
   assertions that its capability is present and its list answers. `AllInOneWiringSuite`'s startup-log
   string and mounted-path set are yours and they have moved for every service since the eighth.
2. **The alerts stream joins the mounted-path set** once W7-03's relay lands. Today
   `AllInOneWiringSuite` asserts the alerts *feed* is proxied and says nothing about the stream, which
   is how a 404 shipped with every suite green.
3. **Owned rule.** `libs/config/test/.../ShippedConfigurationSuite.scala`, `notKuiConfiguration`:
   widening the pattern `"kafka-jmx-exporter.yml"` to `".yml"` leaves `./mill libs.config.test` at
   395/395 with the suite 14/14 green, because every listed row is a `*.yaml` and the widened pattern
   still matches something on disk. The reconciliation the packet shipped is inert for exactly the
   class of file it was written to notice. The packet asserted the opposite.
4. **Four figures and sentences that measurement contradicts, all in files you own.**
   * `deployment/compose/README.md:65` says *"The eight services are reachable only from inside the
     compose network"* directly under a sentence enumerating **seven**. It read "six" before wave 6
     changed 6 to 8 where 7 was meant.
   * `deployment/metrics/kafka-jmx-exporter.yml` says *"the eight busiest per-topic lines it served"*
     and then lists **five**, and says `__consumer_offsets` leads *"by eleven orders of magnitude"*
     where its own figures give about fifty and its packet's report says more than two hundred.
   * `deployment/compose/README.md` records `networkprocessoravgidlepercent` at **0.399** as a
     cold-idle measurement; on a cold traffic-free stack it reads **0.999**, and `requesthandler` idle
     reads 0.985 against the README's 0.958.
   * The contract preflight's failure advice — *"Give the missing one a container in
     docker-compose.yml and an address in kui.yaml"* — is wrong for the failure it actually printed in
     wave 6, where the container and the address both existed and the **contract** was missing.
5. **The RBAC half of a four-part claim is still false.**
   `deployment/quickstart/kui-quickstart-auth.yaml:28-32` says `ShippedConfigurationSuite` catches four
   things; three of the four redden the suite and *pointing both roles at a cluster that is not here*
   does not. Make it true or say which three the sentence covers.
6. **`AllInOneWiring`'s alerts configuration is still ungated**, disclosed last wave: replacing
   `config.alerts` with `AlertsConfig.Default` in the `services(...)` call leaves `apps.allinone.test`
   with no additional failure, so an operator's thresholds are silently replaced by shipped defaults.
   The startup-log line that would make it observable is yours.
7. `ci.yml` derives its image list from the compose file — do not edit a list, add a container.

**Acceptance**
```
./mill apps.allinone.test
./mill libs.config.test
./mill checkArchitecture
./mill apps.allinone.checkFormat + libs.config.checkFormat
docker compose -f deployment/compose/docker-compose.yml config -q
# images built from the working tree by the derived list, then:
docker compose -f deployment/compose/docker-compose.yml up -d --wait
./deployment/compose/smoke.sh              # three consecutive runs, from a torn-down stack each time
./deployment/quickstart/quickstart.sh      # then curl every alerts, metrics and connect endpoint
```
**Mutation line:** widen `notKuiConfiguration` to `.yml` and name the case that goes red after your
work. **And a green one:** drop a `depends_on` and report what the stack does, with the patch and
without it — wave 6 did this for `kui-metrics` and the answer is in the compose file; do it for the
container you add.

---

## W7-A1 — Adversarial: the adapters nobody has mutated, and a browser case that fails only in company

**Owns**
```
services/cluster/*/test/**   services/topic/*/test/**   services/consumer/*/test/**
services/message/*/test/**   services/identity/*/test/**
frontend/e2e/brokers.spec.ts   frontend/e2e/features.spec.ts
```
No production file. Run in a `git worktree` (house rule 14).

**Contract.** Wave 6's A1 scored 42 mutations in these five services and found **67% ungated**, the
highest rate any sample has produced, by aiming at three shapes plus a fourth it discovered: a module
that declares a test module and ships no test source, or one suite for eight production files. It
closed 28. It also disclosed, precisely, what its own new suites do **not** defend — that list is your
starting point and it is the first time an adversary has handed the next one a map of its own gaps.

**Do**
1. **The flake, and house rule 9 governs it.** `frontend/e2e/brokers.spec.ts:57` fails in a full
   `pnpm -C frontend e2e` run and passes 9/9 alone — measured at integration, and seen by three
   agents across two waves. Run it in **both** directions: what is different about the full run, and
   does the difference explain the failure? If you cannot explain it, say so and do not raise a
   timeout.
2. **The six gaps A1 disclosed in its own work**, each now an ungated rule with a known location:
   `MessageMappingSuite` asserts nothing about headers, the three size fields, or the `Phase` and
   `Finished` frames; `ConsumerCapabilitySuite` never exercises `ConsumerCapabilities.make`'s
   `case None => starting` arm; `IdentityMappingSuite` does not cover `grant` with a `None` resource
   pattern; `UserDirectoriesSuite` asserts nothing about `StoredUserDirectory.update`'s optimistic
   version or about the hash being written through `SecretJson`; `ConfiguredClusterProfilesSuite`
   ignores `fetchedAt` and `stale`; `PurgeUseCaseSuite` does not touch the cleanup-policy warning.
3. **A second flake, in your own tree, and the same rule applies.**
   `services/cluster/infrastructure`'s `ProfileChangeListenerSuite.subscribersReceiveTheChangeAndNeverTheProfile`
   failed once under sixteen-way parallel load and passes alone; it is a race between a stream fibre
   subscribing and a `store.push`, papered over with `IO.sleep(50.millis)`. It flaked at the wave
   base before anybody had touched that tree, so it is not wave 6's. Run it in both directions;
   raising the budget is not an answer.
4. **Then go where nobody has been: the `infrastructure` layers.** Every previous sweep of these five
   services aimed at domain, application and api. The Kafka adapters — `ClusterAdminClients` aside,
   which was 4-for-4 gated — have never been mutated as a class, and they are where a `None` becomes
   a `0` for the last time before the wire.
5. At least **thirty** scored mutations, one at a time, full suite per mutation, reverted from bytes
   you saved. `-Werror` will reject some cuts; a compile failure is not a red, so re-cut and say what
   you re-cut. Watch the summed test **total** on every run — a failed module compile aborts the rest
   while Mill still prints `SUCCESS`.
6. **Report the rate against 67%**, and say whether the fourth clue still holds now that the
   "declared test module, no sources" list is down to three.

**Acceptance**
```
./mill services.cluster.__.test + services.topic.__.test + services.consumer.__.test + services.message.__.test + services.identity.__.test
./mill checkArchitecture
./scripts/run-tests.sh
pnpm -C frontend e2e e2e/brokers.spec.ts e2e/features.spec.ts
```
**Mutation line:** a case named for each rule closed, each verified red by re-applying the mutation.
**And a green one:** this packet is all green ones. Report the ratio and the sample method.

---

## W7-A2 — Adversarial: the three modules that still ship no test, and the four `libs` nobody sweeps

**Owns**
```
libs/cache/test/**   libs/contracts-core/test/**   libs/filter/test/**   libs/http/test/**
libs/kernel/test/**  libs/observability/test/**    libs/security-core/test/**
libs/serde/test/**   libs/testkit/test/**
libs/kafka/test/**   libs/kafka-auth/test/**       libs/serde-confluent/test/**
libs/http/src/kui/http/sse/SseWire.scala            (named exception)
libs/http/src/kui/http/sse/SseEvent.scala           (named exception)
services/schema/**
services/identity/app/**   services/message/app/**   services/topic/app/**
```
`libs/config/**` is excluded entirely — it is W7-10's. Run in a `git worktree`.

**Contract.** Wave 6's A2 scored 39 mutations, found 46% in `libs/` and closed 18 of 18, and it
excluded four modules for runtime — `libs/kafka`, `libs/kafka-auth`, `libs/serde-confluent` (Kafka
testcontainers) and `libs/config`. **Three of those four are now yours**, and they are the modules
that talk to Kafka, which is where every adapter defect in this project has come from. Budget the
runtime: run them as their own invocation, not inside a nine-module `+` chain.

**Do**
1. **The three modules that resolve as test targets and contain nothing**:
   `services.identity.app.test`, `services.message.app.test`, `services.topic.app.test`. Six were on
   that list at the start of wave 6 and three were closed — including
   `services/schema/app`, whose emptiness kept four constants ungated for two milestones. Give each a
   suite or state a `needsOutsideOwnership` row to W7-01 deleting the `object test` block. Their
   production source is in your `Owns` for exactly this reason.
2. **Two named production exceptions, both dead code with a scaladoc that claims otherwise**, filed by
   A2 and unowned since:
   * `SseWire.parseFrame`'s `lines.filterNot(_.startsWith(":"))` is **inert** — a `:`-leading line
     yields the key `""`, which none of the three `collect` patterns matches — while the class
     scaladoc lists comment-skipping as implemented behaviour. Delete it and say why, or keep it and
     say it is defensive and inert.
   * `SseEvent.render`'s `payload.split('\n')` is dead: `payload` is `noSpaces`, so the `case Nil` arm
     is unreachable and the multi-line branch cannot run, while the scaladoc describes a caller that
     cannot exist. The rule underneath **is** gated (`noSpaces` → `spaces2` reddens six cases) — say
     so and name the case.
3. `services/schema` is yours whole for a second wave. Its bulkhead's **behaviour** is still ungated:
   `MaxConcurrentPerRegistry` is pinned as a constant and nothing asserts that concurrency against a
   registry is actually capped. And `RegistryCredentials.RefreshMargin` was scored only *after* A2's
   own cases landed, so its verdict says nothing about whether it was gated before.
4. `MaskingEngine.maskKeepingEnds`'s clamp was reasoned to be inert and **not measured**. Measure it.
   And `libs/kafka`'s `AdminClientPoolSuite.aReconnectClassFailureReplacesTheClientExactlyOnce` failed
   once inside a full `run-tests.sh` under load and passes alone at 497/497 — a real-time timing
   dependence in a module that has never had an owner. House rule 9.
5. At least **thirty** scored mutations across the block, weighted to the four modules nobody has
   swept. Report the rate per area, and say whether the composition-root clue still pays now that
   `services/schema/app` and `services/identity/api` have suites.

**Acceptance**
```
./mill libs.http.test + libs.observability.test + libs.kernel.jvm.test + libs.securityCore.jvm.test + libs.serde.test + libs.cache.test + libs.filter.test + libs.contractsCore.jvm.test + libs.testkit.jvm.test
./mill libs.kafka.test + libs.kafkaAuth.test + libs.serdeConfluent.test
./mill services.schema.__.test
./mill checkArchitecture
./scripts/run-tests.sh          # the "no test sources" list must be shorter, or say why it is not
```
**Mutation line:** a case per rule closed, each verified red. **And a green one:** all of them; report
the ratio, and report separately what you found in the Kafka-facing modules, because that is the
number nobody has.

---

## W7-A3 — Adversarial: the closer. The wave's own new code, after it freezes

**Owns**
```
every `**/test/**` and `*.test.ts(x)` of the ten building packets above — and only after they freeze
frontend/packages/feature-clusters/**   frontend/packages/feature-schemas/**
```
Run in a `git worktree`. **This packet starts last**, when the ten building packets have reported.
Until then its owners may not touch a test tree it will hold, and after the freeze the building
packets may not edit theirs.

**Contract.** This is the mechanism change, and it is the answer to a measured failure rather than an
idea. Wave 6's ten verification passes found **46** ungated rules in the code the wave had just
written and closed **zero**, under a house rule requiring them to close each one — because a verifier
runs while the packet it verifies still owns that tree. Meanwhile the densest seam in the wave is
exactly there: **4.6 holes per building packet, 80% of them undisclosed.** An adversary hunting
unowned code closes about 25 rules for a packet's capacity; you should beat that, because you are
handed the findings instead of having to hunt for them.

**Do**
1. **Take the ten verification reports as input** and land a case for every finding that is still
   open when you start. Each case is verified the same way an adversarial closure is: apply the
   mutation, watch the named case go red, revert from bytes you saved.
2. **Then hunt in the same code**, which no adversary has ever been allowed to do. Wave 6's own
   evidence says the wave's new code runs at 4.6 ungated rules per packet; measure it properly and
   report the rate, because that number has only ever been inferred from what verifiers happened to
   notice.
3. **Third pass on `feature-clusters` and `feature-schemas`.** Wave 5's A2 swept them at 33% and wave
   6's A3 re-swept them at **63%**, using A2's own map. Sweep them a third time and report the yield.
   If it falls materially, a package can be declared swept and the ratio can move in wave 8; if it
   holds, the standing finding is that one pass never exhausts a package, and that is worth knowing
   with three points rather than two.
4. **Two items A3 left open and one it filed.** The router-base question — driving `ClustersRoute`
   through a router with `base: "/ui"` produced `/ui/ui/clusters/…`, but the `/ui` came from the
   package's own `testing.ts` rather than from `shellPaths`, so it was run in one direction only;
   settling it needs one assertion in the shell's own routing test, which is W7-06's, so state the
   row. The `features.spec.ts` scratch-subject growth — four subjects per run against a registry that
   publishes no delete — is A1's file this wave; hand it the analysis. And `DataTable`'s
   modifier-click defect is now W7-05's owned rule; check it landed.
5. **Report, per finding you close, whether the packet that wrote it had disclosed it.** That is the
   number the disclosure debate has been missing: wave 4 11%, wave 5 18%, wave 6 20%, all measured by
   verifiers who did not have to close anything.

**Acceptance**
```
pnpm -C frontend test
./scripts/run-tests.sh
./mill checkArchitecture
pnpm -C frontend build-storybook && (serve) && node frontend/scripts/a11y-stories.mjs
```
**Mutation line:** a case per rule closed, each verified red by re-application. **And a green one:**
report every rule you found and could **not** close, with the seam it needs, because those are wave
8's owned rules.

---

## Where the packets meet

Every pair below shares a boundary. The contract is stated on both sides so that neither has to read
the other's diff.

| Pair | The contract both sides code against |
| --- | --- |
| W7-01 → everybody | **The edges, first.** `build.mill` is W7-01's alone, and its first commit lands `connect.contract.jvm` on `services.gateway.api`'s `moduleDeps` plus any test dependency an adversarial packet declares — before its own service work. House rule 15; wave 6 lost the ninth service's routing to exactly this gap. |
| W7-01 ↔ W7-03 | The Connect endpoints: `Section`-wrapped reads and three writes under `/api/v1/clusters/{clusterId}/connect/…`, proxied the way metrics and alerts are. W7-03 adds **no gateway path of its own** — `OpenApiMergeSuite` pins a hard-coded list. The writes move `MergedDocumentShapeSuite`'s `writes.size`; the tenth service moves `ServiceContractsSuite`'s id `Set`. Both are W7-03's. |
| W7-02 ↔ W7-03 | **The alerts stream.** `AlertsStreamEndpoint` stays out of `ServiceContracts.byService` and W7-03 hand-writes the relay; W7-02 makes `acknowledge` publish a frame, which is what a relay has to carry to be worth testing. Neither is provable without the other, and the joint criterion is `curl -N` through the gateway with a frame in the report. |
| W7-02 ↔ W7-05 | **The alerts wire, house rule 12.** W7-02 renders golden documents from its own encoder to `services/alerts/contract/test/resources/golden/*.json`; W7-05 reads that exact path off disk and decodes each through `data/alerts/events.ts`, failing when a file is missing. The path is the contract. This is the pattern that closed M7 and the reason M8's wire is still two hand-written mirrors. |
| W7-02 ↔ W7-06 | The third metrics rule — `TrafficCards.tsx:382`'s stale caption — lives in W7-06's tree and is stated in both packets so neither writes it twice. |
| W7-04 ↔ W7-05 | Two lines in the kernel, shipped by W7-05 whether or not W7-04 asks: `FeatureId` gains `"connect"`, and `styles/index.css` gains the import naming `feature-connect`'s one stylesheet at the number W7-04 states. `build-tests`' `CssReferencesSuite` requires it and `build-tests/**` is owned by nobody. |
| W7-04 ↔ W7-06 | The Connect screens are a **route and a nav destination**, not a dashboard tab. W7-06 adds the route in `routing/**`, the `ECOSYSTEM` nav row in `nav/**` and the badge row in `App.tsx`; W7-04 ships what is behind it plus the registration seam. A feature reaches its own pages through `kui.paths.*` and edits no routing file. |
| W7-05 ↔ W7-06 | **One open count.** Five of the kernel store's eight accessors have no product caller and the shell re-derives the count itself under a different null rule. The two packets agree one answer before either ships: the kernel's `openCount()` is the number and the shell reads it, or the accessor goes. Whichever loses is deleted, not left. |
| W7-05, W7-06 ↔ W7-07 | The dashboard's alerts card is drawn **by the shell from the kernel store**, not by importing `@kui/feature-alerts` — the shell may not statically import a feature package or `bundle-shape.mjs` fails the build. `feature-alerts` owns the screen; the shell owns the card; both read one store. |
| W7-06 ↔ W7-03 | The browser half of the stream: `shell.spec.ts`'s bell case gains *acknowledging the seeded event moves the bell without a reload*, which is only true once the relay exists. If W7-03 slips, W7-06 reports the case as written and skipped, and says so. |
| W7-09 ↔ W7-01, W7-03 | The merged documents and `schema.d.ts` move for somebody else's endpoints, and `./scripts/feature-matrix-check.sh` goes red with them. **Wave 6 shipped that red**; this wave it is W7-09's acceptance line. |
| W7-09 ↔ W7-02 | `MT-002`'s third stale class name is in `services/metrics/app/test/.../MetricsWiringSuite.scala:44`, which is W7-02's file. And the metrics `data` sub-schemas are W7-02's DTOs: either they gain their shapes and TD-024 closes, or `frontend/README.md:33` is corrected. Not both, not neither. |
| W7-10 ↔ W7-02 | ADR-052 §11 and `deployment/metrics/kafka-jmx-exporter.yml` currently give different reasons for excluding internal topics — the ADR argues the domain must **not** read `kui.topics.internalPrefix` and the ruleset file explains the exclusion through it. W7-02 owns the ADR, W7-10 the ruleset; one paragraph changes. |
| W7-10 ↔ W7-03 | `smoke.sh` scrapes `ServiceContracts.byService` with `sed` to derive the contract set, and a contracted service that is not a container fails the preflight before a container starts. W7-03 owns the Scala; W7-10 owns the script and the container. |
| W7-A1 ↔ W7-A2 | `services/{identity,message,topic}/app/**` — **production and test** — is W7-A2's, because the three empty test modules are its subject. W7-A1's `services/*/test/**` block **excludes `*/app/test/**`** for those three services. Stated in both blocks; it is the only place the two adversaries could have collided. |
| W7-A3 ↔ every building packet | A3 owns their unit and component test trees **after they freeze**, and not before. A building packet that edits its test tree after reporting has taken a file back from a packet that is mid-flight. A3 does not own the `e2e/**` specs — those are allocated per file and need a running stack. |
| every packet ↔ the guard files | House rules 1, 2 and 3 keep `build-tests/**` (bar two named comment lines), `10-tokens.css` and `constants.generated.ts` out of reach. If your change needs one of them, your change is shaped wrongly, and that has been true for six waves. |

## The partition, checked

**Backend.** `services/connect` is W7-01's and is new. `services/alerts` and `services/metrics` are
W7-02's, `services/gateway` W7-03's, `services/schema` **W7-A2's whole, source included**.
`services/cluster`, `services/topic`, `services/consumer`, `services/message` and `services/identity`
have their test trees owned by W7-A1 and their production source owned by nobody — **except
`services/identity/app/**`, `services/message/app/**` and `services/topic/app/**`, which are W7-A2's
whole**, production and test, because the three empty test modules are its subject. W7-A1's block
therefore reads `services/<name>/*/test/**` minus `*/app/test/**` for those three; it is written out
in both packets. `libs/**` is owned by nobody except the twelve test trees in W7-A2's block, two named
`libs/http/src/kui/http/sse/*.scala` files, and `libs/config/**`, which W7-10 holds whole.
`build.mill` is **W7-01's alone** and is the only file three packets want; W7-10 needs the image
target and W7-A2 may need a test dependency, and both ask through `needsOutsideOwnership` — but
**W7-01 lands the gateway's contract edge unasked, first**, which is house rule 15 and the whole of
wave 6's worst defect.

**Frontend.** Inside `frontend/packages/shell/`, no packet owns `**`. `src/features/` and
`package.json` are W7-04's; `src/App.tsx` with its two test files, `src/chrome/`, `src/nav/`,
`src/data/`, `src/routing/`, `src/overview/`, `src/pages/`, `src/index.ts` and `styles/**` are
W7-06's — named one by one, and note that `src/overview/` moves from wave 6's metrics packet to the
frame packet, because the three rules left in it are browser rules and the dashboard's new alerts card
belongs beside the traffic cards. `src/messages.ts`, `src/bootstrap.ts`, `src/health.ts` and
`src/index.tsx` are **owned by nobody and need no edit**: they are the boot path and nothing here
changes it.

`frontend/packages/kernel/**` is W7-05's for the third wave running, and the reason is unchanged: it
has had an owner twice and shipped ungated rules both times, four of them last wave in a file that
owner wrote. `frontend/packages/feature-connect/**` is new and W7-04's. `feature-alerts` → W7-07;
`feature-topics`/`feature-consumers`/`feature-messages` → W7-08;
`feature-clusters`/`feature-schemas` → W7-A3. `frontend/e2e/` is allocated **per file**:
`connect.spec.ts` → W7-04 and new; `shell.spec.ts`/`dashboard.spec.ts`/`search.spec.ts`/`traffic.spec.ts`
→ W7-06; `alerts.spec.ts` → W7-07; `topics.spec.ts`/`consumers.spec.ts`/`messages.spec.ts` → W7-08;
`brokers.spec.ts`/`features.spec.ts` → W7-A1. `fixtures.ts`, `globalSetup.ts`, `tsconfig.json` and
`playwright.config.ts` are unowned and need no edit.

`frontend/tsconfig.json` is **W7-04's**, as the seventh package's reference is its job.
`frontend/README.md`, `frontend/packages/api/README.md` and
`frontend/packages/api/src/schema.d.ts` are W7-09's, so neither directory is owned as a tree;
`constants.generated.ts`, `api/src/index.ts`, `api/src/probes.ts` and `api/src/types.test.ts` are
unowned and need no edit. `frontend/vitest.config.ts`, `vite.config.ts` and `package.json` are unowned
— `bundle-shape.mjs` and `boundaries.mjs` read their rosters from the filesystem, so a seventh feature
package is picked up with no edit anywhere. `frontend/scripts/a11y-stories.mjs` is W7-05's.

**Build and deployment.** `.github/workflows/ci.yml`, `deployment/**` and `apps/allinone/**` are
W7-10's alone. `scripts/run-tests.sh` is unowned and needs no edit — it derives its module list from
`./mill resolve __.test`. `scripts/feature-matrix-check.sh` is W7-09's; there is no other file in
`scripts/`. `build-tests/**` is unowned **except two comment lines** named file-and-line in W7-09's
block, which claim a Mill task that has never existed.

**Documents.** `docs/api/**` and eight named documents are W7-09's; `ARCHITECTURE.md` is W7-03's;
ADR-054 is W7-01's new file; ADR-052 and ADR-053 are W7-02's, because the metrics and alerts decisions
they record are that packet's to correct. Every other ADR, `DEPENDENCY_MATRIX.md`, `docs/ROADMAP.md`,
`docs/ROADMAP-SOLID.md`, `docs/testing.md`, `docs/api/error-codes.md` (generated),
`docs/operations/**` and `docs/domain/**` are unowned and need no edit.
`research/design/SCREENS-V4.md` is **W7-06's, for the alerts-card paragraph and nothing else**.
`docs/plan/ROADMAP.md` is **unowned this wave**: it records milestone shape, and neither finishing M8
nor opening half of M9 changes either shape. `docs/plan/WAVE-07.md` is this file; the wave's closing
act deletes it.

**Four nesting checks, done rather than assumed.** `docs/` is not owned as a tree. `frontend/packages/shell/`
is not owned as a tree — three packets divide `src/` by named subdirectory with four files left over
that are named above rather than left to inference. `services/identity/`, `services/message/` and
`services/topic/` are not owned as trees: W7-A1 holds `*/test/**` **excluding** `*/app/test/**`, and
W7-A2 holds `*/app/**` whole — the one overlap two adversaries could have had, resolved by exclusion
and stated in both blocks. And W7-A3's ownership of the building packets' test trees is a **sequenced**
claim, not a concurrent one: it begins when they freeze, and it is the only ownership in this plan that
moves during the wave.

## What wave 8 will be, so nobody builds it here

Wave 8 opens **`services/ksql`** — the last new service in the plan — and **closes M10**, which is a
closing wave rather than a building one. The ksqlDB vocabulary shipped in wave 1 and is still unused;
the read-only case is already settled and asserted in `RbacLawsSuite`. The one decision this wave must
not pre-empt is the ksqlDB result region, which no capture shows (§7.9).

M10 is a longer list than it looks. It needs `docs/FEATURE_MATRIX.md` accurate against the code, a
newcomer's overview, the a11y sweep over every story, a browser suite covering all twenty-three
screens, `docs/plan/` reduced to two files — and the one thing that has now failed three criteria in a
row: **a comparison gate that cannot be neutered in one line**. Wave 8 should assume it will spend a
packet on that alone, and it should be a packet that attacks its own gate before it reports.

**And on the ratio.** Wave 6 kept 3:1 and ended **+39** rules better gated: ten owned rules closed,
seventy-five closed by adversaries, forty-six opened. Wave 7 keeps thirteen packets and 3:1, and moves
one adversary onto the wave's own new code, because that is where the density is — 4.6 ungated rules
per building packet, 80% of them undisclosed, in the one place no adversary was allowed to look. The
pre-commitment for wave 8 is this: **if W7-A3 closes more rules than W7-A1 and W7-A2 together, the
closer becomes permanent and one hunting adversary is dropped to 4:1. If it closes fewer, the wave's
own code is not the densest seam after all and the ratio stays.** And if W7-A3's third pass over
`feature-clusters` and `feature-schemas` finds materially less than the 33% → 63% those packages have
produced, a package can be declared swept — which is the first time this project would have evidence
that any part of it is finished being hunted.
