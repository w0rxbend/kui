# CLUI-004 — Brokers list: rack, leaders, replicas, skew; metric columns as `—`

- **ID:** CLUI-004
- **Title:** Brokers list: rack, leaders, replicas, skew; metric columns as `—`
- **Milestone / Feature:** M1 / BR-001, CL-003
- **Owner role:** Frontend Architect
- **Size:** L
- **Dependencies / blocked by:** CLUI-003 (the page skeleton, the route, the shared components).
  Entirely inside `frontend/ui-clusters`; no restyle constraint.

## Goal (user value)

The screen an operator opens when something is wrong with a cluster: every broker, which one is the
controller, where each one sits (host, port, rack), how much disk it is using, and — the reason the
screen is worth building rather than reading from a shell — how unevenly the partitions are spread
across them. A skew of 40 % on one broker is the difference between "the cluster is fine" and "one
machine is about to fill up", and no `kafka-topics.sh` invocation shows it in one glance.

## Scope

1. Fill in the `BrokersPage` skeleton CLUI-003 created: a summary strip, a broker table, and the
   per-page staleness treatment.
2. **The summary strip**, from the same response as the table: broker count, the active controller
   (or `No active controller` in the danger colour when the cluster reports none), the Kafka version,
   online / offline partitions, under-replicated partitions, in-sync versus total replicas, and the
   controller type — KRaft or ZooKeeper — from the cluster's own report. Kafbat's "Uptime" and
   "Partitions" metric sections, with KUI's vocabulary.
3. **The broker table**, sortable, one row per broker, with the columns below.
4. **Skew**, computed in the browser from the assignment counts the response already carries — it is
   not a metric and does not wait for M8 (DEVPLAN §3, explicitly). Two skews: replicas and leaders.
5. **Threshold colouring** through the kernel's `ThresholdValue`: warning at 10 %, critical at 20 %,
   matching the reference product's thresholds, and applied only above them — the design's rule is
   that a value crossing a threshold changes colour rather than being coloured constantly.
6. **Magnitude bars** on disk usage, scaled against the largest broker in the table.
7. **Row click opens the broker**, so this task adds the `BrokerDetail` page id, its route and its
   `history.state` codec, and a detail page skeleton — heading, back link, breadcrumbs, the
   identity strip — which CLUI-005 fills with tabs. The same rule CLUI-003 followed: a link must go
   somewhere real on the day it is drawn.
8. **Breadcrumbs**: `Clusters / <cluster name> / Brokers`, through the kernel's `Breadcrumbs`.

## Columns

| Column | Source | Rendering | Sortable |
| --- | --- | --- | --- |
| Broker | broker id | the id, as the row's link; a `controller` tag beside it when this broker is the controller | yes, default ascending |
| Host | host | text, breakable — a broker DNS name is long and must wrap rather than widen the table | yes |
| Port | port | numeric, right-aligned | yes |
| Rack | rack | text, or `—` when the broker declares none | yes |
| Disk | disk usage bytes, segment count | `1.4 GiB, 128 segments` with a magnitude bar; `—` when the log-dir call did not answer for this broker | yes, by raw bytes |
| Leaders | leader-partition count | number | yes |
| Leader skew | computed | `12.4 %`, threshold-coloured, or `—` when it cannot be computed | yes |
| Replicas | replica count | number | yes |
| In sync | in-sync replica count | number, in the warning colour when below the replica count | yes |
| Replica skew | computed | as leader skew | yes |
| Bytes in / Bytes out | — | **not rendered as columns** | — |

Throughput is left out for the same reason it is left out of the dashboard: there is no metrics
service until M8, and an empty "Bytes in" column reads as "this broker takes no traffic", which is a
statement about the cluster rather than about KUI. Rack, by contrast, is a column that is genuinely
often `—` because many clusters set no rack, and there the dash is the truth.

Rack is worth its column even so: a rack-aware cluster whose partitions are not spread across racks
is a availability problem that this table makes visible in one column pair (rack, replicas).

## Skew, defined

Skew is the divergence of one broker's count from the average across brokers, as a percentage of
that average:

```
skew(broker) = 100 * (count(broker) - mean) / mean
```

Rules, each of which is a test case:

