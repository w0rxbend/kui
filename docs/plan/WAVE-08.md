# Wave 8 — the eleventh service, the browser evidence two milestones are blocked on, and the forty-four rules the last wave shipped ungated

**Milestones covered:** **the rest of M9** in [ROADMAP.md](ROADMAP.md) — `services/connect` is built,
routed, imaged and answering real documents through the gateway, and the milestone is open on two
clauses that are each one command and one file away — and **M10**, which is the closing milestone and
is a longer list than it looks. `services/ksql` is the eleventh and last new service in this plan.

**Why this shape.** Wave 7 closed M8 on a frame that no packet in the wave ever produced: the
integrator seeded one event, held the stream open, acknowledged, and the criterion that had been
outstanding for two waves was met in under ten minutes. The same two commands were within reach of
thirteen packets. **The gap between "the code is right" and "the criterion is met" was two commands,
and the whole wave left it open** — so in this wave the stack rebuild is an owned, first-hour act with
a named owner (house rule 19), and every packet whose acceptance names the browser depends on it
rather than on its own docker luck.

Wave 7 also shipped **44 new ungated rules** across ten building packets, disclosed six of them, and
its own closer never received the reports it was written to consume. Every one of the 44 is an
**owned rule** below, in the packet whose tree it lives in, quoted with the exact mutation that leaves
the suite green — because a named owned rule has now been closed **40 of 40 across four waves**, and
it is the only mechanism in this project with a perfect record. The seven the adversaries left open
because they need a production seam are owned too, by the packet that can cut the seam.

**Parallelism.** `Owns` is disjoint across every packet: no two tasks may edit the same file, and no
packet owns a directory containing another packet's file. Eight dependencies are declared, all on a
*stated shape* and none on a diff: W8-03 on W8-01's contract module and on the `build.mill` edge
W8-01 lands first; W8-03 on W8-01's `services/ksql/domain` existing before `ArchitectureDocumentSuite`
can be green (see the guard table — this one bites on arrival); W8-05 on W8-01's endpoints and on
W8-06's widened `FeatureId`; W8-07 on W8-06's kernel store; W8-04 and W8-08 on W8-10's rebuilt stack;
W8-09 on W8-01 and W8-03 having landed before the merged documents are regenerated; W8-A3 on all ten
building packets having frozen **and on their verification reports existing as files**.

**The tree you start from is green, and every figure here was re-measured at wave 7's integration on
2026-09-10.** `./scripts/run-tests.sh` **75 modules, 75 with tests, 3,987 cases** — and for the first
time the runner names **no** module that resolves as a test target and ships no test source;
`./mill checkArchitecture` 180 modules, 10 rules, no layering violations; `./mill __.openApiCheck`
2408/2408 over **61 paths, 72 operations, 156 schemas**; `./mill __.checkFormat` 234/234;
`./mill __.fix --check` 4962/4962; `pnpm -C frontend test` **77 files, 1,767 cases**;
`pnpm -C frontend typecheck` exit 0; `node frontend/scripts/boundaries.mjs` **409 files in 10
packages**; `pnpm -C frontend a11y` **780 stories × 2 themes, no violations**;
`./scripts/feature-matrix-check.sh` **251 claims checked, all true**; and
`./deployment/compose/smoke.sh` passing with ten services routed. Anything red is yours.

**Two things are red or flaky and neither is a secret.**

* `pnpm -C frontend e2e e2e/connect.spec.ts` — **one failure and three skips**, measured at wave 7's
  integration against a stack built from this tree. `connect.spec.ts:56` asserts
  `Array.isArray(section.data?.items)` against a server that renders
  `connectors.data.workers[].connectors.data.items`. It is W8-04's owned rule and it is the file that
  keeps M9 open.
* `pnpm -C frontend test` fails intermittently with `Test timed out in 5000ms` inside an **axe** case,
  and the file it lands on moves between runs. Measured in both directions at integration: the whole
  suite failed once on `feature-connect`'s a11y case and the same package run alone passed 62/62 three
  times in a row. It is the vitest default timeout expiring inside axe under 77 parallel jsdom
  environments, not an assertion. `frontend/vitest.config.ts` is **W8-06's** this wave, precisely so
  that somebody owns it: raise the timeout for axe cases or lower the pool concurrency, and run it in
  both directions per house rule 9.

**The designed intermediate state.** W8-01 adds `services/ksql` and regenerates its own
`services/ksql/api/openapi.json`; W8-03 adds the routing. `./mill __.openApiCheck` will be **red on
`services.gateway.api` and green on the other nine** until W8-09 regenerates `docs/api/openapi.json`
and `docs/api/openapi.browser.json`, and `./scripts/feature-matrix-check.sh` will be red with it.
That is the third wave in a row with this exact shape and it is W8-09's acceptance line, not the
integrator's repair. **New this wave, and it is not the same shape:** `services/gateway`'s own test
suite goes red the moment `services/ksql/domain/` exists on disk, because wave 7 added
`ArchitectureDocumentSuite`, which enumerates `services/*` off the filesystem and demands an
`ARCHITECTURE.md` §3 row and a `build.mill` `contract.jvm` entry for every service it finds. That is
a real cross-packet coupling, it was disclosed nowhere, and it is in the guard table with both owners
named.

**`stash@{0}` is still in this repository and this wave does not drop it either.** It holds 46 files
and 3,256 insertions of six packets' wave-5 mid-flight work, created by accident and unpoppable
without conflicts; its owners carried that work forward and the entry is stale. Two waves have now
told an integrator to drop it and neither did, which was the right call both times: a document
asserting that data is disposable is not evidence that it is. Whoever wants it gone runs
`git stash show --stat stash@{0}` first, confirms nothing in it post-dates `8fc0c77`, and drops it
deliberately as its own act — not as a line item in a wave. **It is now three waves old and M10's
closing list does not include it**, so if it is to go, wave 9 is the place to say so out loud.

**House rules.** Read them before starting. The first seventeen are wave 7's; two are new and each is
a wave-7 finding.

Backend: Scala 3 + Mill, ADR-041 layering (machine-enforced by `./mill checkArchitecture`), Tapir
endpoints, ADR-034 error envelope, ADR-039 capability fold, ADR-035 streaming, ADR-045
plan→token→confirm for destructive mutations. Frontend: TypeScript + SolidJS 2 + Vite under
`frontend/` (pnpm, not Mill), Storybook-first — a story per state — and browser types generated from
`docs/api/openapi.browser.json`. Comments explain **why**, not what, at roughly the 25% density of the
surrounding code. There is no ESLint or Prettier; the codebase is hand-written at 100 columns (Scala
at 110, per `.scalafmt.conf`). **Do not reformat a file you are not otherwise changing.**

1. **No new stylesheet files, with exactly one exception.** `build-tests`' `CssReferencesSuite`
   requires every stylesheet on disk to be named exactly once in
   `frontend/packages/kernel/styles/index.css`. The eighth feature package needs one stylesheet, in
   its own `styles/` directory, in the shape `feature-connect/styles/81-connect.css` has — **and the
   index that must name it is in W8-06's tree, not W8-05's.** One file, one line, one owner.
2. **No new custom properties in `frontend/packages/kernel/styles/10-tokens.css`.** A Scala mirror
   lives in `build-tests/src/kui/build/design/Tokens.scala`, which no packet owns.
3. **No new `ErrorCode`.** `./mill frontend.apiConstants --check` compares
   `frontend/packages/api/src/constants.generated.ts` byte for byte. The thirty-one that exist cover a
   ksqlDB adapter: `KUI-UPSTREAM-KSQL` has been declared and unused since wave 1, and so have the
   `KSQL` resource and its `VIEW`/`EXECUTE` actions.
4. **A gate you cannot make fail is not a gate.** Every packet's acceptance list has a **mutation
   line**: name one change to the shipped code that reverses the packet's headline rule, apply it, run
   the acceptance suite, record which case went red, revert it.
5. **Report a mutation that stayed green.** Alongside the red one, apply at least one mutation to a
   rule you did *not* write the test for and record what happened.
6. **No honest-refusal-only acceptance.** No packet may satisfy its acceptance list entirely with
   assertions that something is absent, refused or not configured. This wave has teeth on it: M9 is
   open partly because every positive Connect case in the browser suite *skips*.
7. **A card may not be drawn from a figure the design did not name.**
8. **`./mill a.test b.test` runs nothing.** Mill parses the second task path as a vararg to the first,
   MUnit takes it as a test-name filter, it matches nothing, and every suite reports `0 failed,
   1 ignored, 0 total` while Mill exits `SUCCESS`. The separator is `+`. **A second form does the same
   thing:** `./mill services.cluster.infrastructure.test kui.cluster.infrastructure.store.SomeSuite` —
   one module plus a fully-qualified suite name — matches nothing and prints SUCCESS. The glob form
   `'*SomeSuite*'` runs it. And Mill **aborts** the remaining tasks in a `+` chain on the first
   failure unless `-k` is passed, while still printing per-task results for what did run, so a
   mutation run must compare its summed case total against its own baseline every time.
9. **A root cause is not established until it has been run in both directions.** Wave 7 discharged the
   standing example: `brokers.spec.ts:57` was not a timeout at all but a Playwright strict-mode
   violation — two elements titled `Brokers` while the list is in flight — raised rather than polled,
   so a longer timeout was provably useless. Reproduced by holding the window open, fixed by scoping
   the locator, and green in company for the first time in two waves. The new standing example is the
   **axe 5000ms flake** in `pnpm -C frontend test`, which is W8-06's.
10. **State the scope of a measurement, and expect the next reader to re-run it.**
11. **A comment that names a figure must name a figure something in the tree can check.**
12. **Two sides of one wire are one packet, and the binding case decodes the encoder's own output.**
    Wave 7 applied it to the alerts wire and it worked — six goldens, a Scala suite and a browser suite
    over the same files. **It has a hole in it the shape of `frontend/e2e/`:** `connect.spec.ts`
    hand-writes a wire shape nothing compares to a golden, and that single file is what keeps M9 open.
    A packet that owns an `e2e` spec against its own service reads the service's golden from disk.
13. **A verifier's finding is an owned rule or a case, never a paragraph.**
14. **Adversarial packets run in a `git worktree`.** Revert by restoring bytes you saved yourself,
    never with `git checkout --`, `git restore --source=HEAD` or `git stash`. **And namespace your
    scratch directory**: the session scratchpad is shared between the agents in a wave, and wave 7 had
    one packet's harness overwritten by another's mid-run and a bulk restore drop twenty-five of
    somebody else's files into a worktree root.
15. **An edge has an owner.** Whoever owns `build.mill` lands **every** module-dependency edge this
    plan declares **first, before any of its own work**, and its acceptance list includes compiling the
    consumer module that needs the edge. Wave 7 obeyed this and nothing about the tenth service's
    routing arrived at integration; it is the cheapest rule in this document.
16. **A stream ships its relay.** `ContractRouting.derive` decodes and re-encodes JSON, so it cannot
    carry an event stream; every SSE endpoint needs a hand-written relay in the shape of
    `AlertsStreamRoutes`. **The criterion for a stream is `curl -N` through the gateway, in the report,
    with the frame quoted — and a frame that only ever arrives because something changed has to be
    made to change.** Wave 7's alerts stream is silent for fifteen seconds on a healthy cluster; what
    produced its frame was a write issued three seconds after the stream opened.
17. **A claim about your own gate is measured, not asserted.** Wave 6's checker packet published five
    edits where it cost one; wave 7's published four where it costs two. If your packet claims a gate
    is now harder to defeat, **defeat it** — publish the cheapest attack you found and its cost, and if
    the cheapest attack is cheaper than the number you were about to write, write the measured one.
18. **NEW: a verification pass writes a file.** Wave 7's closer was to consume the ten verification
    reports and never received them, so it hunted blind and its findings had **zero overlap** with the
    44 the verifiers had already filed. Every verification pass in this wave writes
    `docs/plan/verification/W8-<packet>.md` with one row per finding — file, exact mutation, suite
    command, the case that would close it — **before** the packet it verifies is declared frozen. That
    directory is W8-A3's `Owns`. A finding that is not in a file is not filed.
19. **NEW: the stack is rebuilt once, by name, in the first hour.** Wave 7 left two milestone clauses
    open that cost ten minutes at integration, because every packet that needed a stack assumed
    somebody else's images were current and three quoted digests for one tag. **W8-10 builds
    `kui-allinone` and `kui-frontend` from the tree, recreates the quickstart on them, deploys one
    connector on the quickstart's Connect worker, and publishes the two image ids in its first
    report.** Every other packet drives that stack and quotes those ids; nobody rebuilds it under
    another packet's feet without saying so.

