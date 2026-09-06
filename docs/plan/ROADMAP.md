# The plan: from here to the twenty-three screens

**What this is.** The milestone order that turns this repository into the product drawn in
`screens/` and read in [`research/design/SCREENS-V4.md`](../../research/design/SCREENS-V4.md).
It is the only long-lived document in `docs/plan/`; see [README.md](README.md) for how the
directory works.

**It supersedes `docs/ROADMAP-SOLID.md`**, which was written before two commits landed that
completed four of its ten milestones — and whose banner still overstates one of the four: `7193d2d`
met M2's exit criterion, but M2 also asked for the dead Scala `e2e/` module to be deleted and for a
build-time bundle-shape check, and neither had happened when the banner was written. The module was
deleted in wave 2. The bundle-shape check still does not exist: `TECH_DEBT.md`'s TD-016 was closed
on a real Vite manifest showing all five feature packages under `dynamicImports`, and closed with
the guard explicitly left open, so the only thing between here and the same regression is a
convention.

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
| M7 | `services/metrics` — **the shape already exists**, see below | 6 dashboard cards, 4 stat cards, the whole Traffic tab |
| M8 | `services/alerts` | Alerts tab, the alerts card, the notification bell |
| M9 | `services/connect`, `services/ksql` | the `ECOSYSTEM` group |

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


## M7 — `services/metrics`  ·  the adapter exists; four endpoints and the cards do not  ·  **NOT CLOSED, wave 5 finishes it**

**Wave 4 built the half that was hard and it works.** There is now a Prometheus text-exposition
reader, a retention buffer over `SeriesWindow`, a scrape loop, and one endpoint answering real
numbers. `ConfiguredClusterSources.source` returns a real source. `MetricsSourceKind.Jmx` stays
declared and unimplemented behind a stated refusal, for the reason ADR-050 gives: `SafeUrl` is
`http`/`https` only by `ARCHITECTURE.md` §14, so a JMX service URL cannot be written into
`MetricsSourceSettings.url` at all — implementing that row means a new settings shape, not a new
adapter behind the same one.

**Measured here, on the running quickstart, not read off a report.**
`curl -s '…/clusters/quickstart/metrics/throughput?range=24h'` answers `"status":"ok"` with **288
buckets**, which is `ThroughputRange.Last24Hours.bucketCount` and not the number of samples, of which
46 carried a rate. That is the assertion two waves of this criterion could not make.

**The scrape loop was tested by breaking it, and it is correct.** The claim reached this document
that the collector "does not survive its first failed pass" — that a cold start against an absent
exporter leaves a cluster silently unmeasured for the life of the process. It is **false**, and it was
checked rather than argued: the exporter was stopped for 150 s under a running KUI (five WARN passes,
one per interval, then `circuit … is now open`, `halfopen`, `closed` and the series resumed); and then
KUI was restarted with **no exporter at all**, so that its first scrape failed the way it does in the
distributed stack, and it filled a bucket **26 seconds** after the exporter came back — one scrape
interval. The loop retries, the circuit breaker recovers, and a failed scrape evicts nothing.

**So what stops M7 closing is one deployment and four endpoints.**

* **`./deployment/compose/smoke.sh` fails, deterministically, at the one assertion M7 was written to
  add.** Two independent runs from a torn-down stack both ended `FAILED: buckets carrying a measured
  rate was 'no' after 90s`. Everything before it passes, including `the exporter serves both byte-rate
  families` and `the measured cluster's throughput: ok`. `.github/workflows/ci.yml:342` runs that
  script, so the compose job is red as shipped. **The cause is not established.** `kui-metrics` has no
  `depends_on` in `deployment/compose/docker-compose.yml` while the exporter waits on the broker's
  health, so it certainly starts first — and adding one `depends_on` made the script pass. But the
  reason given for why that matters is the loop claim disproved above, and a compose stack that
  produces no traffic serves an exposition of `0.0`, which is a *measured zero* and not a null. A fix
  that works for a reason nobody can state is how this repository got `degraded` where it meant
  `available`. Wave 5 reproduces it and explains it before it patches it.
* **Four endpoints do not exist:** `…/metrics/latency?window=`, `…/metrics/request-handlers`,
  `…/metrics/producers?top=`, `…/metrics/record-size`, each `Section`-wrapped so one dead exporter
  costs one card.
* **The cards that read them do not exist:** the p99 line with the current value in each legend chip,
  the ring gauges, Top producers — which is what finally gives `Monogram` a caller, four waves after
  it was built — the message-size histogram, and the four stat-card sparklines. The Traffic tab is
  built and draws throughput; its last row is three `NotMeasured` sentences.
