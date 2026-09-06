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

## M2 — One command, the whole product  ·  no new service  ·  **NOT CLOSED**

The stack comes up and serves an interface. `deployment/compose/docker-compose.yml` now runs seven
containers — five backend services, the gateway and `kui-frontend` — `kui-metrics` among them with
its address in `kui.yaml`, so the eighth service is no longer a contract routed to nothing.
`smoke.sh` derives the service list from `/api/v1/capabilities` instead of a remembered list of
four names, stops a container, asserts the other four stay available and the interface stays up,
and asserts recovery. Re-run here: `up -d --wait` brings all seven to Healthy in 22s, both curls
succeed, and the capability document lists `cluster consumer message metrics topic`.

Two things stop this closing, and neither is cosmetic.

* **The CI job that runs this criterion cannot get one of the images.** `.github/workflows/ci.yml`'s
  `compose` job builds five — gateway, cluster, topic, message, consumer — and the stack now needs
  six. `kui-metrics` has an `image:` and no `build:`, and
  `docker compose -f deployment/compose/docker-compose.yml pull kui-metrics` answers
  `pull access denied for kui-metrics, repository does not exist`. It passes on this machine only
  because a `./mill deployment.docker.metrics.docker.build` has been run here by hand. The packet
  that added the container and the packet that owns `ci.yml` were different packets, and the
  contract stated between them said only that no CI *step* would be added.
* **The new capability check is structurally blind to the defect it was written for.** It compares
  `docker compose config --services` against `/api/v1/capabilities`, and the capability document
  reflects `kui.gateway.services` — the **addresses**. The defect was a **contract** with no
  address, and `ServiceContracts.byService` is on neither side of the equality. `services/schema`
  is in that map, has a `deployment.docker.schema` target, and appears in neither
  `deployment/compose/kui.yaml` nor `docker-compose.yml`: the identical defect survives one service
  over, and both sets simply omit it.

Also worth knowing: `smoke.sh` failed once in three runs here, at the recovery step, with
`cluster capability was 'degraded' after 90s`. The script's own comment documents that this step
was already raised from 40s to 90s for the same reason, and the machine was busy. It is a load
measurement, not a fault — but a smoke test people learn to re-run is one of the things this
milestone exists to prevent, and the ceiling should be raised once more or the wait should poll the
gateway's readiness rather than its p95 rule.

**What was wrong with the old exit criterion.** It was already corrected once (it used to name
`localhost:8080/ui/`, which has answered 503 by design since ADR-048). It is still wrong in a
quieter way: it asserts that a stack comes up without saying where the images come from, and every
image on a developer's machine is whatever was last built there. The whole wave-2 browser suite read
red here until a `--no-cache` rebuild of `kui-frontend`, because a cached BuildKit layer was serving
a bundle from before the tree was repaired.

**Exit:** the images are built from the working tree —
`./mill '{deployment.docker.gateway,deployment.docker.cluster,deployment.docker.topic,deployment.docker.message,deployment.docker.consumer,deployment.docker.metrics}.docker.build'`,
and compose builds `kui-frontend` itself — then
`docker compose -f deployment/compose/docker-compose.yml up -d --wait` followed by
`curl -sf localhost:8090/ui/ >/dev/null && curl -sf localhost:8080/api/v1/health/ready` both
succeed, `./deployment/compose/smoke.sh` passes three consecutive runs, and its capability check
covers every service the gateway holds a **contract** for, not only every service it holds an
address for.

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

## M4 — The frame every screen shares  ·  no new service  ·  **NOT CLOSED**

Most of this is built and, unusually for this repository so far, it is built *and reachable*. Driven
in a real browser against the quickstart stack at `/ui/clusters/quickstart/dashboard/overview`, the
frame draws: the drawer head naming the cluster with `healthy · 4.3 · 1 broker`, each part omitted
rather than dashed when unknown; `Add a cluster` linking to `/ui/clusters/manage`; navigation badges
carrying real counts (Clusters 1, Topics 10, Consumers 3, Schemas 1); the storage meter reading
`23% · 47.0 GB of 203.2 GB` from the log directories the shell fetches; the dashboard's tab strip
with both tabs addressable; and an Overview body full of measured figures with `NotMeasured`
sentences where nothing samples. `ECOSYSTEM` correctly renders nothing. `pnpm -C frontend test`
passes 281 cases across 15 files in `packages/shell`, including new suites in `src/routing`,
`src/nav` and `src/chrome`.