`pnpm` is not on the default PATH in a non-login shell; it lives at `~/.local/share/pnpm/bin/pnpm`,
with node at `~/.nvm/versions/node/v26.8.1/bin`. Mill's shared daemon is contended when several
packets run at once and dies with `Worker wire broken, worker likely crashed`; **use
`./mill --no-daemon`** for anything you intend to record a number from.

**Running the a11y sweep** is three commands, not one — build Storybook, serve `storybook-static` on
`:6017`, then sweep. `frontend/storybook-static/` is a **shared output directory**: a failed build
leaves it half-written and the next packet's sweep reads the wreckage, which happened twice in wave 7.
If your sweep reports a harness failure, rebuild before you report a violation. W8-06 owns the harness.

**Seeding, for the packets whose criterion needs something to look at.** One open alert:
`diskUsedWarningPercent: 1` in `deployment/quickstart/kui-quickstart.yaml`, restart
`kui-quickstart-kui`, revert the file afterwards — that opens exactly one `storage` event and is how
M8 was closed. One SSE frame: hold `curl -N …/alerts/stream` open and `POST` an acknowledgement three
seconds in; the stream is silent otherwise. One connector: W8-10 deploys it, per house rule 19.

---

## The guard files

Everything below asserts a shape, a count or a roster that this wave's work can invalidate. None is
owned by the packet most likely to break it — that is the point of listing them. If your change makes
one red, it is your change that is unfinished, and the repair goes in the packet that owns the guard,
named through `needsOutsideOwnership` if that is not you.

| Guard | What it pins | Who breaks it |
| --- | --- | --- |
| `services/gateway/api/test/.../ArchitectureDocumentSuite.scala` | **NEW IN WAVE 7 AND THE SHARPEST TRAP IN THIS TABLE.** It enumerates `services/*` **off disk** and requires (a) an `ARCHITECTURE.md` §3 table row for every service directory that has a `domain/`, and (b) a `<service>.contract.jvm` entry in `build.mill` for every service publishing a `contract` module. **The moment `services/ksql/domain/` exists, `./mill services.gateway.__.test` goes red for a directory W8-01 does not own.** Repairable only in `ARCHITECTURE.md`, which is W8-03's | **W8-01** creates the directory; **W8-03** owns the repair and must land the row and the `moduleDeps` expectation in the same breath as W8-01's first commit |
| `build.mill` — `services.gateway.api`'s `moduleDeps` | The dependency edge. `ksql.contract.jvm` must be there **before** W8-03 can compile its row | **W8-01**, first, per house rule 15 |
| `services/gateway/api/test/.../routing/ServiceContractsSuite.scala` | A hard-coded `Set` of **eight** service ids — `cluster, topic, consumer, message, schema, metrics, alerts, connect` — plus `ServiceContracts.of(alerts)` being `AlertsEndpoints.all` and not the stream endpoint. An eleventh service moves both | **W8-03**, which owns the map and the suite |
| `services/gateway/api/test/.../openapi/MergedDocumentShapeSuite.scala` | `writes.size` over `ServiceContracts.proxied(cluster)` and distinct operationIds across the merged document. **Its `thePublicClusterPathsEqualTheDerivedSet` case derives its expectation from `proxied`, so it cannot see an endpoint being removed from the public surface** — only the hard-coded `writes.size` can. ksqlDB's execute is a write, so both move | **W8-03** |
| `services/gateway/api/test/.../openapi/OpenApiMergeSuite.scala` | A hard-coded sorted path list over `gatewayDoc + clusterDoc` only | W8-03 if it adds a *gateway* path (it must not; a ksql push-query relay is a proxy route, in the shape `AlertsStreamRoutes` already has) |
| `apps/allinone/test/.../AllInOneWiringSuite.scala` | The startup-log string and the mounted-path set, which include the alerts feed and the public alerts stream. An eleventh service moves both, and a ksql stream joins them | **W8-10** |
| `docs/api/openapi.json`, `docs/api/openapi.browser.json` — `./mill services.gateway.api.openApiCheck` | A byte comparison against a fresh Tapir render of every service's endpoints. `build.mill` aims the *gateway* module's check at these, so the gateway module goes red for somebody else's endpoint | W8-01 and W8-03; repaired only by **W8-09** |
| `scripts/feature-matrix-check.sh` + the `<!-- checked: merged-document -->` regions in `docs/adr/ADR-048-*.md` and `frontend/packages/api/README.md` | **61 paths, 72 operations, 156 schemas, `X-Kui-Principal` on 56 operations over 45 paths, `X-Csrf-Token` on 24, `If-Match` on 2.** The script prints `251 claims checked, all true` over six sections. It went red at wave 6's integration and was repaired by hand; wave 7 kept it green through the wave | W8-01 and W8-03; repaired only by **W8-09**, whose owned rules are five holes in the script itself |
| `frontend/packages/api/src/schema.d.ts` + `ci.yml`'s regenerate-then-`git diff --exit-code` | The browser's types. **Nothing in Mill checks this**; that CI step is the only gate | **W8-09** |
| `frontend/scripts/bundle-shape.mjs` | Every `frontend/packages/feature-*` package present as a **dynamic** entry in the Vite manifest — seven today, eight after `feature-ksql`. Roster read from the filesystem, so it joins automatically and fails the build if it is statically imported. **It reads `frontend/dist/.vite/manifest.json`, so it is stale until somebody runs `pnpm -C frontend build`** | **W8-05** |
| `frontend/packages/kernel/styles/index.css` + `build-tests`' `CssReferencesSuite` | Every stylesheet on disk named exactly once; 38 files, 37 imports today | W8-05 adds the file, **W8-06 adds the line** — house rule 1 |
| `frontend/packages/shell/src/features/registry.ts` | *"The body of a `load` thunk must be a bare `import("@kui/feature-…")` and nothing else"* — the property `bundle-shape.mjs` measures. Its header prose counts the packages and **nothing asserts the count** | **W8-05**, both ends, and gate the count this time |
| `scripts/run-tests.sh` | **75 modules, 75 with tests, 3,987 cases**, and — for the first time — an **empty** "no test sources" list. Derived from `./mill resolve __.test`, so an eleventh service simply appears. **That empty list is a property this wave must not regress**: a new module with a declared test target and no sources puts it back | **W8-01**; and every adversary, whose job it was to empty it |
| `services/{metrics,alerts}/contract/test/resources/golden/*.json` + `frontend/.../overview/wire.golden.test.ts` and `.../data/alerts/wire.golden.test.ts` | Ten metrics documents and six alerts documents, read by Scala suites and browser suites over the same files. Moving one reddens both sides. **This is the pattern `services/connect` already follows and `e2e/connect.spec.ts` does not** | W8-02 (Scala side), W8-06 and W8-07 (browser side) |
| `services/connect/contract/test/resources/golden/*.json` + `frontend/packages/feature-connect/src/documents/*.json` | Five documents, byte-identical across the two trees, read off disk by `wire.golden.test.ts`, which counts connectors out of the **raw JSON** so a decoder that dropped every row cannot pass | W8-02 (service side), **W8-04** (browser side and the e2e spec that ignores them) |
| `libs/contracts-core/.../sse/SseEvents.scala` + `SseEventsSuite` | Five SSE event names, asserted by literal. **There is no `SseEventName.Alerts` and the alerts wire mirrors its name by eye**; a ksql push-query stream must not add a sixth copy of that mistake | **W8-01**, as a named two-file production exception |
| `libs/config/test/src/kui/config/ShippedConfigurationSuite.scala` | A list of shipped configuration files reconciled against disk in both directions, plus wave 7's `widenedExclusionProbes`, which derives `.yml` twins of `shipped` **only** — a pattern widened to an extension no `shipped` row uses is not probed | **W8-10**, both ends |
| `deployment/compose/smoke.sh` | The **contract** set scraped from `ServiceContracts.byService` against the containers; twelve exporter line shapes; four alerts assertions; three connect assertions **which pass with the Connect worker stopped** — W8-10's owned rule | W8-10 owns the script; W8-03 owns the Scala it scrapes |
| `docs/FEATURE_MATRIX.md` rows vs. its own prose, and `README.md` | **189 rows; 70 COMPLETE; 178 in scope; 39% delivered**, all inside checked regions, and `DECISIONS.md` machine-compared against `docs/adr/ADR-*.md` in both directions at **54 rows over 54 ADRs**. Nothing moved in wave 7 and the file says why, naming `KC-001/002/005/006` as the rows a driven pass would move | every packet that finishes a capability; recorded by **W8-09** |
| `build-tests/**` | The token mirror and the stylesheet roster. Unowned — house rules 1 and 2 forbid the rest | nobody |
| `frontend/packages/api/src/constants.generated.ts` | 31 error codes, byte for byte | house rule 3 forbids it |
| `./mill __.checkFormat`, `./mill __.fix --check`, `./mill checkArchitecture`, `./scripts/run-tests.sh`, `./scripts/feature-matrix-check.sh`, `./deployment/compose/smoke.sh`, `node frontend/scripts/boundaries.mjs` | **All seven are green at the start of this wave**, re-measured at wave 7's integration. `__.fix --check` caught a real import-order defect on its first ever integration run; it is on the list for good | whoever makes it so |

---

## W8-01 — `services/ksql`: the eleventh service, the edges first, and a push query that is a stream

**Owns**
```
services/ksql/**                                             (new)
build.mill
docs/adr/ADR-055-ksql-endpoints.md                           (new)
libs/contracts-core/src/kui/contracts/sse/SseEvents.scala    (named exception)
libs/contracts-core/test/src/kui/contracts/sse/SseEventsSuite.scala   (named exception)
```

**Contract.** Everything outside the service shipped in wave 1 and is still unused: `Resource.Ksql`
with `KsqlView`/`KsqlExecute` in `libs/security-core/.../Vocabulary.scala`, `ErrorCode.UpstreamKsql`,
and `KsqlSettings` per cluster in `libs/config` (so `kui.clusters.0.ksql.url` already loads). Do not
widen any of them. The worked example is `services/connect`, which wave 7 built in one packet with its
`build.mill` edge landed first and nothing about its routing arriving at integration — copy that
shape.

**The read-only case is already settled and asserted**: `KsqlExecute` implies `KsqlView`, so a
read-only cluster lists objects and refuses statements, which `RbacLawsSuite` already holds. Do not
re-litigate it.

**Do**
1. **First, before any of your own work, land every dependency edge this wave declares in
   `build.mill`, and prove it compiles.** The edges are: `ksql.contract.jvm` on
   `services.gateway.api` (W8-03), `mvn"org.typelevel::cats-effect-testkit::${Versions.catsEffect}"`
   on `libs.serdeConfluent.test` (W8-A1 asked for it and wave 7 could not land it), and any test
   dependency an adversarial packet declares. Your acceptance list includes
   `./mill services.gateway.api.compile` **with a stub contract object present**, so W8-03 is never
   blocked on you.
2. **Tell W8-03 the instant `services/ksql/domain/` exists.** `ArchitectureDocumentSuite` reads
   `services/*` off disk and reddens the whole gateway suite for a service with no `ARCHITECTURE.md`
   §3 row. That is not your file. It is the one cross-packet coupling in this wave that fires on
   directory creation rather than on an import.
3. The six ADR-041 layers over the ksqlDB REST API: object listing (streams, tables, queries,
   topics), statement execution as a **mutation**, and push queries over ADR-035.
4. **`DROP … DELETE TOPIC` is plan→token→confirm (ADR-045).** Copy `services/topic`'s shape. A
   cancelled mutation is `MutationOutcome.Unknown`, never `Failed` — three services have had this
   wrong and been repaired; do not create a fourth.
5. **The push query ships its gateway relay's contract**, and the relay itself is W8-03's. House rule
   16: the criterion is `curl -N` through the gateway with a frame quoted, and a frame that only
   arrives when something changes has to be made to change — say in ADR-055 what makes one arrive.
6. **If the stream needs an SSE event name, add it to `SseEventName` and to nothing else.** The two
   `libs/contracts-core` files are yours as a named exception for exactly this. `SseEventName` has no
   `Alerts` entry and the alerts wire mirrors its name by eye; a sixth copy of that mistake is worse
   than the first five. If you add a name, add the case that pins its literal, and say in ADR-055 how
   the browser's constant is compared to it.
7. ADR-055: which ksqlDB version is assumed, what a statement that returns rows looks like on the
   wire versus one that returns a status, what happens to a push query whose client disconnects, the
   retention (if any) of anything this service keeps, and **the ksqlDB result region, which no capture
   shows** (§7.9) — decide it here, before W8-05 draws it.
