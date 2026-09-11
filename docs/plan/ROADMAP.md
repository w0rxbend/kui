# The plan: from here to the twenty-three screens

**What this is.** The milestone order that turns this repository into the product drawn in
`screens/` and read in [`research/design/SCREENS-V4.md`](../../research/design/SCREENS-V4.md).
It is the only long-lived document in `docs/plan/`; see [README.md](README.md) for how the
directory works.

**It supersedes `docs/ROADMAP-SOLID.md`**, which was written before two commits landed that
completed four of its ten milestones — and whose banner still overstates one of the four: `7193d2d`
met M2's exit criterion, but M2 also asked for the dead Scala `e2e/` module to be deleted and for a
build-time bundle-shape check, and neither had happened when the banner was written. The module was
deleted in wave 2, and the bundle-shape check exists: wave 4 shipped
`frontend/scripts/bundle-shape.mjs`, wired as `pnpm bundle-shape` in the frontend CI job, and closed
TD-016's successor on the check having been *run in both directions* rather than on the check
existing. It reads its roster of feature packages from the filesystem, so the sixth one M8 added and
the seventh M9 added were both picked up with no edit anywhere — measured again in wave 7, where
`bundle-shape.mjs` reported **seven** dynamically imported feature packages, none reachable statically
from the entry chunk, with nobody having edited a roster. That is the property the roster it replaced
did not have.

`docs/ROADMAP.md` stays as the historical M0–M8 record of how the backend was built and is not a
plan for this work.

## The definition of done

1. Every screen in `screens/` renders **real data**. No fixtures, no fabricated figures. Where a
   figure genuinely cannot be measured the UI says so; it never shows a zero.
2. Every backend capability those screens need **exists as a service or an endpoint**.
3. Tested: unit and component tests, an a11y sweep with zero violations in both themes, and a
   browser suite driving the deployed product.
4. Documented: README, ARCHITECTURE, ADRs for new decisions, an accurate `docs/FEATURE_MATRIX.md`,
   and an overview a newcomer can read.
5. One command brings the whole product up under `docker compose`.

## The ordering rule

**Runnable, then verifiable, then features.** A milestone whose exit criterion needs a harness that
does not exist comes *after* the milestone that builds the harness. That is why the first three
milestones ship almost no product: they make the gates able to fail, they make the whole product
come up from one command, and they build the primitives that thirty later cards are drawn with.

Every exit criterion below is a command. If you cannot run it, it is not an exit criterion.

**And a command that runs is not the same as a command that tests what it says.** Two traps have
now been paid for twice, so they are stated once here rather than repeated in every criterion.
*Every criterion naming `pnpm -C frontend e2e`, `./deployment/compose/smoke.sh` or a `curl` against
a running stack requires the images to have been built from the working tree first* — a cached
BuildKit layer served a bundle from before a repair and turned a green tree into a red browser
suite, and one image the distributed stack needs is not built by any CI job at all. And *every
criterion naming the a11y sweep requires three commands, not one*: build Storybook, serve
`storybook-static` on `:6017`, then sweep. `frontend/scripts/a11y-stories.mjs` drives a browser
against a served index and exits 2 with `Could not reach Storybook` when nothing is listening.

## Cost honesty

Four milestones require **a new backend service**, in the ADR-041 six-layer shape, with its own
config section, RBAC resource, `ServiceContracts` entry, Mill module, container image and
regenerated OpenAPI documents. The schema service — the smallest complete one in the repository —
is 3,203 production lines and 825 test lines. Budget that per service, plus its adapter.

| Milestone | New service | Blocks |
| --- | --- | --- |
| M7 | `services/metrics` — **built, wave 5; closed, wave 6** | 6 dashboard cards, 4 stat cards, the whole Traffic tab |
| M8 | `services/alerts` — **built wave 6; CLOSED wave 7, on a frame delivered through the gateway** | Alerts tab, the alerts card, the notification bell |
| M9 | `services/connect` (wave 7) and `services/ksql` (wave 8) — **CLOSED, wave 8, on a browser suite that is green for the first time** | the `ECOSYSTEM` group |

**The tenth service cost what this table says, and the edge lesson held.** `services/connect` shipped
in one packet with `connect.contract.jvm` landed on `services.gateway.api`'s `moduleDeps` **before any
of its own work**, and nothing about its routing arrived at integration — which is the whole of what
went wrong with the ninth. Budget the six layers, the adapter, the edge and the relay; the edge is one
line and it is the line that costs a wave when it is nobody's.