* **And two of those four cards may not be measurable at all from the source this product reads,
  which has to be decided before anything is built rather than discovered halfway.** The exporter
  beside the quickstart serves six lines, because its ruleset whitelists three families — checked
  here. Widening it reaches what Kafka's own JMX surface has, and that surface answers some of these
  questions and not others. `RequestMetrics.TotalTimeMs` carries real percentiles, and
  `RequestHandlerAvgIdlePercent` and `NetworkProcessorAvgIdlePercent` are real ratios, so the p99 line
  and two of the three ring gauges are ordinary work. But **§3.4 draws "38% PURGATORY" and
  `DelayedOperationPurgatory` publishes a queue *size*, not a percentage**; **§4 draws Top producers
  by `client.id` and a broker publishes no per-`client.id` byte rate unless quotas are configured**,
  only a per-*topic* one; and **§3.5 draws a twelve-bucket record-size histogram with p50/p99/max
  chips, and Kafka publishes no record-size distribution of any kind** — only a mean, as
  bytes-in over messages-in. Each of those three is a design decision taken in the open in an ADR:
  redraw the card as what can be measured, or keep the drawing and keep the `NotMeasured` sentence.
  What none of them may become is a figure derived from something else and labelled as the thing the
  design named, which is definition-of-done rule 1 and the reason this whole service exists.
* **The two-cluster half of the criterion is only provable on the compose stack.** The quickstart runs
  one cluster, so `traffic.spec.ts`'s `not_configured` case is `test.skip`ped with a true reason, and
  the criterion's "a second cluster with no `kui.metrics.sources` entry still answers
  `not_configured` **on the same deployment**" is asserted by `smoke.sh` alone — the script that
  fails.

**What is wrong with the old exit criterion, for the second time.** Its corrected form asked for a
configured source and a series with real numbers, which was right and is now met. What it did not
say is which deployment proves the *pair*, and the answer turned out to be the one stack no green
gate covers. The form below names it.

**Exit:** `./mill services.metrics.__.test` and `./mill __.openApiCheck` pass; against a quickstart
built from the working tree
`curl -s '…/clusters/quickstart/metrics/throughput?range=24h' | jq -e '.throughput.status == "ok"'`
succeeds and `jq '.throughput.data.buckets | length'` equals `ThroughputRange.Last24Hours.bucketCount`
with at least one bucket carrying a non-null rate; the same four assertions hold for
`…/metrics/latency?window=`, `…/metrics/request-handlers`, `…/metrics/producers?top=` and
`…/metrics/record-size`; **`./deployment/compose/smoke.sh` passes three consecutive runs from a
torn-down stack**, which is the only place the measured and unmeasured clusters are asserted on one
deployment, and the reason it fails today is written down before it is repaired; and
`pnpm -C frontend e2e` asserts, for every card whose ADR says it can be measured, a series drawn for
the configured cluster and the `NotMeasured` sentence — not an empty axis — for a cluster with no
source; and for every card whose ADR says it cannot, the sentence on **both** clusters, with the ADR
naming the family that is absent and a captured exposition body from a real broker showing it absent,
so that the refusal is evidence and not an omission.


## M8 — `services/alerts`  ·  **NEW SERVICE**

No alert definition, severity, acknowledgement or event record exists anywhere. Two pieces do:
`Resource.Alerts` with a non-altering `AlertsView` and an altering `AlertsAcknowledge`, and
`kui.alerts` with a retention window and the threshold values the rules below read — both shipped
in wave 1 so this milestone does not have to reopen the vocabulary or the config while it is also
writing a service. The Alerts tab, the alerts card and the bell all read one feed.

One decision was taken early and should be revisited here rather than inherited: `AlertsAcknowledge`
is marked altering, and `isAlter` answers both the audit question and the read-only question with
one field, so acknowledging an alert is refused on a read-only cluster even though it writes to
KUI's own store and not to Kafka. If that is wrong, the fix is to split `isAlter`, and this is the
milestone that knows enough to decide.

* Rules over facts the product already has: offline partitions, under-replicated counts, a group
  stuck rebalancing, a log directory past a threshold, a failed connector task (M9), a schema
  registration.
* An event record with an opened-at, a severity, a category (§3.9 — severity chooses the tone,
  category chooses the glyph) and a resolution, in a store, because a percentage of "1h ago" cannot
  come from a `SnapshotCell`.
* `GET …/events`, an open count, a per-principal read marker, and an ADR-035 stream so the card and
  the bell cannot disagree.

**A note on its exit criterion, written now because M7's was found to be satisfiable by an empty
service.** `jq '.events.status'` being `ok` is true of a feed that has never held an event, so the
criterion below asserts the seeded event reaches the document as well as the screen.