8. **The golden documents, in your own contract module, from your own encoder.** House rule 12. Not a
   hand-written literal: render the DTO and commit what came out. `services/connect` has five and
   `services/alerts` six; a service with none is the shape that produced two mismatched wires.

**Do not** seed a stream or a table to make a screen look alive, and do not add a `kui.clusters.*.ksql`
key.

**Acceptance**
```
./mill services.ksql.__.test
./mill services.gateway.api.compile        # the edge from item 1, before anything else
./mill checkArchitecture
./mill services.ksql.__.checkFormat
./mill services.ksql.api.openApi           # regenerate this module's own document, and commit it
./mill services.ksql.api.openApiCheck
./mill libs.contractsCore.jvm.test
./scripts/run-tests.sh                     # the module count moves; the empty "no test sources" list must stay empty
```
Required cases, by name: a server that names two streams and a table reaches the wire with each
object's kind; a `SELECT … EMIT CHANGES` is a push query and answers a stream rather than a document;
a statement a principal without `KSQL:EXECUTE` asks for is refused before the server is called; a
cancelled execution is audited as `Unknown`; `DROP … DELETE TOPIC` is refused without a plan token and
accepted with one; a deployment with no ksqlDB address answers `not_configured` with a 200.
**Mutation line:** make `KsqlExecute` no longer imply `KsqlView` at the endpoint's authorization
declaration. Name the case that goes red. **And a green one:** mutate whatever bound your object
listing keeps and report whether anything notices.

---

## W8-02 — Three services that shipped ungated: eight rules across `connect`, `alerts` and `metrics`

**Owns**
```
services/connect/**
services/alerts/**
services/metrics/**
docs/adr/ADR-052-metrics-endpoints.md
docs/adr/ADR-053-alert-events.md
docs/adr/ADR-054-connect-endpoints.md
```

**Contract.** Eight rules in code this wave's siblings did not write, every one measured by an
independent verifier at wave 7's integration, with the exact mutation quoted. Close them. They are
not a list of suggestions: each is a sentence a comment in the tree already makes, that nothing in the
tree holds.

**Do — the owned rules, in this order**

1. **A rule that clears itself wakes no subscriber.**
   `services/alerts/infrastructure/.../InMemoryAlertStore.scala:148`, `record`:
   `.whenA(evaluation.opened.nonEmpty || evaluation.resolved.nonEmpty)` → `.whenA(evaluation.opened.nonEmpty)`
   leaves all 1289 alerts tasks SUCCESS. The existing case (`InMemoryAlertStoreSuite:301`) only ever
   *opens* events, so the resolution half is exercised by nothing. This is the direct twin of the rule
   wave 7 closed on the other writer, and **ADR-053's own new bullet claims it is covered** — *"asserts
   the publication for both writers"*. Close the branch and correct the sentence.
2. **The broker-wide idle ratios accept a per-slice line.**
   `services/metrics/infrastructure/.../PrometheusExposition.scala:245`, `ratioOf`:
   `dimensionsOf(sample).isEmpty` → `dimensionsOf(sample).sizeIs < 2` leaves all 1237 metrics tasks
   SUCCESS. This is the **third** copy of the no-double-count guard in that file: `aggregateOf`'s
   reddens seven cases, `purgatoryOf`'s reddens one after wave 7, and `ratioOf`'s — which feeds
   `requestHandlerIdleRatio` and `networkProcessorIdleRatio`, the two saturation gauges on the brokers
   screen — reddens nothing. Under it a single processor's ratio draws as the pool's.
3. **The read-marker cache has an unexercised bound.** `InMemoryAlertStore.scala:189`:
   `val MaxReadMarkers: Long = 2000L` → `2L` leaves all 1289 alerts tasks SUCCESS. Wave 7 disclosed
   this and chose neither of the two ways to close it; choose one.
4. **The failed task a card names is not pinned to task-id order.**
   `services/connect/domain/.../Connectors.scala`, `Connector.reason`:
   `tasks.sortBy(_.id.value).filter(_.state.isFailed)` → `tasks.reverse.filter(...)` leaves 129 cases
   green. The scaladoc promises *"in task-id order, so that two screens reading one document cannot
   pick different tasks"*. The case named for the rule (`ConnectorsSuite:104`) builds tasks `[2, 1]`,
   where `reverse` and `sortBy` agree, so **the case cannot fail**. Fix the fixture, not just the
   assertion.
5. **A connector with no `tasks` key is a connector with no tasks.**
   `services/connect/infrastructure/.../ConnectHttp.scala`, `tasksFrom`: `case None => Some(Nil)` →
   `case None => None` leaves 129 green. Under it a paused or freshly created connector — the case the
   comment names out loud — stops being a connector with zero tasks and becomes `unreadable`, vanishing
   from the list and reappearing in the *"KUI could not describe it"* row.
6. **Three ordering rules on the same wire, all green under mutation.** In the same file:
   `tasksFrom`'s `.map(_.sortBy(_.id.value))` → `.map(_.reverse)`; `expanded`'s
   `.sortBy(_.name.value)` → `.reverse`; `perConnector`'s `names.sorted` → `names.reverse`. All 129
   green for each. Circe hands back the worker's JSON key order, which a Connect herder is under no
   obligation to keep, so a panel's row order changes between polls and rule 4's own input is
   unordered. **Both readers** — the 2.3 path and the pre-2.3 fallback — need the case, and it has to
   compare two connectors' positions, which nothing in 129 cases does.

**Do — the corrections**

7. **Three declarations in `services/connect/domain` have no production caller**, only tests:
   `ConnectorOperation.fromWire` (the routes bind one operation per endpoint statically),
   `ConnectorState.isPaused`/`Paused`, and `ConnectorFacts.partial` (`ConnectMapping` puts
   `facts.unreadable` on the wire itself). Delete them or give them a caller, and say which.
   `ConnectorState`'s scaladoc justifies `Paused` with *"a paused connector's zero throughput is a
   measured zero"* — **there is no throughput anywhere on this wire**, which is the service's own
   headline. House rule 11.
8. **`ADR-053` ships a to-do for work that is already done** — it asks for a browser assertion of
   `ALERTS_EVENT_NAME` against `alerts-stream-frame.json`'s `event` field, which exists at
   `frontend/packages/kernel/src/data/alerts/wire.golden.test.ts:216` and passes. Delete the to-do.
9. **`ADR-052` §11 contains one false clause among correct ones**: *"which is every file in
   `deployment/` that mentions the key at all"*. `deployment/metrics/kafka-jmx-exporter.yml` mentions
   `kui.topics.internalPrefix` at line 179 without setting it. The sentence should say *sets*.
10. **`ADR-054`'s openApi acceptance figure is wrong**: it says *"7 paths, 4 operations, 10 schemas"*;
    the document has 7 paths and **seven** operations, one per path. Four is the merged-document
    figure.
11. **The connect test rig cannot see a dropped endpoint.** `ConnectTestServer.scala:134` assembles
    `HealthEndpoints.make ++ ConnectRoutes` itself rather than driving `ConnectApi.routes`;
    `services/alerts` has `AlertsTestServer.compositionRoutes` for exactly this. And
    `ConnectWiring.make` is constructed by no case at all, so its audit-sink wiring, its `RbacGuard`
    policy and its `(ClusterId, ConnectName)` worker key are unasserted. Give both a case.

**Do not** widen a DTO, and do not put a throughput figure on the Connect wire — the absence is the
decision ADR-054 records.

**Acceptance**
```
./mill services.connect.__.test + services.alerts.__.test + services.metrics.__.test
./mill services.connect.__.checkFormat + services.alerts.__.checkFormat + services.metrics.__.checkFormat
./mill services.connect.api.openApiCheck + services.alerts.api.openApiCheck + services.metrics.api.openApiCheck
./mill checkArchitecture
```
**Mutation line:** re-apply each of the six mutations in items 1–6 one at a time and name the case
each reddens. Six red cases, six names, or the rule is not closed. **And a green one:** report one
mutation in these three services that still stays green.

---

## W8-03 — The gateway: the eleventh service, the relay's terminal frame, and a table that cannot see an omission

**Owns**
```
services/gateway/**
ARCHITECTURE.md
```

**Contract.** Route the eleventh service, close two rules wave 7 shipped ungated, and repair the
document your own new suite gates — which currently contains a false claim, a stale count and a
sentence that was untrue the day it was written.

**Do**
1. **`ARCHITECTURE.md` §3 gets the `ksql` row the moment `services/ksql/domain/` exists.** Your own
   `ArchitectureDocumentSuite` reddens the whole gateway module for a service directory with no row.
   Coordinate with W8-01 on the shape, not the diff.
2. **The alerts relay's terminal error frame is ungated.**
   `services/gateway/api/.../AlertsStreamRoutes.scala`, `relay`: replacing
   `StreamProxy.withTerminalEvent(upstream, ErrorEnvelope.of(...))` with a bare `upstream` leaves
   **43 suites / 387 cases green**, `AlertsStreamRoutesSuite` 5/5 and `StreamProxySuite` 13/13. The
   rule that dies is the one product promise in the file: a browser never sees an SSE connection just
   stop. `StreamProxySuite` tests the helper in isolation and nothing tests that the **relay uses it**.
   Close it, and close the same rule on the ksql relay in the same case shape.
3. **The §3 ports column can be silently emptied.** `everyPortNamedInTheServiceTableIsATraitInThatServicesDomain`
   checks that identifiers the row *names* exist as traits; it never checks that traits that *exist*
   are named. Deleting `` `ClusterFactsPort[F]` `` from the alerts row leaves the suite 6/6 green.
   Omission is invisible, which is the exact failure mode `ServiceContracts`' own comments call out for
   endpoint lists. Make the check bidirectional.
4. **Three false or stale sentences in the document the suite exists to keep true.**
   (a) *"Nine named identifiers the tree declares nowhere"* is **eight**: `MessageFilterPort[F]` is
   declared at `libs/filter/src/kui/filter/CelFilterEngine.scala:88` and belongs in the "owned by a
   library" bucket beside `SerdeRegistry`, `AuditSink` and `GroupAdmin`.
   (b) The connect row's ports cell says *"**not built** when this row was last checked"* while
   `services/connect/domain/src` exists and declares `ConnectWorkerPort[F]`.
   (c) *"six of the eight built services"* — nine services have a `domain` today, ten after W8-01.
5. **`moduleDepsOfGatewayApi()` parses `build.mill` by `build.indexOf("\n      )", start)`** — a
   six-space-indented closing paren. A reformat truncates or over-reads the block silently, and the
   gate can then only go quiet, never loud. Assert the block's shape, not just that it was found.
6. **The `ALERTS:VIEW` refusal case detects a widened requirement only by hanging.** Repointing
   `ResourceRequirement.unnamed` at `Resource.Topic` makes it fail as
   `TimeoutException: test timed out after 31 seconds`, not on its `403`/`KUI-FORBIDDEN` assertion,
   because the body is `Stream.emit(...) ++ Stream.never`. It is red, so the rule is closed, but the
   diagnosis a maintainer gets is "timeout" and it costs 31 seconds of wall clock. Bound the read.
7. **Route ksqlDB**: the `Section`-wrapped reads and the execute write proxied the way connect and
   metrics are, the ninth id in `ServiceContractsSuite`'s `Set`, the moved `writes.size` in
   `MergedDocumentShapeSuite`, and **the hand-written push-query relay** in the shape
   `AlertsStreamRoutes` already has. Add **no gateway path of your own**: `OpenApiMergeSuite` pins a
   hard-coded list.
8. **`ReadinessPollerSuite.scala:165` is 113 columns** and was added by wave 7 under a 110-column
   `.scalafmt.conf`. `./mill services.gateway.__.checkFormat` resolves to four production modules and
   **no test module**, so nothing catches it. Fix the line; the general problem is W8-09's.

**Do not** put an event stream in `ServiceContracts.byService`; `ContractRouting.derive` decodes and
re-encodes JSON.

**Acceptance**
```
./mill services.gateway.__.test
./mill services.gateway.__.checkFormat
./mill services.gateway.__.fix --check
./mill checkArchitecture
```
and, against the stack W8-10 publishes:
```
curl -N http://localhost:8080/api/v1/clusters/<id>/ksql/stream   # a frame, quoted, with what made it arrive
```
**Mutation line:** delete `StreamProxy.withTerminalEvent` from the alerts relay (item 2). Name the
case that goes red. **And a green one:** empty one cell of `ARCHITECTURE.md` §3's ports column *after*
item 3 lands, and report whether the suite notices.

---

## W8-04 — `feature-connect` and the browser evidence M9 is blocked on

**Owns**
```
frontend/packages/feature-connect/**
frontend/e2e/connect.spec.ts
```