Three things are open.

* **The topic tree has no caller.** `nav/topicTree.ts` is written, tested, and exported from the
  shell barrel — and `grep` finds `topicTree` in exactly one place outside its own file and test:
  the barrel that exports it. The drawer does not nest. This is wave 1's failure repeating one
  level up: the packet that built the fold and the packet that owned `App.tsx` were different
  packets, and the wiring was nobody's acceptance case.
* **Three of the wirings that were done cannot be distinguished from not being done.** Replacing
  `countFor: countLookup(readingValue(facts.counts))` with `countFor: () => undefined` in
  `App.tsx` — cutting the store off from the drawer's badges entirely — leaves all 281 shell tests
  green; I re-ran it. The same is true of `onCreateTopic` and of `onSelect={switchEnvironment}`,
  and `EnvRailProps.onSelect` is optional, so an unwired rail is not even a type error. The badges
  demonstrably work in the product; nothing would notice if they stopped.
* **The browser half of the exit criterion was never written.** `frontend/e2e/` contains four spec
  files and none of them opens the dashboard address, reads the drawer head, expands a tree or
  clicks `+`. No wave-2 packet owned `frontend/e2e/**`, so this was not so much missed as never
  assigned.

Two smaller notes. `+ Create topic` navigates to the topics list rather than opening the create
dialog — defensible, since the create flow lives there, but it is not what "wired" meant. And
`Overview.tsx`'s tab dispatch passes the model as a plain captured value, so every model update
destroys and rebuilds the whole dashboard body instead of updating fine-grained; it is bounded today
only because nothing polls yet.

**Exit:** `pnpm -C frontend test` passes new suites in `src/routing`, `src/nav` and `src/chrome`;
`grep -rn topicTree frontend/packages/shell/src` finds a caller and not only an export; and, against
a quickstart stack whose images were built from the working tree, `pnpm -C frontend e2e` passes a
spec that opens `/ui/clusters/<id>/dashboard/overview`, asserts the drawer head names the cluster
and shows a broker count, expands the topic tree, and clicks `+` to land on `/ui/clusters/manage`.

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

## M6 — Every screen that can now be honest  ·  no new service

With M4's frame and M5's data, most of the twenty-three screens can be built end to end.

* Dashboard **Overview** and **Storage** tabs — everything except the five metrics cards, which
  keep their `NotMeasured` sentence.
* Brokers: the four stat tiles, per-broker disk percentage, lazily-fetched configs on expand, the
  version and uptime tags, and a voice line that reads the real under-replication count instead of
  claiming zero.
* Topics list: the statistics region behind its switch, the facet chip bar, the cleanup column, the
  card composition, sort and direction controls, row and card selection sharing one set, the bulk
  bar, and Export.
* Topic object: the Overview tab body (which renders nothing today), the coordinator column, an
  empty state for the Consumers tab, the in-content breadcrumb, and `Produce message` in the header.
* Consumer groups: the server's total instead of the row count, and paging.
* Schema Registry: the two-pane master–detail, subject row facts, and `Register schema`.
* Message browser: the topic's real partition count instead of the hard-coded `0`, the time window,
  the offset range, typed key and value predicates, and filter presets.
* Gateway cross-entity search, and the top-bar field wired to it. **There is no search endpoint
  anywhere** — `docs/api/openapi.json` has no path containing `search` — so this bullet is the one
  piece of M6 that is a new endpoint rather than a screen. It is a fold at the gateway over three
  list endpoints that already exist, `/topics/names` among them, which is what that endpoint was
  built for.
* Toasts on every destructive success.