**Exit:** `./mill services.alerts.__.test` passes; after seeding one event,
`curl -s …/events | jq -e '.events.status == "ok" and (.events.data.items | length) > 0'` succeeds and
`…/events | jq '.events.data.openCount'` is that event's count; and `pnpm -C frontend e2e` asserts
that after the same seeded event the bell carries its unread mark and the alerts card's pill count
equals the API's open count.

## M9 — `services/connect` and `services/ksql`  ·  **NEW SERVICES**

The vocabulary is already shipped and unused: `ConnectName`/`ConnectorName`/`TaskId`,
`ErrorCode.ConnectRebalancing`, the whole Connect RBAC closure, and three orphan kernel components
with no feature behind them. Wave 1 added the rest of it — `ConnectClusterSettings` and
`KsqlSettings` per cluster (so `kui.clusters.0.ksql.url`, which used to be a *failed boot* under
the unknown-key check, now loads), and the non-altering `KsqlView` that `KsqlExecute` implies.
Until the services exist, ADR-032's `not_configured → hidden` rule is already the correct rendering
and the rows must not be faked.

* `services/connect` over the Connect REST API: connector list with expanded status, per-task
  state, pause/resume/restart as mutations, deploy, and the failure reason §7.7 asks for.
* `services/ksql`: object listing, statement execution as a mutation, push queries over ADR-035,
  and plan→token→confirm for `DROP … DELETE TOPIC`. The read-only case is already settled:
  `KsqlView` exists and `KsqlExecute` implies it, so a read-only cluster lists objects and refuses
  statements, which is asserted in `RbacLawsSuite`.
* Two feature packages, two routes, `FeatureId` widened, and `ECOSYSTEM` finally has three rows.
* The ksqlDB result region, which no capture shows — decide it here (§7.9) before building it.

**Exit:** the capability document carries a `connect` row —
`jq -e '.entries[] | select(.key.service=="connect")'`, and note the shape, which is
`entries[].key.service` and not the `services[].id` this document used to name; with no Connect
address configured the drawer shows no Kafka Connect row, and with one it shows the connector
count — both asserted by `pnpm -C frontend e2e`.

## M10 — Close the book  ·  no new service

* `docs/FEATURE_MATRIX.md` accurate against the code, re-counted with the command it publishes.
* README, ARCHITECTURE and a newcomer's overview describing what is now true.
* ADRs for the decisions this plan forced: the sixth series colour, the metrics source, the event
  model, the payload-status projection (§7.1), the theme-control convention (§7.4).
* The a11y sweep clean over every story in both themes; the browser suite covering all
  twenty-three screens; `docs/plan/` reduced to `README.md` and this file.

**The comparing command now exists.** Wave 3 wrote `./scripts/feature-matrix-check.sh`: it re-counts
the matrix's rows, re-derives the merged document's paths, operations, schemas and header coverage
with `jq`, re-reads the pinned npm versions, and compares each against the prose inside
`<!-- checked: … -->` regions in six documents. It printed `49 claims checked, all true.` here, it
is wired into `ci.yml`'s `generated` job, and thirteen single-word mutations across four documents
each made it exit 1. Wave 4 named the glob and closed it: the manifests are a named array, disk is
reconciled against that array, and both halves fail loudly when broken.

**And the same hole reopened one line over, which is the finding this milestone should carry rather
than the fix.** `close_section` floors each section at `counted > 0` and not at a count, so handing
`jq` the *first* manifest instead of all nine drops the run from 49 assertions to 45 and still prints
"all true" and exits 0 — byte for byte the output the old glob produced. Three more of the script's
own assertions can be deleted the same way, and the milestone table `docs/FEATURE_MATRIX.md` publishes
about itself still sits outside every `<!-- checked: -->` marker while the state totals were brought
inside one. A gate whose floor is "at least one assertion ran" is a gate that measures its own
liveness and not its own coverage, and this is the file whose entire job is to guarantee that about
every other file.

**Exit:** from a clean checkout, with every container image built from it:
`./scripts/run-tests.sh`, `pnpm -C frontend test`, the a11y sweep (build, serve, sweep — see the
ordering rule), `pnpm -C frontend e2e`, `./mill __.openApiCheck`, `./mill checkArchitecture` and
`./deployment/compose/smoke.sh` all pass; `./scripts/feature-matrix-check.sh` exits 0, fails when any
one input file is made unreadable, **and fails when any one section is made to check fewer claims
than it published last time** — a per-section count and not a `> 0` floor, which is the hole the glob
fix left behind; and every count `docs/FEATURE_MATRIX.md` publishes about itself, the milestone table
included, sits inside a checked region.

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