**Contract.** This packet closes a milestone. `services/connect` is built, routed, imaged, capable and
answering real documents through the gateway; **the only thing standing between it and M9 is one spec
file and one deployed connector**, and the connector is W8-10's. Eleven rules in this package survive
mutation with all 58 cases green, nine of them undisclosed. The eleven are the work; the spec is the
milestone.

**Do — the spec first, because it is the blocker**
1. **`e2e/connect.spec.ts` is written against a wire that does not exist.** Lines 33–47 hand-write
   `interface ConnectorsDocument` with `connectors.data.items` and `connectors.data.workers: {name,
   status}[]` — the *pre-rewrite* prose shape, the one this package's own `wire.ts` was corrected away
   from mid-wave. The service renders `connectors.data.workers[].connectors.data.items`. Measured at
   wave 7's integration against a correctly routed server:
   - `:76` `expect(Array.isArray(section.data?.items)).toBe(true)` **fails**;
   - `:124` `const items = section?.data?.items ?? []` is always `[]`, so `test.skip(items.length === 0, …)`
     skips the positive half **forever**, and its skip message blames
     `deployment/quickstart/kui-quickstart.yaml`, which will be a false explanation.
   Rewrite it against `services/connect/contract/test/resources/golden/connectors-response.json`, which
   this package already carries byte-identical copies of. **House rule 12 applies to `e2e/` too**: read
   the golden off disk rather than restating the shape.
2. **Drive it against the stack W8-10 publishes, with a connector deployed on it**, and quote the two
   image ids. The two positive cases must **run**, not skip: house rule 6.

**Do — the eleven owned rules.** Each mutation below leaves `pnpm -C frontend test packages/feature-connect`
at 58/58 green. Nine were undisclosed.

3. `ConnectorList.tsx`, `pendingFor`: replace
   `return pending !== undefined && pending.subject === connectorLabel(connector) ? pending.command : undefined;`
   with `return pending?.command;` — **a command in flight against one connector marks every card
   busy.** Its twin `failureFor` is gated; the pending half is not.
4. `wire.ts`, `decodeConnector`: `taskCount: asNumber(record["taskCount"]) ?? tasks.length` → `?? 0` —
   a connector whose `taskCount` the service omits renders *"This connector has no tasks."* over a
   described task list.
5. `ConnectorPanel.tsx`: `fallback={<p …>{NO_REASON_REPORTED}</p>}` → `{""}` — a failed connector with
   no reason renders an **empty red block**, which `NO_REASON_REPORTED`'s own doc says reads as *"KUI
   knows and will not say"*.
6. `ConnectorList.tsx`: `<Show when={props.state.kind === "forbidden"}>` → `<Show when={false}>` — a
   principal without `CONNECT:VIEW` gets a blank page. (Wave 7 disclosed the *per-worker* banner; this
   is the outer state and a different block.)
7. `ConnectorList.tsx`: `<Show when={props.state.kind === "stale" ? props.state.reason : undefined}>` →
   `<Show when={undefined}>` — a listing the kernel marked stale draws as fresh. There is a Storybook
   story for it and no case.
8. `ConnectorPanel.tsx`: `busy={props.pending !== undefined}` → `busy={false}` — this is not cosmetic:
   the kernel `Button`'s `inert()` is what swallows the click, so `busy` is the only thing stopping a
   second `POST /pause` while the first is in flight.
9. `ConnectorPanel.tsx`: replace the
   `{...(props.refusal === undefined ? {onPause, onRestart} : {actionsDisabledReason: props.refusal})}`
   spread with unconditional handlers — 58/58 green, *including* the case named *"hands an unpermitted
   principal no working control anywhere on the page"*, because that case measures `aria-disabled`
   only. Defused today solely by the kernel; a kernel rework lands it live with the suite green.
10. `wire.ts`, `decodeConnector`: delete `if (name === undefined) return undefined;` — a nameless
    connector is drawn instead of dropped, and its Pause posts `connectorName: "(unnamed)"`.
11. `wire.ts`, `decodeWorker`: `const connect = asString(record["connect"]) ?? ""` — banners read
    *": the cluster is rebalancing"* and `operateSubject()` hands the empty string to the permission
    question.
12. `ConnectorList.tsx`, `WorkerNotice`: `<Show when={page().kind === "forbidden"}>` → `<Show when={false}>`
    (disclosed in wave 7 and confirmed).
13. `data.ts`: `RESTART_PATH`'s last segment `/restart` → `/reboot` (disclosed in wave 7 and confirmed).

**Do — the corrections**
14. **Two orphans in the package.** `model.ts:125 taskCaption()` has no product caller — `ConnectorPanel`
    uses its own local `taskSentence` — and `model.ts:208 THROUGHPUT_NOT_MEASURED` has **no reader at
    all, not even a test**: the words the suite asserts come from the kernel's `ConnectorCard.tsx:99`
    literal. Two uncompared copies of one user-facing sentence. Delete them or compare them.
15. **`ErrorCodes.ConnectRebalancing` still has no code reader**, contrary to wave 7's report:
    `wire.ts` folds on its own local `REBALANCING_REASON_CODE = "STARTING"` and `connect.test.tsx:401`
    writes the bare string `"KUI-CONNECT-REBALANCING"`. Read the generated constant or say why not.
16. **A header that contradicts the ten lines under it.** `ConnectorPanel.tsx`'s `NotDescribedPanel`
    doc says *"There is no task bar and no state pill"*; the component renders a `StatusPill` from
    `connectorChip("UNKNOWN")` and the suite asserts its text.
17. **Column violations:** `styles/81-connect.css` lines 5 and 120 are 101 columns, `package.json`
    line 6 is 135, `e2e/connect.spec.ts:56` is 101. The 100-column claim held for `.ts`/`.tsx` only.

**Do not** import from another feature package, and do not statically import anything the shell loads
dynamically — `bundle-shape.mjs` fails the build.

**Acceptance**
```
pnpm -C frontend test packages/feature-connect
pnpm -C frontend typecheck
node frontend/scripts/boundaries.mjs
pnpm -C frontend build && node frontend/scripts/bundle-shape.mjs
pnpm -C frontend e2e e2e/connect.spec.ts        # against W8-10's stack, with a connector on it
```
`connect.spec.ts` must report **four passed, zero skipped**, and the report quotes the two image ids.
**Mutation line:** item 8, `busy={false}`. Name the case that goes red. **And a green one:** after the
eleven are closed, find a twelfth and report it.

---

## W8-05 — `feature-ksql`, the eighth feature package, the registration seam, and a count nobody asserts

**Owns**
```
frontend/packages/feature-ksql/**                            (new)
frontend/packages/shell/src/features/**
frontend/packages/shell/package.json
frontend/tsconfig.json
frontend/e2e/ksql.spec.ts                                    (new)
docs/adr/ADR-056-ksql-result-region.md                       (new)
```

**Contract.** The eighth feature package and the third `ECOSYSTEM` row. The route and the nav
destination are **W8-07's**; you ship what is behind them plus the registration seam. `FeatureId` gains
`"ksql"` in W8-06's tree and `styles/index.css` gains one import line there too — house rule 1, one
file, one line, one owner.

**Do**
1. The ksqlDB screens §7.9 asks for: an object list (streams, tables, queries), a statement editor,
   and a result region. **The result region is the one decision in this wave that no capture shows.**
   W8-01 settles the *wire* in ADR-055; you settle the *rendering* in ADR-056, and you settle it
   before you draw it. A push query's result is unbounded and arrives over time; say what the screen
   does when it is still arriving, when it stops, and when it stops because the server went away.
2. **Read the service's goldens off disk**, the way `feature-connect`'s `wire.golden.test.ts` does —
   including its best property, which is that it counts rows out of the **raw JSON** and compares, so
   a decoder that dropped every row cannot pass. Copy that, not the shape of a package that
   hand-writes an interface.
3. **The registration seam, all four ends:** the eighth entry in
   `shell/src/features/registry.ts` whose `load` thunk is a bare `import("@kui/feature-ksql")` and
   nothing else; one dependency in `shell/package.json`; the eighth reference in
   `frontend/tsconfig.json`; and the lockfile importer, which is mechanically forced and goes in
   `needsOutsideOwnership`.
4. **Gate the registry's own count.** `registry.ts`'s header says *"seven of them, counted in this
   array"* and nothing asserts it — `shell/src/app.render.test.tsx` iterates the registry rather than
   sizing it. It has now been wrong-by-inheritance twice. Assert the size, or delete the sentence.
5. **A statement a read-only cluster cannot run is refused in the browser too**, with the
   subject-aware question — `kui.permits(Actions.KsqlExecute, <cluster or object>)`, not the
   subjectless form. W8-08 is repairing four screens that got this wrong; do not add a fifth.
6. `e2e/ksql.spec.ts` against W8-10's stack, driving the real address. If the quickstart has no ksqlDB
   server, **say so and assert the `not_configured` rendering** — but then item 1's positive half is
   not proved, and house rule 6 says an acceptance list cannot be all refusals. Raise it in
   `needsOutsideOwnership` early enough for W8-10 to add a container.

**Do not** edit any routing file. A feature reaches its own pages through `kui.paths.*`.

**Acceptance**
```
pnpm -C frontend test packages/feature-ksql packages/shell
pnpm -C frontend typecheck
node frontend/scripts/boundaries.mjs
pnpm -C frontend build && node frontend/scripts/bundle-shape.mjs      # eight dynamic feature chunks
pnpm -C frontend build-storybook && (serve :6017) && pnpm -C frontend a11y 'ksql-|screens-ksql'
pnpm -C frontend e2e e2e/ksql.spec.ts
```
**Mutation line:** make the statement editor's permission question subjectless. Name the case that
goes red. **And a green one:** mutate the registry entry's `load` thunk into something other than a
bare import and report what `bundle-shape.mjs` says.

---

## W8-06 — The kernel: two rules in the alerts store, the eighth `FeatureId`, and the flake nobody owns

**Owns**
```
frontend/packages/kernel/**
frontend/scripts/a11y-stories.mjs
frontend/vitest.config.ts
```

**Contract.** Two ungated rules in a file this owner wrote last wave, the two-line seam W8-05 needs,
and the one flake in `pnpm -C frontend test` — which is now house rule 9's standing example and is
yours because the config it lives in has never had an owner.

**Do**
1. **The other half of last wave's own owned rule is ungated.**
   `src/data/alerts/store.ts:397`, `applyRead()`: moving `setStreamed(null);` from the first statement
   to **below** the transport-failure early return leaves 23 files / 452 cases green. The file says the
   rule is *"on EVERY outcome … and on a refusal it is how a bell shows a live count beside a feed that
   says it cannot be read"*; only the `ok` path is gated. The file's own defence of why this is
   harmless is correct today, which is exactly why the mutation is invisible — and it makes *"on every
   outcome"* a claim the suite does not hold.
2. **The cancellation constraint is ungated.** `store.ts:478`, `stop()`: deleting `episode += 1;`
   leaves 452 green, because the `stopped` flag already refuses every such read on its own. The
   `episode` field's doc says it is *"Raised by `start()`, by `stop()` and by every read"*. This is the
   redundant-depth-versus-reachable-gate category: **either delete the line and the sentence, or
   construct the state that makes it reachable.** Wave 7 deleted `connect()`'s unreachable guard and
   shipped this twin with a comment that reads as a considered defence.
3. **A false hand-off shipped in the source.** `store.ts`'s header states as measured fact that
   `Alerts.unreadCount`, `lastReadAt` and `connection` have no production callers. `unreadCount()` has
   one: `shell/src/App.tsx`'s drawer badge, which wave 7's frame packet wired in the same wave. Correct
   the sentence, and do **not** delete `unreadCount` — the other two are still callerless and their
   fate is a decision to record, not to assume.
4. **`FeatureId` gains `"ksql"` and `styles/index.css` gains one import line** naming
   `feature-ksql`'s single stylesheet at the number W8-05 states. Ship both whether or not W8-05 asks.
   `build-tests`' `CssReferencesSuite` requires the line and `build-tests/**` is owned by nobody.
5. **The axe flake, in both directions.** `pnpm -C frontend test` fails intermittently with
   `Test timed out in 5000ms` inside an axe case; the file moves between runs; measured at wave 7's
   integration the whole suite failed once on `feature-connect`'s a11y case and that package alone
   passed 62/62 three times. It is the vitest default timeout expiring under 77 parallel jsdom
   environments. **Reproduce it deliberately** (raise the concurrency, or lower the timeout until it
   fires every time), fix it in `vitest.config.ts`, and show it not firing under the load that made it
   fire. House rule 9: explain it before you patch it, or say you could not.
