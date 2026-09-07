# Wave 6 — `services/alerts`, the wire that two packets guessed differently, and the first wave that ends ahead

**Milestones covered:** **M8** in [ROADMAP.md](ROADMAP.md) — `services/alerts`, the ninth service —
and **the rest of M7**, which wave 5 did not close. M7 owes three things and none of them is an
endpoint: two of the four new wires do not match the browser that reads them, nobody has yet seen any
of the four answer through a gateway built from this tree, and the browser cases written to catch
exactly that drift read the same wrong field names and pass while the cards draw nothing.

**Why this shape.** Wave 5 ran three adversarial packets against nine building ones, and the
arithmetic is not close. The three adversaries mutated 162 rules, scored 156, found **72 ungated**,
and **closed 65 of them with a case that genuinely fails** — ten of those closures were re-applied at
integration and every one went red. The nine builders closed their nine owned rules, all nine
verified, and shipped **33 new ungated rules**, of which their verifiers found every one and closed
**none**, because a verifier files a paragraph and owns no test tree. Wave 4 ended the wave 25 rules
worse gated than it began. Wave 5 ended it 44 better. The mechanism is not in question.

So one thing changes and one thing does not. **The ratio does not fall** — wave 5's own
pre-commitment said it falls only if the adversaries report a materially *lower* ungated rate than
wave 4's 29% census, and they reported 46%, on samples chosen by reading comments rather than at
random. Ten building packets and three adversarial ones, the same 3:1.

**What changes is what a verifier may do.** Thirty-three holes were found and thirty-three were left
open, at a cost of nine verification passes, because the verifier's deliverable was a report. From
this wave **every verification pass owns the test tree of the packet it verifies** and lands a case
for every hole it finds, exactly as the adversarial packets do. That converts nine reporters into
nine closers at no extra packet cost, and it is worth more than a fourth adversary: an adversary
closes about 22 holes for a whole packet's capacity, while a verifier that lands its own findings
closes 3.7 for none.

**Parallelism.** `Owns` is disjoint across every packet: no two tasks may edit the same file, and no
packet owns a directory containing another packet's file. Five dependencies are declared, all on a
*stated shape* and none on a diff: W6-03 on W6-01's feed endpoints, W6-04 on the same, W6-04 on
W6-05's widened `FeatureId`, W6-10 on W6-01's Mill module and image target, and W6-09 on W6-01 and
W6-03 having landed before the merged documents are regenerated.

**The adversarial packets are scoped to what nobody else owns, and this wave that constraint cost
real time.** Wave 5 lost twenty-two measurements to a false red when W5-A1's mutation in `libs/http`
broke W5-A3's compile in a shared working tree; a sibling's `git add -A` staged a live mutation so
that `git checkout --` restored it rather than reverting it; and one packet's `git stash` swept
forty-six files belonging to six other packets and destroyed a seventh's test file, which had to be
recovered from a rescue copy. **Adversarial packets run in a `git worktree` copy of the tree this
wave, not in the shared checkout.** Revert by restoring the bytes you saved before the mutation;
never with `git checkout --`, `git restore --source=HEAD` or `git stash`, all three of which
destroyed somebody's uncommitted work in wave 5.

**And `stash@{0}` is still in this repository.** It holds 46 files and 3,256 insertions of six
packets' mid-flight work from wave 5, created by accident and unpoppable without conflicts; the work
it holds was carried forward by its owners and the entry is now stale. Nobody should pop it. Whoever
integrates wave 6 drops it, having first confirmed with `git stash show --stat` that nothing in it
post-dates `8fc0c77`.

**The designed intermediate state.** W6-01 adds the alerts service and regenerates its own
`services/alerts/api/openapi.json`; W6-03 adds its gateway routing. `./mill __.openApiCheck` will be
**red on `services.gateway.api` and green on the other seven** until W6-09 regenerates
`docs/api/openapi.json` and `docs/api/openapi.browser.json`, because `build.mill:1513` aims the
gateway module's check at those two files. `./scripts/feature-matrix-check.sh` is red for the same
reason and W6-09 turns it green. Do not repair either by editing a file you do not own.

**And one intermediate state that is new, because a ninth service has not been added since wave 1.**
Three hard-coded literals broke when the *eighth* service was registered, and the M7 record names
them: a service-id `Set` in the gateway's `ServiceContractsSuite`, the startup-log string in
`AllInOneWiringSuite`, and the mounted-path set beside it. All three are in this wave's guard table
with an owner. Expect a fourth: `scripts/run-tests.sh` derives its module list from
`./mill resolve __.test` and will print a module count above 63 and a *new* line naming any alerts
test module that ships with no test sources — six modules are already on that list, and the whole
point of W6-A2 is that a declared test module with no sources is how `services/schema/app` kept four
ungated constants for two milestones.

**House rules that apply to every packet** — read them before starting.

Backend: Scala 3 + Mill, ADR-041 layering (machine-enforced by `./mill checkArchitecture`), Tapir
endpoints, ADR-034 error envelope, ADR-039 capability fold, ADR-035 streaming, ADR-045
plan→token→confirm for destructive mutations. Frontend: TypeScript + SolidJS 2 + Vite under
`frontend/` (pnpm, not Mill), Storybook-first — a story per state — and browser types generated from
`docs/api/openapi.browser.json`. Comments explain **why**, not what, at roughly the 25% density of
the surrounding code. There is no ESLint or Prettier; the codebase is hand-written at 100 columns
(Scala at 110, per `.scalafmt.conf`). **Do not reformat a file you are not otherwise changing.**

The ten rules from wave 5 stand unchanged, and four are new. Every one is a wave-5 finding.

1. **No new stylesheet files, with exactly one exception.** `build-tests`'s `CssReferencesSuite`
   requires every stylesheet on disk to be named exactly once in
   `frontend/packages/kernel/styles/index.css`. The sixth feature package needs one stylesheet, in
   its own `styles/` directory, in the shape `feature-consumers/styles/71-consumer-screens.css`
   already has — **and the index that must name it is in W6-05's tree, not W6-04's.** So W6-05 adds
   the import line, at the number W6-04 states, in the same way it widens `FeatureId`: the component
   is the kernel's and the requirement is the design's. One file, one line, one owner. Nobody else
   adds a stylesheet.
2. **No new custom properties in `frontend/packages/kernel/styles/10-tokens.css`.** A Scala mirror
   lives in `build-tests/src/kui/build/design/Tokens.scala`, which no packet owns.
3. **No new `ErrorCode`.** `./mill frontend.apiConstants --check` compares
   `frontend/packages/api/src/constants.generated.ts` byte for byte. The thirty-one that exist cover
   an alerts feed: a store that will not answer is `KUI-UPSTREAM-UNAVAILABLE`, an acknowledgement of
   an event that is already closed is `KUI-CONFLICT`.
4. **A gate you cannot make fail is not a gate.** Every packet's acceptance list has a **mutation
   line**: name one change to the shipped code that reverses the packet's headline rule, apply it,
   run the acceptance suite, record which case went red, revert it.
5. **Report a mutation that stayed green.** Alongside the red one, apply at least one mutation to a
   rule you did *not* write the test for and record what happened.
6. **No honest-refusal-only acceptance.** No packet may satisfy its acceptance list entirely with
   assertions that something is absent, refused or not configured. Every packet that ships a
   capability asserts the capability *working* against something real, and the refusal beside it.
7. **A card may not be drawn from a figure the design did not name.** ADR-052 settled this for three
   of M7's cards. It applies unchanged to an alert: a severity KUI infers from a threshold it chose
   is an alert; a severity KUI infers from a number it did not measure is a fabrication.
8. **`./mill a.test b.test` runs nothing.** Mill parses the second task path as a vararg to the
   first, MUnit takes it as a test-name filter, it matches nothing, and every suite reports
   `0 failed, 1 ignored, 0 total` while Mill exits `SUCCESS`. The separator is `+`.
9. **A root cause is not established until it has been run in both directions.** Say what you
   observed with the patch and without it, and if the two do not explain each other, say so instead
   of closing the item. Wave 5's compose failure is the standing example: the fix is in, the failure
   no longer reproduces, and **nobody has explained it** — see W6-10 item 1.
10. **State the scope of a measurement, and expect the next reader to re-run it.**
11. **New: a comment that names a figure must name a figure something in the tree can check.** Wave
    5's metrics packet was assigned to delete an invented `348601.0` from a comment, deleted it, and
    shipped `680 Kafka families` three times and `961` once, against an ADR in the same commit that
    says 670 and 481. A number in prose that no test, fixture or document reproduces is the same
    defect wearing a different value. If you write a figure, write beside it the file the reader can
    count it in.
12. **New: two sides of one wire are one packet.** W5-01 shipped
    `producers.data.{measuredBy, topics[{topic, bytesInPerSecond}]}` and W5-04 shipped a reader for
    `producers.data.entries[{clientId, bytesPerSecond}]`, both green, both unit-tested against their
    own shape, on two sides of a contract stated in prose in the wave plan. Two of five cards draw
    nothing against a real broker and the browser suite says nothing, because it reads the same wrong
    names. Where a wire is new, one packet owns the DTO **and** the code that decodes it, and the
    case that binds them decodes the **encoder's own output** rather than a hand-written literal.
13. **New: a hole a verifier finds is a case the verifier lands.** Every verification pass owns the
    test tree of the packet it verifies. A finding reported without a case is reported as a finding
    that could not be closed, with the reason.
14. **New: adversarial packets run in a `git worktree`.** See above. Three separate incidents in one
    wave, one of which nearly committed a live mutation.

`pnpm` is not on the default PATH in a non-login shell; it lives at `~/.local/share/pnpm/bin/pnpm`,
with node at `~/.nvm/versions/node/v26.8.1/bin`.

**Running a browser suite.** `pnpm -C frontend e2e` drives a stack it does not start, and wave 5
proved what happens when nobody rebuilds it: `traffic.spec.ts` was reported as seven failures against
an image built before the code existed, and the four metrics endpoints answer `KUI-ROUTE-NOT-FOUND`
on the quickstart running *right now* for the same reason — measured at integration, not inferred.
So: `./mill deployment.docker.allinone.docker.build` and
`docker build --no-cache -f deployment/frontend/Dockerfile -t kui-frontend:0.1.0-SNAPSHOT .`
**before** `quickstart.sh`, every time the tree has changed, and say in the report which image id you
drove. The metrics and alerts buffers are in memory, so a restarted KUI needs a minute before a card
has anything to draw.

**Running the a11y sweep** is three commands, not one — build Storybook, serve `storybook-static` on
`:6017`, then sweep. The theme race is real: wave 5 saw two aborts in one packet's three attempts and
none at integration over 739 stories. A single abort with no axe violation printed is not an a11y
failure — re-run the named story alone before you report one, and W6-05 owns the harness.

---

## The guard files

Everything below asserts a shape, a count or a roster that this wave's work can invalidate. None is
owned by the packet most likely to break it — that is the point of listing them. If your change makes
one red, it is your change that is unfinished, and the repair goes in the packet that owns the guard,
named through `needsOutsideOwnership` if that is not you.