And one thing this milestone inherits rather than adds: `useQuery` exists, is tested, and **has no
consumer outside the kernel**. Four route files carry a hand-rolled `useFetch` —
`ClustersRoute.tsx`, `ConsumersRoute.tsx`, `SchemasRoute.tsx`, `TopicsRoute.tsx`. The migration is
per-feature and belongs with the screen, which is why it was not done earlier.

**What was wrong with the old exit criterion.** "A spec that fails on an em dash where a sentence
belongs" is not runnable as stated, because an em dash *beside* a sentence is the correct rendering:
the storage meter prints `—` for the figure and the sentence underneath, and both are right. The
rule that is actually checkable is the one the design states — a panel that cannot be measured shows
a sentence, and a panel that shows only a dash and no sentence is the defect.

**Exit:** `pnpm -C frontend test` passes, and against a quickstart stack whose images were built
from the working tree `pnpm -C frontend e2e` passes a spec per screen; `grep -rl useFetch
frontend/packages/*/src` is empty; `curl -s '…/search?q=orders' | jq '.results'` answers across
topics, groups and subjects; and no panel on any screen renders an em dash without a sentence beside
it, asserted per screen rather than by one sweeping regex.

## M7 — `services/metrics`  ·  the service exists; the adapter does not

Nothing in this repository reads a broker metric: there is no JMX client and no Prometheus parser.
Everything *around* that hole was built in wave 1 and is what this milestone now fills. The service
exists in all six ADR-041 layers with a Mill module, a container image, a `ServiceContracts` entry,
an `AllInOneWiring` entry and a regenerated OpenAPI document; `Resource.Metrics` and `MetricsView`
exist; `kui.metrics` parses with every field defaulted; and `SeriesWindow` in `libs/cache` is the
retention primitive this used to need and no longer has to write. Its one endpoint answers
`not_configured` with a 200 today, which is the correct answer and not a placeholder. Six cards and
four stat cards depend on the adapter that is still missing.

* An adapter that reads broker metrics — JMX per broker, or a Prometheus/JMX-exporter endpoint the
  deployment declares. Whichever, a cluster that configures neither answers `not_configured` and
  the cards keep their sentence; that is not a failure mode, it is the design.
* A retention buffer keyed by (cluster, metric, bucket) over `SeriesWindow`, whose three refusals
  are already the ones this milestone needs: a never-sampled bucket is absent rather than zero, a
  window shorter than the requested period answers `None` rather than a percentage computed over
  four minutes and printed as "over the last 24h", and eviction is against a supplied `Instant` so
  it is testable.
* The four remaining endpoints, each `Section`-wrapped so one dead exporter costs one card
  (`…/metrics/throughput?range=` already exists and answers honestly):
  `…/metrics/latency?window=`, `…/metrics/request-handlers`, `…/metrics/producers?top=`,
  `…/metrics/record-size`.
* Then the Traffic tab, the throughput card, the p99 card, the ring gauges, top producers, the
  message-size histogram, and the four stat-card sparklines.

**Exit:** `./mill services.metrics.__.test` and `./mill __.openApiCheck` pass; against the
quickstart stack `curl -s '…/metrics/throughput?range=24h' | jq '.throughput.status'` prints one of
`ok|stale|unavailable|not_configured`, and with no exporter configured it prints `not_configured`
and `pnpm -C frontend e2e` asserts the Throughput card shows the sentence rather than an empty axis.

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

**Exit:** `./mill services.alerts.__.test` passes; `curl -s …/events | jq '.events.status'` is
`ok`; `pnpm -C frontend e2e` asserts that after a seeded event the bell carries its unread mark and
the alerts card's pill count equals the API's open count.

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

**Exit:** from a clean checkout, with every container image built from it:
`./scripts/run-tests.sh`, `pnpm -C frontend test`, the a11y sweep (build, serve, sweep — see the
ordering rule), `pnpm -C frontend e2e`, `./mill __.openApiCheck`, `./mill checkArchitecture` and
`./deployment/compose/smoke.sh` all pass, and `docs/FEATURE_MATRIX.md`'s counts match its own
recount command — checked by a command that *compares* them, not by a person reading two numbers.
The recount published in that file today prints the counts and nothing compares them to the prose,
which is how the prose drifted.

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