6. **`wire.golden.test.ts`'s `decodeDocument()` has a vacuous-pass hole in the counter built to close
   vacuous passes.** In the `feed-response` branch, a golden whose section is `forbidden`,
   `not_configured` or `unavailable` increments `decoded` without ever reaching `decodeAlertFeed`. No
   such golden exists today; make one, or make the counter refuse.

**Do not** add a custom property to `10-tokens.css` (house rule 2), and do not widen `FeatureId` past
the one entry.

**Acceptance**
```
pnpm -C frontend test packages/kernel
pnpm -C frontend test                                  # the whole suite, three consecutive runs, all green
pnpm -C frontend typecheck
./mill build-tests.test                                # CssReferencesSuite sees the new line
pnpm -C frontend build-storybook && (serve :6017) && pnpm -C frontend a11y
```
**Mutation line:** item 1 — move `setStreamed(null)` below the early return. Name the case that goes
red. **And a green one:** report one mutation in the kernel that still stays green after items 1–6.

---

## W8-07 — The frame: a green pill over twelve open alerts, `ECOSYSTEM`'s third row, and the twenty-three screens

**Owns**
```
frontend/packages/shell/src/App.tsx  (+ its two test files)
frontend/packages/shell/src/chrome/**
frontend/packages/shell/src/nav/**
frontend/packages/shell/src/data/**
frontend/packages/shell/src/routing/**
frontend/packages/shell/src/overview/**
frontend/packages/shell/src/pages/**
frontend/packages/shell/src/index.ts
frontend/packages/shell/styles/**
frontend/e2e/shell.spec.ts
frontend/e2e/dashboard.spec.ts
frontend/e2e/search.spec.ts
frontend/e2e/traffic.spec.ts
frontend/e2e/brokers.spec.ts
research/design/SCREENS-V4.md                                 (the ksql-row paragraph only)
```

**Contract.** Three rules in the dashboard card this owner shipped last wave, the ksql route and nav
row, and the one thing M10 needs from the browser suite that nobody has scoped: **coverage of all
twenty-three screens.**

**Do**
1. **The alerts card's pill tone is gated by nothing.** `src/overview/AlertsCard.tsx`:
   `tone={(props.alerts.openCount() ?? 0) > 0 ? "danger" : "success"}` → `tone="success"` leaves
   **20 files / 523 cases green** — verified twice at wave 7's integration, and the reverse
   (`tone="danger"`) is green too. **A cluster with twelve open alerts draws a green dot pill reading
   `12 open` on the dashboard, and a cluster the rules swept clean draws a red one reading `None
   open`.** This is the exact defect the paragraph this same packet added to §3.8 argues against — *"A
   green None open there would be the most reassuring thing this screen can say and it would be about
   nothing at all"*. The sentence is gated; the colour carrying it is not. All six of the card's cases
   select on text.
2. **A refusal can be drawn as an empty card.** Same file, `cardState()`:
   `case "forbidden": return "forbidden"` → `return "empty"` leaves 523 green. The kernel's `Card.tsx`
   branches its empty-state illustration on exactly that word, so a principal without `ALERT:VIEW`
   gets *"this cluster has no alerts"* instead of *"you are not allowed to see them"* — a refusal
   rendered as a measurement, one file away from the retry-under-a-refusal rule this packet closed
   last wave.
3. **The `"loading"` arm of the same six-line switch** — `return "loading"` → `return "ready"` — 523
   green (disclosed in wave 7). Three holes in one switch; close all three with cases that read the
   state word, not the sentence.
4. **`ECOSYSTEM` gets its third row and ksqlDB gets its address.** The route in `routing/**`, the nav
   row in `nav/**`, the badge row in `App.tsx`, the crumb and the landing address. `routes.test.ts`'s
   whole path→feature map is asserted at **fourteen** rows today (wave 7's report said thirteen and
   had itself added the fourteenth); it becomes fifteen.
5. **Scope the twenty-three screens, and say which are uncovered.** M10 asks for a browser suite
   covering all twenty-three screens in `screens/`. There are **eleven** spec files. Nobody has ever
   published the mapping. Produce it — one table, screen → spec → case — in
   `docs/plan/verification/W8-07.md`, and write the specs for the gaps you own. Gaps in another
   packet's specs go in `needsOutsideOwnership` with the screen named. **This is the single largest
   unknown in M10 and it is being measured for the first time.**
6. **A capability finished with no matrix row.** Wave 7 mounted the dashboard alerts card — M8's third
   reader — and `docs/FEATURE_MATRIX.md` carries no claim about it. Tell W8-09, in
   `needsOutsideOwnership`, which rows your work moves.
7. **Two stale citations.** `App.tsx`'s bell comment was reported as naming `knownOpenCount` and does
   not (it says *"straight off the kernel store's one derivation"*); and
   `feature-alerts/src/data.ts:7` and `alertsRoute.test.tsx:46` still cite `ALERTS_FEED_PATH` by name
   in prose after wave 7 un-exported it, so a cross-package citation now names a symbol that package
   cannot reach. The `feature-alerts` half is W8-08's; tell it.

**Do not** statically import a feature package — `bundle-shape.mjs` fails the build. The ksqlDB screen
is drawn by `@kui/feature-ksql`; the frame draws the route to it.

**Acceptance**
```
pnpm -C frontend test packages/shell
pnpm -C frontend typecheck
pnpm -C frontend build && node frontend/scripts/bundle-shape.mjs
pnpm -C frontend e2e e2e/shell.spec.ts e2e/dashboard.spec.ts e2e/search.spec.ts e2e/traffic.spec.ts e2e/brokers.spec.ts
```
Against W8-10's stack with one seeded alert, `shell.spec.ts`'s two alerts cases must **run**, not skip
— they both passed at wave 7's integration and the report must say so with the image ids.
**Mutation line:** item 1, `tone="success"`. Name the case that goes red. **And a green one:** report
one mutation in `src/overview/` or `src/nav/` that still stays green.

---

## W8-08 — The four screens that ask the wrong permission question, and a filter bar nothing clicks

**Owns**
```
frontend/packages/feature-alerts/**
frontend/packages/feature-topics/**
frontend/packages/feature-consumers/**
frontend/packages/feature-messages/**
frontend/e2e/alerts.spec.ts
frontend/e2e/topics.spec.ts
frontend/e2e/consumers.spec.ts
frontend/e2e/messages.spec.ts
```

**Contract.** Eight ungated rules and two defects that are simply wrong as shipped. The headline is
one rule stated four times: **a page whose whole subject is one named object asks the permission
question without naming it.** Wave 7 closed it in `GroupRoute.tsx`, wrote three paragraphs about it,
and left the identical line in the two packages one directory over — on lines it was editing.

**Do — the permission question, everywhere it is wrong**
1. `feature-topics/src/TopicsRoute.tsx:736, 743, 758, 765` — the topic page's *Empty this topic*,
   *Delete this topic*, *Add partitions* and settings-editor gates ask `kui.permits(Actions.TopicDelete)`
   etc. with **no subject**, on a page whose whole subject is one named topic. Changing them to name a
   **foreign** subject leaves **157/157 green**, because nothing on that page can tell a subject-aware
   answer from a subjectless one. `kernel/src/state/session.ts:110-116` is explicit that the subjectless
   form is *"the right answer for a list heading and the wrong one for a row's delete button"*. An
   account granted `TOPIC:DELETE` on `analytics.*` is offered a live *Delete this topic* on
   `orders.payments`.
2. `feature-messages/src/MessagesRoute.tsx:338, 343, 552` — the same, for `TopicMessagesProduce` and
   `TopicMessagesRead`; **165/165 green** under a foreign subject.
3. **The test helper is the reason it is invisible.** `feature-messages/src/route.test.tsx:903`'s
   `permitsAllBut` is `(asked) => !denied.some(...)` and throws the `name` away — the unfixed twin of
   the helper wave 7 repaired at `groupRoute.test.tsx:135`. Repair it first; the cases follow.
4. **A read-only cluster's flag reaches none of the alerts screen's write gates.**
   `feature-alerts/src/AlertsRoute.tsx:104` passes `readOnly: false` as a **hard-coded constant** into
   `writeBlockedReason`. This is not a mutation finding: it is wrong as shipped. A cluster registered
   read-only still hands a permitted principal a fully enabled *Acknowledge* button and the write is
   issued. `feature-topics` already threads the real flag through `useClusterReadOnly`
   (`TopicsRoute.tsx:178, 379, 471`); use it.

**Do — the remaining ungated rules**
5. `feature-alerts/src/AlertsRoute.tsx` — **the *Alert state* chip bar is carried by nothing.** Three
   separate mutations leave 61/61 green: swapping the `open`/`resolved` chip values (the reader clicks
   *Open* and is shown the closed events); `onChange={setLifecycle}` → `onChange={() => {}}` (every
   chip dead); and `state: lifecycle()` → `state: "all"` in the `FeedFilter` memo. `filterEvents` is
   gated at the model level and `AlertsFeed`'s application of the prop is gated; the wire from the bar
   to the filter is not, because **no case in the package ever clicks *Open* or *Resolved***. The one
   route-level filter case clicks the *severity* chip.
6. `feature-topics/src/TopicsRoute.tsx:330`, `pollUntilListed`: delete `if (listed()) return;` —
   **157/157 green.** That is the short-circuit the function is named for: every successful create then
   fires all six reloads over three seconds. The only case driving the loop passes `() => false` as
   `listed`, so it is structurally incapable of seeing it.
7. `frontend/e2e/topics.spec.ts:193` — *asks the gateway whether the cluster is registered read-only*
   — **gates nothing.** Its `waitForResponse` on `GET /api/v1/clusters/quickstart` is satisfied by the
   *shell's* own request: `shell/src/App.tsx:717` → `clusterStore.ts:128` fetches that endpoint on
   every route. Proved at wave 7's verification: two such requests fire on
   `/ui/clusters/quickstart/consumer-groups`, where `feature-topics` is not mounted at all. Delete
   `useClusterReadOnly` entirely and the case still passes. Make it assert something only this
   packet's request can satisfy.
8. `feature-topics/src/TopicsRoute.tsx:306`: `CREATE_POLL_INTERVAL_MS = 500` → `50` — 157/157 green
   (disclosed; the stated reason for leaving it, that gating it means asserting wall-clock duration, is
   sound, so **either gate it with fake timers or delete the export**, which has no consumer).

**Do — the corrections**
9. **The alerts fixtures name rules the service does not have.** `AlertVocabulary.scala:87-96` ships
   exactly four: `offline-partitions`, `under-replicated-partitions`, `stuck-rebalance`,
   **`disk-usage`**. Six of the nine documents in `feature-alerts/src/documents/` name
   **`log-directory-usage`**, and `events-dark-rule.json` invents a fifth rule
   **`connector-task-failure`**. Neither id exists; `AlertRule.fromWire` would reject both. Regenerate
   them from the service's own goldens, which is what house rule 12 is for.
10. `events-unknown-vocabulary.json` carries `openCount: 1` over `rules: []`, in a set the last pass
    declared *"made internally consistent"*.
11. `feature-alerts/src/fixtures.tsx`'s `storeFor` is exported and called only from inside its own file.
12. `feature-alerts/src/data.ts:7` and `alertsRoute.test.tsx:46` cite `ALERTS_FEED_PATH`, which the
    shell un-exported (see W8-07 item 7). `docs/plan/ROADMAP.md:470`'s reference to `feature-alerts`'
    deleted `src/wire.ts` is **W8-09's** to remove, not yours.

**Do not** re-derive the alerts wire: `feature-alerts` decodes through `@kui/kernel`'s
`decodeAlertFeed` and that is the whole point of the wave-7 rewrite.

**Acceptance**
```
pnpm -C frontend test packages/feature-alerts packages/feature-topics packages/feature-consumers packages/feature-messages
pnpm -C frontend typecheck
pnpm -C frontend build-storybook && (serve :6017) && pnpm -C frontend a11y
pnpm -C frontend e2e e2e/alerts.spec.ts e2e/topics.spec.ts e2e/consumers.spec.ts e2e/messages.spec.ts
```
**Mutation line:** item 1 — point one of the four topic-page gates at a foreign subject. Name the case
that goes red. **And a green one:** report one mutation across these four packages that still stays
green.

---

## W8-09 — M10's comparison gate, and a price two waves have published wrong

**Owns**
```
scripts/feature-matrix-check.sh
docs/FEATURE_MATRIX.md
DECISIONS.md
TECH_DEBT.md
docs/api/**
README.md
frontend/README.md
frontend/packages/api/README.md
frontend/packages/api/src/schema.d.ts
docs/adr/ADR-048-solidjs-typescript-vite-frontend.md
docs/plan/ROADMAP.md                                         (three named stale lines only)
docs/overview/**                                             (new — the newcomer's overview)
```