| Guard | What it pins | Who breaks it |
| --- | --- | --- |
| `services/gateway/api/test/.../routing/ServiceContractsSuite.scala` | A **hard-coded `Set` of service ids**. It was one of the three literals that broke when the eighth service was registered, and a ninth is registered this wave | **W6-03**, which owns both the map and the suite |
| `apps/allinone/test/.../AllInOneWiringSuite.scala` | The startup-log string and the mounted path set. The other two literals from the same event | **W6-10** |
| `docs/api/openapi.json`, `docs/api/openapi.browser.json` — `./mill services.gateway.api.openApiCheck` | A **byte** comparison against a fresh Tapir render of every service's endpoints. `build.mill:1513` aims the *gateway* module's check at these, so the gateway module goes red for somebody else's endpoint | W6-01 and W6-03; repaired only by W6-09 |
| `services/alerts/api/openapi.json` — that module's own `openApiCheck` | The same comparison one layer in, inside W6-01's boundary | W6-01, for itself |
| `services/metrics/contract/**` and its decoders | **There is no golden file for the metrics wire and there never has been.** The wave-5 guard table named `services/metrics/contract/test/resources/golden/*.json`; that directory does not exist and `git log --diff-filter=A` over it is empty, while cluster, consumer, message, topic, gateway and `libs/contracts-core` all have one. Four DTOs went onto the wire with no encoded-instance golden, and two of them are the mismatch this wave repairs | **W6-02**, which creates it |
| `scripts/feature-matrix-check.sh` + the two `<!-- checked: merged-document -->` regions | `N paths and M schemas`, `X-Kui-Principal on N of its M operations`, `X-Csrf-Token on N`. Today, measured at integration: **54 paths, 65 operations, 150 schemas, principal on 50 operations over 39 paths, csrf on 20**. The script prints `105 claims checked, all true` | W6-01 and W6-03; repaired only by W6-09 |
| `frontend/packages/api/src/schema.d.ts` + `ci.yml:217-220`'s regenerate-then-`git diff --exit-code` | The browser's types. **Nothing in Mill checks this**; that CI step is the only gate | W6-09 |
| `frontend/scripts/bundle-shape.mjs` | Every `frontend/packages/feature-*` package present as a **dynamic** entry in the Vite manifest, roster read from the filesystem rather than hard-coded — so a sixth feature package joins it automatically and fails the build if it is statically imported | **W6-04**, the moment it adds `feature-alerts` |
| `frontend/packages/kernel/styles/index.css` + `build-tests`'s `CssReferencesSuite` | Every stylesheet on disk named exactly once | W6-04 adds the file, **W6-05 adds the line** — see house rule 1 |
| `frontend/packages/shell/src/features/registry.ts` | *"The body of a `load` thunk must be a bare `import("@kui/feature-…")` and **nothing else**"* — the property `bundle-shape.mjs` measures | W6-04 owns both ends |
| `scripts/run-tests.sh` | 63 modules, **57 with tests** — the count moved when `services/schema/app` got its first test source — and the six it names out loud as having none. Derived from `./mill resolve __.test`, so nothing is hard-coded and a ninth service simply appears | W6-01; and W6-A2, whose subject is exactly those six |
| `libs/contracts-core/test/resources/golden/*.json` + their Scala twins | Cluster and topic DTOs in *two* places per document | nobody this wave — no shared DTO changes. If you are about to, stop |
| `services/gateway/api/test/.../openapi/OpenApiMergeSuite.scala:36-59` | A hard-coded sorted path list over `gatewayDoc + clusterDoc` only, so an alerts path cannot reach it | W6-03 if it adds a *gateway* path (it must not; it adds proxy routes) |
| `services/gateway/api/test/.../openapi/MergedDocumentShapeSuite.scala` | `writes.size` over `ServiceContracts.proxied(cluster)`; distinct operationIds across the merged document. **An acknowledgement is a write**, so this one moves | W6-03 |
| `libs/config/test/src/kui/config/ShippedConfigurationSuite.scala` | A **hand-written** list of shipped configuration files, loaded through the real loader. Wave 5 added the two missing rows and left the registry unable to notice a *seventh* file: a new `deployment/examples/*.yaml` with invented field names loads nothing and the suite stays green at 13 | W6-10 owns both ends |
| `deployment/compose/smoke.sh` | The **contract** set scraped from `ServiceContracts.byService`, compared against the containers. A ninth contracted service that is not a container fails it — which is the check working | W6-10 owns the script; W6-03 owns the Scala file it scrapes |
| `deployment/metrics/kafka-jmx-exporter.yml` | Twelve line shapes now asserted by `smoke.sh`, up from two | W6-10 |
| `docs/FEATURE_MATRIX.md` rows vs. its own prose, and `README.md` | 189 rows; 68 COMPLETE; 178 in scope; 38% delivered. **CG-005 and MT-002 are false in the tree today** — see W6-09 items 4 and 5 | every packet that finishes a capability; repaired by W6-09 |
| `build-tests/**` | The token mirror and the stylesheet roster | house rules 1 and 2 forbid it |
| `frontend/packages/api/src/constants.generated.ts` | 31 error codes, byte for byte | house rule 3 forbids it |
| `libs/config`'s `checkMetricsRules` and `AlertThresholds`' loader rules | `retention >= scrapeInterval`, `callTimeout < scrapeInterval`, and `diskUsedCriticalPercent > diskUsedWarningPercent`, all refused at load | W6-01 if it re-validates a threshold the loader already refuses |
| `./mill __.checkFormat`, `./mill __.fix --check`, `./mill checkArchitecture`, `./scripts/run-tests.sh` | **All four are green at the start of this wave** — re-measured at integration: 3462 cases over 63 modules, 150 modules and 10 rules with no layering violations, 1967/1967 on `openApiCheck`. Wave 5 cleared the two that had carried for four waves. Anything red here is yours | whoever makes it so |

---

## W6-01 — `services/alerts`: the ninth service, and a feed that must not invent a severity

**Owns**
```
services/alerts/**                                          (new)
build.mill
docs/adr/ADR-053-alert-events.md                            (new)
```

**Contract.** Everything outside the service already exists and shipped in wave 1 so that this
milestone does not have to reopen the vocabulary while it is also writing a service:
`Resource.Alerts` with a non-altering `AlertsView` and an altering `AlertsAcknowledge`
(`libs/security-core/.../Vocabulary.scala:69,194,206,261-262`), and `kui.alerts` with
`AlertThresholds` — `offlinePartitions`, `underReplicatedPartitions`, `rebalanceDuration`,
`diskUsedWarningPercent`, `diskUsedCriticalPercent`, every one bounded by the loader
(`libs/config/src/kui/config/AlertsConfig.scala`). Its own scaladoc states the boundary you must
keep: *"The rule — which fact is read, what severity is opened, what the event says — belongs to the
alerts service and is deliberately not modelled here: a rule expressed in YAML is a small programming
language."* Do not widen `kui.alerts`.

**The worked example you are following** is `services/metrics`, which is the smallest complete
service in the repository after `services/schema`: six ADR-041 layers, a Mill module with its own
`openApi`/`openApiCheck` tasks and a `KuiImage` (`build.mill:2493` and `:3122`), a `ServiceContracts`
entry, an `AllInOneWiring` entry, a readiness poll, and a container. Budget the adapter and the
endpoints; the seven registration points outside the service are the cheap part and three of them
belong to other packets this wave.

**Do**
1. **Decide `isAlter` before you write the endpoint, in ADR-053, and it is the decision M8 was told
   to take rather than inherit.** `AlertsAcknowledge` is marked altering, and `Action.isAlter`
   answers *both* the audit question and the read-only question with one field
   (`Vocabulary.scala:84` decides the second by resource). So acknowledging an alert is refused on a
   read-only cluster although it writes to KUI's own store and never to Kafka. Either that is right
   and the ADR says why an operator watching a read-only cluster may not clear their own bell, or
   `isAlter` splits into two questions. **`RbacLawsSuite` asserts the current answer**, so whichever
   you choose is a case, not a comment.
2. The rules, over facts the product already has and no fact it does not: offline partitions,
   under-replicated counts, a group stuck rebalancing past `rebalanceDuration`, a log directory past
   its two thresholds. A connector task failure is M9's and a schema registration is a candidate —
   name in ADR-053 which rules ship and which are deliberately not shipped, because a rules engine
   that half-exists is indistinguishable from one that is broken.
3. The event record: an opened-at, a severity, a category (§3.9 — severity chooses the tone,
   category chooses the glyph), a resolution, and a store. **A percentage of "1h ago" cannot come
   from a `SnapshotCell`**, which is why this milestone has a store at all. `libs/cache` is complete
   and you *use* it; a fourth refusal added there is a sign the store is being written in the wrong
   layer.
4. `GET …/events`, an open count, a per-principal read marker, and an ADR-035 stream, so the card and
   the bell cannot disagree. `Section`-wrapped like every other read: one dead rule costs one row.
5. **Acknowledgement is a write and it is audited.** Follow `services/consumer`'s `MutationGuard`
   and **not** `services/topic`'s or `services/message`'s: all three implement one classification and
   the consumer one is the only correct one. A **cancelled** mutation is `MutationOutcome.Unknown`,
   because — in `AuditSink.scala:83-88`'s own words — *"Kafka gives no guarantee that it was not
   applied, so a record claiming either would be a lie."* The other two write `Failed`; W6-A1
   repairs them. Do not copy from the wrong one.
6. **The bound, and it is measured against something other than itself.** Whatever your feed's page
   size is, the case that proves it does not compute its input from the constant. Wave 5's schema
   packet built an oversized document as `"x" * (MaxDefinitionBytes + 1)` and the bound could be
   raised a thousandfold with 1186 tasks green; W5-A3 closed it and the pattern is now the first
   thing an adversary looks for.
7. **Mill and the image.** The module block, its `openApi`/`openApiCheck` tasks, its
   `deployment.docker.alerts` `KuiImage`, and its entry in the all-in-one's dependency list.
   `build.mill` is yours alone this wave, which is why the compose entry is W6-10's and the routing
   is W6-03's: three packets, one file each, no shared line.
   **W6-A2 needs one line from you** and it is in its `needsOutsideOwnership`: add
   `mvn"org.typelevel::cats-effect-testkit::${Versions.catsEffect}"` to
   `services.schema.infrastructure.test`'s dependencies, exactly as `libs/cache`'s test module
   already carries it, so that a token cache's expiry can be tested under a controlled clock. It is
   one line in your file and it unblocks a rule nobody could close.
8. ADR-053: the `isAlter` decision, which rules ship, the event and severity model, why the feed is
   `Section`-wrapped per rule rather than per document, and the retention the store keeps.
   **Then add its row to `DECISIONS.md` — no, you cannot: that file is W6-09's**, and the fact that
   ADR-052 was missed there for a whole wave and repaired by hand is W6-09 item 6. State the row you
   need in `needsOutsideOwnership`; W6-09 lands it and lands the gate that stops ADR-054 repeating it.

**Do not** add a key to `kui.alerts`, do not model a rule in YAML, and do not seed an event to make a
screen look alive. A feed with no events is `ok` with an empty list and the screen says so.

**Acceptance**
```
./mill services.alerts.__.test
./mill checkArchitecture
./mill services.alerts.__.checkFormat
./mill services.alerts.api.openApi          # regenerate this module's own document, and commit it
./mill services.alerts.api.openApiCheck     # then green here; the gateway's stays red until W6-09
./scripts/run-tests.sh                      # the module count moves; no module may ship with no test sources
```
Required cases, by name: a cluster with one offline partition opens exactly one event carrying its
severity and category; a threshold raised above the fact closes no event and opens none; a group
rebalancing for less than `rebalanceDuration` opens nothing and one past it opens one; **an
acknowledgement by a principal without `ALERTS:ACKNOWLEDGE` is refused before the store is written**;
a cancelled acknowledgement is audited as `Unknown` and never as a success or a failure; the open
count is the count of open events and not of all of them; a feed with no events answers `ok` with an
empty list.
**Mutation line:** make the rebalance rule fire at `rebalanceDuration / 2`. Name the case that goes
red. **And a green one:** mutate the retention the store keeps and report whether anything notices.

---

## W6-02 — The wire two packets guessed differently, and the two cards that draw nothing

**Owns**
```
services/metrics/**
frontend/packages/shell/src/overview/**
frontend/packages/shell/src/pages/**
frontend/packages/shell/styles/32-shell-dashboard.css
frontend/packages/shell/styles/37-overview.css
frontend/e2e/traffic.spec.ts
```
**This packet owns both ends of one wire on purpose, and that is house rule 12 written as a
partition.** Wave 5 split them and the result is below.

**Contract, measured at integration rather than reported.** Two of the four new wires do not match the
browser that reads them, and the mismatch is silent because the top-level `Section` key matches on
both sides, so `readMetric` unwraps happily and the decoder answers an empty array:

* `services/metrics/contract/.../RequestHandlerDtos.scala:52-54` sends
  `requestHandlers.data.{requestHandlerIdleRatio, networkProcessorIdleRatio, purgatory:[{operation,
  delayedRequests}]}`. `frontend/packages/shell/src/overview/metrics.ts:305` reads
  `data.readings[]` of `{id,label,ratio,count,unit,goodDirection}`. `handlerPanel` returns **zero
  gauges**.
* `ProducerDtos.scala:20,53` sends `producers.data.{measuredBy, topics:[{topic, bytesInPerSecond}]}`.
  `metrics.ts:414-420` reads `data.entries[]` of `{clientId?, topic?, bytesPerSecond?}`.
  `producerBoard` returns **zero rows** and subject `"producer"`.