- **Reported only when it is above the mean.** A broker carrying fewer partitions than average is
  not a problem; the reference product shows `-` for it, and so does KUI. The tooltip on the header
  says so: *"How far this broker's count is above the average across brokers."*
- **A mean of zero yields `—`, never a division by zero and never `Infinity`.** A cluster with no
  partitions is a real state on a fresh install.
- **A single broker yields `0 %`**, not `—`: with one broker the mean is that broker, and the answer
  is genuinely zero.
- **A broker whose count is unknown is excluded from the mean**, and shows `—` itself. Averaging in
  a zero for a broker whose log dirs failed would understate every other broker's skew — a silent
  wrong number, which is worse than a dash.

## Non-goals

- **No metrics tab, no JMX, no charts.** M8.
- **No broker configuration edits.** BR-002 is read-only in M1 (DEVPLAN §3); there is no edit
  affordance anywhere in this screen, not even a disabled one.
- **No polling.** D10. The refresh button is CLUI-008.
- **No CSV export.** Kafbat has one; nothing in M1's scope claims it, and it is not in the feature
  matrix for this milestone.
- **No partition-level detail.** That is the log-dir table on the broker detail page (CLUI-005) and,
  for topics, M2.
- **No second request.** Everything above comes from `GET /api/v1/clusters/{id}/brokers`. If a
  figure is not in that response it renders `—` and a note goes to CLAPI, not a new call.

## Design references

- **`research/kafbat/ui-analysis.md`** "Brokers list": the metric sections, the column set, the skew
  thresholds (warn ≥ 10 %, attention ≥ 20 %), the `N/A` when disk usage is missing for a broker, the
  active-controller marker, and the "No Active Controller" danger text. Also its recorded defect —
  a full-page loader on every five-second refetch — which KUI does not reproduce because it does not
  refetch (DEVPLAN §10 D10).
- **`research/kafka/admin-capabilities.md`** §1 — what `describeCluster`, `describeLogDirs` and the
  node set actually return, which is the authority on which of these figures can be missing and
  when. Read it before deciding any cell is impossible.
- **`research/design/REFERENCE.md`** — magnitude bars beside quantities; threshold colouring rather
  than constant colouring; the compact-density switch changing row padding only.
- **ADR-030** — minimum broker 2.8 and capability gating rather than version assumptions: a figure
  the broker's version cannot supply is `—`, not an error.
- **ADR-032** — the stale rule, and the fallback panel for an unavailable service.
- **`ARCHITECTURE.md` §6** — the `Section` this screen unwraps; §9 — the 30 s contract.

## Files to create

```
frontend/ui-clusters/src/kui/ui/clusters/brokers/BrokerRow.scala
frontend/ui-clusters/src/kui/ui/clusters/brokers/BrokerSummary.scala
frontend/ui-clusters/src/kui/ui/clusters/brokers/Skew.scala
frontend/ui-clusters/src/kui/ui/clusters/brokers/BrokerDetailPage.scala   (skeleton; CLUI-005 fills it)
frontend/ui-clusters/test/src/kui/ui/clusters/brokers/SkewSuite.scala
frontend/ui-clusters/test/src/kui/ui/clusters/brokers/BrokerRowSuite.scala
frontend/ui-clusters/test/src/kui/ui/clusters/brokers/BrokersPageSuite.scala
frontend/ui-clusters/resources/css/41-clusters-brokers.css
```

## Files to change

```
frontend/ui-clusters/src/kui/ui/clusters/brokers/BrokersPage.scala   (skeleton → the real screen)
frontend/ui-clusters/src/kui/ui/clusters/ClustersRoutes.scala        (BrokerDetail page, route, codec)
frontend/ui-clusters/src/kui/ui/clusters/ClustersFeature.scala       (route the new page)
frontend/ui-clusters/src/kui/ui/clusters/Messages.scala
```

The new stylesheet is numbered `41` because the 40s are this feature's band (ADR-024) and
`40-clusters.css` is already the dashboard's. Both are picked up by the same mechanism that already
loads `40-clusters.css`; no build change is needed.

## Public Scala signatures to implement