**Contract.** M10's central hole has now failed three exit criteria. Wave 6's checker cost **one** edit
to defeat and its packet published five. Wave 7's rebuild cost **two** and its packet published four.
Both were measured by somebody else. **This packet's first deliverable is not a gate; it is a
measurement of its own gate, published before it claims anything** (house rule 17, which exists
because of this file).

**Do — the gate**
1. **Reproduce the two-line attack before you change anything**, so the baseline is yours:
   ```
   # (a) in check_document_region's `HEADER on N operations` loop, after the fact lookup:
       [[ $header == X-Csrf-Token ]] && fact=${header_ops[X-Kui-Principal]}
   # (b) in header_for_kind:
       csrf) printf 'X-Kui-Principal' ;;
   ```
   then publish `X-Csrf-Token on 56 operations` in ADR-048 and `frontend/packages/api/README.md`
   against documents carrying it on 24. The run prints `251 claims checked, all true` and exits 0.
   Verified at wave 7's integration.
2. **Close it by pinning the derivation, not by adding a fifth count.** Five guards in wave 7's own
   rebuild survive mutation with the run green, four of them asked for by name in its plan:
   - `header_fact_from_document`'s **independence** — replace its body with a read of the very table it
     exists to be independent of and the run stays at 251/all true, which makes both the header-table
     reconciliation and `audit_document_facts` tautologies in one function body;
   - `claim`'s refusal of a claim that records and consumes **without comparing** —
     `if (( $# == 0 || $# % 3 != 0 ))` → `if false` — green. `verify_comparator` drives `claim` into an
     agreeing and a disagreeing state, never a zero-group one;
   - the **empty-marked-block** refusal in *both* region loops — `if [[ -z ${text// /} ]]` → `if false`
     — green in both;
   - `reconcile_region`'s refusal of a marker with **no `claims:` list** — green;
   - `close_section`'s count assertion, weakened `==` → `<=` in **one character** — green.
   Every one of the five needs a **fixture**, not a real document: the script's own comment concedes
   *"No file has carried an empty marked region yet"*, which is precisely why.
3. **Publish the cheapest attack you can find against your own finished gate, with its cost.** If it
   is still two lines, say two. A number nobody has defeated is not a measurement.
4. **The knownUngated disclosure wave 7 wrote is itself wrong** and should not be carried forward:
   editing *both* `kind_for_header` and `header_for_kind` consistently does **not** stay green —
   `reconcile_registry` fires, seven disagreements. The real seam is `header_for_kind` **alone**, paired
   with a claim-site edit.

**Do — the documents**
5. **Regenerate the merged documents and everything downstream**: `./mill services.gateway.api.openApi`
   for `docs/api/openapi.json` and `.browser.json` after W8-01 and W8-03 land, then
   `pnpm --filter @kui/api run generate`, then the prose figures in ADR-048 and
   `frontend/packages/api/README.md`. This is the third wave with this hand-off and the second in which
   it is an acceptance line rather than an integrator's repair.
6. **Three false sentences this repository currently ships**, each in a file you own:
   - `TECH_DEBT.md`'s TD-023 publishes *"four edits inside the script"*. It is two.
   - `TECH_DEBT.md`'s TD-024 names **`ConnectorsResponse.connectors`**, a type that does not exist;
     the property wave 7 added is `ConnectorListResponse.connectors` at `schema.d.ts:1575`, and the
     measured delta is **one** property, not the two the row implies.
   - `docs/adr/ADR-053-alert-events.md:255` still publishes *"57 paths, 68 operations and 154 component
     schemas"* about documents that are now 61/72/156. **ADR-053 is W8-02's file** — send it, do not
     edit it.
   And `docs/plan/ROADMAP.md:470`'s *"`feature-alerts`' `src/wire.ts`"* names a file wave 7 deleted;
   that line, plus any other stale citation you find in that file, is the only reason you may touch it.
7. **`docs/FEATURE_MATRIX.md` accurate against the code, re-counted with the command it publishes.**
   Wave 7's pass moved **no row** and said so with a scope on it, naming `KC-001`, `KC-002`, `KC-005`
   and `KC-006` as the rows a driven pass would move. W8-04 and W8-10 are driving that stack this wave.
   Move the rows the browser evidence supports and no others, and take W8-07's list of what its work
   finished.
8. **The newcomer's overview** M10 asks for: `docs/overview/`, one document a person who has never seen
   this repository can read end to end, describing the eleven services, the eight feature packages, the
   two deployment shapes and the gates. It is not a README duplicate and it is not an ADR index.
9. **The scalafix gate covers no test source anywhere.** `fix` is not defined on Mill test modules —
   `./mill libs.kafka.test.fix` does not resolve — so `./mill __.fix --check`, which this project treats
   as a repository-wide gate, sees no test tree at all. Four packets landed most of their work in test
   trees last wave. Record it as a TD row with the seam it needs; do not paper over it.

**Do not** edit a figure inside a `<!-- checked: -->` region in a document you do not own, and do not
regenerate before W8-01 and W8-03 have landed — you will regenerate twice.

**Acceptance**
```
./scripts/feature-matrix-check.sh
./mill __.openApiCheck
./mill frontend.apiConstants --check
./mill tools.errorCodes.test
rm -rf frontend/.tsbuild && pnpm -C frontend typecheck
pnpm --filter @kui/api run generate   # run twice; the second run must produce a byte-identical file
```
plus, published in the report: the cheapest attack on the finished gate and its measured cost, and the
five fixtures from item 2 each verified red.
**Mutation line:** item 2's `close_section` one-character weakening, after your fixture lands. Name the
disagreement it produces. **And a green one:** attack your own gate and report what still works.

---

## W8-10 — The eleventh container, the stack the wave drives, and four sentences measurement contradicts

**Owns**
```
.github/workflows/ci.yml
deployment/**
apps/allinone/**
libs/config/**
```

**Contract.** **House rule 19 is yours, and it is the first hour of this wave.** Wave 7 left two
milestone clauses open that cost ten minutes at integration, because every packet needing a stack
assumed somebody else's images were current and three quoted three digests for one tag. Build both
images, recreate the quickstart, deploy one connector, publish the ids. Then close four rules and four
false sentences.

**Do — first, before anything else**
1. **Build and publish the stack.**
   ```
   ./mill deployment.docker.allinone.docker.build
   docker build -f deployment/frontend/Dockerfile -t kui-frontend:0.1.0-SNAPSHOT .
   deployment/quickstart/quickstart.sh down && deployment/quickstart/quickstart.sh
   ```
   Publish both image ids in your **first** report, not your last. Wave 7's integration measured
   `kui-allinone` `d39a08971ffd` and `kui-frontend` `827368273ecd`; yours will differ and that is the
   point.
2. **Deploy one connector on the quickstart's Connect worker**, and make it part of the quickstart
   rather than a thing you did by hand. The worker runs and has nothing on it, which is why
   `connect.spec.ts`'s two positive cases skip and why **M9 is open**. A `FileStream` source or
   anything the stock `apache/kafka` Connect image carries is enough; it needs to have tasks and a
   state. Publish the `curl` that shows KUI reading it, and note that the Connect REST port is not
   published to the host today — decide whether it should be.
3. **`./deployment/quickstart/quickstart.sh` has never been run by the packet that owns it**, three
   waves running, because another agent held ports 8080/8090. Run it. It is item 1.

**Do — the owned rules**
4. **A `kafka` metadata store is refused at start-up.**
   `libs/config/src/kui/config/KuiConfigSource.scala`, `rolesNameConfiguredClusters`:
   `val storeMayAddMore = draft.store.kafka.isDefined || draft.store.dir.isDefined` →
   `... = draft.store.dir.isDefined` leaves `libs.config.test` at 395/395 SUCCESS. Only the `dir` arm
   is covered. Under the mutation an ADR-036/ADR-042 deployment whose role names a cluster registered
   at run time is refused at boot — *"a worse answer than the one this rule exists to improve on"*, in
   the method's own words.
5. **Only the first offending role is ever reported.** Same method:
   `draft.rbac.roles.zipWithIndex.flatMap` → `... .take(1).flatMap` — 395/395 SUCCESS. An operator with
   two bad roles fixes one, restarts, and hits the second. **This is the property wave 7 verified by
   hand and then wrote into `kui-quickstart-auth.yaml`'s comment as a measured fact**, with no test
   holding it. Every case in the section uses a one-role fixture, so `$index` is never exercised past
   0 either. (Cutting both at once fails to compile under `-Werror`; cut them separately.)
6. **The provenance line an operator reads can be wrong.**
   `apps/allinone/src/kui/allinone/AllInOneWiring.scala`, `logAlertThresholds`:
   `val tuned = alerts != AlertsConfig.Default` → `alerts.thresholds != AlertThresholds.Default` leaves
   3821/3821 SUCCESS. An operator who tunes only `kui.alerts.retention` gets *"no kui.alerts section;
   the alert rules use the shipped default thresholds"* printed beside their own non-default retention.
   Wave 7 closed the context map to all eight fields and left the flag that interprets them open.