* Latency and record size **do** match — `startingAt`/`produceP99Millis`/`fetchP99Millis`/
  `stepSeconds`, and `meanBytes`. Two of four, not four of four.

And the cards then lie in words. With the arrays empty, `RequestHandlersCard` falls to
`NO_HANDLER_READINGS` — *"The metrics source answered and served no request-handler readings"* — and
`TopProducersCard` to `NO_PRODUCERS` — *"The metrics source answered and named no producers"*. The
source served three readings and five topics. On a screen whose whole promise is that it says what it
knows, a confident false statement about the source is worse than the em dash this product refuses
elsewhere.

**Do**
1. **Settle the two shapes and make one of them true.** Either side may move; the ADR-052 spellings
   are the server's and the browser's are inventions, so the cheap answer is almost certainly to move
   the browser — but that is a decision recorded in ADR-052's own text, not a diff. Whichever way it
   goes, **the case that binds them decodes the encoder's own output**: a Scala test that renders the
   DTO to JSON and a browser test that decodes that exact captured document. A hand-written literal
   on each side is what produced this.
2. **Create the golden the wave-5 guard table said existed.** `services/metrics/contract/` has no
   `test/` directory and never has, while every other contract module in the repository does. Four
   DTOs are on the wire with no encoded-instance golden. Add
   `services/metrics/contract/test/resources/golden/*.json` and the suite that reads it, in the shape
   `libs/contracts-core` and `services/topic` already use.
3. **Re-cut `traffic.spec.ts`'s two blind cases.** Line 313 reads `section.data?.readings ?? []` and
   line 353 reads `wire.producers.data?.entries ?? []` — the same wrong names, so both `for` loops
   run zero times, `first` is `undefined` and the producers-title assertion is skipped entirely.
   Those two cases report green against a real endpoint while the cards render empty. A browser case
   that iterates an empty array has asserted nothing: assert the array is **non-empty** first, or
   assert against the count the API answered.
4. **The two ungated rules this packet owns in `services/metrics`.**
   `PrometheusExposition.scala:275`'s `isAttribute` anchor — `name.contains(s"_$attribute")` made
   `name.contains(attribute)` leaves 1237/1237 green, and its own scaladoc says it is what stops
   `replicationbytesinpersec` being read as `bytesinpersec` (replication traffic charted as cluster
   throughput) and `brokerrequesthandleravgidlepercent` as `requesthandleravgidlepercent`. It survives
   because in `kafka-jmx-exporter.txt` the *correct* lines precede the decoys — aggregate at line 27
   against replication at 39, handler pool at 42 against the two decoys at 56-57 — and both readers
   use `.find`, so first-match-wins is doing the work the anchor claims. **Reorder one fixture so a
   decoy precedes its real line, or assert against a body containing only the decoy.** And
   `MetricsBuffer.scala:150`'s `samples.nonEmpty && samples.forall(_.isEmpty)` made
   `samples.exists(_.isEmpty)` stays green: the mixed window — an exporter that served a percentile
   an hour ago and then stopped — is the case that separates *answer a series with a gap on the end*
   from *refuse the whole card*, and neither behaviour is asserted.
5. **The three ungated rules this packet owns in the browser**, each verified green at 208 and again
   at 1449 at integration. `TrafficCards.tsx:84` and `:211`: replacing both
   `fallback={<NotMeasured why={…} />}` with `fallback={undefined}` leaves the suite green and both
   cards then draw a titled card with an **empty body** — no sentence, no dash — which is the
   panel-renders-nothing failure this screen exists to prevent and is the state a real cluster is in
   today. `TrafficCards.tsx:189`: `ceiling()`'s `Math.max(...rates)` made `Math.min(...rates)` leaves
   it green, so every producer but the quietest is drawn pegged full and the comparison the card
   exists to draw is destroyed — nothing asserts any bar's **length**, which is the figure.
   `TrafficCards.tsx:197-199`: `state={props.state.kind === "failed" ? "unavailable" : "ready"}` made
   `state="ready"` with `message` and `code` dropped leaves it green, so a gateway error draws a
   healthy-looking card with no message, no stable `KUI-` code and no Retry.
6. **Five dead exports, in the packet whose predecessor was assigned to delete three.**
   `producersTitle`, `NO_PRODUCERS`, `NO_HANDLER_READINGS`, `RECORD_SIZE_NOUN` (`TrafficCards.tsx`)
   and `NO_LATENCY_SENTENCE` (`LatencyCard.tsx`) are exported and referenced by nothing outside their
   own module — not a test, not a story, not the spec. Two of them are the ungated sentences in item
   5, which is not a coincidence: an exported string nobody imports is an assertion nobody wrote.
   Import them where they are asserted, or stop exporting them.
7. **Four false figures in shipped scaladoc, and this is house rule 11's worked example.**
   `BrokerReadings.scala:117`, `RecordSizeDtos.scala:15` and `PrometheusExposition.scala:34` each say
   the stock-ruleset exposition carries **680** Kafka families; ADR-052:29, the committed fixture
   header and the raw capture all say **670**. `BrokerReadings.scala:8` says `PurgatorySize` is
   **961** on the quickstart broker; ADR-052:81 and :95, the fixture and `PrometheusExpositionSuite`
   all say **481**, and nothing in the tree reads 961. The prose each defends is correct. Only the
   numbers are invented — verbatim the shape of the `348601.0` the predecessor was sent to remove.
8. **`Section.Stale` is documented and unreachable.** `MetricsMapping.sectionOf` has three arms —
   `Measured -> Ok`, `NotMeasured -> NotConfigured`, `Unreadable -> Unavailable` — and no code path
   in `services/metrics` produces `Stale`, while the wave-5 report told the browser packet to branch
   on `ok|stale|unavailable|not_configured`. Either the buffer can answer `stale` for a window whose
   newest sample is older than one scrape interval — which is the honest reading and is what
   `ThroughputCard.captionOf`'s stale branch was written for — or the fourth arm goes and the
   browser stops carrying dead render code. Decide it in ADR-052 and assert whichever answer.
9. **A comment claiming a defence the fixture does not make.** `PrometheusExpositionSuite.scala:86-87`
   states *"The name test has to be anchored at a `_`, and the fixture is what makes the unanchored
   version fail."* It does not: the anchor was deleted and that very case stayed green. Item 4 makes
   the sentence true; do not leave it false.

**Acceptance**
```
./mill services.metrics.__.test
./mill services.metrics.__.checkFormat
./mill services.metrics.api.openApi && ./mill services.metrics.api.openApiCheck
./mill checkArchitecture
pnpm -C frontend test packages/shell/src/overview packages/shell/src/pages
pnpm -C frontend typecheck
pnpm -C frontend build-storybook          # then serve :6017 and:
node frontend/scripts/a11y-stories.mjs 'screens-overview|screens-traffic|screens-settings'
pnpm -C frontend e2e                       # against images built from this tree, and say which image id
# and, against a quickstart built from this tree, all five:
curl -s '…/clusters/quickstart/metrics/{throughput,latency,request-handlers,producers,record-size}'
```
Required cases, by name: **a request-handlers document rendered by the server decodes into gauges in
the browser**; **a top-producers document rendered by the server decodes into rows in the browser**;
a card whose source answered and served nothing says so in a sentence; a producer board's bars are
drawn against the largest rate on the card; a failed read draws its message and its code; a captured
exposition in which a decoy precedes the real line is still read as the real line.
**Mutation line:** `PrometheusExposition.isAttribute`'s `s"_$attribute"` → `attribute`. Name the case
that goes red — there is none today. **And a green one:** mutate the record-size mean's rounding and
report what happens.

---

## W6-03 — The gateway routes a ninth service, and a composition root that must touch nothing

**Owns**
```
services/gateway/**
ARCHITECTURE.md
```

**Contract.** W6-01's feed is `Section`-wrapped reads and one write under
`/api/v1/clusters/{clusterId}/alerts/…`. The gateway proxies them the way it proxies metrics: a
contract row, a capability fold entry, **no new *gateway* path**. `OpenApiMergeSuite` holds a
hard-coded list over `gatewayDoc + clusterDoc`, so a proxied path cannot reach it and a gateway path
of your own would move it.

**Do**
1. Route the feed, the open count, the read marker, the stream and the acknowledgement. **The
   acknowledgement is a write**, so `MergedDocumentShapeSuite`'s `writes.size` over
   `ServiceContracts.proxied` moves and `ServiceContractsSuite`'s hard-coded service-id `Set` gains
   its ninth member. Both are yours; both broke last time a service was added, and both are in the
   guard table so that nobody is surprised twice.
2. **The ungated rule this packet owns.** `GatewayWiring.scala:169`: making the composition root
   probe every routed upstream during wiring —
   `Resource.eval(registry.attachProbe(trigger.probe) *> routed.traverse_(trigger.probe))` — leaves
   `./mill services.gateway.__.test` at 1345/1345 with `GatewayWiringSuite` 6/6 green, and it was
   proved non-vacuous by printing the routed list from inside the mutation. The case named for the
   rule, `theGatewayStartsWhenEveryUpstreamIsUnreachable`, was **rewritten last wave to close exactly
   this** and does not: the probe's connection is closed before the test calls `accept()`, Linux drops
   it from the backlog, and the 250 ms `accept` still times out. A connection *held open* until
   `accept()` does make it red. Close it for the ordinary shape of the bug — count connections at the
   socket, or assert the upstream client is never constructed during acquire — not for the shape that
   happens to be observable.
3. **A permission model asserted only at its easiest point.** The new `PolicyRbacPreCheckSuite` is
   real work and it closed the seam that mattered, but every direct `rbac.check(...)` in it passes
   `requestSegments = Nil` and both route-driven cases use cluster-scoped reads, so
   `EndpointDecision`'s **resource-pattern** matching — the part that decides whether a role scoped to
   `orders.*` may touch `payments.v1` — is exercised by no case in the class the packet was written
   to construct. Add the pattern cases. The threading of segments is separately gated
   (`ClusterRoutingSuite.theRbacPreCheckReceivesTheRequestPath`), so this is coverage and not a
   second hole — say which it is in the report rather than counting it twice.
4. `MetricsProxySuite.oneUnavailableMetricsFamilyCostsOneCardAndNotTheTab` has **no production locus
   at the gateway**: `reportIfInfrastructure` keys off `InfrastructureError` alone and a
   `Section.Unavailable` arrives as a `Right`, so no gateway-side change can make its capability
   assertion fail. The case is true and worth keeping; move the assertion to where the decision is
   made, or say in its header that it pins a stub and not a rule.
5. `SearchSuite.scala:28-32` says *"Only the two cases that drive a `SearchSource` directly need it"*
   and there is one. A comment describing code that is not there, in the packet whose predecessor was
   assigned to remove three of them.