```scala
package kui.ui.clusters.brokers

/** One broker row, reduced to what the table draws. Pure, so every rule is a test row. */
final case class BrokerRow(
    brokerId: BrokerId,
    host: String,
    port: Int,
    rack: Option[String],
    isController: Boolean,
    diskUsageBytes: Option[Long],
    segmentCount: Option[Int],
    leaderCount: Option[Int],
    replicaCount: Option[Int],
    inSyncReplicaCount: Option[Int],
    leaderSkewPercent: Option[Double],
    replicaSkewPercent: Option[Double]
)

object BrokerRow {
  /** Every row, with both skews already computed across the whole set. */
  def of(brokers: BrokerListResponse): List[BrokerRow]
}

/** The summary strip above the table. */
final case class BrokerSummary(
    brokerCount: Int,
    controller: Option[BrokerId],
    version: Option[String],
    controllerType: Option[String],       // "KRaft" | "ZooKeeper", as the service reports it
    onlinePartitions: Option[Int],
    offlinePartitions: Option[Int],
    underReplicatedPartitions: Option[Int],
    inSyncReplicas: Option[Int],
    totalReplicas: Option[Int]
)

object BrokerSummary {
  def of(brokers: BrokerListResponse): BrokerSummary
  /** True when the strip must shout: no controller, or any offline partition. */
  def hasAlarm(summary: BrokerSummary): Boolean
}

object Skew {
  /** The percentages, one per broker, in the input's order.
    *
    * `None` for a broker whose count is unknown, and for every broker when fewer than one broker
    * has a known count. Values at or below the mean are `None`, which is what renders `—`.
    */
  def percentages(counts: List[Option[Int]]): List[Option[Double]]

  /** The threshold band a skew falls in. 10 % warns, 20 % is critical. */
  def level(percent: Option[Double]): ThresholdLevel
}
```

```scala
package kui.ui.clusters.brokers

object BrokersPage {
  /** @param cluster  which cluster's brokers; comes from the route.
    * @param openBroker how a row click reaches the detail page.
    */
  def apply(
      cluster: ClusterId,
      queries: ClustersQueries,
      openBroker: (ClusterId, BrokerId) => Unit,
      zone: Signal[String]
  ): HtmlElement
}
```

Route table after this task:

| Page | Pattern | `history.state` tag |
| --- | --- | --- |
| `Overview` | `/clusters` | `clusters.overview` |
| `Brokers(id)` | `/clusters/<id>/brokers` | `clusters.brokers` |
| `BrokerDetail(id, brokerId)` | `/clusters/<id>/brokers/<brokerId>` | `clusters.broker` |

CLUI-005 adds the tab segment to the last pattern; the codec it writes must keep decoding a stored
state that has no tab, so a Back button pressed across a deployment upgrade lands on the log-dirs
tab rather than on "not found".

## Library coordinates

None new. `ThresholdValue`, `ThresholdLevel`, `MagnitudeBar`, `DataTable`, `Breadcrumbs`, `Tag` and
`EmptyState` are all already in `frontend/ui-kernel`; `domtestutils::19.0.0` is already on the test
classpath after CLUI-003.

## Acceptance criteria

```
$ ./mill frontend.uiClusters.compile
$ ./mill frontend.uiClusters.test
$ ./mill frontend.uiClusters.checkFormat && ./mill frontend.uiClusters.fix --check
$ ./mill frontend.uiShell.checkBundleShape
```

All clean; `checkBundleShape` still reports the feature in a module of its own.

Manual, once, against the Compose stack of CFGOP-006 with a three-broker cluster:

```
$ open http://localhost:8080/ui/clusters/<cluster>/brokers
```

Expected: three rows; exactly one carries the `controller` tag; the summary strip names the same
broker as the active controller; `Leaders` across the rows sums to the online partition count in the
strip; a broker with more than its share shows a coloured skew and the others show `—`; the
`Bytes in` and `Bytes out` columns do not exist; the `scrapedAt` time is shown above the table.

Against a cluster whose credentials authorise `describeCluster` but not `describeLogDirs`
(fault-injection scenario 4, DEVPLAN §7): the table still renders every broker, `Disk` is `—` for
all of them, and the page shows no error — the section is `Ok`, one field is absent.

## Tests required

`SkewSuite` (pure, and property-based where the rule is a property):