**And the ninth service cost more than the seven registration points said it would, in one specific
way worth budgeting for.** The first wave-6 pass left `services/alerts` complete and unreachable:
the Mill module was in one packet, the gateway row in another, and the dependency edge between them
(`services.gateway.api`'s `moduleDeps`) was in neither, so the routing could not compile and landed
at integration. Its **stream** was then left out because `ContractRouting.derive` cannot carry an
event stream and the hand-written relay was nobody's. The continuation added `AlertsStreamRoutes`
and mounted it in both deployment shapes. Budget, per service: the six layers, the adapter, **the
dependency edge in `build.mill` that its consumer needs**, and **one hand-written relay per stream**.

**M7's estimate came down and the other two came down with it.** Wave 1 built `services/metrics` as
a walking skeleton: twenty Scala files in all six ADR-041 layers, a Mill module with its own
`openApi`/`openApiCheck` tasks and a `KuiImage`, one `Section`-wrapped endpoint that answers
`not_configured` with a 200 rather than a 404, its `ServiceContracts` line, its `AllInOneWiring`
entry, its readiness poll and its regenerated OpenAPI documents. The RBAC resources and actions for
metrics, alerts and ksqlDB shipped with it, and so did the `libs/config` sections for all four —
every field defaulted, so an existing YAML still boots unchanged, and
`kui.clusters.0.ksql.url` stopped being a *failed boot*. So what M7, M8 and M9 each still cost is
the adapter and the endpoints, not the seven registration points outside the service. The three
places that broke when the eighth service was registered were all hard-coded literals in test
suites — a service-name set in the gateway and a startup-log string in the all-in-one — and both
are now known and fixed, which is most of what a worked example is for.

Nothing before M7 needs a new service. That is deliberate: the honest half of this product can be
finished first, and each of the four cards that cannot be measured keeps its `NotMeasured` sentence
until the service that measures it exists.

---

## M1 — Gates that can fail  ·  no new service  ·  **CLOSED, wave 2**

Four gates are wired, each has been proven able to fail against the real tree rather than a fixture,
and the sweep that was red is green: `pnpm lint:boundaries` (344 files across 8 packages,
comment-aware so prose naming an illegal import is not a violation), `pnpm build-storybook`,
`pnpm a11y` — **694 stories × 2 themes, zero violations**, re-run here against a Storybook rebuilt
from scratch — and `./mill frontend.apiConstants --check` (31 codes).

The two things this milestone was left holding are both done. The fourteen `empty-table-header`
violations were one blank column heading in the consumer-groups table and are gone; the repair
shipped a *visible* `Activity` heading rather than the visually-hidden string the packet asked for,
because `Column.header` is typed `string` and rendered as a bare text node with no class or ARIA
hook, and the packet owned no stylesheet to add one. That is a design change made in the open, not
smuggled, and it is on wave 3's list. The dead Scala `e2e/` module is gone — 21 sources, its
`build.mill` block and `.scalafix-e2e.conf` — `./mill resolve e2e` says `Cannot resolve e2e`,
`scripts/run-tests.sh` no longer offers a `--with-e2e` flag that did nothing, and the `compose`
job's upload of an artefact directory nothing produced is gone with it.

**What was wrong with the old exit criterion.** It said `pnpm -C frontend a11y` exits 0, as one
command. It cannot: `frontend/scripts/a11y-stories.mjs` drives a browser against a *served* index
and exits 2 with `Could not reach Storybook at http://localhost:6017` when nothing is listening.
CI gets this right (build, background `http-server`, poll `/index.json`, sweep); the criterion did
not, and anyone running the line as written would have read a green milestone as broken. The same
error is in M3's and M10's criteria and is corrected there too.

**Exit:** `.github/workflows/ci.yml` runs `pnpm a11y`, `pnpm build-storybook`, `pnpm lint:boundaries`
and `./mill frontend.apiConstants --check`; each fails when given a deliberately broken input; the
sweep exits 0 when actually run, which is three commands —
`pnpm -C frontend build-storybook`, then `npx --yes http-server frontend/storybook-static -p 6017 -s &`,
then `pnpm -C frontend a11y`; `grep -c 'e2e' .github/workflows/ci.yml` is 1, naming only `pnpm e2e`;
and `test ! -d e2e`.

## M2 — One command, the whole product  ·  no new service  ·  **CLOSED, wave 3**

Both obstacles are gone, and the criterion was run here end to end rather than reasoned about.

`.github/workflows/ci.yml` no longer carries a literal image list. It derives one from the file
that names the images — `grep -oE 'image: kui-[a-z]+:' deployment/compose/docker-compose.yml`,
`kui-frontend` excluded because Compose builds it — and refuses to proceed if that list comes back
empty. Adding a service to the compose file now adds it to the build with nobody remembering
anything. Run here, the derivation prints `cluster consumer gateway message metrics schema topic`
and the seven images build in 97s.

`smoke.sh` no longer compares two sets that both omit the defect. It reads the gateway's **contract**
map — `ServiceContracts.byService` in `services/gateway/api/.../routing/ServiceContracts.scala` —
and asserts every service the gateway holds a contract for is a container this stack runs. That is
the assertion the old equality could not make: `services/schema` was in that map, had a
`deployment.docker.schema` image and appeared in neither `kui.yaml` nor `docker-compose.yml`, and
both sides of the old check simply omitted it. The stack now runs eight containers — six backend
services, the gateway and `kui-frontend` — the sixth address was added, and the check prints
`contracts routed: cluster consumer message metrics schema topic`, six of six. A verification run
that deleted the schema container, its `depends_on` and its address together was caught by the
preflight before a container started, which is the defect shape that used to be invisible.

**Verified here, in this order.** The seven images built from the working tree through the derived
list; `docker compose -f deployment/compose/docker-compose.yml config -q` clean; `up -d --wait`
brings all eight containers to running; `curl -sf localhost:8090/ui/` and
`curl -sf localhost:8080/api/v1/health/ready` both succeed (`{"ready":true,…}`); and
`./deployment/compose/smoke.sh` passes three consecutive times, each printing the seven image names,
the six routed contracts, one service stopped with the other five available and the interface still
answering 200, and recovery.

**One thing changed in the criterion's favour and should be known.** The recovery step used to
assert `cluster capability: available` and now asserts *not* `unavailable`. On the first of my three
runs the recovered value printed `degraded` — so the old assertion would have failed on this machine
and the new one passed. That is not a theoretical relaxation: `degraded` is `LatencyWindow`'s p95
rule, and a service that has just restarted has one slow sample in a fifty-sample window. The
reshape is right, because the step exists to prove recovery and not to prove a percentile; the
weight is carried by the `proxied cluster list: ok` assertion immediately before it, which is a real
request through the gateway to the restarted service. But a reader should not think the two
assertions are equivalent. They are not, and the difference was exercised.

**What was wrong with the old exit criterion,** for the record, since it was corrected three times.
It named `localhost:8080/ui/`, which has answered 503 by design since ADR-048. It asserted that a
stack comes up without saying where the images come from, and every image on a developer's machine
is whatever was last built there. And it asked the capability check to cover the routed services
when the defect it was written for was a service that was *contracted* and not routed.

**Exit:** the images are built from the working tree by the same derivation CI uses —
`images=$(grep -oE 'image: kui-[a-z]+:' deployment/compose/docker-compose.yml | sed 's/image: kui-//; s/:$//' | grep -v '^frontend$' | sort -u)`
then `./mill "{$(for i in $images; do printf 'deployment.docker.%s,' "$i"; done | sed 's/,$//')}.docker.build"` —
then `docker compose -f deployment/compose/docker-compose.yml up -d --wait` followed by
`curl -sf localhost:8090/ui/ >/dev/null && curl -sf localhost:8080/api/v1/health/ready` both
succeed, and `./deployment/compose/smoke.sh` passes three consecutive runs with its
`contracts routed:` line naming every service in `ServiceContracts.byService`.

## M3 — The primitives thirty cards are drawn with  ·  no new service  ·  **CLOSED, wave 2**

`pnpm -C frontend test` is 56 files and 1062 cases green, `build-storybook` builds 694 stories, and
the a11y sweep is clean over all of them in both themes. All six defects wave 1's own verification
found are repaired, and five of the six are pinned by a test that goes red when the repair is
reverted: `StatCard`'s empty-slot guard is truthiness, so `visual={hasSeries && <Sparkline/>}`
reserves no empty box; `RingGauge` is `role="img"` with an `aria-label` that names the caption and
the reading, and says `not measured` rather than an em dash; `useQuery` reads `QueryState.stale`;
`QueryCache.watch`'s docstring describes what a probe actually observes; and four contrast ratios
were re-measured against the `#0a0e13` declared beside them rather than against `#000000`.

The sixth is repaired and **unguarded, and worse guarded than before**. `.kui-monogram--md` now
exists — but deleting the whole rule leaves 399/399 kernel tests green, Storybook building, and
nine monogram stories clean under axe, because `vitest.config.ts` deliberately loads no CSS and axe
does not measure size. The repair also *moved* `width`, `height` and `font-size` out of the
`.kui-monogram` base into the modifier, so a monogram that lost its size class used to render at
32px and now has no size at all. Carried into wave 3 as a real item: either the size belongs in the
base, or something has to be able to see the rule.

**Exit:** `pnpm -C frontend test` and `pnpm -C frontend build-storybook` pass with the new stories
present, and the sweep — built, served, then `pnpm -C frontend a11y`, per M1 — exits 0.

## M4 — The frame every screen shares  ·  no new service  ·  **CLOSED, wave 3**

All three obstacles are gone, and each was checked here rather than read off a report.

**The topic tree has a caller.** `App.tsx:103` imports `topicTree` and `App.tsx:714` calls it with
the cluster store's own names reading. The drawer nests: `e2e/dashboard.spec.ts` opens the dashboard,
clicks `Expand Topics`, asserts the subtree is visible, asserts a prefix row is a link, and asserts
`__consumer_offsets` is not in it.

**The wirings can now be distinguished from not being done.** I re-ran wave 2's own probe:
replacing `countFor: countLookup(readingValue(facts.counts))` with `countFor: () => undefined` at
`App.tsx:733` now fails one named case — `app.render.test.tsx` › "carries the store's own count into
the drawer's badge" — where last wave it left 281 tests green. The other three wirings the wave-2
retrospective named (`onCreateTopic`, `onSelect={switchEnvironment}`, and the tree's `childrenFor`)
each redden a case as well, and a fifth mutation at the *store* — cutting `topicNames` off at
`clusterStore.ts` so the drawer can never receive them — reddens three. The seam is what is asserted,
not the function.

**The browser half exists and passes.** `frontend/e2e/dashboard.spec.ts` is six cases and
`shell.spec.ts` four; against a stack whose `kui-allinone` and `kui-frontend` images I built from
this tree, the whole suite is **56 passed** across eight spec files.

Two of the milestone's smaller notes are settled and one is settled by argument. `Overview.tsx`'s
tab dispatch no longer captures the model: it returns the component and hands the model to
`<Dynamic>`, and the broker list is keyed on `BrokerBar.id`, so a poll replaces figures rather than
the subtree — three separate mutations redden the two cases that pin it. `+ Create topic` still
navigates to the topics list rather than opening the create dialog, and the reason is now written
beside it: no address opens that dialog, the dialog belongs to the topics screen, and the e2e case
asserts the behaviour that ships. That is a decision recorded rather than a wiring missed.

**One thing this milestone shipped that is still not reachable.** `topicTree`'s *favourites* branch —
`TopicTreeInput.favourites`, `rank: "favourite"`, the star icon, `NavItem`'s rank-0 case — is
exercised only by `topicTree.test.ts`, a fixture and a story. Nothing in the product records a
favourite, so the one production call site passes no favourites. That is the same shape as the fold
itself a wave ago, one level down, and it is named in wave 4 rather than left to be rediscovered.

**Exit:** `pnpm -C frontend test` passes new suites in `src/routing`, `src/nav` and `src/chrome`;
`grep -rn topicTree frontend/packages/shell/src` finds a caller and not only an export; replacing
`App.tsx`'s `countFor` with `() => undefined` reddens a named case in `app.render.test.tsx`; and,
against a quickstart stack whose images were built from the working tree, `pnpm -C frontend e2e`
passes a spec that opens `/ui/clusters/<id>/dashboard/overview`, asserts the drawer head names the
cluster and shows a broker count, expands the topic tree, and clicks `+` to land on
`/ui/clusters/manage`.

## M5 — Fill the `None`s that already have a source  ·  no new service  ·  **CLOSED, wave 2**

All eleven figures are on the wire, and — this is the part that mattered — they were confirmed
against a real broker rather than against a fixture. Against the quickstart stack, once the first
refresh has landed:

```
.cluster.summary.data.underReplicatedPartitionCount   0        (was null; the sweep now fills it)
.cluster.summary.data.onlinePartitionCount            86
.cluster.summary.data.controllerUptime                {"percent":null,"windowSeconds":21600,"coverageSeconds":60}
.brokers.data[0]                                      partitionCount 86, leaderCount 86
.groups.data.items[0]                                 coordinatorHost "kafka", coordinatorPort 9092
.statistics.data                                      topicCount 10, partitionCount 86, sizeBytes 21913
.subjects.items[0]                                    {subject, format AVRO, versionCount 1, compatibility}
```

The controller-uptime refusal is visible and correct: a window one minute into a six-hour period
answers `percent: null` beside the length it is measured over, rather than a percentage computed
over a minute. The topic list carries `cleanupPolicy` from a batched `describeConfigs`, a produce
rate differenced from two consecutive snapshots, and `/topics/names` exists for the tree that has
not been built yet.

Two risks travel with it, and both are about the code between Kafka and those numbers rather than
about the numbers. `KafkaPartitionSweeper.sweep` has **no test at all** — only its pure helpers
`placementOf` and `censusOf` are exercised, and the `unreadable = batch.skipped.keySet` line that
makes the whole refuse-rather-than-partially-sum design fire against a real cluster is asserted
nowhere. `KafkaTopicAdmin.cleanupPolicies` — the batching, the per-key failure isolation and the
whole-batch fallback, all argued at length in its scaladoc — has no test either, and the
"a `describeConfigs` failure costs the column and not the page" acceptance case is met by a
hand-built snapshot with `policy = None` rather than by any call that fails. `RecordingAdminPool`
in `services/cluster/infrastructure/test` is the counting-fake pattern both need. Until then, the
only thing that has ever established that these numbers arrive is a person running a broker.

Also carried forward: `99.98 %` appears as the worked controller-uptime example in two scaladocs, a
suite name and `services/cluster/contract/test/resources/golden/clusters-response.json`, and the
shipped window — 6 h at a 1 min step, 360 buckets — cannot produce it. The nearest reachable values
are `100.00` and `99.72`.

**What was wrong with the old exit criterion.** It named `./mill __.test`. Neither CI nor this
repository runs that; `scripts/run-tests.sh` is the runner, it is what `.github/workflows/ci.yml:109`
invokes, and it is the only one that reports the eight modules with no test sources at all rather
than silently counting them as passes. The criterion also asked for one field the cluster service
now fills and none of the three it actually added, and it did not say that a freshly started stack
answers `null` to all of them until the first refresh lands.

**Exit:** `./scripts/run-tests.sh` and `./mill __.openApiCheck` pass; and against a settled
quickstart stack, `curl -s localhost:8080/api/v1/clusters/<id> | jq '.cluster.summary.data'` carries
a numeric `underReplicatedPartitionCount` and a `controllerUptime` with a stated `windowSeconds`,
`…/brokers | jq '.brokers.data[0].leaderCount'` is a number,
`…/consumer-groups | jq '.groups.data.items[0].coordinatorHost'` is a host, and
`…/topics/statistics | jq '.statistics.data.topicCount'` is the cluster total.

## M6 — Every screen that can now be honest  ·  no new service  ·  **CLOSED, wave 4**

It closed on the third attempt, and the honest account of the third attempt is that **one of its two
obstacles was real and the other was a `jq` expression in its own exit criterion**.

Both real obstacles are gone. `Register schema` has an endpoint behind it: `POST
…/schemas/subjects/{subject}/versions`, authorized by `Action.SchemaCreate` — declared in wave 1 with
no caller for two milestones — refused with `KUI-READ-ONLY` on a read-only cluster before the registry
is asked, and answering a rejection as a 400 `KUI-VALIDATION` carrying the registry's own sentence in
`details[0]`, which the dialog renders rather than paraphrases. It was driven against the real
Apicurio registry in `features.spec.ts` and the subjects it wrote are in the quickstart. And the
twelve rules that could not fail were closed and **re-verified by mutation, one at a time, each
reverted** — recorded in `TECH_DEBT.md`'s TD-023 with the case each one reddens.

**The clause that failed, and why correcting it is not moving the goalposts.** The criterion said
`jq -e '.paths["/api/v1/clusters/{clusterId}/schemas/subjects"].post' docs/api/openapi.json`
succeeds. It does not, and it must not: that path is the subject *collection*, and a POST on it would
be "register a schema without naming a subject", which is not the Confluent registry API and never
was. The endpoint that shipped is on the version collection of a named subject, which is the right
shape. The clause was written from a guess about a path rather than from the design, and it is the
**third** criterion in this document to be corrected for naming a command that ran without testing the
claim. Run in its corrected form here, it succeeds and names `schema.subject.version.register`.

**What is closed, verified here rather than read off a report.** `./scripts/run-tests.sh` — 63 modules,
56 with tests, **3270 cases, all passing**. `pnpm -C frontend test` — 1324. `pnpm -C frontend e2e`
against the running quickstart — **69 passed, 1 skipped**, with one case failing on a first pass and
passing in isolation, because the container it reads had been restarted underneath it by this
verification. `grep -rn 'function useFetch' frontend/packages/*/src` totals zero.
`curl -s '…/search?q=orders'` answers three topics and one subject with `"partial":[]`.
`./mill checkArchitecture`, `./mill __.openApiCheck` (1967/1967), `./mill frontend.apiConstants
--check`, `node frontend/scripts/boundaries.mjs` (367 files), `node frontend/scripts/bundle-shape.mjs`
and `./scripts/feature-matrix-check.sh` (49 claims) all pass.

**Two things it closes with, both recorded rather than waved past.** `SR-005`'s two compatibility
*writes* and the compatibility check have recorded-response tests, seven stories and **no browser
evidence** — the row says so and stays `REVIEW`; the criterion never asked for it and wave 5 does.
And the search fold now names topics, consumers and schemas in `partial` when a deployment has no
clusters at all, which makes "no clusters" distinguishable from "nothing matched" — the thing item 3
required — but `partial` is defined by ADR-049 §2 as the services the gateway *could not ask*, and on
a deployment with no clusters all three are perfectly askable. It errs safely and the sentence a user
reads is not literally true. ADR-049 is where that is settled, and wave 5 settles it.

**Exit (corrected, third time):** `pnpm -C frontend test` passes, and against a quickstart stack whose
images were built from the working tree `pnpm -C frontend e2e` passes a spec per screen;
`grep -rn 'function useFetch' frontend/packages/*/src` totals zero;
`curl -s '…/search?q=orders' | jq '.results'` answers across topics, groups and subjects; no panel on
any screen renders an em dash without a sentence beside it, asserted per screen rather than by one
sweeping regex;
`jq -e '.paths["/api/v1/clusters/{clusterId}/schemas/subjects/{subject}/versions"].post.operationId'
docs/api/openapi.json` succeeds — **the version collection of a named subject, which is where the
registry API puts it** — and the `Register schema` control is enabled and asserted in
`features.spec.ts`; and each of the twelve mutations recorded in `TECH_DEBT.md`'s TD-023 reddens a
named case — run them, do not assume them.


## M7 — `services/metrics`  ·  no new service  ·  **CLOSED, wave 6**

**Every clause of the criterion was executed here, at integration, against images built from this
tree** — `kui-allinone:0.1.0-SNAPSHOT` `sha256:9e36c66d5bf7`, `kui-frontend:0.1.0-SNAPSHOT`
`sha256:c697f8bf8f23`, both built after the last source change and the quickstart torn down and
recreated on them. That is the sentence three waves of this milestone could not write.

**The wire that two packets guessed differently is one wire.** One packet now owns the DTO, the
decoder and the file between them, and the file exists: `services/metrics/contract/test/resources/golden/`
holds **ten** documents, `GoldenFilesSuite` reads all ten on the Scala side and
`frontend/packages/shell/src/overview/wire.golden.test.ts` reads the same ten off disk on the browser
side, failing loudly rather than skipping when one is missing — verified by moving a golden out of
the tree and watching both sides go red. `requestHandlers` is
`{requestHandlerIdleRatio, networkProcessorIdleRatio, purgatory[]}` on both sides and `producers` is
`{measuredBy, topics[], internalTopicsExcluded}` on both sides.

**All five reads answer real numbers through a gateway built from this tree**, measured with `curl`
against the recreated quickstart: `throughput` 288 buckets with one carrying a rate on a
minutes-old stack; `latency?window=24h` with a bucket at `produceP99Millis 22.0` and
`fetchP99Millis 504.0`; `request-handlers` at `0.979` idle with `Fetch 48` delayed requests;
`producers` naming five application topics by **topic**, `internalTopicsExcluded: 1`, with
`__consumer_offsets` gone; `record-size` at a `220.6` byte mean. `window=1h` is refused with
`KUI-VALIDATION` naming the three windows that exist, which is the honest half beside it.

**`./deployment/compose/smoke.sh` passes three consecutive runs from a torn-down stack on the tree
as it ships** — the run W6-10 could not make, because its own acceptance was blocked on a routing row
that landed at integration. Each run prints `500 records produced to smoke-traffic, 500 read back`,
`the exporter serves all 12 line shapes the reader reads`, the measured cluster's throughput `ok`
with all three of `bytesInPerSecond`, `bytesOutPerSecond` and `recordsPerSecond` carrying a figure,
both latency percentiles carrying one, and the unmeasured cluster at `not_configured` — the
measured-and-unmeasured pair on one deployment.

**And the run without the `kui-metrics` `depends_on` was made and written down**, which house rule 9
had been owed since wave 4: the stack comes up healthy in 25s, logs one WARN, files a bucket 17s
later with no traffic produced at all, and a full `smoke.sh` on that edit **passes**. So the
wave-4 failure that line was once thought to explain is **not** a start-order problem, the line is
headroom rather than a repair, and `docker-compose.yml` now says so in its own comment instead of
claiming a mechanism. The wave-4 failure itself remains unexplained and unreproduced in nine runs
across three waves; it is recorded as unexplained rather than closed.

**The browser cases that used to iterate an empty array now assert a figure.** `traffic.spec.ts` is
10 passed / 1 skipped: the request-handlers case computes `measuredReadings` and asserts it is
greater than zero before it iterates, the producers case takes an explicit empty branch that asserts
the *sentence* and zero bars, and the else branch requires every wire row on the screen and one
progress fill per row that carried a rate. The four invented figures are gone — `670` and `481` now
match ADR-052 and the committed fixture, and each citation names a line a reader can count.

**What it leaves behind, carried into wave 7 as owned rules rather than as notes.** Three rules in
this milestone's own code are ungated and were found by verification, not by the packet: a *fresh*
gauge may be stamped with the request instant instead of the scrape it came from
(`MetricsUseCases.scala:171`, the stale half is gated and the fresh half is not); the stale caption on
the three new Traffic cards is asserted nowhere, so an hour-old reading can draw with no badge and no
sentence; and the purgatory reader's anti-double-count guard can be widened with everything green.
`TD-024` records the one thing regeneration did not buy: the five `data` payloads still have no
sub-schema, so `schema.d.ts` types them as opaque and `frontend/README.md:33` is still false.

**Exit** (met): `./mill services.metrics.__.test` and `./mill __.openApiCheck` pass; a case decodes
each of the five payloads from a document rendered by the server's own encoder and a golden file for
the metrics wire exists; against a quickstart **built from the working tree, with the image id
recorded**, each of the five reads answers `ok` with at least one measured figure;
`./deployment/compose/smoke.sh` passes three consecutive runs from a torn-down stack and the run
without its `kui-metrics` `depends_on` is recorded beside it; and `pnpm -C frontend e2e` asserts a
non-empty series or reading for every card ADR-052 says can be measured — an assertion that fails
when the array is empty — and the `NotMeasured` sentence for a cluster with no source.

## M8 — `services/alerts`  ·  **NEW SERVICE**  ·  **CLOSED, wave 7**

**Every clause of the criterion was executed at wave 7's integration, on a quickstart torn down and
recreated on images built from this tree** — `kui-allinone:0.1.0-SNAPSHOT` `d39a08971ffd`,
`kui-frontend:0.1.0-SNAPSHOT` `827368273ecd`. That is the sentence two waves of this milestone could
not write, and the clause that had never once been run is this one:

```
$ curl -sN http://localhost:8080/api/v1/clusters/quickstart/alerts/stream
HTTP/1.1 200 OK
Content-Type: text/event-stream; charset=UTF-8
X-Kui-Correlation-Id: 0c5061fdaa39f9ef

event: alerts
data: {"cluster":"quickstart","openCount":0,"at":"2026-09-10T22:22:34.435078Z"}
```

The frame is not a heartbeat and not a keepalive: with the stream held open and nothing changing, it
is **silent for fifteen seconds**. What produced it was a `POST
…/alerts/events/{id}/acknowledgement` issued three seconds after the stream opened, against an event
seeded the way this milestone means it (`diskUsedWarningPercent: 1`, container restarted, exactly one
`storage` event, `openCount: 1`). So the two halves that were each other's blockers — W7-02's rule
that an acknowledgement publishes a frame, and W7-03's hand-written gateway relay — met end to end
for the first time, and the count in the frame is the count the write changed.

**The browser half is green on the same stack.** `e2e/shell.spec.ts:127` — *acknowledging an open
event moves the bell, with no reload* — **passes**, where at wave 7's verification it ran and skipped
because the feed it was pointed at had nothing open. `shell.spec.ts:64`'s bell case and all four of
`alerts.spec.ts`' non-refusal cases pass beside it. The fifth `alerts.spec.ts` case skips, correctly,
because this deployment does configure alerts.

**And the three things the milestone owed are discharged, each by a case that fails when reverted.**
The acknowledgement publishes a frame (`InMemoryAlertStoreSuite`); the feed's `openCount` is the whole
store's and not the page's, asserted against the shipped store on the `limit = 0` page that
`acknowledge` and the stream actually make; the shell's second derivation is gone and the bell reads
the kernel store's one accessor; and the dashboard's alerts card is mounted at `Overview.tsx:597`
with six cases on it. The wire is no longer two hand-written mirrors: `services/alerts/contract`
now carries six golden documents including `alerts-stream-frame.json`, `GoldenFilesSuite` reads the
directory in both directions, and the browser's `wire.golden.test.ts` reads the same files off disk
and asserts `ALERTS_EVENT_NAME` against the golden's own `event` field. House rule 12, applied to the
one wire wave 6 left out.

**What it leaves behind, carried into wave 8 as owned rules.** `InMemoryAlertStore.record`'s
publication condition has a **resolved** half that is asserted by nothing — the exact twin of the
rule this wave closed, on the other branch — so a condition that stops firing closes its event and
wakes no tab. `MaxReadMarkers` is a bound nothing exercises. `AlertsStreamRoutes.relay`'s terminal
error frame — the promise that a browser never sees an SSE connection just stop — can be deleted with
all 387 gateway cases green. And the alerts screen's *Alert state* chip bar can have Open and Resolved
swapped with all 61 `feature-alerts` cases green, because no case in that package ever clicks either
chip.

---

### The record of how it was built


**The ninth service exists and it is honest.** Six ADR-041 layers, 49 Scala files, 142 cases over 17
suites, its own `openApi`/`openApiCheck`, a `deployment.docker.alerts` image, a compose entry with a
healthcheck, an `AllInOneWiring` entry, a `ServiceContracts` row, and ADR-053, which takes the
decision this milestone was told to take rather than inherit: **`isAlter` does not split**, an
acknowledgement stays refused on a read-only cluster, and the ADR names the three call sites to start
from if that is ever revisited. Four rules ship — offline partitions, under-replicated partitions, a
group rebalancing past `rebalanceDuration`, a log directory past its two thresholds — and the ADR
names the two that deliberately do not. A rule whose facts cannot be read contributes no event and
says so; it never contributes a comfortable zero.

**Measured here against the recreated quickstart** (`kui-allinone` `sha256:9e36c66d5bf7`,
`kui-frontend` `sha256:c697f8bf8f23`, both built from this tree): the feed answers
`{"status":"ok"}` with four rule reports and an empty item list on a healthy cluster. Seeding an
event the way the milestone means it — a threshold below the fact, `diskUsedWarningPercent: 1` in
the quickstart's own configuration file, container restarted — produces exactly one open event,
`severity warning`, `category storage`, *"Log directory /tmp/kafka-logs is 32% full on broker 1"*,
`openCount: 1`, `unreadCount: 1`. With that event standing, `pnpm -C frontend e2e` is green on both
halves the criterion names: `alerts.spec.ts`'s *the card's count is the API's own figure* takes its
positive branch and asserts the pill reads `1 open` with one row, and `shell.spec.ts`'s *the bell
carries the open count the alerts service answered* asserts the bell's accessible name and badge
carry the same figure. `smoke.sh` asserts four more against the distributed stack — the feed is `ok`,
it says when the rules last ran, it names the rules it evaluated, and at least one rule read the
facts it needs — and `alerts capability: available` during the fault-isolation step.

**The public stream routing gap is closed in the wave-6 continuation.**
`GET …/alerts/stream` is now a gateway-owned SSE relay over the service's
`AlertsStreamEndpoint`. Keeping it out of `ServiceContracts.byService` remains correct:
`ContractRouting.derive` decodes and re-encodes JSON, which is the wrong thing to do to an event
stream. `AlertsStreamRoutes` performs the edge authorization check, sends the signed principal to
the service through the service client and preserves the SSE body. The route is present in the
merged OpenAPI document and `AllInOneWiringSuite` asserts that the one-process deployment mounts
the public stream path.

**Three things it owed at the end of wave 6, all three closed in wave 7.** The one write in the
service woke no subscriber; the count the bell draws was ungated at both ends and derived twice; and
the alerts card was mounted nowhere. All three are now gated, and the wire that was two hand-written
mirrors is one golden directory read from both sides.

**Exit** (corrected, because the form above is satisfiable by a service whose stream nobody can
reach): `./mill services.alerts.__.test` passes; after seeding one event,
`curl -s …/alerts/events | jq -e '.events.status == "ok" and (.events.data.items | length) > 0'`
succeeds and `.events.data.openCount` is that event's count; **`curl -N …/alerts/stream` answers
`200 text/event-stream` through the gateway and delivers a frame**; `pnpm -C frontend e2e` asserts
that after the same seeded event the bell carries its unread mark and the alerts card's pill count
equals the API's open count, **and that acknowledging that event moves the bell without a reload**;
and a golden document rendered by `services/alerts`' own encoder is decoded by the kernel's reader,
the way `services/metrics`' ten are.

## M9 — `services/connect` and `services/ksql`  ·  **NEW SERVICES**  ·  **CLOSED, wave 8**

**It closed on the two clauses it had been open on for two waves, and both were measured at wave 8's
integration rather than read off a report.** `services/ksql` is the eleventh and last new service in
this plan: six ADR-041 layers, its own `openApi`/`openApiCheck`, a `deployment.docker.ksql` image, a
compose entry, an `AllInOneWiring` entry, a `ServiceContracts` row, ADR-055 and ADR-056, a
hand-written push-query relay (`KsqlStreamRoutes`, house rule 16), and — landed first, per house rule
15 — `ksql.contract.jvm` on `services.gateway.api`'s `moduleDeps`. `feature-ksql` is the eighth
feature package. On the quickstart stack, with `kui-allinone` `c575438c5bfc`:

```
$ curl -s .../clusters/quickstart/ksql/objects
{"objects":{"status":"ok","data":{"items":[{"kind":"stream","name":"QUICKSTART_ORDERS",
 "topic":"orders.v1","format":"JSON", …},{"kind":"query","name":"transient_QUICKSTART_ORDERS_…",
 "statement":"SELECT * FROM QUICKSTART_ORDERS EMIT CHANGES;", …}, …]}}}
$ curl -s .../api/v1/capabilities | jq '[.entries[].key.service] | unique'
["alerts","cluster","connect","consumer","identity","ksql","message","metrics","schema","topic"]
```

**And `pnpm -C frontend e2e` is green for the first time in this project's history: 101 passed, 4
skipped, 0 failed, exit 0.** `connect.spec.ts` is 4 passed and 0 skipped against
`quickstart-file-source` running on the quickstart's Connect worker — the two positive cases that had
skipped forever now run, and the shape assertion that failed against a correctly routed server passes,
because W8-04 rewrote the spec against the service's own golden. `ksql.spec.ts` is 3 passed, 1 skipped.

**One thing about that green is worth more than the green.** On the first full run at this
integration, `ksql.spec.ts` was **2 failed** — the ksqlDB page rendered nothing and
`getByText("ksqlDB")` found no element. It was not a product defect. `kui-frontend:0.1.0-SNAPSHOT`
had been built in W8-10's first hour, per house rule 19, and the frontend *build* then broke mid-wave
on `feature-ksql`'s missing exports and was repaired afterwards by somebody else. The served image
carried **seven** feature chunks and no `feature-ksql`, fourteen hours after house rule 19 was obeyed
to the letter. Rebuilding the one image (`e9fadeecc330`) turned both failures green with no source
change. **House rule 19 bought the first hour and not the last one**, and the eleventh service came
within one command of repeating, exactly, the wave-7 failure it was written to prevent.

**One clause is retired rather than met, and it is recorded as retired.** *"With no Connect address
configured the drawer shows no Kafka Connect row"* is now asserted by **no browser case in any
deployment**: W8-04's rewrite dropped *"a deployment with no Connect worker says so"*, and W8-A3
argued it down — the case only ever ran on a stack that has never existed, house rule 6 is against an
acceptance made of absences, and `ksql.spec.ts:175` and `alerts.spec.ts:268` are the same skip in two
more places. The honest statement is that the not-configured browser path needs **a second Playwright
project against a stack with those addresses unset**, and that a `test.skip` in the main spec was
never evidence of anything. It is on wave 9's list as a coverage question, not as an M9 clause.

**The tenth service, for the record.** It exists, is routed, and answers through the gateway on a
stack built from this tree. Six ADR-041 layers, 129 cases over 13 suites, its own `openApi`/`openApiCheck`, a
`deployment.docker.connect` image, a compose entry, an `AllInOneWiring` entry, a `ServiceContracts`
row, ADR-054, and — landed *first*, as house rule 15 demands — `connect.contract.jvm` on
`services.gateway.api`'s `moduleDeps`, which is the edge whose absence made the ninth service
unroutable. Measured at wave 7's integration on `kui-allinone` `d39a08971ffd`:

```
$ curl -s .../clusters/quickstart/connect/connectors
{"connectors":{"status":"ok","data":{"workers":[{"connect":"quickstart",
 "connectors":{"status":"ok","data":{"items":[],"unreadable":[]},"fetchedAt":"…"}}]},"fetchedAt":"…"}}
$ ... /capabilities | jq '.entries[] | select(.key.service=="connect")'
{"key":{"service":"connect","cluster":"quickstart"},"state":{"status":"available"}, …}
```

`feature-connect` is the seventh feature package, 21 files, 62 cases, code-split like the other six,
20 stories clean under axe in both themes, and its decoder is bound to the service's own goldens by a
suite that reads the directory off disk and counts connectors out of the raw JSON.

**And the milestone does not close, for two reasons that are one measurement each.**

* **The browser has never seen a connector.** The quickstart's Connect worker runs and has nothing
  deployed on it, so `connect.spec.ts`'s two positive cases *skip* and the criterion's last
  clause — a connector's task state read through the gateway from a running worker, on the screen —
  is unmet. This is not a build problem; it is one connector short of proved.
* **The browser case that would have caught it is written against a wire that does not exist.**
  `e2e/connect.spec.ts:56` hand-writes `connectors.data.items` — the pre-rewrite prose shape — where
  the service renders `connectors.data.workers[].connectors.data.items`. Against the stale image it
  failed on a 404; against a correctly routed server built from this tree it now **fails on its own
  assertion**, `expect(Array.isArray(section.data?.items)).toBe(true)`, measured at integration. So
  the one spec written to prove the tenth service reachable is the one file the golden suite does not
  cover, and it carries the exact defect the package it tests was written to remove.

**Exit, clause by clause, all met at wave 8's integration.** The capability document carries a
`connect` row and a `ksql` row — met, quoted above. A connector's task state, read through the gateway
from a running Connect worker, appears on the screen with the image id recorded — met:
`connect.spec.ts` 4 passed / 0 skipped on `kui-allinone` `c575438c5bfc` and `kui-frontend`
`e9fadeecc330`, against `quickstart-file-source` RUNNING 1/1. Every stream and table the ksqlDB server
named is a row on the screen — met, `ksql.spec.ts:203`. The drawer's *positive* half — with an address
configured the feature is named and reachable — met, `connect.spec.ts:175` and `ksql.spec.ts:162`. The
drawer's not-configured half is **retired**, above, with the reason and the cost of doing it properly.

---

### The record of how the split was made


**The split is a cost judgement made on wave 6's numbers rather than a preference.** The interrupted
wave-6 run spent ten building packets and delivered **one** service — with its gateway routing
landing at integration and its stream relay still absent at the limit cutoff — while opening 46 new
ungated rules. The resumed wave closed that routing edge before M9 begins. Asking one wave for two
new services plus 46 carried rules would still produce the same shape again: everything present,
nothing joined. So **wave 7 builds `services/connect`**, and **wave 8 builds `services/ksql` and
closes M10**.

The vocabulary is already shipped and unused: `ConnectName`/`ConnectorName`/`TaskId`,
`ErrorCode.ConnectRebalancing`, the whole Connect RBAC closure, `ConnectClusterSettings` and
`KsqlSettings` per cluster (so `kui.clusters.0.ksql.url`, which used to be a *failed boot* under the
unknown-key check, now loads), and the non-altering `KsqlView` that `KsqlExecute` implies. Three
orphan kernel components have no feature behind them. Until the services exist, ADR-032's
`not_configured → hidden` rule is the correct rendering and the rows must not be faked.

* `services/connect` over the Connect REST API: connector list with expanded status, per-task state,
  pause/resume/restart as mutations, deploy, and the failure reason §7.7 asks for. **Wave 7.**
* `services/ksql`: object listing, statement execution as a mutation, push queries over ADR-035, and
  plan→token→confirm for `DROP … DELETE TOPIC`. The read-only case is already settled: `KsqlView`
  exists and `KsqlExecute` implies it, so a read-only cluster lists objects and refuses statements,
  which is asserted in `RbacLawsSuite`. **Wave 8.**
* Two feature packages, two routes, `FeatureId` widened, and `ECOSYSTEM` finally has three rows.
* The ksqlDB result region, which no capture shows — decide it in wave 8 (§7.9) before building it.

**And a lesson from the ninth service that the tenth and eleventh must not repeat.** A push-query
stream and a Connect task-state stream are both ADR-035, and `ContractRouting.derive` cannot carry
either: it decodes and re-encodes JSON. **A service that ships a stream ships its gateway relay in
the same wave, owned by the same packet as the routing, and the criterion is a `curl -N` through the
gateway** — not a contract entry and a green suite.

**Exit** (Connect half unmet on its last two clauses, both measured at wave 7's integration): the
capability document carries a `connect` row — `jq -e '.entries[] | select(.key.service=="connect")'`,
and note the shape, which is `entries[].key.service` and not the `services[].id` this document used to
name — **met**; with no Connect address configured the drawer shows no Kafka Connect row, and with one
it shows the connector count, both asserted by `pnpm -C frontend e2e` — **unmet, and the reason is the
spec rather than the product**: `e2e/connect.spec.ts` decodes `connectors.data.items` against a server
that renders `connectors.data.workers[].connectors.data.items`, so its positive half can never run and
its shape assertion fails against a correctly routed server; and a connector's task state, read
through the gateway from a running Connect worker, appears on the screen with the image id
recorded — **unmet: no connector has ever been deployed on the quickstart's worker**, so the two cases
that would assert it skip. Wave 8 deploys one, rewrites the spec against the golden the service already
ships, and runs both against images built from the tree.

## M10 — Close the book  ·  no new service  ·  **four of six bullets closed in wave 8; the milestone does not close**

**What closed, measured here.** `docs/FEATURE_MATRIX.md` is accurate and every count it publishes
about itself sits inside a checked region — `./scripts/feature-matrix-check.sh` exits 0 at **283
claims over seven sections** (251 over six in wave 7). `README.md`, `ARCHITECTURE.md` and the
newcomer's overview — `docs/overview/README.md`, new and linked from the root README — describe what
is now true, and the five sentences the eleventh service falsified in `README.md` were re-measured
rather than adjusted. `DECISIONS.md` is machine-compared against `docs/adr/ADR-*.md` in both
directions at **56 rows over 56 ADRs**. The a11y sweep is clean over **790 stories × 2 themes**. And
`pnpm -C frontend e2e` is green, 101 passed / 4 skipped / 0 failed, which it has never been before.

**What does not close, and it is four things, each one measured rather than asserted.**

1. **The browser suite covers 21 of the 23 screens, not 23.** The mapping was published for the first
   time by W8-07 (`docs/plan/verification/W8-07.md` §1) and it is the largest unmeasured thing in this
   milestone finally measured: **21 covered, 1 partial (`M14`), 1 uncovered (`M08`)**. `M08` is the
   cluster-switch toast, and no case has ever opened the cluster selector's menu **because the
   quickstart registers one cluster and there is nothing to switch to** — the same missing second
   cluster makes `M09`'s second-cluster clause unproved and keeps `traffic.spec.ts:536` skipping.
   `M14` is the plural receipt after a *bulk* delete; single deletion is covered and the toast
   machinery is proved, this specific receipt is not. **The blocker for `M08`/`M09` is the deployment,
   not a spec**, and it is one entry in `deployment/quickstart/kui-quickstart.yaml`.
2. **The comparison gate closed one half of its criterion and the other half is open — measured at
   this integration, not predicted.** See below.
3. **`./deployment/compose/smoke.sh` has not been run since the eleventh service and the eleventh
   container landed.** Wave 8's integration ran thirteen gates and that was not one of them, and
   neither was `pnpm -C frontend e2e`; both were run here instead, and one of the two was red. The
   distributed eleven-container stack is the one deployment shape nobody in wave 8 drove.
4. **`docs/plan/` is not reduced to `README.md` and this file.** It holds `WAVE-09.md` and
   `docs/plan/verification/`, and the wave that still needs a plan file cannot be the wave that
   deletes it. This is M10's own last bullet and it is now the last one standing.

* `docs/FEATURE_MATRIX.md` accurate against the code, re-counted with the command it publishes.
* README, ARCHITECTURE and a newcomer's overview describing what is now true.
* ADRs for the decisions this plan forced: the sixth series colour, the metrics source, the event
  model, the payload-status projection (§7.1), the theme-control convention (§7.4).
* The a11y sweep clean over every story in both themes; the browser suite covering all
  twenty-three screens; `docs/plan/` reduced to `README.md` and this file.

**Wave 6 closed the half of this that could be closed by a comparison, and left the half that cannot.**
`DECISIONS.md` is now machine-compared against `docs/adr/ADR-*.md` in both directions — 54 rows over
54 ADRs after wave 7 added ADR-054 and its row — and deleting ADR-052's row, the omission that survived a whole wave, now fails the run. The
script grew four mechanisms (a claim registry, per-region markers, a residue check over unclaimed
figures, and a manifest reconciliation), reports **251 claims checked over six sections** after wave
7's pass, and every
deletion the last criterion named now fails loudly.

**And the hole it was written to close is still open. Wave 7 doubled its price and published a
figure four times that.** W7-09 rebuilt the checker's header comparison so that the fact each claim is
measured against is derived a second time, independently, and reported in `TECH_DEBT.md`'s TD-023
that a green false figure now costs **four edits inside the script**. Measured at wave 7's
integration, on the shipped script, it costs **two**:

```
# (a) in check_document_region's `HEADER on N operations` loop, after the fact lookup:
    [[ $header == X-Csrf-Token ]] && fact=${header_ops[X-Kui-Principal]}
# (b) in header_for_kind:
    csrf) printf 'X-Kui-Principal' ;;
```

With those two lines in place, publishing `X-Csrf-Token on 56 operations` in ADR-048 and in
`frontend/packages/api/README.md` — against documents that carry it on **24** — leaves the run
printing `251 claims checked, all true` and exiting 0. The registry sees a claim of that kind was
made, the marker sees the region was checked, `close_section` sees the count, and the independent
re-derivation now agrees, because both of its sides were pointed at the same wrong header. That is
genuine progress — one edit became two, and wave 6's single-line attack no longer works — and it is
**the third wave running in which the packet assigned to close this hole published a price for it
that measurement does not support**. House rule 17 exists for exactly this and was not obeyed by the
packet that wrote it into the plan.

Five further guards this same rebuild shipped survive mutation with the run green, four of them
guards the wave plan asked for by name: `header_fact_from_document`'s independence (replace its body
with a read of the table it exists to be independent of — green), `claim`'s refusal of a claim that
records without comparing (`if false` — green), the empty-marked-block refusal in *both* region loops
(green), `reconcile_region`'s refusal of a marker with no `claims:` list (green), and
`close_section`'s count assertion, which weakens from `==` to `<=` in one character (green).

So what this milestone still owes is unchanged and now measured three times: a section must publish
**what it compared**, not how many times it incremented a variable, and the thing that derives the
fact must be pinned by a fixture rather than trusted to be independent. A fifth count will not do it,
a second derivation did not do it, and the next attempt should be a packet that attacks its own gate
before it reports rather than after.

### Wave 8 did that, and it is the first wave that did. Half the criterion is now met.

**The packet attacked its own gate before it reported, found the inherited price was cheaper than
three published prices, and said so.** W8-09 reproduced the two-line attack this document publishes
above against the repaired-but-unhardened script and then found the gate it had inherited cost **one
line**, not two and not four: `claim "$kind-operations" "$tok" "${BASH_REMATCH[2]}" "$fact"` →
`… "$fact" "$fact"`. A comparison of a fact with itself is a true statement about nothing, and every
gate the last three waves added read one side of it — the kind, the marker, the count and the
independent re-derivation all agreed, and each was right to.

Two new refusals close it, both on the **claimed** side, which is the half nothing read: `claim`
refuses a claimed figure that is absent from the text it struck out of the block, and
`audit_document_facts` **re-reads each block out of its own file** and requires every figure the
ledger says that block published to be in it. Section 6, `guard-fixtures`, drives eight shipped
functions over input written for the occasion — because the reason six earlier refusals were green
under mutation is that a refusal is only exercised by input this repository does not contain.

**Reproduced here, by the integrator, not read off the report.** The one-line collapse applied to the
shipped script, with `ADR-048` and `frontend/packages/api/README.md` then publishing
`X-Csrf-Token on 59 operations` against documents that carry it on 26:

```
feature-matrix-check: … the `csrf-operations` claim compared `26` as the figure the document
  publishes, and the text it struck out of the block — `X-Csrf-Token` on 59 operations — does not
  contain it.
feature-matrix-check: 4 disagreement(s) over 283 compared claims.
```

Four disagreements from two independent readers, and the attack that defeated wave 7's gate is dead.
**The `csrf-operations` half of the exit criterion is met.**

### And the other half of the same criterion is open, measured the same way

The criterion asks for the weakening to fail *"demonstrated on the `csrf-operations` claim **and on a
dependency row**"*. The dependency row is still one line. In `reconcile_dependencies`:

```
claim npm-version "" "$version" "${matched:-not in the cell}"   ->   … "$version" "$version"
```

With that single edit, `DEPENDENCY_MATRIX.md` was made to record `vite` at **9.9.9** against a
`frontend/package.json` that pins **8.2.2**, and the run printed `283 claims checked, all true` and
**exited 0** — claim, marker, section count and the manifest reconciliation all standing. The same
false row against the *unmutated* script is caught immediately (`DEPENDENCY_MATRIX.md records vite as
9.9.9; frontend/ pins 8.2.2`), so the row is compared; what is missing is any fixture that drives
`npm-version` the way `guard-fixtures` drives the eight document guards, and any re-reading of
`DEPENDENCY_MATRIX.md`'s own rows equivalent to `audit_document_facts`. The eight new fixtures cover
the marked-document machinery and **none of them covers the dependencies section.** The script and the
matrix were restored byte-identical afterwards (`md5sum` clean).

That is the fourth wave in which this gate is the last thing standing, and it is the first in which
the number in front of it is one somebody else measured and agreed with. The remaining work is one
fixture and one re-read, in the section the fixtures skipped.

**Both of the smaller things it owed are closed, and one of them left a new one behind.**
`frontend/README.md`'s `@kui/api` row now says what is true — nothing *inside* that package mirrors a
server type, and `shell/src/overview/metrics.ts` outside it still does, with TD-024 cited — so the two
documents agree. `BrowserConstantsMain` and `ErrorCodeDocMain` both grew a real `exitStatus` seam and
both directions are gated: changing `!= 0` to `< 0` reddens `BrowserConstantsSuite.scala:174` and
`ErrorCodeDocSuite`, re-verified at wave 7's integration. What it left behind is one sentence of the
same class it was closing: **TD-024 names a type that does not exist.** `ConnectorsResponse.connectors`
is nowhere in `schema.d.ts`; the property the wave actually added is `ConnectorListResponse.connectors`
at `schema.d.ts:1575`, and the delta is one property, not the two the row implies. **Corrected in wave
8**, to `ConnectorListResponse.connectors` at `schema.d.ts:1657`, and the row re-measured rather than
adjusted: **177** `: unknown;` lines, **152** of them the index signature, **25** named properties, one
of which (`KsqlObjectsResponse.objects`) the eleventh service added. None of the five metrics payloads
has closed.

**Exit** — seven of the nine clauses met at wave 8's integration; the two that are not are named.
From a clean checkout, with every container image built from it:
`./scripts/run-tests.sh`, `pnpm -C frontend test`, the a11y sweep (build, serve, sweep — see the
ordering rule), `pnpm -C frontend e2e`, `./mill __.openApiCheck`, `./mill checkArchitecture` and
`./deployment/compose/smoke.sh` all pass; `./scripts/feature-matrix-check.sh` exits 0, fails when any
one input file is made unreadable, fails when any one section is made to check fewer claims than it
published last time, **and fails when any single comparison is weakened while its claim, its marker
and its count all stay standing** — demonstrated on the `csrf-operations` claim and on a dependency
row, which are the two this criterion has now been written against twice; every count
`docs/FEATURE_MATRIX.md` publishes about itself sits inside a checked region; `DECISIONS.md` is
machine-compared against `docs/adr/ADR-*.md` in both directions, which wave 6 delivered; and
`./scripts/run-tests.sh` names **no** module that resolves as a test target and ships no test source
— **met in wave 7 and held in wave 8**: the list stayed empty through an eleventh service, and the run
is **81 modules with 81 carrying tests, 4,254 cases**.

**The two unmet clauses, stated as commands.** `./deployment/compose/smoke.sh` — **not run since the
eleventh service and the eleventh container landed**, by anybody, in the wave or at its integration.
And the weakening demonstration on **a dependency row** — one line, exit 0, reproduced above. The
`pnpm -C frontend e2e` clause is met and was red until an image was rebuilt from the tree at this
integration, which is the difference between the criterion and the tree that the ordering rule's own
warning is about.

---

## What wave 1 actually did

Wave 1 was twelve parallel packets over a disjoint file partition, and eleven of the twelve produced
work that survived independent re-verification: the four CI gates and the import-boundary checker,
the frontend in every compose stack, the four chart primitives and the sixth series colour, the
kernel's selection and stat-visual and monogram and bulk bar, `useQuery`, the dashboard route, the
shell's navigation and prefix and cluster-store vocabulary, the RBAC and configuration sections for
four services that do not exist yet, a bounded time series, and `services/metrics` as a walking
skeleton. `./mill __.compile`, `./scripts/run-tests.sh`, `./mill checkArchitecture`,
`./mill __.openApiCheck`, the frontend's typecheck, its 971 tests and its build are all green on
the composed tree. **The a11y sweep is not**, and that is the wave's honest headline: of the three
milestones it was written to close, M1 and M3 both hang on the same fourteen violations, and M2
hangs on a compose block that a sibling packet made stale while it was being written.

What surprised us is worth keeping, because it is all the same lesson in three shapes. **A file can
have a second owner nobody listed.** `--kui-color-series-6` was added to the stylesheet by the
packet that owned stylesheets, and broke two suites in `build-tests/`, where a Scala mirror of the
token list lives that no packet's `Owns` block named — the wave's only genuine two-sided collision,
and it was invisible until the packets were composed. **A gate can be green and still not bind.**
The cluster store's skip-the-unmeasured-disk rule survives being mutated into zero-filling, because
the one unmeasured directory in its fixture belongs to a broker that also has a measured one; the
time-series cell counts a window whose every sample has been evicted as a *fresh hit*, so an
exporter dead for hours reads as a healthy cache. Both were found by mutating the code and watching
the suite stay green, which is the only way either could have been found. **And a verification pass
can invent the thing it was written to remove.** The packet whose entire job was to make
`docs/FEATURE_MATRIX.md` say only what is true reasoned backwards from a count delta to a causal
story about which rows moved when, and wrote that story down as established history; re-counting the
rows commit by commit disproves every clause of it. Wave 2 repairs all three, and each repair is
attached to the packet that owns the file rather than deferred to a later pass — which is how the
drift being repaired got started.

No milestone changed order. The two candidates were considered and both dissolved on inspection:
M5's produce rate needs two consecutive snapshots and not a retention window, so it does not wait on
M7; and M7's newly cheaper shape does not let it move ahead of M6, because what M7 still costs is
the adapter, and the adapter has no consumer until M6's cards exist. What did change is three exit
criteria. M1's now requires the sweep itself to exit zero rather than merely to be wired, because a
gate that is wired and red has not closed anything. M2's names port 8090 — the old criterion pointed
at `localhost:8080/ui/`, which has answered 503 by design since ADR-048 and could never have passed.
And M2's now asks that the smoke test's capability check cover every service the gateway routes,
rather than the literal list of four names that let an unreachable eighth service through.

## What wave 2 actually did

Wave 2 was thirteen parallel packets over a disjoint file partition, and it closed **three of the
five milestones it was written against**: M1, M3 and M5. M2 and M4 did not close, and in both cases
the reason is the same shape — the work was done and the *last hop* belonged to nobody.

What changed. The a11y sweep is green over 694 stories in both themes and the dead Scala `e2e/`
module is gone. The frame is wired and, for the first time in this project, was verified by driving
it: a browser at `/ui/clusters/quickstart/dashboard/overview` shows a cluster block naming the
cluster and its broker count, navigation badges carrying real counts, a storage meter reading real
log directories, and a dashboard whose panels either carry a measured figure or say why they do not.
Eleven `null`s became numbers and were checked against a real broker, not a fixture: partition and
leader counts from one `describeTopics` sweep that refuses rather than partially sums, a controller
uptime that answers `null` beside its stated window rather than a percentage computed over a minute,
the consumer coordinator's whole address, a cluster-wide topics statistics document, a batched
cleanup policy, a produce rate differenced from two snapshots, and a subject summary enriched per
page rather than per registry. The distributed stack runs the eighth service. `docs/FEATURE_MATRIX.md`
says only what its own rows say, re-derived commit by commit.

What surprised us, in four shapes, all of which are now wave 3's problem.

**The partition was disjoint and the wave still collided seven times.** Every collision landed in a
file that *no packet owned*: shared golden documents in `libs/contracts-core`, the gateway's own
copies of them, two hard-coded endpoint rosters in the gateway's test suites, and a hand-written
`interface SubjectPage` in the shell that mirrored a wire shape a different packet widened. The
partition rule protects against two packets editing one file. It does not protect against one packet
changing a *shape* that an unowned file asserts, and that is what breaks a wave. Wave 3's partition
therefore has a section that names the unowned files and says why each needs no edit — and the guard
files are listed up front rather than discovered by a red build.

**A green gate is still the default failure.** Eight rules shipped this wave that no test can fail,
and every one was found by mutating the code and watching the suite stay green: the store's wire to
the drawer's badges can be cut entirely and 281 tests still pass; the all-in-one can be made to
ignore the operator's metrics configuration and its suite still passes; a CSS rule can be deleted
whole; a use case's short-circuit can be turned off inside a test whose *name* claims to assert it.
Two of these I re-ran myself before writing this. The lesson is not "write more tests" — it is that
a packet's acceptance case must name the *seam*, not the function, because a rule composed by hand
inside a test file is not the rule the product runs.

**A criterion can pass on a machine and be unrunnable everywhere else.** The distributed stack comes
up here because a `kui-metrics` image was built here by hand; CI builds five images and the stack
needs six, and the image cannot be pulled. The browser suite read red for an hour against a
BuildKit-cached bundle from before the tree was repaired. And the a11y line printed in this document
exits 2 on its own, because the sweep needs a served Storybook. Three exit criteria were rewritten
for this and a standing note was added to the ordering rule.

**And the thing built last wave is still the thing not called this wave.** `topicTree` is written,
tested, exported — and referenced by exactly one file, the barrel that exports it. `useQuery` has no
consumer outside the kernel. `KafkaPartitionSweeper.sweep`, the code that produces M5's headline
numbers, has no test at all; the only thing that has ever established that those numbers arrive is a
person running a broker. Wave 1 built a vocabulary nobody consumed and wave 2 was the wiring wave;
wave 2 built a fold nobody consumed, and wave 3 has to be the wave that stops the pattern rather than
the wave that repeats it one level up.

No milestone changed order. Two exit criteria were corrected for the second time (M1's and M2's) and
four more for the first (M3's, M5's, M6's, M10's); what they had in common is that each named a
command that ran, and none of them named the thing that had to be true.

## What wave 3 actually did

Wave 3 was fourteen parallel packets over a disjoint file partition, and it closed **two of the
three milestones it was written against**: M2 and M4, both of which had failed twice. M6 did not
close, and for the first time the reason is not a last hop that belonged to nobody — the screens
are built, they are reachable, and they were driven. It is that one bullet of M6 needs an endpoint
nobody has written, and that twelve of the rules the wave shipped cannot be made to fail.

What changed. The distributed stack is a stack CI can actually build: the image list is derived
from the compose file rather than remembered, and the smoke test compares the containers against the
gateway's **contract** map rather than against its address list, which is the assertion the old
equality was structurally unable to make. Verified here: seven images built from the tree, eight
containers up, both curls, three consecutive smoke passes. The frame's wirings became observable —
cutting the store off from the drawer's badges now reddens a named case where a wave ago it left
281 tests green — and `frontend/e2e/` grew from four spec files to eight, 56 cases, every one of
them green against images built from this tree. Nine of M6's ten bullets shipped: the topics list
and the topic object, consumer-group paging over the server's own total, the schema registry's two
panes, the message browser's real partition count and typed CEL predicates, the brokers screen's
four tiles, the dashboard's fine-grained body, cross-entity search at the gateway with the top-bar
field wired to it, and toasts. Every hand-rolled `useFetch` is gone. The a11y sweep is clean over
719 stories in both themes, up from 694, and `./scripts/feature-matrix-check.sh` now compares the
repository's published counts against the things they count — 49 claims, all true.

What surprised us, in three shapes.

**Naming the ungated rule in the brief did not close it.** House rule 4 said in as many words that a
gate you cannot make fail is not a gate, and every packet was given a mutation line to run. Fourteen
packets ran their own mutation, watched a case go red, and reported it — and independent verification
then found **twelve further rules with no gate at all**, several of them the packet's own headline.
The search field's out-of-order episode guard, which its author defends in a three-sentence comment
and its report headlines by name, can be deleted and 333 shell tests still pass. The pattern is
sharp and it is not laziness: a packet mutates the thing it just wrote and finds the case it just
wrote, because the two were written together. The mutation that finds a hole is the one you did not
plan. Wave 4 therefore asks every packet to disclose a mutation that stayed **green** — an honest
negative — because a report with no green mutation in it is a report that stopped looking.

**A criterion can be satisfied by the absence of the thing it measures.** M7's exit criterion asks
that the throughput endpoint print one of `ok|stale|unavailable|not_configured`, and that with no
exporter it print `not_configured`. Against the quickstart today, with no adapter anywhere in the
repository, it prints exactly that and `./mill services.metrics.__.test` passes. Every clause is
green over a service that measures nothing and was never meant to yet. Two waves of correcting
criteria have been about commands that ran without testing the claim; this is the sharper version —
a criterion whose only positive assertion is a refusal, which the thing it is guarding will always
be able to make. It is why wave 4 has to put a metrics exporter in the quickstart before it can
prove anything about M7 at all.

**The partition held, and the handoffs did not.** Wave 2 collided seven times in files nobody owned;
wave 3 listed those files up front and collided **once** on a gate — the new count-checking script,
red on arrival because the search endpoint moved three numbers that a sibling packet had just wrapped
in checked markers, which is the gate doing its job. Every other cross-packet breakage was stale
prose, found and repaired at integration. But two of the fifteen explicitly-contracted handoffs
simply did not happen: the `ARCHITECTURE.md` §9 paragraph that W3-08 was to write and W3-09 to paste
does not exist in the file, and the schema drawer badge still costs five registry round-trips because
W3-10 delivered the cheap count-only mode and W3-06 never changed the call. Both are the same failure
as wave 2's last hop, moved from "nobody owned the file" to "two people owned the sentence". Wave 4's
rule is that where a handoff is one paragraph or one constant, **one packet owns both ends**.

No milestone changed order. Three exit criteria were corrected: M6's, whose `grep -rl useFetch`
clause can never be empty because the comments explaining the hook's removal name it; M7's, for the
reason above; and M10's, which can now name the comparing command it asked for, along with the two
holes that command still has. M8's was tightened in passing, on M7's lesson.

## What wave 4 actually did

Wave 4 was ten parallel packets over a disjoint file partition, and it closed **one of the two
milestones it was written against**: M6, on its third attempt, after its criterion was corrected for
the third time. M7 did not close and was never going to — the wave was written to *start* it — but
it got further than planned: the adapter, the buffer, the scrape loop and one endpoint answering real
numbers off a real broker, verified here at 288 buckets of which 46 carry a rate.

What changed. `Register schema` has an endpoint, and `Action.SchemaCreate` — declared in wave 1 and
referenced by nothing but its own implication row for two milestones — has a caller. The quickstart
and the distributed stack both run a JMX-exporter sidecar, so for the first time there is something
in this repository for a metrics adapter to read. The dashboard has a Traffic tab with a real series
in it, drawn as a gap where nothing was sampled and never as a zero. `smoke.sh`'s image preflight can
fail on an empty derivation. And `frontend/scripts/bundle-shape.mjs` finally exists: TD-016 was closed
without it in M2, re-filed as TD-022, carried through two waves, and is now closed on the check
having been run in both directions rather than on the check existing.

### Did changing the mechanism work? Three numbers, then the answer.

Wave 4's mechanism was to stop repeating the instruction and instead **assign each packet one named
ungated rule to close**, and to require every packet to disclose a mutation that stayed *green*.

**Ten of ten owned rules were genuinely closed** — every one re-applied by an independent verifier
who watched the named case go red and then reverted the tree. That is the first mechanism in four
waves with a perfect hit rate. Naming the rule, in the packet that owns the file, works.

**The same ten packets shipped thirty-five new ungated rules.** Independent verification found them;
**thirty-one of the thirty-five were not disclosed by the packet that wrote them**, despite house rule
5 requiring a green mutation from every packet. The green mutations packets *did* disclose were
mostly pre-existing holes in code they had not written.

**And the first honest measurement of the standing debt says the wave is not anomalous.** Five agents
mutated **89 rules in code nobody was editing** — `libs/`, the five untouched services, the gateway,
the kernel, three feature packages — and **26 survived**, 29%. That is the house rate, and it has
nothing to do with wave 4: those rules were written in M0–M5. The survivors cluster in one shape and
it is not laziness. `libs/http`'s `RbacGuard` has no suite in `libs/` at all; its *denial* branch
turns out to be gated anyway, by `services/cluster/api`'s `ServiceRbacGuardSuite` — two named cases
went red when I made it fail open, which corrects the alarm the census raised — but its
**fail-closed-on-undecidable** branch is gated by nothing anywhere: made to fail open, the whole
cluster-api suite is 752/752 green. The pattern is that a rule gets a test when a *consumer inside its
own module* exercises it, and goes untested when its only consumer is somewhere else.

So: **8, 8, 12, and now 35 — against a standing pool where 29% of everything is ungated.** The rise
is not a collapse in quality. It is the first wave that looked hard, and the honest reading is that
waves 2 and 3 shipped at something near the same rate and nobody counted. One number settles the
mechanism question: assigning a rule closes **one hole per packet**, and a packet opens about
**three and a half**. The mechanism works and it is losing three to one.

**Recommendation, without hedging: wave 5 runs one adversarial packet per three building packets, and
they mutate code they did not write.** Nine building packets, three adversarial. The evidence is not a
preference. Builders found 4 of their own 35 holes; verifiers found 31 — roughly eight to one, on the
same code, under the same house rule. A packet mutates the thing it just wrote and finds the case it
just wrote, because the two were authored to one understanding; that is not a discipline failure and
another instruction will not fix it, because wave 3 was told and wave 4 was told twice. What found
holes was a second person with no stake in the packet passing. The three adversarial packets are
therefore scoped to code **no building packet owns this wave** — that constraint is load-bearing, and
the census learned it the hard way, losing runs to false reds from siblings editing underneath it —
and they own the *test* directories of what they attack, so that a hole found is a case landed rather
than a paragraph filed.

What surprised us, in three shapes.

**A root cause can be wrong and its fix can still work, which is the worst combination.** The
distributed stack's smoke test fails at the one assertion M7 exists to add; the cause was reported as
a scrape loop that dies on its first failed pass, and the fix — one `depends_on` — made the script
pass. The loop claim is false. Stopping the exporter under a running KUI produces one WARN per
interval, then `open`, `halfopen`, `closed`, and the series resumes; restarting KUI with no exporter
at all, so its first scrape fails exactly as it does in compose, fills a bucket 26 seconds after the
exporter returns. Both were run here. So the compose failure is real, reproduced twice, and
**unexplained** — and a patch that works for a stated reason that is false is how `degraded` came to
be asserted where `available` was meant. Wave 5 explains it before it patches it.

**A verified fact and a scoped fact are not the same fact, and the scope is where the alarm lives.**
The census reported a fail-open authorization guard as its most severe finding, correctly noting it
had run only `./mill libs.__.test`. Run against the suite that actually exercises it, the denial
branch is gated and the alarm dissolves — while the branch beside it, the one that refuses an endpoint
the policy cannot decide at all, is gated by nothing in the repository. The severe finding was real
and it was the *other* one. A measurement whose scope is stated honestly can still hand the next wave
the wrong target, and the only defence is that the next wave re-runs it.

**The rule that cannot fail is now a register entry rather than a wave note, and that is the change
that will outlast this plan.** `TECH_DEBT.md`'s TD-023 records the class, not a list: twelve of wave
3's closed and re-verified by mutation, nine of wave 4's left open and named. A wave plan is deleted
the day its work lands, which is how the same holes were rediscovered twice; a debt register is not.
The thirty-five and the twenty-six go into it before this file is written, and every one of them is
assigned to a packet below rather than left to be found a third time.

No milestone changed order. Two exit criteria were corrected: M6's, which asked `jq` for a POST on
the subject *collection* — a path the registry API does not have and the design deliberately does not
publish — and M7's, which asked for a series with real numbers in it and did not say which deployment
proves the measured and unmeasured clusters *together*, the answer being the one stack no green gate
covers. M10's was tightened on wave 4's own lesson: its comparing script's glob was closed and the
same hole reopened one line over, because a section floored at "at least one assertion ran" measures
its own liveness and not its own coverage.

## What wave 5 actually did

Wave 5 was twelve parallel packets over a disjoint file partition — **nine building and three
adversarial**, the first wave to spend a quarter of its capacity on people who ship no product — and it
closed **neither of the two milestones it was written against**. M7 got everything it asked for and one
thing nobody checked: four endpoints, an ADR that settles the three unmeasurable cards against a
captured exposition, a widened exporter ruleset with twelve line shapes asserted where two were, gateway
routing, five drawn cards, and a `smoke.sh` that passes three consecutive times from a torn-down stack —
and **two of the four new wires do not match the browser that reads them**. M8 was never in this wave.

What changed, and all of it was re-measured at integration rather than read off a report.
`./scripts/run-tests.sh` is **3462 cases over 63 modules, 57 with tests** — the "with tests" count moved
for the first time in the project's history because an adversarial packet wrote the first test source
`services/schema/app` has ever had. `pnpm -C frontend test` is **1449**. `./mill checkArchitecture`,
`./mill __.openApiCheck` (1967/1967), `./mill __.fix --check`, `./mill __.checkFormat`,
`node frontend/scripts/boundaries.mjs` (372 files, 8 packages) and `./scripts/feature-matrix-check.sh`
(105 claims, up from 49) are all green, and the a11y sweep is clean **first try** over 739 stories in
both themes. Two gates that had been red for four waves because nobody owned the file — `libs/config`'s
formatting and `tools/error-codes`' scalafix — are green. `docs/api/openapi.json` is 54 paths, 65
operations and 150 schemas, up from 50/61/146.

### Did the adversarial split work? Six numbers, then the answer.

**The three adversarial packets mutated 162 rules, scored 156 that carried a rule, found 72 ungated —
46% — and closed 65 of them with a case that genuinely fails.** Ten of those closures were re-applied at
integration, one mutation at a time, and every one reddened the named case: the fail-closed
authorization branch, an operator's own logback file, a static cluster's absent store version, a
`NOT_CONTAINS` over a missing header, a 1 MB schema bound, a registry's references, a bulkhead width, a
dialog's JSON guard, a broker's unreadable leader count, and a half-measured disk. Seven were left open
and each is named, with the seam it needs.

**The nine building packets closed nine of nine owned rules and shipped 33 new ungated ones.** Their
verifiers found every one of the 33 and closed **none**, because a verifier's deliverable was a report.
Twenty-seven of the 33 were not disclosed by the packet that wrote them, under a house rule requiring
exactly that disclosure.

**So the disclosure rate moved from 11% to 18% and the rate of holes shipped did not move at all** —
3.5 per building packet in wave 4, 3.7 in wave 5. Four waves of instruction have now produced no
measurable change in a builder's ability to find their own hole, and the mechanism question is settled:
it is not a discipline problem, and a fifth instruction will not fix it.

**The arithmetic of the wave is the first that ends ahead.** Wave 4: ten owned rules closed, 35 opened,
**net −25**. Wave 5: twelve owned rules closed (nine builders, three adversaries), 65 closed by
adversaries, 33 opened, **net +44**. Per packet, an adversary closed about 22 rules; a builder opened
about 3.7. The three adversarial packets cost 25% of the wave and produced two-thirds of its gating.

**And the ungated rate in code nobody was editing went up, not down: 46% against wave 4's 29%.** That is
not a regression — the samples are different in kind. Wave 4 sampled 89 rules at random; wave 5's three
packets chose theirs by reading comments for *"a rule with a paragraph defending it and no test"*, which
is a hunting method rather than a census. What it establishes is that the heuristic works: in code
nobody is editing, reading the comments finds a hole about every other try.

**Where the survivors cluster, and all three packets found the same shape independently.** A rule gets a
test when a consumer *inside its own module* exercises it — wave 4's finding — and the sharper version
is that four kinds of file account for almost all of it. **Composition roots and the classes only they
construct**: `RegistryCredentials` was 5 of 5 green with no suite at all, `SchemaWiring` 4 of 4 green
because `services/schema/app` declared a test module in `build.mill` and shipped no test source, and
`LogbackSelection`, `LoggingAuditSink` and `RbacGuard.fromPolicy` are all constructed only by a `Main`.
**Teardown and cancellation**: *"every cancellation branch I mutated in every service survived"*, and
four of the kernel's six wave-4 holes were the same shape. **Mapping code whose consumer is a route**:
the route suite asserts a status code and the field-level rule underneath — a `None` that must not
become a `0`, a sort order, a four-state discriminator — is not what a route suite looks at.
**And rendering files**: 16 of one packet's 17 survivors were in a `.tsx`, while all 12 rules in the
same package's pure-data module were gated. `scripts/run-tests.sh` still names **six** modules that
resolve as test targets and contain nothing.

**Recommendation, and it is not "more adversaries".** Wave 6 keeps 3:1 — ten building packets and three
adversarial — because wave 5's own pre-commitment said the ratio falls only if the rate falls, and it
rose. What changes is what a *verifier* may do: **every verification pass now owns the test tree of the
packet it verifies and lands a case for every hole it finds.** Thirty-three holes were found and
thirty-three were left open at a cost of nine full verification passes; converting those nine readers
into nine closers costs no packet capacity and is worth more than a fourth adversary, which would buy
about 22 closures for a whole packet. Two mechanical changes go with it: adversarial packets run in a
`git worktree` copy, and no packet reverts a mutation with `git checkout --`, `git restore
--source=HEAD` or `git stash`.

What surprised us, in three shapes.

**Two packets can each be right and the product still broken, and every gate will agree with both.**
W5-01 shipped `producers.data.{measuredBy, topics[{topic, bytesInPerSecond}]}` and W5-04 shipped a
reader for `producers.data.entries[{clientId, bytesPerSecond}]`. Both are unit-tested against their own
shape. The `Section` key matches, so the decode *succeeds* and answers an empty array, and the card then
draws *"The metrics source answered and named no producers"* over a source that named five. The e2e
cases written to catch exactly that drift read the same wrong field names, so they iterate an empty
array, skip their only assertion and report green. The wave plan stated the contract in prose on both
sides, which is what it has always done and what worked for `RingGauge.goodDirection`; the difference is
that a prop is one word and a wire is a shape. **Wave 6's rule 12 is that one packet owns the DTO and
the code that decodes it, and the binding case decodes the encoder's own output.**

**The adversarial packets' worst enemy was each other.** One packet's mutation in `libs/http` broke a
sibling's compile in the shared tree, silently truncating its run from 2633 cases to 1006 while Mill
still printed `SUCCESS`, and twenty-two mutations were scored red that were nothing of the kind. A third
agent's `git add -A` staged a live mutation, after which `git checkout --` *restored the mutation* and
reported a clean tree. A fourth ran `git stash` and swept forty-six files belonging to six other packets,
destroying one file that had to be recovered from a copy. The scoping rule — attack code no building
packet owns — was followed and does not protect against any of this, because a dependency of what you
are measuring can be edited by somebody who owns it. Every one of the three was caught, disclosed and
recovered; the third was two commands from committing a mutated authorization guard.

**The instruction to remove invented figures produced four new ones.** W5-01's brief named a comment
claiming a parser *"would answer 348601.0"* where the fixture sums to 249601.0, told the packet to
delete it, and the packet did — then shipped `680 Kafka families` three times and `961` once, against an
ADR in the same commit that says 670 and 481. Nothing in the tree reads either number. This is the third
wave in which a packet assigned to remove a class of defect created a fresh instance of it while
removing the named one — wave 3's comment claiming a defence something else was making, wave 4's dead
export, wave 5's invented figure — and the pattern is now specific enough to gate rather than to warn
about: **a figure in prose must name the file a reader can count it in.**

No milestone changed order. Three exit criteria were corrected: M7's for the third time, because every
clause of it is satisfiable by two sides that never met — the endpoint answers, the suite is green, the
card draws a sentence, and the sentence is false — so it now requires a document rendered by the
server's own encoder to decode in the browser, and a golden file for the one contract module in the
repository that has never had one. M8's was tightened to name the three hard-coded literals a ninth
service moves, all of which broke when the eighth was added. And M10's records that wave 5 closed the
per-section count it asked for and that the hole moved one level up: a count of assertions cannot see an
assertion that stops asserting, and `DECISIONS.md` — from which ADR-052's row simply went missing — is
read by no script, workflow, build target or suite in the repository.

## What wave 6 actually did

Wave 6 was thirteen parallel packets over a disjoint file partition — **ten building and three
adversarial**, the same 3:1 as wave 5 — before its integration run was interrupted. M7 closed, on
its fourth criterion, with every clause executed against images built from the tree. At the limit
cutoff M8 had built the ninth service but left its ADR-035 stream returning 404 through the gateway.
The resumed integration added the gateway relay, published it in the merged contract, and mounted it
in both deployment shapes.

What changed, all of it re-measured at integration rather than read off a report.
`./scripts/run-tests.sh` is **3,767 cases over 69 modules, 66 with tests** (wave 5: 3,462 over 63,
57 with tests); `pnpm -C frontend test` is **1,631 over 71 files** (wave 5: 1,449);
`./mill __.openApiCheck` covers **57 paths, 68 operations, 154 schemas**;
`checkArchitecture` is 165 modules and 10 rules with no layering violations; `__.checkFormat`,
`__.fix --check` and `node frontend/scripts/boundaries.mjs` (391 files, 9 packages) are green; the
a11y sweep is clean over **762 stories** in both themes; and `./deployment/compose/smoke.sh` passes
**three consecutive runs from a torn-down stack** with nine services routed and an alerts feed
asserted, which is the run the packet that owns it could not make. The browser suite is 86 passed,
2 skipped and **one failure**: `brokers.spec.ts:57` fails in the full run and passes 9/9 when its own
spec is run alone — a load-dependent flake three independent agents saw and nobody has root-caused,
carried into wave 7 with the instruction to run it in both directions.

**Two things were red in the integrated tree and are repaired here.** `./scripts/feature-matrix-check.sh`
— wired into CI's `generated` job and not on the integration gate list — was failing with **12
disagreements**, because the merged documents gained the two alerts operations at integration and the
prose figures in ADR-048 and `frontend/packages/api/README.md` still published wave 5's 54/65/150.
The service and browser documents now publish 57/68/154, including the gateway-owned stream, and
the feature-matrix checker compares those figures with the generated document. `services/alerts`
had also reached the first integration pass complete but unroutable: the Mill module was W6-01's,
the gateway row W6-03's, and the dependency edge between them was in neither packet's `Owns`. The
integration repair added that edge; this continuation adds the separate relay that SSE requires.

### Did the mechanism work? Six numbers, then the answer.

**The three adversarial packets applied and scored 130 mutations, found 75 genuine ungated rules —
58% — and closed all 75 with a case that fails.** I re-applied **ten** of those closures at
integration, one at a time, across all three packets and both languages: the topic and message
cancelled-mutation classifications, `MessageMapping`'s tombstone, `AuditOffsets`' render order,
`UserDirectories`' case-insensitive key, `ClusterSnapshots`' uptime window, `RegistryCredentials`'
expiry, `RbacGuard`'s read-only lookup, `LoggingAuthAuditSink`'s field names, `BrokerList`'s empty
total and `SubjectList`'s `NONE` sentence. **Every one reddened the case the packet named**, and the
tree was byte-identical afterwards. Two further "green" mutations were reported by A3 as *not*
findings, with the reasoning shown — a redundant guard whose two values render identically, and a
query key that never binds — which is the first time an adversary has argued a green mutation *down*
rather than counting it.

**The ten building packets closed ten of ten owned rules and shipped 46 new ungated ones.** Every
owned rule was re-applied by an independent verifier and went red; I re-applied W6-01's myself. The
46 were found by verification, and **37 of them — 80% — were not disclosed** by the packet that wrote
them, under a house rule requiring exactly that disclosure. Disclosure has now moved 11% → 18% → 20%
in three waves while the rate of holes shipped per building packet has gone **3.5 → 3.7 → 4.6**. Five
waves of instruction have produced no measurable change in a builder's ability to find its own hole,
and the question is settled: it is not a discipline problem and a sixth instruction will not fix it.

**The one mechanism change this wave made did not happen at all.** House rule 13 said every
verification pass owns the test tree of the packet it verifies and lands a case for every hole it
finds. **Forty-six holes were found and zero cases were landed** — every verifier's evidence section
ends with the tree restored byte-for-byte to how it was found, and several name the case that *would*
close the hole without writing it. The rule cost nothing and bought nothing, and the reason is
structural rather than lazy: a verifier runs while the packet it verifies is still the live owner of
that test tree, so the only honest thing it can do is describe the case. Wave 5 converted nine
readers into nine closers on paper; on the tree it converted none.

**The arithmetic. Wave 4: 10 closed, 35 opened, net −25. Wave 5: 12 closed, 65 closed by adversaries,
33 opened, net +44. Wave 6: 10 owned closed, 75 closed by adversaries, 46 opened, net +39.** Per
packet: an adversary closed **25** rules; a builder opened **4.6**; a verification pass detected 4.6
and closed **0**.

**And the ungated rate in code nobody was editing rose again: 58%, against wave 5's 46% and wave 4's
29% census.** Three samples, three methods, and the trend is a property of the *hunt* rather than of
the code: random sampling found 29%, comment-guided found 46%, and wave 6's targeted sampling —
aimed at composition roots, teardown, mapping code whose only consumer is a route, and the fourth
clue nobody had written down, **a module that declares a test module in `build.mill` and ships no
test source or one suite for eight production files** — found 58%, with A1 at 67%. That fourth clue
is the strongest of the four: 22 of A1's 28 survivors sat in four such modules. The list of modules
that resolve as test targets and contain nothing went from six to **three**.

**A second pass over a package a previous adversary has already swept still pays, and pays more.**
A3 re-swept `feature-clusters` and `feature-schemas`, which wave 5's A2 swept at 33%, and found
**63%** — because A2's own finding (*every rule in `model.ts` is gated; sixteen of seventeen
survivors are in a `.tsx`*) told it where to aim. One adversarial pass does not exhaust a package;
it produces the map for the next one.

**Recommendation, and it is not a fourth adversary.** Wave 7 keeps thirteen packets and the 3:1
ratio, and **re-scopes one of the three adversaries into a closer**: A1 and A2 keep hunting code no
building packet owns, and **A3 runs last, after the building packets freeze, owning the test trees of
the wave's own new code with the verification reports as its input**. The case for it is the wave's
own densest seam: 46 holes per wave live in the code the wave just wrote, at 4.6 per packet and 80%
undisclosed, and it is the one place no adversary is permitted to hunt and no verifier may land a
case. Converting 46 found / 0 closed into 46 found / 46 closed costs one packet of the same
capacity that currently buys 25. House rule 13 is retired as written and replaced: **a verifier's
finding is either an owned rule in the next wave's plan or a case in the closer's packet, and a
finding with neither is not filed.** Every one of wave 6's 46 is an owned rule in wave 7 below.

What surprised us, in three shapes.

**A service can be complete, tested, imaged, documented and unreachable, and every gate can agree.**
`services/alerts` reached the first integration pass with 142 cases, its own OpenAPI document, a
container, a healthcheck, an `AllInOneWiring` entry and an ADR — and could not be routed, because the
one line joining it to the gateway (`alerts.contract.jvm` in `services.gateway.api`'s `moduleDeps`)
belonged to no packet's `Owns`. Two packets each did their half correctly. The half that was
nobody's was the *edge*. The same shape initially ate the stream: `ContractRouting.derive` cannot
carry SSE, the relay had to be hand-written, and no packet owned it. `AlertsStreamRoutes` and the
all-in-one mount assertion now close that edge. **A wave plan that partitions files does not
partition the edges between them, and an edge with no owner is invisible to every gate in the
repository.**

**The gate that was written to be un-neuterable was neutered in one line, and its own packet
published the opposite.** W6-09 rebuilt `feature-matrix-check.sh` around a claim registry, per-region
markers, a residue check and a manifest reconciliation, and reported that a green false figure now
costs five edits across three files where it used to cost one. Measured here: it costs **one** —
changing `carries("X-Csrf-Token")` to `carries("X-Kui-Principal")` in the script's own `jq`, after
which the two documents may publish any number at all and the run prints `216 claims checked, all
true`. The registry sees that a claim of that kind was made; nothing anywhere looks at what it was
compared *against*. This is the third wave in which the packet assigned to close a class of defect
created a fresh instance of it while closing the named one, and the second in which a packet's own
report asserted a gate that does not exist.

**The instruction to build a wire in one packet worked, and it is the only instruction that has.**
House rule 12 — one packet owns the DTO, the decoder and the golden between them, and the binding
case decodes the encoder's own output — produced ten golden documents read by a Scala suite and a
browser suite over the same files, and both halves go red when one is moved. Two waves of prose
contracts produced two mismatched wires and a card that lied about its source. The rule is cheap,
mechanical and the reason M7 closed; wave 7 applies it to the alerts wire, which is still two
hand-written mirrors of one document with `ALERTS_EVENT_NAME` copied by eye.

## What wave 7 actually did

Wave 7 was thirteen parallel packets over a disjoint file partition — **ten building and three
adversarial**, the same 3:1 as waves 5 and 6, with the third adversary re-scoped as a **closer** that
was to run after the builders froze and land a case for every hole their verifiers had filed. It
closed **M8**, on the clause that had never once been executed, and it built and routed the tenth
service without leaving its routing to the integrator. **M9 did not close** and **M10 was not
attempted**.

What changed, all of it re-measured at this integration rather than read off a report.
`./scripts/run-tests.sh` is **3,987 cases over 75 modules — and 75 of the 75 carry tests**, which is
the first time in this project's history that the runner names no module resolving as a test target
with nothing in it (wave 6: 3,767 over 69, 66 with tests; the list has gone six → three → zero).
`pnpm -C frontend test` is **1,767 over 77 files** (wave 6: 1,631 over 71). `./mill __.openApiCheck`
is 2408/2408 over **61 paths, 72 operations, 156 schemas** (57/68/154). `checkArchitecture` is 180
modules and 10 rules with no layering violations; `__.checkFormat` (234/234), `__.fix --check`
(4962/4962), `pnpm typecheck` and `node frontend/scripts/boundaries.mjs` (409 files in 10 packages)
are green; the a11y sweep is clean over **780 stories** in both themes;
`./scripts/feature-matrix-check.sh` is **251 claims, all true**; and `./deployment/compose/smoke.sh`
passes with ten services routed, a `connect capability: available` row and the connect read asserted.

**M8 closed on a frame.** Held open with nothing changing, the gateway's alerts stream is silent; a
`POST …/acknowledgement` three seconds in produced
`event: alerts` / `data: {"cluster":"quickstart","openCount":0,"at":"…"}`, and `shell.spec.ts:127`
— *acknowledging an open event moves the bell, with no reload* — passed on the same stack, where at
verification it had run and skipped. Both halves are quoted in M8 above with the image ids.

**One gate was red at integration and is repaired here.** `./mill __.fix --check` — added to the
integration list this wave — failed on `apps.allinone.fix`: scalafix wanted the two new
`kui.connect.*` imports moved below the multi-line `kui.config` block in `AllInOneWiring.scala`. Two
packets wrote into that file and neither owns its style gate. Repaired with `./mill apps.allinone.fix`
(import order only) and re-verified. No previous wave's integration ran that gate; it caught exactly
the class of thing it was added for, on its first run.

### Did the mechanism work? Seven numbers, then the answer.

**The ten building packets closed ten of ten owned rules.** Every one was re-applied by an independent
verifier who watched the named case go red and then restored the tree from bytes. That is **40 of 40
across four waves**. Naming the rule, in the packet that owns the file, remains the only mechanism in
this project with a perfect record, and nothing here should be changed about it.

**The same ten packets shipped 44 new ungated rules — 4.4 each — and disclosed six of them.** That is
a disclosure rate of **14%**, against 11% → 18% → 20% in the three waves before it. Wave 7 is the
first wave in which every builder was told in its own plan that an adversary would run over its
frozen code afterwards, and disclosure **fell**. Six waves of instruction have now produced no
measurable improvement, and one wave of pre-announcing the audit made it worse. The question is
closed: it is not a discipline problem, a seventh instruction will not fix it, and the only thing
that has ever found a builder's hole is somebody else mutating the builder's code.

**The three adversaries scored 113 mutations, found 51 genuine ungated rules, and closed 44 with a
case that fails.** A1: 36 scored, 25 ungated (**69%**), 22 closed. A2: 32 scored, 16 green of which
15 genuine (**50%**), 11 closed. A3: 45 scored, 12 green of which 11 genuine (**25%**), 11 closed.
The seven left open are each named with the production seam they need — a Kafka `Admin` stub two
modules do not have, a keystore whose zero-fill is unobservable from outside, a Caffeine bound that
evicts approximately, and one timing property a unit case cannot honestly assert, which the packet
said rather than faked. **Two green mutations were argued *down* as non-defects** with the reasoning
shown — an inert SSE comment filter whose behaviour comes from the field grammar, and a
`rest > 0 ? rest : undefined` whose consumer already swallows both values — and one of those
corrected a **false gate claim a previous wave's adversary had written into a case comment**.

**I re-applied eleven of the 44 closures at this integration, across all three adversaries and both
languages, one at a time, restoring from saved bytes after each.** `ConnectCapabilities.degraded`'s
features, `WireFormat`'s schema id zero, `OffsetLookup`'s no-node leader, `SchemaWiring`'s bulkhead,
`Pbkdf2`'s salt width, `ConfiguredProfileSource`'s fallback connection, `feature-connect`'s
whitespace trace, the kernel's timeless resolution, `feature-alerts`' acknowledger, `feature-schemas`'
neutral format tone and `feature-clusters`' absent scrape instant. **Every one reddened the case the
packet named**, by name, and the tree was byte-identical afterwards.

**The arithmetic. Wave 4: 10 closed, 35 opened, net −25. Wave 5: 12 + 65 closed, 33 opened, net +44.
Wave 6: 10 + 75 closed, 46 opened, net +39. Wave 7: 10 + 44 closed, 44 opened, net +10.** Per packet:
an adversary closed **14.7** (wave 6: 25, wave 5: 22); a builder opened **4.4**; a verification pass
detected 4.4 and closed **0**, for the third wave running.

**And the fall is explained by the two seams running out and by the closer never running.** A1 and A2
both report the clue that produced wave 6's 58% is **spent**: "declares a test module in `build.mill`
and ships no test source" went three → zero *during this wave*, because A2 wrote the suites. A3's
third pass over `feature-clusters` and `feature-schemas` found **23%**, against 63% and 33% on the
same two packages — and every one of its three findings was in a `.tsx`, while all seven mutations it
put on the two `model.ts` files were gated. That is the first evidence this project has that a part
of it is finished being hunted: **the pure-data modules of those two packages are swept.**

### The closer did not run, and the pre-commitment measured the wrong thing

Wave 6's pre-commitment was: if the closer shuts more rules than the two hunters together, it becomes
permanent and one hunter is dropped to 4:1. It closed 11 against their 33, so by the letter the ratio
stays. **That answer is not usable, because the closer never received its input.** Its own report says
so plainly: *"the ten verification reports are not in the repository and were not handed to me… with
no reports on disk I hunted the same code from scratch instead."* The mechanism that was to be tested
— 44 filed findings arriving as 44 landed cases — was never run. What ran was a third hunter pointed
at frozen code, and it yielded 25% where a hunter on unowned code yielded 69%.

**The number that matters instead is the overlap, and it is zero.** A3 hunted the same code ten
verification passes had just mutated, and **not one of its eleven findings is among the verifiers'
44**. Its kernel findings are in `data/alerts/events.ts`; the verifier's two are in `store.ts`. Its
three `feature-connect` findings are none of that verifier's eleven. Its `feature-alerts` finding is
not among that verifier's three. So the wave's own new code holds **at least 55** ungated rules — 44
found by reading and mutating against a report, 11 found by mutating blind — and the two methods found
disjoint sets. The seam is real and it is denser than anyone has measured; what is missing is a wire
from the finding to the case, and that wire is a **file**, not a good intention. Wave 8 writes the
verification reports into the tree as the packets freeze, and the closer's `Owns` includes them.

**Recommendation, stated as a change rather than a ratio.** Wave 8 keeps thirteen packets and 3:1, and
changes exactly one thing: **the closer is handed a list, not a licence.** Each verification pass
writes `docs/plan/verification/W8-<packet>.md` with its findings in the shape the closer consumes —
file, exact mutation, the case that closes it — and the closer's acceptance is *a case per filed
finding*, verified red. The pre-commitment is re-run properly: **if a closer handed the wave's own
filed findings closes thirty or more of them, it becomes permanent and replaces one hunter at 4:1; if
it cannot, the closer is retired for good and every finding becomes an owned rule in the next plan,**
which is the mechanism with the 40/40 record. And the hunters retire the spent clue for A1's new one,
which is the sharpest thing either of them found: *every ungated rule in a file that already had a
suite was ungated because the fixture could not express the failing input, or because no assertion
ever read that field.* A hard-coded `headers = Nil` in a test helper is cheaper to grep for than a
missing suite.

What surprised us, in three shapes.

**A spec can be the only thing standing between a finished service and a closed milestone, and it can
fail for the reason it was written to prevent.** `services/connect` is built, routed, imaged, capable
and answering real documents through the gateway. `e2e/connect.spec.ts` hand-writes
`connectors.data.items` where the service renders `connectors.data.workers[].connectors.data.items`,
so its two positive cases skip forever and its shape assertion **fails against a correctly routed
server** — measured here, on images built from this tree, after the 404 that had been masking it went
away. The package it tests was rewritten mid-wave for exactly that wire, and the golden suite that
binds the decoder to the service's own documents does not cover `e2e/`. House rule 12 has a hole in
it the shape of the browser suite.

**A packet can measure its own gate honestly and still publish a false price for it, three waves
running.** House rule 17 was written in this wave's own plan because wave 6's checker packet claimed
five edits where it cost one. Wave 7's checker packet raised the real price from one to **two** — a
genuine improvement, and the single-line attack no longer works — and published **four**. It is
reproduced above in two lines. Five further guards in the same rebuild, four of them asked for by
name in the plan, survive mutation with the run green. The pattern is now specific enough to state as
a rule of estimation: a packet's claim about the cost of defeating its own gate should be treated as
unmeasured until someone else defeats it.

**A wave can close a milestone with a command nobody in the wave ran.** M8's outstanding clause was a
`curl -N` delivering a frame and an e2e case asserting the bell moves. Both were within reach of every
packet in the wave; one packet ran the curl on an instance it then destroyed and left no capture, and
the packet that owns the e2e case reported it as *not run* when in fact it runs and skips. At
integration both took under ten minutes, needing only two images built from the tree and one
`diskUsedWarningPercent: 1`. **The gap between "the code is right" and "the criterion is met" was two
commands, and thirteen packets left it open.** Wave 8's plan therefore makes the stack rebuild an
explicit, owned, first-hour act rather than a line inside somebody's acceptance list.

No milestone changed order. Two exit criteria were corrected: M9's, which is now recorded clause by
clause with the two that are unmet and why, and M10's, which records that the price of a green false
figure moved from one edit to two and that its packet published four.

## What wave 8 actually did

Wave 8 was thirteen packets and **nineteen agents over three runs**, and it was **killed twice by a
session limit before it finished**. Ten building packets and three adversaries were planned; the
interruptions did not stop the building work, and they did stop almost all of the machinery that was
supposed to measure it. Both halves of that sentence matter and they are separated below, because the
wave's own commit message — *"seven of nineteen agents finished… six building packets plus all three
adversaries never ran"* — was written at the second cutoff and is **not** what the tree says.

**Judge the tree, not the reports. All ten building packets landed.** Re-checked file by file here:

| Packet | What is on disk | Filed a verification file? |
| --- | --- | --- |
| W8-01 | `services/ksql`, all six ADR-041 layers, 41 Scala sources, its own `openapi.json`, ADR-055, the `SseEventName` for the push stream, and the `build.mill` edges landed first | **no** |
| W8-02 | ADR-052/053/054 corrections, and new cases across `connect`, `alerts` and `metrics` (`ConnectorsSuite`, `ConnectHttpSuite`, `ConnectWiringSuite`, `InMemoryAlertStoreSuite`, `PrometheusExpositionSuite`) | yes |
| W8-03 | `KsqlStreamRoutes.scala` — the hand-written relay house rule 16 demands — `ServiceContracts`, `ARCHITECTURE.md` §3, and `MessageStreamRoutesSuite`/`SseFrames` beside it | **no** |
| W8-04 | `feature-connect` rewritten and `e2e/connect.spec.ts` rewritten against the service's own golden; 62 → 75 cases | yes |
| W8-05 | `feature-ksql`, the eighth feature package — 20 files, ~3,900 lines with its documents — plus `e2e/ksql.spec.ts`, ADR-056, `registry.test.ts` and the `tsconfig` reference | **no** |
| W8-06 | the kernel's alerts store, the eighth `FeatureId`, `styles/index.css`'s line for `82-ksql.css`, `a11y-stories.mjs`, and **`frontend/vitest.config.ts`, which is new and is the axe flake finally closed** | **no** |
| W8-07 | `App.tsx`, `nav/`, `routing/`, `chrome/`, `overview.render.test.tsx`, four new browser cases, the `SCREENS-V4` ksql paragraph — **and the twenty-three-screen mapping, published for the first time** | yes |
| W8-08 | the four screens' permission question given its subject, the alerts chip bar gated, `pollUntilListed`'s short-circuit gated, the alerts fixtures regenerated against the service's vocabulary | **no** |
| W8-09 | the merged documents, `schema.d.ts`, `docs/overview/README.md`, the `README` re-measurements, TD-023/024/027, and 486 lines of new refusals and fixtures in `scripts/feature-matrix-check.sh` | **no** |
| W8-10 | the eleventh container, `smoke.sh`, the quickstart's ksqlDB server and Connect worker, `connect-seed.sh` and `ksql-seed.sh`, `AllInOneWiring`, `ci.yml`, `libs/config`'s suites | **no** |

**All three adversaries ran too.** Their work is on disk as 31 changed or new test files and no
production file anywhere in the repository is modified — checked here, and checked by each of them
against bytes they saved themselves rather than with `git restore`.

So the honest count is **ten of ten building packets landed and three of ten filed the file house
rule 18 asks for**. A packet with no report is not a packet that did not run; six of the seven silent
ones shipped the largest surfaces in the wave.

**The gates, re-run here rather than read off the integration report.** `./mill __.compile`
8251/8251. `./scripts/run-tests.sh` **4,254 cases over 81 modules, all 81 carrying tests** (wave 7:
3,987 over 75). `./mill checkArchitecture` **195 modules**, 10 rules, no layering violations (180).
`./mill __.openApiCheck` 2629/2629 over **65 paths, 76 operations, 160 schemas** (61/72/156).
`./mill __.checkFormat` 252/252; `__.fix --check` 5353/5353. `pnpm -C frontend test` **1,895 over 82
files** (1,767 over 77). `node frontend/scripts/boundaries.mjs` **424 files in 11 packages** (409 in
10). The a11y sweep is clean over **790 stories × 2 themes**. `./scripts/feature-matrix-check.sh` is
**283 claims over seven sections, all true** (251 over six) — the one gate the wave-8 checkpoint left
knowingly red, closed by strengthening the checker and not by relaxing it, with per-section counts
that all went **up**. And `pnpm -C frontend e2e` is **101 passed, 4 skipped, 0 failed**, which is the
first green browser suite this project has had.

**Two gates were not on the integration list and both needed to be.** `pnpm -C frontend e2e` was red
when I ran it — two `ksql.spec.ts` failures — and `./deployment/compose/smoke.sh` has still not been
run since the eleventh service landed. The e2e failures were a stale `kui-frontend` image and are
written up in M9; the smoke gate is in M10's unmet list.

### What the interruptions cost, itemised

1. **House rule 18 ran at 30%.** Three of ten verification passes wrote their file. The mechanism
   the whole wave was designed around — a verifier files, the closer closes — was handed **23 filed
   findings** where the wave's own arithmetic predicted about forty-four.
2. **The wave's largest new surface was verified by nobody.** `services/ksql` is 41 Scala sources in
   six layers; `feature-ksql` is the eighth feature package; the gateway's push-query relay is
   hand-written code that `ContractRouting.derive` cannot generate. None of the three has ever been
   mutated by anyone, and none has a filed finding. That is where wave 9's hunter goes.
3. **The stack owner froze before the tree was buildable, and nobody rebuilt.** W8-10 obeyed house
   rule 19 exactly — images built in the first hour, ids published — and then the frontend *build*
   broke mid-wave on `feature-ksql`'s missing exports and was repaired later by somebody else. The
   served `kui-frontend` image carried seven feature chunks and no `feature-ksql` fourteen hours
   later, and the eleventh service's browser evidence did not exist until I rebuilt one image at
   integration. **House rule 19 needs a second half: the stack is rebuilt again after the last
   packet freezes, by the same owner, and the browser criteria are run against *that*.**
4. **A load experiment outlived its packet and made four gates look red.** W8-A2's house-rule-9
   re-verification of the `ProfileChangeListener` flake spawned sixteen busy loops whose `kill` line
   did not take; forty-nine of them were still spinning ninety-seven minutes later at a load average
   of 62 on 16 cores. `./mill __.openApiCheck` then failed a *different random subset* of its ten api
   modules per run with `Subprocess failed` and no stderr — indistinguishable from a stale committed
   document — and `pnpm test` aborted with `Timeout starting forks runner` having executed **zero**
   test files while exiting non-zero. The integrator proved the documents current by extracting each
   module's runtime classpath and running all ten `OpenApiDocument --check` mains directly under
   `java` before touching anything, then killed the processes; both gates went green immediately.
   **A packet that spawns load traps its own kill, and a frontend run that reports `Test Files no
   tests` is not a red suite — it is not a measurement at all.**
5. **`stash@{0}` is four waves old and still here.** Wave 8's plan said wave 9 is the place to say so
   out loud. It is said in `WAVE-09.md` and it is a deliberate act for a human, not a line item.

### Did the mechanism work? Six numbers, then the answer.

**The three adversaries scored 94 mutations, found 45 genuine ungated rules, and closed 42 with a
case that fails.** A1: 40 scored, 17 ungated (**42.5%**), 14 closed, 3 filed with the production seam
or the environment each needs. A2: 37 scored of which 3 are **provable equivalent mutants argued down
rather than counted**, 11 ungated of 34 (**32%**), all 11 closed. A3, the closer: 17 mutations, 17
closures, **16 case-level changes** — and its number is a different kind of number, below.

**A1's replacement clue fired eleven times out of seventeen and was right every time.** Wave 7 retired
*"declares a test module and ships no test source"* as spent and replaced it with *"a rule ungated in
a file that already had a suite is ungated because the fixture could not express the failing input, or
because no assertion ever read that field."* The instances are worth quoting because they are a
grep-able shape: `KafkaRecordSourceSuite` read `offsets(records).map(_._2)` and nothing read the
partition half of a tiebreak; every log in that suite was complete before the browse started, so no
fixture could put a record *at* a window's bound; `MutationAuditSuite`'s local `toKui` could only
produce 4xx codes, so `httpStatus < 500` had never been asked about a status on the other side of its
own boundary and the guard's entire `Left(error)` arm had no case at all; `PurgeTokenSuite` always
passed a list already in partition order; `CachingSchemaRegistrySuite` used `CacheMetrics.noop` and
exactly one subject. **The clue is not spent and should be carried again.**

**A2's sweep produced the project's second piece of evidence that a part of it is finished being
hunted, and its sharpest finding is one character.** Nothing it broke in `libs/kernel`, `libs/cache`,
`libs/observability`, `libs/contracts-core` or `libs/serde` survived; all three of its `libs` holes
were in `libs/security-core` and all three were in code with no production caller or no downstream
reader. It declares `services/schema`'s `RegistryCredentials` and `libs/serde`'s detection table
finished, and explicitly **refuses** to declare the four modules it put one or two mutations into
finished, which is house rule 10 obeyed against its own interest. And: `Permission.covers`' `case _
=> false` is one character from a fail-open — a `TOPIC` permission with a forgotten `value` would
grant every topic in the cluster — and inverting it reddened **not one case** in eleven services and
thirteen libs.

**Wave 8's building packets' own disclosure rate cannot be computed, and saying so is the point.**
Seven of ten filed nothing, so the denominator does not exist. Six waves of instruction produced no
improvement in disclosure and wave 7 established that pre-announcing the audit made it worse; wave 8
establishes something narrower and more useful — **the report is the fragile part of the mechanism,
not the discipline.** It is the thing that does not survive an interruption, and it is the thing
everything downstream is built on.

### The closer experiment, answered — and the answer is not the one the pre-commitment asked for

Wave 7's closer hunted blind because its input never arrived, and its eleven findings had **zero
overlap** with the verifiers' forty-four. Wave 8 handed the closer three files.

**Every one of W8-A3's seventeen closures came from a filed finding. It did not hunt once, and it did
not need to: the filed list never ran out.** Seventeen mutations applied, seventeen rules closed, and
`fromFiledReport: true` on all sixteen rows of its report. Two further findings were **argued down**
with the reasoning shown — a dropped e2e case that only ever ran on a stack that does not exist, and
a W8-04 acceptance line re-measured as *resolved* rather than owed (`pnpm build` exit 0, eight feature
packages, `feature-connect-B5jO-rTJ.js` a real dynamic chunk). Four more, plus two structural halves,
were handed to the owners of the production files they need.

**Closing beat hunting, and it is not close.** Per mutation applied: the closer converted **17 of 17**;
A1 found 17 ungated in 40 (42.5%); A2 found 11 in 34 (32%); wave 7's blind closer found 11 in 45
(25%). A hunter spends three or four mutations to find one hole. A closer spends one, because
somebody already found it — the mutation is only there to prove the case goes red. The filed finding
is doing the expensive half of the work and it is doing it **inside the packet that wrote the code**,
which is also where it is cheapest.

**By the letter of the pre-commitment, the closer is retired. That reading should be rejected, and
here is why, without hedging.** The commitment was *thirty or more filed findings closed*. Seventeen
is fewer than thirty. But the number thirty was calibrated against wave 7's forty-four filed findings
from ten verification passes, and wave 8 produced **twenty-three from three**, because seven agents
were killed before they could write a file. The closer closed 74% of everything that was filed and
then stopped because there was nothing left to close. **The pre-commitment measured the closer and
what it actually measured was house rule 18's compliance rate.** A closer cannot close findings
nobody wrote down.

**Recommendation for wave 9, stated as a change.** The closer is **kept and made permanent**, and the
pre-commitment is re-stated against the input rather than against a flat count: *a closer that lands
a verified-red case for **80% or more of the findings filed to it** is the right role, whatever the
absolute number.* Wave 8 hit 74% with two argued down and six correctly refused as production edits
it did not own, which is 100% of what it could legitimately close. Two further changes follow from the
evidence rather than from preference:

* **The ratio moves to one hunter and one closer.** Wave 8's hunters are hitting 32–42% on the trees
  they sweep and both report their richest seams narrowing; the closer hits 100% and is starved. The
  binding resource is filed findings, so wave 9 buys more of them: the hunter is pointed at the three
  surfaces nobody has ever mutated — `services/ksql`, the gateway's relay and `feature-ksql` — and
  **its output is a filed file, in the closer's shape**, not a set of closures of its own.
* **A packet is not done until its verification file exists.** Wave 8 proves the file is the first
  thing an interruption takes. In wave 9 it is written *first*, as a stub naming the mutations the
  pass intends, and filled as they are run — so a packet killed halfway leaves a partial file rather
  than nothing, which is strictly more than three of ten.

### What surprised us, in three shapes

**A gate can be cheaper than three consecutive waves published, and the packet that finds that out is
the one that attacked it.** W8-09 reproduced the two-line attack the plan handed it, then found the
gate it had inherited cost **one** line — a comparison of a fact with itself, which every existing
guard agreed with and each was right to, because the kind was right, the marker was right, the count
was right and the independent re-derivation was pointed at the same number. Nothing in a 1,200-line
checker read the **claimed** side of a comparison. House rule 17 has now produced, on its first
obeyed application, a *lower* published price than the plan's — which is exactly the direction it was
written to make possible.

**An image is a measurement instrument and it goes stale silently.** The eleventh service was built,
routed, capable, answering real ksqlDB objects through the gateway, and its screen had never been
rendered by a browser, because one image was fourteen hours older than the build that could produce
it. Nothing was red. The service was green, the suite was green, the a11y sweep was green, the bundle
had eight dynamic chunks — and the container serving `/ui/` had seven. **The tree being right and the
stack being right are two facts, and wave 8 is the second wave running in which the second one was
nobody's after the first hour.**

**Half of what a wave produces is the evidence that it worked, and that half is what an interruption
deletes.** Wave 8 lost no production code to being killed twice. It lost seven verification files, a
disclosure denominator, an image rebuild, two gates from the integration list, and the input its own
mechanism experiment was designed to consume. The code survived the interruption and the *knowledge
about the code* did not — which says that the cheapest thing a wave can do to become
interruption-proof is to write its findings down as it goes rather than at the end.

No milestone changed order. **M9 closed.** M10 closed four of its six bullets and is open on four
named things: two screens of browser coverage that need one line of quickstart configuration, the
dependency-row half of its own weakening criterion, a `smoke.sh` run, and `docs/plan/` reducing to two
files. Wave 9 is a short closing wave and is the last one in this plan.