6. `ARCHITECTURE.md`'s A7 sentence was corrected last wave and **two more copies survive**:
   `frontend/packages/shell/src/features/registry.ts:15` (W6-04's) and
   `build-tests/.../ArchitectureRules.scala:161` with `ArchitectureSuite.scala:378` (unowned, and
   house rules 1 and 2 keep it that way). Yours is already right; name the other two in
   `needsOutsideOwnership` so the count of copies goes to zero this time instead of from four to
   three.

**Acceptance**
```
./mill services.gateway.__.test       # three consecutive runs
./mill checkArchitecture
./mill services.gateway.__.checkFormat
./mill services.gateway.api.openApiCheck   # red against docs/api/** until W6-09; that is designed
```
Required cases, by name: an alerts read the gateway routes reaches the service and its Section
survives; an acknowledgement is proxied as a write and carries its CSRF and principal headers; **the
gateway contacts no upstream while wiring**; a role scoped to one topic pattern is refused a request
naming another; a request the policy denies does not reach the upstream service.
**Mutation line:** `GatewayWiring.scala:169`, the probe-during-acquire above. Name the case that goes
red — there is none today. **And a green one:** mutate the capability fold's precedence for a
`not_configured` row and report what happens.

---

## W6-04 — `feature-alerts`: the sixth feature package, and the registration seam that goes with it

**Owns**
```
frontend/packages/feature-alerts/**                         (new package)
frontend/packages/shell/src/features/**
frontend/packages/shell/package.json
frontend/tsconfig.json
frontend/e2e/alerts.spec.ts                                 (new)
```
**depends on W6-01's feed shape and W6-05's widened `FeatureId`.**

**Contract.** `FeatureId` is `"clusters" | "topics" | "messages" | "consumers" | "schemas"` at
`frontend/packages/kernel/src/feature/registration.ts:38`. It is the **kernel's**, so W6-05 widens it
to carry `"alerts"` whether or not you ask — the design already names the requirement, exactly as
`RingGauge.goodDirection` was handled last wave, and that handoff worked. You code against the widened
union and raise nothing.

The feed's shape is ADR-053's, not W6-01's diff: `Section`-wrapped, `status` in
`ok | stale | unavailable | not_configured`, and where events are answered each carries an opened-at,
a severity, a category and a resolution. **A severity chooses the tone and a category chooses the
glyph** (§3.9) — read the ADR, not the endpoint.

**Do**
1. The Alerts screen: the feed, filtered by severity and by open/resolved, with the count the API
   answered and never a count the browser recomputed from a page.
2. The alerts card on the dashboard, reading the same store as the bell so the two cannot disagree —
   which is why the store is in the kernel and not in this package. **The bell is W6-06's**, in the
   shell's chrome; you ship the screen and the card, W6-05 ships the store, W6-06 hangs the bell off
   it. Three packets, one feed, no shared file.
3. **The registration seam, both ends, because it is four files and one idea.** A `load` thunk in
   `frontend/packages/shell/src/features/registry.ts` whose body is *a bare
   `import("@kui/feature-alerts")` and nothing else* — `bundle-shape.mjs` measures exactly that and
   reads its roster from the filesystem, so it will start checking your package the moment the
   directory exists; the `workspace:*` dependency in `frontend/packages/shell/package.json`; the
   project reference in `frontend/tsconfig.json`; and the package's own `package.json`/`tsconfig.json`.
   **Add the reference to `frontend/tsconfig.json` that `feature-schemas` still does not have** — it
   is typechecked today only transitively, through `packages/shell/tsconfig.json`, which is a
   property nobody chose and W5-A2 measured in both directions.
4. **No feature may import another feature and nothing may import `@kui/shell`.** Shared code goes to
   `@kui/kernel`. `boundaries.mjs` checks the first even when the dependency is *declared*, because
   one edge puts both chunks in one download and quietly deletes the property E2E-001 asserts.
5. **One stylesheet, in your package's own `styles/` directory**, in the shape
   `feature-consumers/styles/71-consumer-screens.css` already has. **You cannot register it**:
   `frontend/packages/kernel/styles/index.css` must name it and that file is W6-05's, which ships the
   line at the number you state. Say the number in your report; a stylesheet on disk that the index
   does not name fails `build-tests`, which nobody owns.
6. A story per state, including the two that are not "some alerts": a feed that is `ok` and empty,
   and one that is `not_configured` because the deployment runs no alerts service. **`not_configured`
   is hidden, not empty** — ADR-032 — and the row must not be faked.
7. Do not seed an event to make the screen look alive, and do not compute a severity in the browser.
   A severity that is not in the document is not a severity.

**Acceptance**
```
pnpm -C frontend test packages/feature-alerts
pnpm -C frontend typecheck
node frontend/scripts/boundaries.mjs
node frontend/scripts/bundle-shape.mjs     # six feature packages, all dynamically imported
pnpm -C frontend build-storybook          # then serve :6017 and:
node frontend/scripts/a11y-stories.mjs 'alerts-|screens-alert'
pnpm -C frontend e2e                       # against images built from this tree
```
Required cases, by name: an event's severity chooses its tone and its category chooses its glyph; a
feed that answered with no events says so and draws no count; **a deployment with no alerts service
draws no Alerts row at all rather than an empty one**; acknowledging an event the API refuses leaves
the row unacknowledged and says why; the open count on the card is the API's own figure.
**Mutation line:** make the card recompute the open count from the rows it holds. Name the case that
goes red. **And a green one:** mutate the severity-to-tone table and report what happens.

---

## W6-05 — The kernel: five rules its own packet left open, and the store the bell and the card share

**Owns**
```
frontend/packages/kernel/**
frontend/scripts/a11y-stories.mjs
```

**Contract.** Wave 5 gave the kernel an owner for the first time since M3 and it was worth it: six
named rules closed, all six re-verified. It also shipped **five more ungated rules and disclosed none
of them**, and one of the five is in the file its own headline was about. That is not a criticism of
the packet; it is the finding this wave is shaped around, and it is why the five are named here with
their exact mutations.

**Do — the five, each with the mutation that proves it. Every one was re-run at integration.**
1. **`data/capabilities/store.ts` — the stale-poll-answer guard, and it is the serious one.** Delete
   `if (current !== episode) return;` from **inside** `void options.poll().then((outcome) => {…})`,
   leaving the other two occurrences alone: **1449/1449 green**, measured here. The episode can change
   between `poll()` going out and its promise resolving — the stream recovers, or `stop()` runs — and
   without the guard a snapshot fetched by an abandoned chain is applied over the fresh one, so
   capabilities the gateway has just re-enabled are painted disabled again from an answer nobody was
   waiting for. The packet's report says all three occurrences are now individually red-able. One is.
2. The same file's `tick()` entry guard: deleting `if (current !== episode) return;` from the first
   line leaves 1449 green. It is genuinely redundant — every caller has just made the same test — so
   keeping it is defensible. What is not defensible is the sentence claiming it is gated. Make the
   comment true or make the line testable.
3. The same file's `beginPollingFallback`: `if (polling || stopped) return;` → `if (polling) return;`
   leaves 1449 green. It is the exact twin of `connect()`'s `if (stopped) return;`, which the packet
   **did** disclose. Disclosing one and not its twin is what makes this one worth naming.
4. **`components/overlay.ts` — splice by identity.** Replacing
   `const at = trapping.lastIndexOf(element); if (at !== -1) trapping.splice(at, 1);` with
   `trapping.pop();` leaves 1449 green, and the comment beside it asserts a specific failure — *"a
   route change tearing down a drawer takes its dialog with it, and the two cleanups do not run in a
   guaranteed order"* — that nothing constructs. Popping under out-of-order teardown removes the
   wrong element and leaves a dead surface permanently on top of the stack, silently swallowing
   Escape for every surface opened afterwards. **This is a comment claiming a defence nothing makes,
   shipped by the packet whose own item 9 was a note about the same file acquiring one.**
5. `numbers.ts`'s `share`: `of <= 0` → `of === 0` leaves 1449 green, and `share(-4212, -100)` then
   yields a **full bar** for a quantity nothing measured — the exact sentence the rule is written to
   prevent. Reachability is low; the rule is the wave's owned rule from last wave and its guard is
   still one character from being wrong.
6. **The Tab trap has the same shape as the Escape trap and is untouched.** The packet's own
   diagnosis is that both surfaces listen on `document`, so `stopPropagation` cannot keep a key from
   a sibling listener — which is equally true of the Tab branch below it, which still runs in *every*
   mounted surface on every Tab and is not scoped by the `trapping` stack. It is benign today only by
   accident of layout: `Dialog` portals to `document.body`, so `focusableWithin(drawerElement)` never
   contains the dialog's stops. Scope it, or write down that it is benign by accident and what would
   end that.
7. **One line for W6-04, and it is the stylesheet index.** `styles/index.css` must name
   `feature-alerts`'s single stylesheet or `CssReferencesSuite` fails, and that index is in your tree
   rather than theirs. Add the import at the number W6-04 states. It is the same handoff as
   `FeatureId` below, and both exist because a file's owner ships what the design requires whether or
   not the packet that needs it has landed.
8. **The alerts feed store**, and it belongs here for the reason the capabilities store does: the
   bell is in the shell's chrome and the card is in a feature package, a feature may not import the
   shell and the shell must not statically import a feature, so the one place both can read is the
   kernel. One ADR-035 subscription, one open count, a per-principal read marker. **Widen `FeatureId`
   at `feature/registration.ts:38` to carry `"alerts"` whether or not W6-04 asks** — the design names
   the requirement and the pattern worked last wave.
9. **The a11y harness message is self-contradictory in the case it exists for.** Forcing the theme
   timeout prints *"asked for the dark theme and got dark"*, because the attribute lands between the
   `waitForFunction` timeout and the `page.evaluate` that reads it back — and under genuine load that
   is precisely the case. The retry and the `HARNESS FAILURE` framing are real improvements and the
   sweep was clean first-try over 739 stories at integration; the diagnostic is still wrong in the
   moment it is read.
10. **`frontend/scripts/**` has no test harness at all** — `vitest.config.ts` globs
   `packages/*/src/**`, so the three scripts are exercised only by being run. `THEME_WAIT_MS` can go
   back to a flat `[10_000]`, the third attempt can be dropped and the failure message can be
   re-merged into the `✗ <id>` shape with every gate green. The packet disclosed this accurately.
   Either give `scripts/` a vitest project of its own, or say in `TECH_DEBT.md` — through
   `needsOutsideOwnership`, the file is W6-09's — that three CI gates are guarded by nothing.

**Do not** add a custom property to `10-tokens.css` or a stylesheet file — house rules 1 and 2 —
because `build-tests/**` mirrors both and is owned by nobody.

**Acceptance**
```
pnpm -C frontend test packages/kernel
pnpm -C frontend test                      # the whole suite: the kernel's invariants are load-bearing
pnpm -C frontend typecheck                 # in nine packages downstream
pnpm -C frontend build-storybook          # then serve :6017 and:
node frontend/scripts/a11y-stories.mjs     # the unfiltered sweep, twice, and both must be clean
```
Required cases, by name, one per rule above: **a poll answered after the episode moved is not
applied**; a store that has been stopped does not start a poll chain; a nested overlay torn down out
of order leaves the stack correct; a share of a negative quantity draws an empty bar; the alerts store
answers one open count to two subscribers.
**Mutation line:** the stale-poll-answer guard, item 1. Name the case that now goes red.
**And a green one:** mutate a primitive nobody asked you to touch and report whether the suite
notices.

---

## W6-06 — The frame: the bell, a disk that reads as unknown when it is known, and a sort nothing holds

**Owns**
```
frontend/packages/shell/src/App.tsx
frontend/packages/shell/src/app.render.test.tsx
frontend/packages/shell/src/shell.test.tsx
frontend/packages/shell/src/chrome/**
frontend/packages/shell/src/nav/**
frontend/packages/shell/src/data/**
frontend/packages/shell/src/routing/**
frontend/packages/shell/src/index.ts
frontend/packages/shell/styles/30-shell.css
frontend/packages/shell/styles/31-shell-nav.css
frontend/packages/shell/styles/33-chrome-drawer.css
frontend/packages/shell/styles/34-chrome-topbar.css
frontend/packages/shell/styles/35-chrome-navigation.css
frontend/packages/shell/styles/36-frame.css
frontend/packages/shell/styles/38-chrome-rail.css
frontend/e2e/shell.spec.ts
frontend/e2e/dashboard.spec.ts
frontend/e2e/search.spec.ts
research/design/SCREENS-V4.md
```

**Contract.** The alerts screen is a **route and a nav destination**, not a dashboard tab — that is
decided here rather than left to be discovered, because dashboard tabs live in W6-02's `overview/`
and a feature package reaching into them would invert the dependency. So: you add the route to
`routing/**`, the nav row to `nav/**`, the `FeatureId`-keyed badge row in `App.tsx`, and the bell in
`chrome/**`. W6-04 ships what is behind the route. The bell reads W6-05's kernel store and **not**
`@kui/feature-alerts`: a static import of a feature from the shell puts that chunk in the entry
download and deletes the property `bundle-shape.mjs` exists to assert.

**Do**
1. The bell: an unread mark driven by the store's per-principal read marker, and the open count.
   A count of zero is **no badge**, not a zero — the never-a-zero rule the cluster store already
   keeps and which `maybe`'s four cases pin.
2. **The two ungated rules this packet owns.** `nav/navigation.ts:147`: deleting
   `.sort((a, b) => a.registration.order - b.registration.order)` leaves 234 cases green, while the
   module header at line 31 states the rule verbatim — *"Entries are sorted by their declared order
   and never by anything that changes at run time"* — and the drawer's row order then becomes
   whatever order the capability frame happened to arrive in, which is the rows-move-under-the-cursor
   failure `prefixes.ts` writes its own tie-break to prevent. **A sixth feature registration lands in
   this wave**, so the sort stops being theoretical the day W6-04 merges.
   And `chrome/StorageMeter.tsx:81`: `broker.totalBytes <= 0` → `< 0` leaves 234 green, and a
   zero-capacity broker then computes `0/0 = NaN`, fails both threshold comparisons and is drawn
   **`ok`** — a green healthy segment for a disk about which nothing is known. Its own docblock states
   the rule and its cause.
3. **A product defect in the same component, found by reading rather than by mutating, and it is the
   milestone's central rule broken.** `StorageMeter.tsx:138`'s `<Show when={percent()}>` is falsy at
   zero. Mounted with `usedBytes: 0, totalBytes: 500_000_000_000`, the panel renders the percentage as
   `—` inside `kui-storage__percent--unknown` with `title="Disk usage could not be read"` while the
   caption directly below reads `0 B of 500.0 GB`. The card contradicts itself and the em dash asserts
   an unreadable disk that was read perfectly — the exact inversion of the file header's own
   *"Unknown is a track, not a zero"*. It is pre-existing and it is inside your `Owns`; the component's
   only case is the empty-list one, which is why nothing sees it.
4. `chrome/SearchField.tsx:119`'s `RESULT_CLICK_GRACE_MS` is exported and read by nobody outside its
   own module — the case that pins the rule deliberately uses absolute milliseconds, and the only
   other mention in the repository is prose in `search.spec.ts:71`. This is precisely the shape the
   same packet removed from the barrel one item earlier. Import it in the case, or stop exporting it.
5. **Five hard-coded census figures in prose, two already wrong.** `App.tsx:709`,
   `chrome.test.tsx:554`, `:605`, `:1180` and `topicTree.test.ts:103` all say **223**; two of them say
   *"all 223 cases in this package"* and `@kui/shell` holds 442. Nothing will ever update them. Say
   what the number is *of*, or delete it — a count in a comment with no mechanism behind it is house
   rule 11.
6. `app.render.test.tsx`'s *"a memo over an empty topic list yields no subtree"* documents itself as
   *"a cluster that has no topics at all… the one an operator meets on a cluster they have just
   registered"*, and the stub overrides only `/topics/names` while `/topics` still answers
   `page.totalItems: 128`. The row actually asserted is **Topics, badge 128, no subtree** — a cluster
   whose count endpoint and name index disagree. It gates the rule; its prose describes a state it
   does not build. Build that state or rewrite the prose.
7. `research/design/SCREENS-V4.md` §2.2 item 4 still draws *"Two starred favourites by exact name,
   then prefix groups with counts, then a padlocked internal"*, and the shipped drawer has had no
   favourites branch since wave 4. Wave 5 filed this as `needsOutsideOwnership` and was right to —
   `research/` was in no packet's partition — and it is in nobody's this wave either, so **it is
   yours**: either the design document records that the branch was removed and what bringing it back
   costs, or it stays a decision recorded only in a source comment, which is where it has been for
   two waves.
8. `topicSubtree`'s case *"is the fold's own rows, in the fold's own order, when there are any"*
   compares `topicSubtree` against `topicTree` over one input, so a mutation to both is invisible to
   it and the packet's own disclosure explains why. Give it an independent expectation.

**Acceptance**
```
pnpm -C frontend test packages/shell/src/chrome packages/shell/src/nav packages/shell/src/data packages/shell/src/routing packages/shell/src/app.render.test.tsx packages/shell/src/shell.test.tsx
pnpm -C frontend typecheck
node frontend/scripts/boundaries.mjs
node frontend/scripts/bundle-shape.mjs     # the shell must not statically import feature-alerts
pnpm -C frontend build-storybook          # then serve :6017 and:
node frontend/scripts/a11y-stories.mjs 'chrome-|shell-'
pnpm -C frontend e2e                       # against images built from this tree
```
Required cases, by name: **the drawer's rows are in their declared order and not in the order the
frame arrived**; a broker whose capacity is zero is drawn as unknown and never as healthy; **a disk
that has used none of a known capacity draws 0% and not an em dash**; the bell carries no badge when
nothing is open; an alerts route with no alerts service draws no row.
**Mutation line:** delete `navigation.ts`'s declared-order sort. Name the case that goes red — there
is none today. **And a green one:** mutate the storage meter's threshold tones and report what
happens.

---

## W6-07 — Consumers and messages: a destructive control with no permission gate

**Owns**
```
frontend/packages/feature-consumers/**
frontend/packages/feature-messages/**
frontend/e2e/consumers.spec.ts
frontend/e2e/messages.spec.ts
```

**Contract.** Wave 5 built the forget-offsets control CG-005 had been missing for two milestones, and
it is good work: per topic because the endpoint is per topic, a receipt quoting the server's own
figure, an empty-partition answer that is a warning rather than a green tick. Five rules around it are
ungated and all five are things an operator acts on. The first is the most serious single finding in
the wave.

**Do**
1. **The permission gate on the whole destructive control, and it is ungated.** `GroupRoute.tsx:148`:
   `onForgetOffsets={mayReset() ? (topic) => {` → `onForgetOffsets={true ? (topic) => {` leaves
   **1449/1449 green**, re-measured at integration. An account without
   `ConsumerGroupResetOffsets` is then shown an **enabled destructive button** and never the refusal
   sentence, and `forgetRefusal` stops being reachable at all. `GroupDetail`'s disabled+tooltip
   rendering is tested at the component and drawn in a story, but `groupRoute.test.tsx` never mounts
   the route without the permission, so the wiring that decides it is asserted by nothing. **Mount the
   route unpermitted.**
2. `GroupRoute.tsx`: deleting `setForgetting(undefined);` from the confirm success path leaves the
   suite green. The refetch beside it drops the page to `loading`, unmounting the dialog, and
   `<Show when={forgetting()}>` then renders it **again** once the group reloads — so after a
   successful forget the operator faces the same armed confirmation, now describing a group that no
   longer holds those offsets and reading the branch the packet declared unreachable.
3. `consequenceOfForget`: `subscriptions(group).find((one) => one.topic === topic)` →
   `subscriptions(group).at(0)` leaves it green. The route case presses the first row's button only,
   so *"Removes this group's committed offsets on N partitions of &lt;topic&gt;"* — the packet's own
   stated promise that this is the figure the receipt is read against — would silently quote another
   topic's count for every other row.
4. `write.ts:191`: `partitions: answer.value.partitions ?? []` → `?? [0]` leaves it green.
   `DeletedOffsetsDto.partitions` is optional in `schema.d.ts`, so an answer naming no partitions is a
   real wire state, and with the mutation it reports *"Committed offsets forgotten … 1 partition"* —
   a success toast for a server that said nothing was removed, which is exactly what the two sentences
   exist to prevent.
5. The double-submit guard: `busy={forget.busy()}` → `busy={false}` leaves it green.
   `ConfirmDialog.canConfirm()` is `confirmationSatisfied() && props.busy !== true`, so a second press
   while the DELETE is in flight fires a second one, whose empty answer then raises *"Nothing was
   forgotten"* over an action that had just succeeded.
6. The e2e claim that could not be run: the documented `pnpm -C frontend e2e` fails against the shared
   stack because the running frontend image has neither *"Forget offsets"* nor the preset toast in its
   bundle. The typecheck blocker that stopped the image being built is gone, so **build the image and
   run it as documented** — the workaround (a local `vite build` bind-mounted into a second container)
   proves the code and not the claim.

**Acceptance**
```
pnpm -C frontend test packages/feature-consumers packages/feature-messages
pnpm -C frontend typecheck
pnpm -C frontend build-storybook          # then serve :6017 and:
node frontend/scripts/a11y-stories.mjs 'screens-consumer|consumers-|messages-|screens-message'
pnpm -C frontend e2e                       # against images built from this tree, as documented
```
Required cases, by name: **a principal without `ConsumerGroupResetOffsets` is never handed an enabled
forget control**; a successful forget closes its confirmation; the confirmation names the partition
count of the topic whose button was pressed; an answer naming no partitions is reported as nothing
forgotten; a second press while the request is in flight sends one request.
**Mutation line:** `mayReset() ?` → `true ?`. Name the case that goes red — there is none today.
**And a green one:** mutate the receipt's partition pluralisation and report what happens.

---

## W6-08 — Topics: the permission wiring one screen up from where it was fixed

**Owns**
```
frontend/packages/feature-topics/**
frontend/e2e/topics.spec.ts
```

**Contract.** Wave 5's packet named as its own worst finding *"four controls wired to four actions
being indistinguishable under a single-boolean `permits`"*, closed it on the topic **detail** page,
and left the identical hole on the topic **list** page — forty lines above the code it edited, in the
same file. Its new case is even named *"each control on the topic page is gated on its own action"*.
The harness capability that closes it already exists: `permits` is a predicate now. This is one more
case, not new machinery.

**Do**
1. **Three ungated permission gates, all in `TopicsRoute.tsx`, each green at 145/145 and one of them
   re-measured at integration at 1449/1449.**
   `:413` `createBlocked`'s `permitted: kui.permits(Actions.TopicCreate)` → `Actions.TopicDelete`;
   `:420` `purgeBlocked`'s `Actions.TopicMessagesDelete` → `Actions.TopicDelete`;
   `:427` `deleteBlocked`'s `Actions.TopicDelete` → `Actions.TopicMessagesDelete`.
   Consequences, in order: a principal who may delete topics but not messages is offered an enabled
   bulk **Empty**; the mirror — one trusted to reclaim disk and not to destroy a stream gets an
   enabled bulk **Delete** over ticked rows; and Create is gated on the wrong action entirely. Taken
   together the whole list screen's permission wiring is a constant no case in this package can
   observe.
2. `topics.test.tsx:1363`'s comment says *"Four controls, four actions"* and the case reads two
   labels. Two controls, two actions; the other two are on the screen it did not cover. Item 1 makes
   the sentence true.
3. Clean the four consecutive blank lines before *"a purge that partly refused raises a warning
   toast"* — debris from the read-modify-write race, and nothing enforces it because the frontend has
   no formatter.
4. The flake set is wider than the two cases disclosed. A third — *"the consumers tab prints
   host:port"* — took 7829 ms under load against vitest's 5 s default and passed in every other run.
   Give the slow cases an explicit timeout or say which they are; a wave graded on this suite should
   not be guessing.
5. The Acceptance line *"against images built from this tree"* has still not been executed by anybody
   for this package: two consecutive waves drove the already-running quickstart. Build and run it.

**Acceptance**
```
pnpm -C frontend test packages/feature-topics
pnpm -C frontend typecheck
pnpm -C frontend build-storybook          # then serve :6017 and:
node frontend/scripts/a11y-stories.mjs 'screens-topic|topics-'
pnpm -C frontend e2e                       # against images built from this tree, and say which image id
```
Required cases, by name: **each control on the topic list screen is gated on its own action**, with
all three arrangements constructed; a purge that partly refused raises a warning toast; a
`?showInternal=1` is not honoured.
**Mutation line:** `purgeBlocked`'s action → `Actions.TopicDelete`. Name the case that goes red —
there is none today. **And a green one:** mutate the bulk bar's selection cap and report what happens.

---

## W6-09 — The published documents, two false rows, and a checker that can be neutered without moving a number

**Owns**
```
docs/api/**
frontend/packages/api/src/schema.d.ts
frontend/packages/api/README.md
docs/FEATURE_MATRIX.md
README.md
TECH_DEBT.md
DECISIONS.md
docs/adr/ADR-012-microfrontend-loading-strategy.md
docs/adr/ADR-048-solidjs-typescript-vite-frontend.md
scripts/feature-matrix-check.sh
tools/error-codes/**
```
**depends on W6-01 and W6-03.**

**Contract.** Two packets add endpoints, so the merged documents and the browser's generated types
move and `./scripts/feature-matrix-check.sh` is red on arrival. Regenerate; write no Scala and no
TypeScript by hand. Today's figures, measured at integration with `jq`: **54 paths, 65 operations,
150 schemas, `X-Kui-Principal` on 50 operations over 39 paths, `X-Csrf-Token` on 20**, and the script
prints `105 claims checked, all true`.

**Do**
1. Regenerate the three documents and update both `<!-- checked: merged-document -->` regions to the
   counts the regenerated files carry. Derive them with `jq`, not from a packet's note.
2. **The ungated rules this packet owns, and they are last wave's rule reopened one level up.** Wave 5
   gave every section a per-section **count** and proved four deletions fail. The count is still
   blind to a comparison that stops comparing, and three of these were demonstrated at verification:
   * replacing the bare call `reconcile_manifests` (line 433) with `:` prints `105 claims checked,
     all true` and exits 0 — the disk-vs-named-list reconciliation contributes **no assertion**, so
     the whole check is deletable with no number moving. Worse, with it gone a package can be dropped
     from the `manifests` array and the run still prints 105, because `sort -u` makes its dependencies
     indistinguishable from the others';
   * neutering any one comparison while leaving its `assertions=$(( assertions + 1 ))` in place — the
     `X-Csrf-Token` branch was the one run — lets ADR-048 publish `X-Csrf-Token on 99 operations`
     with the run green at 105;
   * deleting the milestone table's `total_line_seen` guard moves nothing;
   * and `close_section`'s own comparison can be floored back to `(( counted >= 0 ))`, after which the
     packet's own named mutation prints `101 claims checked, all true`. **Nothing in the repository
     fails when the rule this packet shipped is reverted.**
   The fix is not a fifth count. It is that a section must publish **what it compared**, not how many
   times it incremented a variable — a checksum over the compared pairs, or an assertion registry the
   script prints and a fixture pins. Prove all four.
3. `docs/FEATURE_MATRIX.md`'s **CG-005 is false in the tree today**, in both halves it names: it
   asserts no file under `frontend/` contains *"Forget this group's offsets on one topic"* and that a
   repository-wide search for `deleteOffsets` finds one reference, its own declaration.
   `feature-consumers/src/GroupDetail.tsx:171` carries that exact heading and `GroupRoute.tsx:94`
   calls the function — verified at integration. The row moves to `COMPLETE` on W6-07's browser
   evidence, and the prose at `:106` and `:542` moves with it.
4. **MT-002 names two classes that no longer exist.** The cell says *"`PrometheusThroughputScrape`
   fetches it inside `callTimeout`; `ThroughputScrapeLoop` runs one fibre per configured cluster"*;
   the tree has `PrometheusBrokerScrape.scala` and `BrokerScrapeLoop.scala`, and
   `grep -rn` over `services/` finds neither old name outside one test header. The same packet
   corrected that cell's closing sentence last wave and left the two names above it.
5. **`README.md:91` tells a user the dashboard's Traffic tab draws all five metrics reads.** Two of
   them draw nothing today (W6-02's headline) and CL-004 is held at `IMPLEMENTING` for exactly that
   reason. The row was right and the user-facing document was moved on the existence of the code —
   which is the failure the wave plan names as *"a row moved on stories is how this matrix drifted
   before"*, applied to README instead of to the row. Move it back or move it forward on W6-02's
   evidence; do not leave the two disagreeing.
6. **`DECISIONS.md` has no gate and ADR-053 is about to walk into the same hole.** ADR-052's row was
   missing, was repaired by hand, and `grep -rn 'DECISIONS.md'` over every `.sh`, `.yml`, `.mill` and
   `.scala` in the tree returns nothing: no script, workflow, build target or suite reads the ADR
   index. Add the check to `feature-matrix-check.sh` — every `docs/adr/ADR-*.md` has a row and every
   row an ADR — and land W6-01's ADR-053 row through it rather than beside it.
7. **The regeneration bought the browser nothing for the five metrics endpoints and that is a
   finding, not a footnote.** `LatencyResponse`, `ThroughputResponse`, `TopProducersResponse`,
   `RecordSizeResponse` and `RequestHandlersResponse` each carry a `data` property with **no
   sub-schema**, so `schema.d.ts` types all five payloads as opaque and
   `frontend/packages/shell/src/overview/metrics.ts` hand-writes every wire shape — which is precisely
   how two of them came to be wrong. `frontend/README.md:33` still says of `@kui/api` *"Nothing
   hand-written mirrors a server type."* Either the Tapir schemas gain their `data` shapes (a
   `needsOutsideOwnership` row to W6-02, whose DTOs they are) or that sentence is corrected and
   `TECH_DEBT.md` carries the gap. Do not leave both standing.
8. `TECH_DEBT.md`'s TD-023: record wave 5's numbers rather than a list — **33 new ungated rules
   shipped by nine building packets, 27 of them undisclosed; 72 found and 65 closed by three
   adversarial packets over 156 scorable mutations, a 46% rate on a sample chosen by reading
   comments; 7 left open and named**. Assign each of the seven to its packet in this wave. And record
   the two live **production defects** wave 5 found and could not repair, both now owned:
   `services/topic` and `services/message`'s `MutationGuard` auditing a cancelled mutation as
   `Failed` (W6-A1), and `BrokerList`'s empty-list `totalLeaders` of `0` (W6-A3).
9. `tools/error-codes`: two disclosed holes remain. `BrowserConstants.vocabulary` can drop
   `mismatchedFallbacks(declaredFallbacks)` entirely with `./mill tools.errorCodes.test` at 291/291
   and `frontend.apiConstants --check` green, because the generated bytes do not move and the three
   new cases only exercise the pure functions; and `BrowserConstantsMain`'s `case Left(problem) => …
   sys.exit(1)` can become `case Left(_) => ()` with everything green. Close the seam, not the
   function.

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
**Mutation line:** neuter one comparison in `check_document_region` while leaving its `assertions`
increment, then publish a false figure in ADR-048, and show the run failing rather than printing
`105 claims checked, all true.` **And a green one:** delete a `docs/adr/ADR-*.md` row from
`DECISIONS.md` after item 6 and report what happens.

---

## W6-10 — The ninth container, and three assertions that assert nothing

**Owns**
```
deployment/**
.github/workflows/ci.yml
apps/allinone/**
libs/config/**
```
**depends on W6-01's Mill module and `deployment.docker.alerts` image target.**

**Contract.** `ci.yml` derives its image list from `deployment/compose/docker-compose.yml` —
`grep -oE 'image: kui-[a-z-]+:'`, `kui-frontend` excluded — so adding the alerts container to the
compose file adds it to the build with nobody remembering anything, and `smoke.sh` compares the
gateway's **contract** set against the containers, so a contracted ninth service that is not a
container fails loudly and in the safe direction. Both properties were verified in wave 3 and again in
wave 5; use them rather than editing a list.

**Do**
1. **The compose failure that has never been explained, and house rule 9 says this is not closed.**
   Wave 4 reported `FAILED: buckets carrying a measured rate was 'no' after 90s`, deterministically,
   twice. Wave 5 was asked to reproduce it and explain the mechanism before patching, and reported —
   correctly, and after three consecutive `PASSED` runs from a torn-down stack — that **it does not
   reproduce**. So the assertion is green, the `depends_on` is in, and the mechanism is unknown. That
   is a better state than a false explanation and it is not a closed item: run the stack **without**
   the `depends_on` and record what happens, because either it fails (and the mechanism is start
   order, which is now demonstrable) or it passes (and the wave-4 failure was something else, which
   the README paragraph currently claims to explain). Write down whichever answer you get.
2. The alerts container: its compose entry in both stacks, its `depends_on`, its healthcheck, its
   configuration in `kui-service.yaml` and the quickstart's, its `AllInOneWiring` entry, and the
   `smoke.sh` assertion that its feed answers. **`AllInOneWiringSuite`'s startup-log string and
   mounted-path set are yours and they broke last time a service was added.**
3. **A claim about a defence nothing makes, in a file that says it is checked.**
   `deployment/quickstart/kui-quickstart-auth.yaml:28-32` says `ShippedConfigurationSuite` loads the
   file through the real loader so that *"a key spelled wrongly, an RBAC role naming a cluster that is
   not here, a TOPIC permission with no value, or a `callTimeout` that is not shorter than
   `kui.metrics.scrapeInterval`"* all fail a unit test. Two of those four are true: removing a TOPIC
   permission's `value` and lengthening `callTimeout` each redden the suite. The RBAC one does not —
   pointing both roles at `no-such-cluster` leaves `./mill libs.config.test` at 395/395 with the suite
   13/13 green. Make it true or say which two the sentence covers.
4. **The registry still cannot notice a file nobody listed, which is the defect its own scaladoc was
   written for.** `ShippedConfigurationSuite.scala:37` is a hand-written `List` and nothing compares
   it to disk: a new `deployment/examples/*.yaml` full of invented field names loads and the suite
   stays at 13. `kui-service.yaml` went unread for three milestones because somebody forgot a row.
   Reconcile the list against `deployment/**/*.yaml` the way `feature-matrix-check.sh` reconciles its
   manifests, with the same both-directions failure.
5. **`smoke.sh:402-406`'s read-back step is ungated and its stated necessity is false.** Its comment
   says the consume is *"the only way to make the broker publish a FetchConsumer percentile"*;
   deleting the whole block leaves a full run `PASSED` with all twelve line shapes served and the
   `FetchConsumer` p99 still there — and the script still prints *"500 records produced to
   smoke-traffic and read back"*, which is now a claim the run cannot detect is false.
6. **Three more sentences in the same two files that measurement contradicts.** `smoke.sh:360-367` and
   `deployment/compose/README.md` say that on the idle compose broker *"nothing else the dashboard
   needs is there at all… no RequestMetrics{request=Produce} bean, because nothing has produced"*. On
   a fresh idle stack the exporter serves `Produce` and `FetchConsumer` p99 at `0.0`, both purgatory
   sizes, and both idle ratios — all **twelve** shapes present. Kafka creates `RequestMetrics` beans
   eagerly per ApiKey; only the per-topic slice is lazy. The traffic step earns its place for exactly
   two assertions — the per-topic line and `bytes_in > 0` — and the files claim much more. The README
   also says *"thirteen"* line shapes where the script prints twelve, and three of the thirteen are
   `_total` counters the ruleset file itself says the reader does **not** parse. And `smoke.sh:492-495`
   justifies its empty-exposition guard with *"a loop over an empty body runs zero times and reports
   that every family was found"* — the loop is over the twelve fixed patterns, not the body, so with an
   empty body every `grep -qE` fails and the step fails loudly with or without the guard. The guard
   gives a better message; the hazard it names cannot occur.
7. **Top producers will rank an internal topic first, and nobody has written the decision down.** The
   per-topic rule is `topic=(.+)` with no exclusion and nothing downstream filters internal topics; on
   the live quickstart the exporter serves
   `…bytesinpersec_oneminuterate{topic="__consumer_offsets"} 156.85` against `{topic="orders.v1"} 0.14`,
   so the card would report `__consumer_offsets` as the top producer by a factor of a thousand — in a
   product whose own `kui.topics.internalPrefix: "_"` exists to keep those rows off the topics screen.
   The filtering belongs to the reader rather than the ruleset, so this is a `needsOutsideOwnership`
   row to W6-02 **and** a paragraph in the ruleset file recording the consequence of `.+`.
8. `smoke.sh:560`'s bucket assertion is still `select(.bytesInPerSecond != null)` and sees one family
   of the five the reader reads. Wave 5 closed the naming hole at the exporter, which is a defensible
   reading of the brief; a KUI-side regression that stopped parsing `messagesinpersec` still leaves the
   compose job green.
9. The formatter run and the `libs/config` reflow are done and `checkFormat` is green tree-wide for
   the first time in five waves. Keep it that way: **no behaviour in `libs/config` changes this wave
   either**, beyond item 4's suite.

**Acceptance**
```
./mill apps.allinone.test
./mill libs.config.test
./mill checkArchitecture
./mill apps.allinone.checkFormat + libs.config.checkFormat
docker compose -f deployment/compose/docker-compose.yml config -q
# images built from the working tree by the derived list, then:
docker compose -f deployment/compose/docker-compose.yml up -d --wait
curl -sf localhost:8090/ui/ >/dev/null && curl -sf localhost:8080/api/v1/health/ready
./deployment/compose/smoke.sh            # three consecutive runs, from a torn-down stack each time
./deployment/quickstart/quickstart.sh    # then curl every metrics and alerts endpoint
```
**Mutation line:** delete the alerts container's `depends_on` — or, for item 1, the metrics one — and
report what the stack does, with the patch and without it. **And a green one:** add a shipped
configuration file nobody listed and report which of the four things above notices.

---

## W6-A1 — Adversarial: teardown, cancellation, and two live defects nobody owned

**Owns**
```
services/cluster/*/test/**
services/topic/*/test/**
services/consumer/*/test/**
services/message/*/test/**
services/identity/*/test/**
services/topic/application/src/kui/topic/application/MutationGuard.scala        (named exception)
services/message/application/src/kui/message/application/produce/MutationGuard.scala  (named exception)
```
Two production files are named exceptions and nothing else is. Wave 5's A1 found a real defect in both,
could not repair it, and filed it — and it is still in the tree, which is the argument for the
exception rather than against it.

**Contract.** This packet builds nothing. Its deliverable is **cases** and its report is a measurement.
Run in a `git worktree`, per house rule 14. Wave 5's A1 mutated 34 rules, scored 33 and found 16
ungated — 48% — and closed 13. **Its own strongest finding is the shape you start from:** *"every
cancellation branch I mutated in every service survived."*

**Do**
1. **Repair the classification, in both files, and land the cases.** `MutationOutcome.Unknown`'s
   scaladoc in `libs/security-core/.../AuditSink.scala:83-88` says *"Kafka gives no guarantee that it
   was not applied, so a record claiming either would be a lie."* `services/topic`'s
   `MutationGuard.scala:174-178` and `services/message`'s `:141-145` both audit a **cancelled**
   mutation as `Failed`, which is exactly such a claim: it tells an operator a topic delete or a
   produce did not happen when it may well have. `services/consumer`'s copy writes `Unknown` and
   argues the rule verbatim. Three implementations of one classification, two of them wrong. Change
   the `Outcome.Canceled()` arm in both to `Unknown`, leave the `Errored` arms as `Failed`, and land
   a case in each in the shape of `services/consumer`'s *"a cancelled mutation is recorded as unknown,
   and never as a success or a failure"*.
2. **The one survivor A1 could not close.** `services/cluster/application/.../ClusterSnapshots.scala:411-413`:
   inserting `uptime.record(now, false) >>` before `wasOffline.set(true) >> raiseError` in `load`'s
   `Left(error)` branch leaves 2633 cases green, so KUI's own outage would be recorded as the
   cluster's. It could not be closed because the only observable is `ControllerUptime.percent`, which
   refuses to compute until its 6 h window is full, and `load` is private while `refreshOne` does not
   touch the window. **The seam is yours to ask for, in `needsOutsideOwnership`, or to reach through a
   `Tuning` with a short window in the rig** — the second is a test-only change and is preferred.
3. **Then go looking, and aim where the data says.** At least **thirty** further mutations in these
   five services, weighted to the three clusters wave 5's A1 identified rather than sampled evenly:
   teardown and cancellation branches (every one it mutated survived); classes whose only consumer is
   a composition root; and mapping code whose only consumer is a route, where the route suite asserts
   a status code and the field-level rule underneath — a `None` that must not become a `0`, a sort
   order, a four-state discriminator — is not what a route suite looks at.
4. **Report the rate, and say whether the shape held.** Wave 4 found 29% at random; wave 5's A1 found
   48% on a comment-guided sample. If a *targeted* sample now finds materially more than a
   comment-guided one, that is the number wave 7 needs.

**Method, and it is not optional.** One mutation at a time, applied to source, full suite run, reverted
from bytes you saved yourself before the next. `-Werror` will reject some mutations as
unused-parameter or unreachable-case; **a compile failure is not a red** — re-cut it so it compiles and
only then score it, and watch the summed test **total** on every run, because a failed module compile
aborts the rest and Mill still prints `SUCCESS` over a truncated count. Finish with
`git status --porcelain` empty over every path you touched and a clean suite run recorded.

**Acceptance**
```
./mill libs.__.test + services.cluster.__.test + services.topic.__.test + services.consumer.__.test + services.message.__.test + services.identity.__.test
./mill checkArchitecture
./scripts/run-tests.sh
```
Note the `+` — house rule 8. Required: a case named for each rule closed, and a table of every mutation
with its verdict and the suite that produced it.
**Mutation line:** `services/topic`'s `MutationGuard` cancelled arm back to `Failed`. Name the case
that goes red after your work; today there is none in the repository.
**And a green one:** this packet is all green ones. Report the ratio.

---

## W6-A2 — Adversarial: composition roots, and six modules that declare a test module and ship no test

**Owns**
```
libs/cache/test/**   libs/contracts-core/test/**   libs/filter/test/**
libs/http/test/**    libs/kernel/test/**           libs/observability/test/**
libs/security-core/test/**   libs/serde/test/**    libs/testkit/test/**
                          — every libs test tree EXCEPT libs/config/**, which is W6-10's whole
services/schema/**
```
`libs/config/**` is excluded entirely. `services/schema/**` is owned whole — source included — because
three of wave 5's A3 findings need a **seam** cut in production code and filing them a second time is
how wave 5 left seven holes standing.

**Contract.** Wave 5's A3 mutated 76 rules in `services/schema`, scored 75, found **39 genuinely
ungated of 72** — 54%, nearly twice the house rate — and closed 36. Its finding is the sharpest in the
wave and it is your subject: **`RegistryCredentials.scala` was 5 of 5 green and had no suite at all;
`SchemaWiring.scala` was 4 of 4 green because `services/schema/app` had a test module declared in
`build.mill` and no test source file.** `scripts/run-tests.sh` still names **six** such modules out
loud — `services.identity.api.test`, `services.identity.app.test`, `services.message.app.test`,
`services.metrics.contract.jvm.test`, `services.schema.contract.jvm.test`, `services.topic.app.test` —
and counts each as zero cases without failing.

**Do**
1. **The six modules with no test sources.** For each, decide and act: it gets a suite, or it is
   deleted from `build.mill` (a `needsOutsideOwnership` row to W6-01, which owns that file). A module
   that resolves as a test target and contains nothing is a green gate over an unmeasured file, and it
   is how four constants in a composition root survived two milestones. Four of the six are in your
   `Owns` or in libs; the two under `services/identity` and `services/message` belong to W6-A1's tree —
   name them to it rather than reaching across.
2. **The three `services/schema` rules A3 could not close, each needing one seam.**
   `SchemaWiring.scala:227-253`'s `tokenBackendFor` pointed at `settings.urls` instead of the
   configured issuer leaves 141/141 green — a client secret sent to the wrong system, argued at length
   in the comment above it — and closing it needs `upstreamConfig` and the token endpoint's
   `UpstreamConfig` to be `private[app]` so a suite can read `name` and `urls`.
   `SchemaWiring.scala:267-291`'s `startupLog` can lose its *"no cluster configures a registry"* INFO
   line with 141/141 green, and its own docstring says that line *"matters as much as the others"*.
   And `RegistryCredentials.scala:164-179`'s token cache can be made never to expire, which needs
   clock control: `cats-effect-testkit`, which `libs/cache`'s test module already carries and which
   **W6-01 adds to `services.schema.infrastructure.test` for you** — it is in W6-01's item 7, so ask
   for it there rather than editing `build.mill`.
3. `services/schema/contract/.../SchemaDtos.scala`: `RegisterSchemaRequest` and
   `CompatibilityCheckRequest` both decode `schemaType` with `getOrElse("AVRO")` while the published
   document lists it in `required`, so the browser is told to send a field the server does not need.
   The fix — `.modify(_.schemaType)(_.copy(isOptional = true))`, or an `Option[String]` field defaulted
   in the mapper — moves `services/schema/api/openapi.json`, which is yours, and then
   `docs/api/**` and `schema.d.ts`, which are **W6-09's**. Sequence it with W6-09 and say so.
4. **Then go looking, in `libs/`, at the shape wave 5 named.** At least **twenty-five** mutations,
   weighted to classes whose only caller is a composition root — `libs/observability`'s sinks and
   selectors, `libs/http`'s `principal` package — and to teardown. `SseWire.parseFrame`'s
   `lines.filterNot(_.startsWith(":"))` is behaviour-preserving to delete and its scaladoc reads as
   though it were load-bearing: score it as inert, and repair the comment.
5. **Report the rate**, and separately for `libs/` and for `services/schema`, because a service that
   an adversary has already swept once is the first data anybody has on whether a second pass pays.

**Method** as W6-A1's, and in a `git worktree`.

**Acceptance**
```
./mill services.schema.__.test
./mill libs.__.test
./mill checkArchitecture
./mill services.schema.__.checkFormat
./mill services.schema.api.openApi && ./mill services.schema.api.openApiCheck
./scripts/run-tests.sh                     # the "no test sources" list must be shorter
```
**Mutation line:** `SchemaWiring`'s `tokenBackendFor` aimed at `settings.urls`. Name the case that
goes red after your work; today there is none.
**And a green one:** this packet is all green ones. Report the ratio, split by area.

---

## W6-A3 — Adversarial: the rendering layer, where sixteen of seventeen survivors were

**Owns**
```
frontend/packages/feature-clusters/**
frontend/packages/feature-schemas/**
frontend/e2e/features.spec.ts
frontend/e2e/brokers.spec.ts
```
Source as well as tests, for the reason wave 5 gave: the rules are in the source and the packet that
wrote them is not in this wave. **Mutate first, then close. Build no feature.**

**Contract.** Wave 5's A2 ran 52 mutations, scored 51, found 17 ungated and closed 16 — and its
finding is the one that tells you where to aim: **every rule in `feature-clusters/src/model.ts` is
gated, 12 of 12, and sixteen of the seventeen survivors are in a `.tsx` file or the one `.ts` function
a `.tsx` calls.** The pure-data rules are pinned and the rendering rules are not, in both packages.

**Do**
1. **The production defect A2 found and did not repair, and it is the milestone's central rule
   broken.** `frontend/packages/feature-clusters/src/BrokerList.tsx:130-135`: `totalLeaders` is a
   `reduce` and `reduce` over an empty list is `0`, so an answered-but-empty broker list draws a bare
   **`0`** on the TOTAL LEADERS tile with no chip — measured by mounting
   `<BrokerList brokers={[]} …>` in the package's own harness. The consequence is that the chip's
   first arm, `props.brokers.length === 0 ? {text: "no broker answered", tone: "attention"}`, is
   **dead code**: it is only evaluated when `totalLeaders()` is `undefined`, which requires at least
   one broker carrying a null count, which means the list is not empty — so deleting it leaves the
   suite green and always will. The edit is
   `if (props.brokers.length === 0 || props.brokers.some(b => b.leaderPartitions === null)) return undefined;`
   and then the arm is reachable and a case can pin it.
2. **Then go looking, in the rendering layer specifically.** At least **twenty-five** mutations across
   the `.tsx` files of both packages — every `<Show when={…}>` whose condition is falsy at zero, every
   `?? 0` inside a guard that a sibling condition keeps unreachable, every branch that decides a
   sentence. Wave 5's A2 closed the ones it found; the ratio says the shape is not exhausted.
3. `docs/FEATURE_MATRIX.md`'s **SR-005** moved on wave 5's browser evidence and the four cases that
   carry it are yours: keep them green, and note that each run registers three scratch subjects
   because this product publishes no subject delete — `kui-e2e-compat-*`, plus one hand-registered
   `kui-a2-probe-1788750105-value` that is in the quickstart registry and cannot be removed through
   the product. Say in the report whether that is acceptable or whether the specs should share one
   subject.
4. **Report the rate**, and say whether a second adversarial pass over a package that has had one
   finds materially less. That number decides whether wave 7 re-sweeps or moves on.

**Method** as W6-A1's, and in a `git worktree`. **Do not** run `git stash` in this repository:
wave 5's A2 did it by accident and swept forty-six files belonging to six other packets.

**Acceptance**
```
pnpm -C frontend test packages/feature-schemas packages/feature-clusters
pnpm -C frontend typecheck
pnpm -C frontend build-storybook          # then serve :6017 and:
node frontend/scripts/a11y-stories.mjs 'schemas-|screens-schema|clusters-|screens-broker'
pnpm -C frontend e2e                       # against images built from this tree
```
Required cases, by name: **a cluster whose broker list came back empty says no broker answered and
never draws a zero**; and one per rule closed.
**Mutation line:** restore `totalLeaders` to the `reduce` over an empty list. Name the case that goes
red after your work; today there is none.
**And a green one:** this packet is all green ones. Report the ratio.

---

## Where the packets meet

Every pair below shares a boundary. The contract is stated on both sides so that neither has to read
the other's diff.

| Pair | The contract both sides code against |
| --- | --- |
| W6-01 ↔ W6-03 | The alerts endpoints: `Section`-wrapped reads and **one write** under `/api/v1/clusters/{clusterId}/alerts/…`, proxied the way metrics already is. W6-03 adds **no gateway path of its own** — `OpenApiMergeSuite` pins a hard-coded list over `gatewayDoc + clusterDoc`. The write moves `MergedDocumentShapeSuite`'s `writes.size` and the ninth service moves `ServiceContractsSuite`'s id `Set`; both are W6-03's and both are named in the guard table because both broke last time. |
| W6-01 ↔ W6-04 | The event shape, decided in **ADR-053** and read there rather than off the endpoint: an opened-at, a severity, a category, a resolution, an open count and a per-principal read marker. Severity chooses the tone, category chooses the glyph. |
| W6-01 ↔ W6-10 | The Mill module and `deployment.docker.alerts` are W6-01's, in `build.mill`, which no other packet touches. The compose entries, the healthcheck, the configuration files and the `AllInOneWiring` entry are W6-10's. One file each, no shared line. |
| W6-01 ↔ W6-09 | ADR-053's row in `DECISIONS.md`. W6-01 states the row it needs; W6-09 lands it **and** lands the gate that stops ADR-054 from repeating ADR-052's disappearance. |
| W6-01 ↔ W6-A2 | One line of `build.mill`: `cats-effect-testkit` on `services.schema.infrastructure.test`. W6-A2 cannot edit that file and cannot close a clock-dependent rule without it. |
| W6-02 ↔ nobody | **Both ends of the metrics wire are one packet, and that is the point.** The DTO, its decoder and the golden between them. |
| W6-02 ↔ W6-09 | The five metrics `data` payloads have no sub-schema in the published document, so `schema.d.ts` types them as opaque. Either W6-02 gives the Tapir schemas their shapes — after which W6-09 regenerates and `frontend/README.md:33` becomes true again — or W6-09 corrects that sentence and files the gap. Not both, and not neither. |
| W6-04 ↔ W6-05 | Two lines, both in the kernel and both shipped by W6-05 whether or not W6-04 asks, exactly as `RingGauge.goodDirection` was handled last wave. `FeatureId` at `frontend/packages/kernel/src/feature/registration.ts:38` gains `"alerts"`; and `frontend/packages/kernel/styles/index.css` gains the import naming `feature-alerts`'s one stylesheet, at the number W6-04 states, because `build-tests`'s `CssReferencesSuite` requires every stylesheet on disk to be named there exactly once and `build-tests/**` is owned by nobody. |
| W6-04 ↔ W6-06 | The alerts screen is a **route and a nav destination**, not a dashboard tab — decided here so that neither packet discovers it. W6-06 adds the route in `routing/**`, the nav row in `nav/**` and the `FeatureId`-keyed badge row in `App.tsx`; W6-04 ships what is behind it, plus the registration seam in `src/features/**` and `shell/package.json`, which are W6-04's precisely because a `load` thunk and its dependency are one idea in two files. |
| W6-04, W6-06 ↔ W6-05 | The alerts feed store lives in `@kui/kernel`. The bell is in the shell's chrome and the card is in a feature package; a feature may not import the shell, nothing may import `@kui/shell`, and the shell must not **statically** import a feature or `bundle-shape.mjs` fails. The kernel is the one place both can read, which is why the capabilities store is there too. |
| W6-02 ↔ W6-04, W6-06, W6-07, W6-08, W6-A3 | No packet fills a card from data it happens to hold. A rate computed from a browse is not a broker metric; an alert count recomputed from a page is not the open count. |
| W6-06 ↔ every feature packet | The shell's route table gains exactly one address, for alerts, and nothing else. A feature reaches its own pages through `kui.paths.*`; no feature edits `routing/**`. |
| W6-09 ↔ W6-07 | `CG-005`'s state. W6-07 drives the forget control in a browser against an image built from this tree; W6-09 moves the row on that evidence and repairs the two sentences that are false in the tree today. A row moved on stories is how this matrix drifted before. |
| W6-09 ↔ W6-A1, W6-A2, W6-A3 | The three adversarial reports are the input to TD-023. Each hands W6-09 a table — rule, mutation, verdict, suite — and W6-09 records the totals, the ratio and the seven wave-5 items each is inheriting. |
| W6-10 ↔ W6-02 | The exporter's per-topic rule is `topic=(.+)` and nothing filters internal topics, so Top producers would rank `__consumer_offsets` first by a factor of a thousand. The filtering belongs to the **reader**: W6-10 records the consequence in the ruleset file, W6-02 decides and implements it. |
| W6-10 ↔ W6-03 | `ServiceContracts.byService`. W6-03 owns the Scala file; `smoke.sh` scrapes it with `sed`/`grep` to derive the contract set, and a ninth contracted service that is not a container fails the preflight before a container starts. |
| every packet ↔ the guard files | House rules 1, 2 and 3 keep `build-tests/**`, `10-tokens.css` and `constants.generated.ts` out of reach. If your change needs one of them, your change is shaped wrongly, and that has been true for five waves. |

## The partition, checked

**Backend.** `services/alerts` is W6-01's and is new. `services/metrics` is W6-02's, `services/gateway`
W6-03's, `services/schema` **W6-A2's whole, source included**. `services/cluster`, `services/topic`,
`services/consumer`, `services/message` and `services/identity` have their test trees owned by W6-A1
and their production source owned by nobody — with **two named exceptions**, the two `MutationGuard.scala`
files W6-A1 repairs, listed file by file in its `Owns` rather than as a directory. `libs/**` is owned by
nobody except the nine test trees in W6-A2's block and `libs/config/**`, which W6-10 holds whole.
`libs/cache`'s `SeriesWindow` is complete and W6-01 *uses* it; a fourth refusal added there is a sign
the event store is being written in the wrong layer.

**Frontend.** Inside `frontend/packages/shell/`, no packet owns `**`. `src/overview/`, `src/pages/`,
`styles/32` and `styles/37` are W6-02's; `src/features/` and `package.json` are W6-04's; `src/App.tsx`,
its two test files, `src/chrome/`, `src/nav/`, `src/data/`, `src/routing/`, `src/index.ts` and the other
seven stylesheets — 30, 31, 33, 34, 35, 36, 38, named one by one — are W6-06's. `src/messages.ts`,
`src/bootstrap.ts`, `src/health.ts` and `src/index.tsx` are **owned by nobody and need no edit**: they
are the boot path and nothing here changes it. **`src/features/` has an owner for the first time**,
because a sixth feature registration is a four-file handoff and wave 3's rule is that one packet owns
both ends of a handoff that small.

`frontend/packages/kernel/**` is W6-05's for the second wave running — the first tree to keep an owner
across waves, and the reason is that wave 5 gave it one and it still shipped five ungated rules.
`frontend/packages/feature-alerts/**` is new and W6-04's. `feature-topics` → W6-08,
`feature-consumers`/`feature-messages` → W6-07, `feature-clusters`/`feature-schemas` → W6-A3.
`frontend/e2e/` is allocated **per file**: `traffic.spec.ts` → W6-02;
`shell.spec.ts`/`dashboard.spec.ts`/`search.spec.ts` → W6-06; `consumers.spec.ts`/`messages.spec.ts` →
W6-07; `topics.spec.ts` → W6-08; `features.spec.ts`/`brokers.spec.ts` → W6-A3; `alerts.spec.ts` → W6-04
and new. `fixtures.ts`, `globalSetup.ts`, `tsconfig.json` and `playwright.config.ts` are unowned and
need no edit — but note that `e2e/tsconfig.json` does not resolve `@kui/shell`, which is why
`traffic.spec.ts` retypes a fragment by hand; whether to add the reference is a
`needsOutsideOwnership` question, not an edit.

`frontend/tsconfig.json` is **W6-04's** this wave, because adding the sixth package's reference is its
job and it should add `feature-schemas`' missing one in the same edit.
`frontend/packages/api/src/schema.d.ts` and `README.md` are W6-09's, so that directory is not owned as a
tree; `constants.generated.ts`, `src/index.ts`, `src/probes.ts` and `src/types.test.ts` are unowned and
need no edit. `frontend/vitest.config.ts`, `frontend/vite.config.ts` and `frontend/package.json` are
unowned and need no edit — `vite.config.ts` names chunks generically and `bundle-shape.mjs` reads its
roster from the filesystem, so the sixth feature package is picked up with no edit anywhere.
`frontend/scripts/a11y-stories.mjs` is W6-05's; `boundaries.mjs` and `bundle-shape.mjs` are unowned and
need no edit.

**Build and deployment.** `build.mill` is **W6-01's alone** and is the only file three packets want:
W6-10 needs the image, W6-A2 needs a test dependency, and both ask through
`needsOutsideOwnership` rather than editing it. `.github/workflows/ci.yml`, `deployment/**` and
`apps/allinone/**` are W6-10's alone. `scripts/run-tests.sh` is unowned and needs no edit — it derives
its module list from `./mill resolve __.test` and computes its counts at run time, which is why a ninth
service simply appears in it. `scripts/feature-matrix-check.sh` is W6-09's; there is no other file in
`scripts/`. `build-tests/**` is unowned and must not be edited.

**Documents.** `docs/api/**` and eight named documents are W6-09's; `ARCHITECTURE.md` is W6-03's;
ADR-053 is W6-01's new file; ADR-052 is **W6-02's**, because two of its decisions — the fourth `Section`
state and the internal-topic filter — are settled by that packet's work. Every other ADR,
`DEPENDENCY_MATRIX.md`, `docs/ROADMAP.md`, `docs/ROADMAP-SOLID.md`, `docs/testing.md`,
`docs/api/error-codes.md`, `docs/operations/**` and `docs/domain/**` are unowned and need no edit.
`docs/api/error-codes.md` is generated and its generator (`tools/error-codes/**`) is W6-09's.
`research/design/SCREENS-V4.md` is **W6-06's**, for one paragraph and nothing else; the rest of
`research/` is unowned and needs no edit. `docs/plan/ROADMAP.md` is **unowned this wave**: it records a milestone's shape, and neither closing M7
nor opening M8 changes either shape. `docs/plan/WAVE-06.md` is this file; the wave's closing act deletes
it.

**Three nesting checks, done rather than assumed.** `docs/` is not owned as a tree — W6-09 owns
`docs/api/**` and eight named documents, W6-03 one, W6-01 and W6-02 one ADR each, and nothing above them
is claimed. `frontend/packages/shell/` is not owned as a tree — W6-02, W6-04 and W6-06 divide `src/` by
named subdirectory and `styles/` by named file, with four `src/` files left over that are named above
rather than left to inference. And `services/cluster/` and its four siblings are not owned as trees:
W6-A1 holds `*/test/**` plus two files named individually, so `services/cluster/api/src/` belongs to
nobody and A1 may not commit to it. The only two blocks that could have overlapped — W6-A2's
`libs/*/test/**` and W6-10's `libs/config/**` — are resolved by excluding `libs/config/**` from A2
entirely, stated in both packets. No packet's `Owns` block contains another packet's file, and no two
blocks name the same path.

## What wave 7 will be, so nobody builds it here

Wave 7 opens **M9** — `services/connect` and `services/ksql`, two more services in the ADR-041 shape,
and the only milestone left that adds any. Their vocabulary shipped in wave 1 and is still unused:
`ConnectName`/`ConnectorName`/`TaskId`, `ErrorCode.ConnectRebalancing`, the whole Connect RBAC closure,
`ConnectClusterSettings` and `KsqlSettings` per cluster, and the non-altering `KsqlView` that
`KsqlExecute` implies — so a read-only cluster listing ksqlDB objects and refusing statements is
already asserted in `RbacLawsSuite`. Three orphan kernel components have no feature behind them. Until
those services exist, ADR-032's `not_configured → hidden` rule is the correct rendering and the
`ECOSYSTEM` rows must not be faked.

It also carries **M10**, which is a closing wave rather than a building one, and one decision this wave
must not pre-empt: the ksqlDB result region, which no capture shows.

**And on the ratio.** Wave 5 kept 3:1 and ended 44 rules better gated, the first wave in this project
to end ahead. Wave 6 keeps 3:1 and adds the change that costs nothing: a verification pass owns the
test tree of what it verifies, so a hole found is a hole closed. If wave 6's adversaries report a
materially lower rate than 46% **and** the builders' own disclosure rate rises above a third, the ratio
can fall to 4:1 in wave 7. If the rate holds and the verifiers' landed cases are what moved the number,
then the answer was never more adversaries — it was that finding a hole and closing one had been two
different jobs, and nobody should be told a fourth time to disclose their own.