7. **`smoke.sh`'s three connect assertions pass with the Connect worker stopped.** Measured at wave 7's
   verification: `docker stop kui-compose-kafka-connect`, then the read answers **HTTP 200, outer
   status `ok`**, nested worker `unavailable`/`UPSTREAM_UNAVAILABLE`, and `connect capability:
   available`. So `await "the connect read on a cluster that names a worker" "ok"` cannot tell *a worker
   answered and is running nothing* from *a worker refuses to answer* — the second and third of the
   three states the comment six lines above it enumerates. Deleting the whole `kafka-connect:` service
   from `docker-compose.yml`, or pointing `kui-service.yaml`'s two `connect.url` entries at a
   nonexistent host, passes identically. The honest assertion is on the **per-worker** section, e.g.
   `[.connectors.data.workers[].connectors.status] | unique == ["ok"]`. Fix the assertion **and** the
   two comments that claim the stronger thing (`smoke.sh`'s own, and `docker-compose.yml`'s
   `kafka-connect` block, which says *"a stack with no worker makes the whole service report
   `not_configured`"*).

**Do — the corrections**
8. **`ci.yml:291-292` names a command whose output contradicts the number beside it.**
   `grep -oE 'image: kui-[a-z-]+:' deployment/compose/docker-compose.yml | sort -u` prints **ten**, not
   the nine written next to it; the real derivation at `:349-350` adds `grep -v '^frontend$'`. The
   packet's own stated remedy for ungated prose was *"every one of them now names the command a reader
   can count it with"*, and the most prominent one gives the wrong count. An eleventh service moves it
   again.
9. **`deployment/compose/README.md`** says the CI list *"is nine today … and `docker compose … config
   --format json` is what says so"*. That command prints the whole JSON topology, not nine.
10. **Widen `widenedExclusionProbes`.** Wave 7's new probe derives `.yml` twins of `shipped` rows only,
    so a pattern widened to an extension no `shipped` row uses — `.yaml` itself, or `.conf` — is not
    probed at all. The case is narrower than its own name says.
11. **The eleventh container**: `deployment.docker.ksql`, a compose entry with a healthcheck, an
    address in `kui.yaml` and `kui-service.yaml`, the `AllInOneWiring` entry, the startup-log string and
    the mounted-path set in `AllInOneWiringSuite`, and the `depends_on` if you can show it is a repair
    rather than headroom (house rule 9 — wave 6 ran that in both directions for `kui-metrics` and found
    headroom). **And a ksqlDB server in the quickstart if W8-05 raises it**, so its positive half is
    not all refusals.

**Acceptance**
```
./mill libs.config.test
./mill apps.allinone.test
./mill checkArchitecture
./mill apps.allinone.checkFormat + libs.config.checkFormat
./mill apps.allinone.fix --check                 # wave 7's integration found a real defect here
docker compose -f deployment/compose/docker-compose.yml config -q
./deployment/compose/smoke.sh                    # three consecutive runs from a torn-down stack
./deployment/quickstart/quickstart.sh            # item 1; the stack every other packet drives
```
**Mutation line:** item 5 — `.take(1)` on the roles fold. Name the case that goes red. **And a green
one:** stop the Connect worker and run `smoke.sh` **after** item 7 lands; it must fail.

---

## W8-A1 — Adversarial: the seven rules that need a production seam, and the clue that replaces the spent one

**Owns**
```
services/message/**
services/topic/**
services/identity/**
libs/kafka/**
libs/kafka-auth/**
libs/serde-confluent/**
libs/filter/**
```
Production **and** test, in all seven trees, because your subject is the seven rules that could not be
closed last wave *for want of a seam* — and cutting a seam is a production change.

**Contract.** Wave 7's two hunters scored 68 mutations and found 40 ungated at 69% and 50%. **Seven
they could not close**, each because the rule is unobservable from outside the code that holds it, and
each is named below with the edit its own packet said it needed. Close them, then hunt with the clue
that replaces the one this project has now exhausted.

**Do**
1. **The seven, with their seams.**
   - `libs/kafka/src/kui/kafka/admin/OffsetLookup.scala` — a purge plan lists only partitions that have
     a leader; the equivalent rule in `services/message`'s `KafkaRecordDeleter` (`.filter(info =>
     Option(info.leader).exists(_.id >= 0))`) has **no suite anywhere** because the module has no Kafka
     `Admin` stub. Build one; `services/cluster/infrastructure/test`'s `RecordingAdminPool` is the
     pattern.
   - `services/topic/infrastructure/.../KafkaTopicWriter.scala` — a removed topic configuration entry
     must be written as `AlterConfigOp.OpType.DELETE`, not `SET`; `SET` pins the key to today's default
     instead of following it. Same missing stub.
   - `services/identity/infrastructure/.../Pbkdf2PasswordHasher.scala` — `constantTimeEquals` can be
     given an early exit with all 79 cases green. **A timing property is not honestly assertable in a
     unit case**, and wave 7 said so rather than writing one that passes for the wrong reason. Decide
     what *is* assertable — a bytecode-level property, a loop-count seam, or an explicit `@nowarn`ed
     comment saying the class of gate this needs — and record the decision.
   - `libs/kafka-auth/src/.../KeyStoreMaterializer.scala:180-204` — every materialized keystore is
     zero-filled before its directory is deleted, and the directory goes in the same release, so no
     case can see it. The named edit: split `cleanUp` into a `private[auth] def zeroFill(directory:
     Path)` a case can drive on a directory it made itself.
   - `libs/serde-confluent/src/.../CachingSchemaRegistry.scala` — `config.schemaCacheSize` reaches
     nothing observable, and Caffeine evicts *approximately*, so a size assertion would be unreliable
     rather than merely awkward. The named edit: report the bound alongside the cache's stats.
   - the same file's two cache **metric names** can be swapped with everything green, so a dashboard's
     schema hit rate becomes the subjects'.
   - `libs/filter/src/kui/filter/CelFilterEngine.scala:148` — the startup warm-up can be made a no-op;
     without it the first records of the first filtered browse after a restart exceed the
     ten-millisecond per-record deadline. Its consequence is latency, so it needs a seam that reports
     whether the warm-up ran, not a timing assertion.
2. **`build.mill` needs `cats-effect-testkit` on `libs.serdeConfluent.test`** for a deterministic TTL
   case; `libs.kafka.test` and `libs.cache.test` already have it. W8-01 lands it — ask in your first
   report, not your last.
3. **Hunt with the new clue, and score at least thirty.** *"Declares a test module in `build.mill` and
   ships no test source"* is **spent** — the list went six → three → **zero** during wave 7. The
   replacement is wave 7's sharpest finding and it is cheaper to grep for: **every ungated rule found
   in a file that already had a suite was ungated because the fixture could not express the failing
   input, or because no assertion ever read that field.** `MessageMappingSuite`'s record helper
   hard-codes `headers = Nil, keySize = 0, headersSize = 0`; `KafkaRecordSourceSuite` builds its own
   `BrowseTuning(…, 0)` in every case so the shipped default is read by nothing;
   `FakeAdmin.deleteGroups` could only answer a complete success; `UserDirectoriesSuite`'s
   `StubStore.put` raised `"not reached"`. Grep for hard-coded zeros and `Nil`s in test helpers and for
   fields no assertion names.
4. **Land a case for every hole you find**, and verify it red by re-applying the mutation. Report the
   ones you cannot close with the seam each needs, as wave 7 did — that list is what made this packet
   possible.

**Do not** change behaviour while cutting a seam. A `private[…]` accessor with the reason in its
scaladoc is the shape; `SchemaWiring.upstreamConfig` and wave 7's three `private[app]` seams are the
precedent.

**Acceptance**
```
./mill -k services.message.__.test + services.topic.__.test + services.identity.__.test
./mill -k libs.kafka.test + libs.kafkaAuth.test + libs.serdeConfluent.test + libs.filter.test
./mill checkArchitecture
./mill __.checkFormat
./scripts/run-tests.sh                # the empty "no test sources" list stays empty
```
Report: mutations scored, ungated found, closed, and the rate, **with its denominator and its hunting
method stated** (house rule 10). Note that `-k` is required in a `+` chain or Mill aborts the rest on
the first failure while still printing results for what ran.

---

## W8-A2 — Adversarial: the eight `libs` and the three services nobody has swept twice

**Owns**
```
libs/http/**
libs/observability/**
libs/kernel/**
libs/security-core/**
libs/serde/**
libs/cache/**
libs/testkit/**
libs/contracts-core/**          EXCEPT src/kui/contracts/sse/SseEvents.scala and its suite (W8-01's)
services/cluster/**
services/consumer/**
services/schema/**
```

**Contract.** Wave 7's A2 measured the nine-module `libs` block at **36%** — the best-defended code in
this repository — and the three Kafka-facing modules at 56%. Your block is the 36% half plus three
services whose test trees have been swept once each. The yield will be lower than A1's and that is the
point: **a low yield honestly measured is the evidence that lets a part of this project be declared
finished**, which wave 7 produced for the first time and only for two frontend packages.

**Do**
1. **Settle the masking orphan.** `libs/security-core/src/kui/security/masking/MaskingEngine.scala`
   and `MaskingRule.scala` have **no production caller anywhere** — `grep` for
   `MaskingRule`/`MaskingKind` across `libs/` and `services/` returns those two files and their suite.
   The whole engine, its keep-ends clamps, its surrogate-pair handling and its header masking are
   reachable from nothing, and no configuration configures masking at all. Wave 7 gated its clamp so
   the decision is not forced by a regression. **Take the decision:** wire it to the message service's
   value rendering, or delete it, and say which and why. That is a production change and this block is
   yours whole.
2. **Score at least thirty mutations**, weighted to `services/cluster` and `services/consumer`, whose
   production trees have never been swept — only their test trees have. Use A1's fixture-shape clue
   (see W8-A1 item 3), not the spent one.
3. **`ProfileChangeListenerSuite`'s flake is repaired and the repair is unverified by anyone but its
   author.** Wave 7's A1 rewrote `subscribersReceiveTheChangeAndNeverTheProfile` after establishing in
   both directions that the 50ms sleep was a budget rather than an ordering, and A2 saw the original
   fail once in three full runs at the wave base. Re-run the repaired case **under load** — sixteen
   busy loops on sixteen cores was A1's method — and say whether it holds. House rule 9 cuts both ways:
   a fix is not established until somebody who did not write it has run it in both directions.
4. **Land a case for every hole**, verified red. Report what you could not close and the seam it needs.

**Do not** touch `libs/contracts-core/src/kui/contracts/sse/SseEvents.scala` or its suite — they are
W8-01's named exception for the ksql event name, and this is the only place your block and a builder's
could have collided.

**Acceptance**
```
./mill -k libs.http.test + libs.observability.test + libs.kernel.jvm.test + libs.securityCore.jvm.test + libs.serde.test + libs.cache.test + libs.contractsCore.jvm.test + libs.testkit.jvm.test
./mill -k services.cluster.__.test + services.consumer.__.test + services.schema.__.test
./mill checkArchitecture
./mill __.checkFormat
```
Report: the rate with its denominator, and — if it is materially below wave 7's 36% — **say so as a
sweep verdict**, naming which modules you would declare finished being hunted and on what evidence.

---

## W8-A3 — Adversarial: the closer, this time with its input

**Owns**
```
docs/plan/verification/**
```
and, **after the ten building packets freeze**, their unit and component test trees — the same trees
their own packets owned during the wave, and not before. You do **not** own `frontend/e2e/**`; those
specs are allocated per file and need a running stack.

**Contract.** **Wave 7's closer never received its input and this packet exists to run the experiment
that was not run.** Its own report says it plainly: *"the ten verification reports are not in the
repository and were not handed to me… with no reports on disk I hunted the same code from scratch
instead."* It hunted blind, found eleven ungated rules in the wave's own new code, closed all eleven —
and **not one of them was among the forty-four the verifiers had already filed.** Zero overlap, in the
same code, by two methods. So the seam is denser than anyone has measured and what was missing was a
wire from a finding to a case. House rule 18 makes that wire a file.

**Do**
1. **Read `docs/plan/verification/W8-*.md`, which is your input and your `Owns`.** Every verification
   pass writes one, per house rule 18, with one row per finding: file, exact mutation, suite command,
   the case that would close it. If a report is missing when its packet freezes, say so by name in your
   own report — that is a mechanism failure worth more than the cases you would have written.
2. **Land a case per filed finding, and verify each red** by re-applying the mutation and watching the
   named case fail, then reverting from bytes you saved yourself. Where a finding turns out **not** to
   be a defect, argue it down with the reasoning shown — wave 7's adversaries did that twice, and one
   of those two corrected a false gate claim a previous wave's adversary had written into a case
   comment. An argued-down finding is a result, not a miss.
3. **Where a finding needs a production seam you do not own**, file it in `needsOutsideOwnership`
   naming the exact edit, the way wave 7's hunters did — that list is what made W8-A1 possible.
4. **Report the overlap.** For every finding you close, say whether it was in a verification report or
   found by your own mutation. That number is the whole experiment.

**Do not** hunt in place of closing. If the filed list runs out, hunt the wave's own new code and say
which findings were yours; but the filed list comes first, because it is what has never been tried.

**Acceptance**
```
./scripts/run-tests.sh
pnpm -C frontend test
./mill checkArchitecture
./mill __.checkFormat
```
plus, in the report: **filed findings received, closed, argued down, left open**, and each closure with
the case it reddens.

**The pre-commitment, restated so that wave 9 can settle it.** If a closer handed the wave's own filed
findings closes **thirty or more** of them, the closer becomes permanent and replaces one hunting
adversary at 4:1. If it cannot — with its input in hand this time — the closer is retired for good and
every verifier finding becomes an owned rule in the next plan, which is the mechanism with the 40 of 40
record.

---

## Where the packets meet

Every pair below shares a boundary. The contract is stated on both sides so that neither has to read
the other's diff.

| Pair | The contract both sides code against |
| --- | --- |
| W8-10 → everybody | **The stack, first hour.** `kui-allinone` and `kui-frontend` built from the tree, the quickstart recreated on them, **one connector deployed on its Connect worker**, both image ids published in W8-10's *first* report. House rule 19. Every packet whose acceptance names a browser drives that stack and quotes those ids; nobody rebuilds it under another packet's feet without saying so. Wave 7 left two milestone clauses open for want of this. |
| W8-01 → everybody | **The edges, first.** `build.mill` is W8-01's alone, and its first commit lands `ksql.contract.jvm` on `services.gateway.api`'s `moduleDeps` and `cats-effect-testkit` on `libs.serdeConfluent.test` — before its own service work. House rule 15; wave 7 obeyed it and lost nothing. |
| W8-01 → W8-03 | **`ArchitectureDocumentSuite` fires on a directory, not an import.** The moment `services/ksql/domain/` exists, `./mill services.gateway.__.test` is red until `ARCHITECTURE.md` §3 carries a `ksql` row and `build.mill` a `ksql.contract.jvm` entry. W8-01 announces the directory; W8-03 lands the row in the same breath. This coupling was created in wave 7 and disclosed nowhere. |
| W8-01 ↔ W8-03 | The ksqlDB endpoints: `Section`-wrapped reads and one write under `/api/v1/clusters/{clusterId}/ksql/…`, proxied the way connect and metrics are, plus **a hand-written push-query relay** which is W8-03's alone. W8-03 adds no gateway path of its own — `OpenApiMergeSuite` pins a hard-coded list. The write moves `MergedDocumentShapeSuite`'s `writes.size`; the eleventh service moves `ServiceContractsSuite`'s id `Set`. |
| W8-01 ↔ W8-05 | **The ksql wire, house rule 12.** W8-01 renders goldens from its own encoder into `services/ksql/contract/test/resources/golden/*.json`; W8-05 reads that exact path off disk and decodes each, failing when a file is missing, and counts rows out of the raw JSON so a decoder that drops every row cannot pass. **The path is the contract.** And ADR-055 settles the wire for the result region while ADR-056 settles its rendering — one decision, two documents, neither guessing at the other. |
| W8-02 ↔ W8-04 | **The Connect wire is already golden and the e2e spec ignores it.** `services/connect/contract/test/resources/golden/*.json` and `feature-connect/src/documents/*.json` are byte-identical and read off disk by `wire.golden.test.ts`. `e2e/connect.spec.ts` hand-writes a third, wrong copy. W8-04 rewrites the spec against the golden; W8-02 does not move the golden's shape this wave. |
| W8-02 ↔ W8-03 | **ADR-053:255 publishes 57/68/154** about documents that are now 61/72/156. ADR-053 is W8-02's; the figure comes from W8-09's regeneration. W8-09 sends it, W8-02 writes it. |
| W8-04, W8-05, W8-07, W8-08 ↔ W8-10 | Every browser acceptance in this wave runs against W8-10's stack. A packet that cannot run its spec because the stack is not up says so and names the hour; it does not build its own images and quote a different digest. |
| W8-05 ↔ W8-06 | Two lines in the kernel, shipped by W8-06 whether or not W8-05 asks: `FeatureId` gains `"ksql"`, and `styles/index.css` gains the import naming `feature-ksql`'s one stylesheet at the number W8-05 states. `build-tests`' `CssReferencesSuite` requires it and `build-tests/**` is owned by nobody. |
| W8-05 ↔ W8-07 | The ksqlDB screens are a **route and a nav destination**, not a dashboard tab. W8-07 adds the route in `routing/**`, the third `ECOSYSTEM` row in `nav/**` and the badge row in `App.tsx`; W8-05 ships what is behind it plus the registration seam. A feature reaches its own pages through `kui.paths.*` and edits no routing file. |
| W8-06 ↔ W8-07 | **One open count, again.** W8-06 must not delete `Alerts.unreadCount` — W8-07's drawer badge calls it, which the kernel's own header currently denies. `lastReadAt` and `connection` are still callerless; their fate is a decision recorded in W8-06's report, not an assumption. |
| W8-07 ↔ W8-08 | `ALERTS_FEED_PATH` was un-exported by the shell in wave 7 and `feature-alerts/src/data.ts:7` still cites it by name in prose. W8-07 owns the shell half, W8-08 the citation. One sentence each. |
| W8-07 ↔ W8-09 | **The twenty-three screens.** W8-07 produces the screen → spec → case mapping in its verification file; W8-09 turns it into `docs/FEATURE_MATRIX.md` rows and into M10's coverage claim. Nobody has ever published this mapping and M10 cannot close without it. |
| W8-09 ↔ W8-01, W8-03 | The merged documents and `schema.d.ts` move for somebody else's endpoints and `./scripts/feature-matrix-check.sh` goes red with them. That red is **W8-09's acceptance line**, for the second wave running. |
| W8-09 ↔ W8-02 | `ADR-052` §11's *"every file in `deployment/` that mentions the key"* is W8-02's sentence to correct and W8-10's file that disproves it. |
| W8-10 ↔ W8-03 | `smoke.sh` scrapes `ServiceContracts.byService` with `sed`; a contracted service that is not a container fails the preflight before a container starts. W8-03 owns the Scala; W8-10 owns the script and the eleventh container. |
| W8-10 ↔ W8-05 | If the quickstart gets a ksqlDB server, W8-10 adds it and W8-05's positive half becomes provable. If it does not, W8-05's acceptance is all refusals and house rule 6 fails it. **Raise it in the first hour or not at all.** |
| W8-A1 ↔ W8-A2 | `libs/contracts-core/**` is W8-A2's **except** `src/kui/contracts/sse/SseEvents.scala` and its suite, which are W8-01's. The two adversaries share no path: A1 holds `kafka`, `kafka-auth`, `serde-confluent`, `filter` and three services; A2 holds the other eight libs and three other services. Written out in both blocks. |
| W8-A3 ↔ every building packet | A3 owns their unit and component test trees **after they freeze**, and not before — and it owns `docs/plan/verification/**` throughout. A building packet that edits its test tree after reporting has taken a file back from a packet that is mid-flight. **Every verification pass writes its file before the packet it verifies is declared frozen**; that is house rule 18 and it is the whole of this wave's mechanism experiment. |
| every packet ↔ the guard files | House rules 1, 2 and 3 keep `build-tests/**`, `10-tokens.css` and `constants.generated.ts` out of reach. If your change needs one of them, your change is shaped wrongly, and that has been true for seven waves. |

## The partition, checked

**Backend.** Eleven services, each owned by exactly one packet: `ksql` (new) → W8-01; `connect`,
`alerts`, `metrics` → W8-02; `gateway` → W8-03; `message`, `topic`, `identity` → **W8-A1 whole**,
production and test, because the seams the seven open rules need are production edits; `cluster`,
`consumer`, `schema` → **W8-A2 whole**, for the same reason. **No service is owned as a partial tree
this wave**, which is a simplification on wave 7 and removes the one exclusion clause it needed.

Thirteen `libs`, each owned by exactly one packet: `config` → W8-10; `kafka`, `kafka-auth`,
`serde-confluent`, `filter` → W8-A1; `http`, `observability`, `kernel`, `security-core`, `serde`,
`cache`, `testkit` → W8-A2; `contracts-core` → W8-A2 **except two named files**,
`src/kui/contracts/sse/SseEvents.scala` and `test/src/kui/contracts/sse/SseEventsSuite.scala`, which
are W8-01's for the ksql event name. That exception is written out in both blocks and it is the only
place the partition is not a whole directory.

`build.mill` is **W8-01's alone** and is the file three packets want; W8-10 needs the eleventh image
target and W8-A1 needs a test dependency, and both ask through `needsOutsideOwnership` — but **W8-01
lands the gateway's contract edge and the testkit dependency unasked, first**.

**Frontend.** Inside `frontend/packages/shell/`, no packet owns `**`. `src/features/` and
`package.json` are W8-05's; `src/App.tsx` with its two test files, `src/chrome/`, `src/nav/`,
`src/data/`, `src/routing/`, `src/overview/`, `src/pages/`, `src/index.ts` and `styles/**` are
W8-07's — named one by one. `src/messages.ts`, `src/bootstrap.ts`, `src/health.ts` and `src/index.tsx`
are **owned by nobody and need no edit**: they are the boot path and nothing here changes it.

`frontend/packages/kernel/**` is W8-06's for the fourth wave running, and the reason is unchanged: it
has had an owner three times and shipped ungated rules each time, including two last wave in the file
that owner had just rewritten. `frontend/packages/feature-ksql/**` is new and W8-05's; so are
`feature-clusters/**` and `feature-schemas/**`, whose only work is one production repair
(`feature-clusters/src/data.ts:170` turns a wire `null` into `new Date(null)` — the Unix epoch — and
renders *"Read 20702d ago"* in the one line whose comment forbids a fabricated date; it is latent
today because `ClusterSummaryDto.scrapedAt` is non-optional, and the fix is `== null`). `feature-alerts`,
`feature-topics`, `feature-consumers` and `feature-messages` → W8-08; `feature-connect` → W8-04.
`frontend/packages/api/` is **not** owned as a tree: `README.md` and `src/schema.d.ts` are W8-09's;
`constants.generated.ts`, `src/index.ts`, `src/probes.ts` and `src/types.test.ts` are unowned and need
no edit.

`frontend/e2e/` is allocated **per file**: `ksql.spec.ts` → W8-05 and new; `connect.spec.ts` → W8-04;
`shell.spec.ts`/`dashboard.spec.ts`/`search.spec.ts`/`traffic.spec.ts`/`brokers.spec.ts` → W8-07;
`alerts.spec.ts`/`topics.spec.ts`/`consumers.spec.ts`/`messages.spec.ts` → W8-08;
`features.spec.ts` → W8-05, beside `feature-schemas`. `fixtures.ts`, `globalSetup.ts`, `tsconfig.json`
and `playwright.config.ts` are unowned and need no edit.

`frontend/tsconfig.json` is **W8-05's**, as the eighth package's reference is its job.
`frontend/vitest.config.ts` is **W8-06's**, which is new this wave and is why the axe flake finally has
an owner. `frontend/vite.config.ts` and `frontend/package.json` are unowned;
`frontend/scripts/bundle-shape.mjs` and `boundaries.mjs` read their rosters from the filesystem, so an
eighth feature package is picked up with no edit anywhere. `frontend/scripts/a11y-stories.mjs` is
W8-06's.

**Build and deployment.** `.github/workflows/ci.yml`, `deployment/**` and `apps/allinone/**` are
W8-10's alone. `scripts/run-tests.sh` is unowned and needs no edit — it derives its module list from
`./mill resolve __.test`. `scripts/feature-matrix-check.sh` is W8-09's; **`scripts/` holds those two
files and no others**, which corrects a sentence wave 7's plan got wrong. `build-tests/**` is unowned;
`tools/**` is W8-09's, because `BrowserConstantsMain` and `ErrorCodeDocMain` are what its regeneration
drives.

**Documents.** `docs/api/**`, `docs/overview/**` (new), `README.md`, `frontend/README.md`,
`frontend/packages/api/README.md`, `DECISIONS.md`, `TECH_DEBT.md`, `DEPENDENCY_MATRIX.md`,
`docs/FEATURE_MATRIX.md` and `docs/adr/ADR-048` are W8-09's. `ARCHITECTURE.md` is W8-03's. ADR-055 is
W8-01's new file and ADR-056 is W8-05's; ADR-052, ADR-053 and ADR-054 are W8-02's, because the metrics,
alerts and connect decisions they record are that packet's to correct. Every other ADR,
`docs/ROADMAP.md`, `docs/ROADMAP-SOLID.md`, `docs/testing.md`, `docs/api/error-codes.md` (generated),
`docs/operations/**` and `docs/domain/**` are unowned and need no edit.
`research/design/SCREENS-V4.md` is **W8-07's, for the ksql-row paragraph and nothing else**.
`docs/plan/ROADMAP.md` is **W8-09's for three named stale lines only** — line 470's reference to a
deleted `wire.ts` among them — and the wave's integrator writes its retrospective afterwards.
`docs/plan/verification/**` is **new, and W8-A3's**; each verification pass writes exactly one file
there, named for the packet it verifies, before that packet is declared frozen.
`docs/plan/WAVE-08.md` is this file; the wave's closing act deletes it.

**Five nesting checks, done rather than assumed.** `services/` is not owned as a tree and neither is
`libs/` — each of the eleven and each of the thirteen goes to exactly one packet, with one two-file
exception stated in both blocks. `docs/` is not owned as a tree. `frontend/packages/shell/` is not
owned as a tree: two packets divide `src/` by named subdirectory with four files left over that are
named above rather than left to inference. `frontend/packages/api/` is not owned as a tree: two files
of it are W8-09's and the rest is unowned. And W8-A3's ownership of the building packets' test trees is
a **sequenced** claim, not a concurrent one: it begins when they freeze, and it is the only ownership
in this plan that moves during the wave.

## What wave 9 will be, and whether there is one

**There is, and pretending otherwise is how M10 has stayed open.** This wave asks for the eleventh
service, the browser evidence two milestones are blocked on, forty-four carried rules, seven that need
production seams, M10's whole closing list *and* a comparison gate that has defeated three criteria.
Wave 7 was ten building packets and delivered one service plus a feature package while opening
forty-four rules; the honest expectation is that wave 8 closes **M9** and **most** of M10, and that
wave 9 is a short closing wave over what is left.

What wave 9 should expect to be holding:

* **The twenty-three-screen coverage claim**, whatever W8-07's mapping turns out to say. Eleven spec
  files against twenty-three screens is the largest unmeasured thing in M10 and nobody has counted it.
* **The comparison gate**, if W8-09 cannot get it below "a packet that attacks its own gate before it
  reports". Three criteria and three false published prices; the fourth attempt should be assumed to
  need a fifth.
* **Whatever wave 8's own verification files record.** At 4.4 ungated rules per building packet, ten
  building packets will file about forty-four again, and W8-A3 will close some fraction of them under
  a mechanism that has never once been run as designed.
* **The masking engine**, if W8-A2's decision is *wire it* rather than *delete it*.
* **`docs/plan/` reduced to `README.md` and `ROADMAP.md`**, which is M10's own last bullet and cannot
  be done by the wave that still needs a plan file.

**And the ratio.** Wave 7 ended **+10** rules better gated — ten owned rules closed, forty-four closed
by adversaries, forty-four opened — against +39 and +44 in the two waves before it. The fall is
explained: both hunters report their richest clue spent, and the closer never received its input.
Wave 8 keeps 3:1 and changes exactly one thing, which is that the closer is handed a list rather than a
licence. **The pre-commitment is in W8-A3's block and it is binary**: thirty or more filed findings
closed and the closer is permanent at 4:1; fewer, and the closer is retired and every verifier finding
becomes an owned rule in wave 9's plan — the one mechanism in this project that has never missed, at
**forty of forty**.