- `skewOfAnEvenSpreadIsZeroForEveryBroker`.
- `onlyBrokersAboveTheMeanReportASkew` — property over arbitrary count lists: every reported value
  is positive.
- `aZeroMeanYieldsNoSkewAndNoNaN` — property: for any list of zeroes, every result is `None`.
- `aSingleBrokerIsZeroPercent`.
- `unknownCountsAreExcludedFromTheMeanAndReportNothing` — the case that would silently inflate every
  other broker's number.
- `levelWarnsAtTenAndIsCriticalAtTwenty` — boundaries exactly: 9.99, 10.0, 19.99, 20.0.

`BrokerRowSuite` (pure):

- `theControllerRowIsMarkedAndOnlyOne`.
- `aBrokerWithNoRackRendersTheMissingMarker`.
- `aMissingLogDirEntryLeavesDiskUnknownRatherThanZero` — the assertion that stops a broker whose
  log-dir call failed from being displayed as empty.
- `rowsPreserveTheResponseOrderBeforeSorting`.

`BrokerSummarySuite` (pure, in `BrokerRowSuite`'s file is acceptable; a separate file is not):

- `noControllerIsAnAlarm`; `anyOfflinePartitionIsAnAlarm`; `aHealthyClusterIsNotAnAlarm`.
- `inSyncBelowTotalIsReported` and does not itself raise the alarm — under-replication is a warning
  colour, not an alarm state; only an offline partition or a missing controller is.

`BrokersPageSuite` (jsdom):

- `everyBrokerIsARowAndEveryRowLinksToItsBroker` — clicking calls `openBroker` with the right ids.
- `noThroughputColumnsExist`.
- `theSkewHeaderCarriesItsExplanation` — the header has an accessible description; a bare `12.4 %`
  with no explanation of what it measures is a number nobody can act on.
- `aStaleSectionKeepsTheRowsUnderTheOverlay`.
- `anUnavailableSectionShowsTheReasonAndNoFabricatedRows` — not an empty table: an explanation.
- `anEmptyBrokerListRendersAnEmptyStateNamingTheCluster`.
- `thresholdColoursAppearOnlyAboveTheThreshold` — a 3 % skew carries no threshold class.
- `breadcrumbsNameTheClusterAndLinkBackToTheDashboard`.

## Observability

- Calls report with `CallScope.Feature`.
- `data-testid` hooks other tasks depend on: `page-clusters-brokers`, `broker-row-<brokerId>`,
  `broker-summary-controller`, `broker-summary-partitions`, `brokers-table`, and
  `brokers-scraped-at` on the timestamp. CFGOP-007 and CLUI-008 assert on these.
- The `scrapedAt` of the section is rendered above the table at all times, fresh or stale. It is the
  visible half of the staleness contract and it is what makes the absence of polling acceptable: the
  user can always see how old the answer is.

## Degraded behavior

- **Section `Unavailable`** — the page renders its heading, its breadcrumbs and an explanation
  carrying the reason verbatim, with a "Try again" button. No fabricated rows and no empty table: an
  empty broker table is a claim that the cluster has no brokers.
- **Section `Stale`** — the whole table and the summary strip go under `StaleDataOverlay`, with the
  section's `fetchedAt`. Sorting still works, because it is client-side over rows in hand.
- **A field missing inside a healthy section** — `—` in that cell, no error, no banner. This is the
  partly-authorised cluster of fault-injection scenario 4 and it must look ordinary.
- **The cluster id in the URL is not a configured cluster** — the page renders "No cluster named
  `<id>` is configured" with a link back to the dashboard, not a 404 route and not a blank page. The
  user has usually followed a stale bookmark after a cluster was renamed, and the fix is on the
  dashboard.
- **The cluster *service* is unavailable** — the shell renders the feature fallback; this page is
  never reached.

## Docs to update

- `docs/domain/cluster.md` — the definition of skew, in one paragraph, with the four rules above.
  It is a computed figure with real edge cases, and a user who sees `—` where they expected a number
  must be able to find out why.
- `docs/frontend/README.md` — one line pointing at `Skew` as the example of a derived figure that
  lives in the browser rather than in a DTO, and why (it is a property of the set of rows, not of
  any one row).
