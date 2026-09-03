# CLUI-003 — Dashboard: cluster rows, per-row status, unavailable rows stay clickable

- **ID:** CLUI-003
- **Title:** Dashboard: cluster rows, per-row status, unavailable rows stay clickable
- **Milestone / Feature:** M1 / CL-001, CL-003, KU-011, KU-010
- **Owner role:** Frontend Architect
- **Size:** L
- **Dependencies / blocked by:** CLUI-002 (the clients), CLUI-001 (`StaleDataOverlay`,
  `Timestamps`), CLAPI-007 (the gateway aggregation this reads). No restyle constraint: everything
  here is in `frontend/ui-clusters`.

## Goal (user value)

The screen the milestone is judged on. Three configured clusters, one of them unreachable: two rows
show their broker count, controller, version, partitions and disk usage; the third says
`Unavailable: connection refused`, keeps its name and its link, and does not delay the other two.
This is exit criterion three of M1 and feature-matrix row KU-011's first delivery.

## Scope

1. **`/ui/clusters` becomes the dashboard**, and the dashboard *is* the cluster list. Kafbat has one
   screen for both (`research/kafbat/ui-analysis.md` "Dashboard"), the rows are the same rows, and a
   second screen over the same data would be a second thing to keep consistent for no gain.
2. **A summary strip above the table**: how many clusters are online, how many are not, and when the
   list was fetched. Two counts and a timestamp, from the same response — no extra call.
3. **The table**, one row per configured cluster, sortable, with per-row status. A row's cells come
   from its own `Section`; a row whose section is not `Ok`/`Stale` renders its identity cells and
   `—` in every data cell, and stays clickable.
4. **A status chip per row**, filled, using a container colour and its paired text colour, never a
   bare dot (`research/design/REFERENCE.md`, "Notable interaction patterns"). The reason string from
   the section is rendered verbatim after the state word (ADR-032's single rule for reasons).
5. **A "Show unavailable only" toggle** — Kafbat's "Only offline clusters" switch, renamed to match
   the state vocabulary KUI actually uses.
6. **Magnitude bars** on disk usage and on the partition counts, scaled against the largest value
   among the rows currently displayed, because the design shows quantities as a bar beside the
   figure so relative size is readable without reading numbers.
7. **Whole-table staleness** through `StaleDataOverlay`: when the list call fails and a previous list
   is held, the table stays, dimmed, badged with when it was fetched.
8. **The brokers page skeleton and its route.** A row click has to go somewhere real, so this task
   adds the `Brokers(clusterId)` page, its route, its `history.state` codec, its heading and
   breadcrumbs, and an empty table. CLUI-004 fills that table in. Nothing here is a placeholder that
   gets deleted.
9. **The M0 ping screen is deleted** — page body, state, messages and suite.

## The row model, stated exactly

One pure function, because every rule below is then a test row rather than a rendering to eyeball.

| Section state of the row | Chip | Data cells | Row is clickable | Overlay |
| --- | --- | --- | --- | --- |
| `Ok(data, fetchedAt)` | `Online`, success container | the data | yes | none |
| `Stale(data, fetchedAt, reason)` | `Degraded: <reason>`, warning container | the data, dimmed | yes | per-row dim, badge shows `fetchedAt` |
| `Unavailable(reason, message, since)` | `Unavailable: <message>`, danger container | `—` | **yes** | none — there is nothing to keep |
| `Forbidden` | `Forbidden`, neutral container | `—` | yes | none |
| `NotConfigured` | row is **not rendered at all** | — | — | — |

`NotConfigured` hides the row rather than dimming it, exactly as ADR-032 hides a `NotConfigured`
navigation entry: the cluster has no such upstream, and showing it invites the user to click on
something that will never work. Every other state is visible, because a user must be able to tell
"misconfigured" from "down" (ADR-032, alternatives rejected).

The clickability of an `Unavailable` row is the point of the criterion and is asserted in the DOM,
not by reading the code: the target page renders its own fallback, and taking the link away would
strand the user on the one screen that cannot explain what is wrong.

## Columns

| Column | Source | Rendering | Sortable |
| --- | --- | --- | --- |
| Cluster | row identity | name, plus a `read only` tag when `readOnly`; the whole cell is the link | yes, default ascending |
| Status | the section | the status chip | yes, ordered `Unavailable < Degraded < Online` so problems sort to the top first |
| Version | section payload | text, `—` when absent | yes |
| Brokers | section payload | count | yes |
| Controller | section payload | broker id, or `none` in the warning colour when the cluster reports no controller | no |
| Partitions | section payload | `<online> of <online + offline>`, warning colour when offline > 0, with a magnitude bar on the total | yes, by total |
| URP | section payload | under-replicated count; `0` in normal colour, anything above in the warning colour | yes |
| Disk | section payload | formatted bytes with a magnitude bar | yes, by raw bytes |
| Topics | — | always `—` | no |
| Production / Consumption | — | **not rendered as columns at all** | — |

Two decisions inside that table, both restating DEVPLAN §3 and §10 D5 so that nobody adds a call to
fill a cell:

- **Topics is a column and it is always `—`.** Kafbat has it, the number needs a `describeTopics`
  sweep, and that sweep belongs to `services/topic` in M2. The column stays so the M2 task is a
  data change rather than a layout change, and the `—` is honest.
- **Throughput is not a column.** There is no metrics service until M8, and an empty column with a
  header promising bytes per second is worse than no column: it reads as "this cluster has no
  traffic". The distinction from Topics is deliberate — Topics is a number the product will have in
  one milestone's time and whose absence is obviously an absence; throughput is a number whose zero
  is meaningful, so an empty cell would be read as data.

Sorting is client-side over the rows already fetched, which is what `DataTable` does. Sorting a
table that contains `—` cells puts the missing values last in both directions, so switching the sort
order never buries the rows that have data.

## Non-goals

- **No cluster creation, editing or deletion.** CW-005 is M8 (DEVPLAN §3). No "Configure new
  cluster" button, no per-row actions menu.
- **No polling.** The browser reads the server's snapshot once per visit and shows `scrapedAt`
  (DEVPLAN §10 D10). The refresh button is CLUI-008 and is not built here.
- **No cluster switcher, no colour tag.** CLUI-006.
- **No virtualization, no pagination.** A deployment has a handful of clusters; `DataTable` renders
  them (DEVPLAN §3).
- **No topic sweep, no second request to fill a cell.** One `GET /api/v1/clusters` and nothing else.

## Design references

- **DEVPLAN §10 D4** — an unreachable managed cluster is a `Section` inside a 200, never an
  unavailable capability. The sidebar must not dim because somebody typed a bad broker address.
- **DEVPLAN §10 D5** — the boundary between a dashboard stat and M2's topic sweep.
- **DEVPLAN §10 D10** — 30 s server-side, no browser polling, a visible `scrapedAt`.
- **ADR-032** — reason strings shown verbatim after the state word; `NotConfigured` hidden;
  `Unavailable` shown and clickable; stale data greyed and timestamped.
- **ADR-039 §6** — only a transport failure of the upstream *service* dims a capability.
- **`research/kafbat/ui-analysis.md`** "Dashboard" (the column set, the offline-only toggle, the
  row-click destination, `emptyMessage`) and IA.3 (the dashboard row of the degraded-state table,
  which is the source of the row model above).
- **`research/design/REFERENCE.md`** — status as a filled chip with a container colour and its
  paired text; quantities with a horizontal magnitude bar; counts that cross a threshold switch to
  the warning colour rather than being coloured constantly; `compact` density changes row padding
  and nothing else.
- **`ARCHITECTURE.md` §6** — the `Section` ADT this renders, and §9 for the 30 s contract.

## Files to create

```
frontend/ui-clusters/src/kui/ui/clusters/dashboard/DashboardPage.scala
frontend/ui-clusters/src/kui/ui/clusters/dashboard/DashboardRow.scala
frontend/ui-clusters/src/kui/ui/clusters/component/SectionChip.scala
frontend/ui-clusters/src/kui/ui/clusters/component/Bytes.scala
frontend/ui-clusters/src/kui/ui/clusters/brokers/BrokersPage.scala        (skeleton; CLUI-004 fills it)
frontend/ui-clusters/test/src/kui/ui/clusters/dashboard/DashboardRowSuite.scala
frontend/ui-clusters/test/src/kui/ui/clusters/dashboard/DashboardPageSuite.scala
frontend/ui-clusters/test/src/kui/ui/clusters/component/BytesSuite.scala
```

## Files to change

```
frontend/ui-clusters/src/kui/ui/clusters/ClustersRoutes.scala   (Brokers page + route + codec)
frontend/ui-clusters/src/kui/ui/clusters/ClustersFeature.scala  (render by page, one ClustersQueries)
frontend/ui-clusters/src/kui/ui/clusters/ClustersState.scala    (ping state out, selection state in)
frontend/ui-clusters/src/kui/ui/clusters/Messages.scala         (the screen's sentences)
frontend/ui-clusters/resources/css/40-clusters.css              (dashboard rules)
frontend/ui-clusters/test/src/kui/ui/clusters/ClustersStateSuite.scala
build.mill                                                      (uiClusters.test → KuiJsDomTests)
```

`frontend/ui-clusters/src/kui/ui/clusters/ClustersPage.scala` is **deleted**; `DashboardPage`
replaces it.

### The `build.mill` change, and why it is in this task

`frontend.uiClusters.test` runs under plain Node today, with the stated justification that
`ClustersStateSuite` touches no `document`. From this task on that is false: the row model's whole
contract is what ends up in the DOM, and the milestone's headline criterion — *the unavailable row
remains clickable* — cannot be asserted anywhere else. The module's test object therefore becomes
`ScalaJSTests with KuiJsDomTests` and gains `com.raquo::domtestutils::19.0.0`
(`DEPENDENCY_MATRIX.md`: `domtestutils_sjs1_3`, `19.0.0`, test, `frontend/*`). Pure suites keep
running unchanged under jsdom.

Per DEVPLAN §6.5 a task edits only the `object` it is wiring in `build.mill`; this edit is confined
to `object test` inside `object uiClusters`.

**This contradicts DEVPLAN §7**, whose test-plan table puts "an unavailable row is still focusable
and clickable" under `frontend.uiKernel.test`. The row is a `ui-clusters` component and cannot be
tested from the kernel without moving it there, which would put a cluster-shaped table in a module
that must not know what a cluster is. The assertion moves to `frontend.uiClusters.test`; the
kernel's DOM suite keeps the overlay's ARIA semantics, which genuinely are kernel behaviour.

## Public Scala signatures to implement

```scala
package kui.ui.clusters.dashboard

/** One dashboard row, already reduced to what the table draws.
  *
  * A pure value with no `Signal` in it, produced by a total function from the response, so that
  * every rule in the row-model table above is one test row rather than a rendering to inspect.
  */
final case class DashboardRow(
    clusterId: ClusterId,
    name: String,
    readOnly: Boolean,
    status: RowStatus,
    version: Option[String],
    brokerCount: Option[Int],
    controller: Option[BrokerId],
    onlinePartitions: Option[Int],
    offlinePartitions: Option[Int],
    underReplicatedPartitions: Option[Int],
    diskUsageBytes: Option[Long],
    fetchedAt: Option[Instant]
)

/** The row's state, which decides its chip, its dimming and its sort position. */
enum RowStatus {
  case Online
  case Degraded(reason: String)
  case Unavailable(reason: String, since: Option[Instant])
  case Forbidden
}

object DashboardRow {

  /** Every row the table shows, in the response's order, with `NotConfigured` clusters dropped. */
  def of(response: ClusterListResponse): List[DashboardRow]

  /** The sort key for the status column: problems first. */
  def statusOrder(status: RowStatus): Int

  /** Rows filtered by the "show unavailable only" toggle. */
  def onlyUnavailable(rows: List[DashboardRow]): List[DashboardRow]

  /** How many rows are `Online`, and how many are not. The summary strip's two numbers. */
  def counts(rows: List[DashboardRow]): (Int, Int)
}
```

```scala
package kui.ui.clusters.dashboard

object DashboardPage {

  /** @param queries    the module's caches; the page subscribes to `queries.clusters`.
    * @param navigate   how a row click reaches the brokers page. Passed in rather than reaching
    *                   for the router, so the page is drivable from a suite with no router.
    * @param zone       the IANA zone the timestamps render in (CLUI-007 supplies the preference;
    *                   until then the caller passes `Timestamps.systemZone()`).
    */
  def apply(
      queries: ClustersQueries,
      navigate: ClusterId => Unit,
      zone: Signal[String]
  ): HtmlElement
}
```

```scala
package kui.ui.clusters.component

/** A filled status chip: container colour, paired text colour, never a bare dot. */
object SectionChip {
  def apply(status: Signal[RowStatus], testId: Option[String] = None): HtmlElement
  /** `Unavailable: connection refused` — the state word, then the reason verbatim. */
  def label(status: RowStatus): String
}

/** Byte formatting, shared by the dashboard, the broker list and the log-dir table. */
object Bytes {
  /** `1.4 GiB`, binary units, one decimal from KiB up, `0 B` for zero, `—` for `None`. */
  def format(bytes: Option[Long]): String
  /** The fraction of the largest value in a set, for a magnitude bar; 0.0 when the max is 0. */
  def fraction(value: Option[Long], max: Long): Double
}
```

```scala
package kui.ui.clusters

/** The pages this feature owns. */
sealed trait ClustersPageId extends Page

object ClustersPageId {
  case object Overview extends ClustersPageId
  final case class Brokers(clusterId: String) extends ClustersPageId
  given CanEqual[ClustersPageId, ClustersPageId] = CanEqual.derived
}
```

Route table after this task, all relative to the deployment's `<basePath>/ui`:

| Page | Pattern | `history.state` tag |
| --- | --- | --- |
| `Overview` | `/clusters` | `clusters.overview` |
| `Brokers(id)` | `/clusters/<id>/brokers` | `clusters.brokers` |

`endOfSegments` on both, for the reason the existing file already records: without it a pattern
claims its own sub-paths and a mistyped URL never 404s.

## Library coordinates

One test-scope addition to `frontend.uiClusters.test`:
`com.raquo::domtestutils::19.0.0` (`DEPENDENCY_MATRIX.md`, `domtestutils_sjs1_3`, scope `test`,
modules `frontend/*`, ADR-018). No production dependency changes; no version is introduced that the
matrix does not already pin.

## Acceptance criteria

```
$ ./mill frontend.uiClusters.compile
$ ./mill frontend.uiClusters.test
$ ./mill frontend.uiShell.compile
$ ./mill frontend.uiShell.checkBundleShape
```

All clean. `checkBundleShape` still reports `kui.ui.clusters.ClustersFeature` in its own JavaScript
module: adding pages must not have pulled the feature into `main.js`.

```
$ ./mill frontend.uiClusters.checkFormat && ./mill frontend.uiClusters.fix --check
$ ./mill __.compile
```

Clean. The last command proves the deletion of the ping page broke nothing outside the module.

```
$ grep -rn "Ping" frontend/ui-clusters/    # expected: no matches
```

Manual check, once, with the Compose stack of CFGOP-006 running and one cluster's bootstrap
address pointed at a closed port:

```
$ open http://localhost:8080/ui/clusters
```

Expected: three rows. Two carry counts and a green `Online` chip. The third carries the cluster's
name, a red chip reading `Unavailable: ` followed by the gateway's reason, `—` in every data cell,
and clicking it opens that cluster's brokers page (which shows its own fallback). The page renders
in one paint; the dead cluster does not delay it.

## Tests required

`DashboardRowSuite` (pure, no DOM) — the row model as a table, one case per line of the row-model
table above:

- `okSectionProducesAnOnlineRowWithItsData`.
- `staleSectionProducesADegradedRowThatKeepsItsData` — the payload is still on the row; only the
  status differs.
- `unavailableSectionProducesDashesAndKeepsTheIdentity` — name, id and `readOnly` are present;
  every optional data field is `None`.
- `forbiddenSectionProducesDashesAndAForbiddenChip`.
- `notConfiguredClustersAreNotRows`.
- `statusOrderPutsProblemsFirst` — sorting a mixed list by `statusOrder` yields
  `Unavailable, Degraded, Forbidden, Online`.
- `countsIgnoreRowsThatAreNotRendered` — a `NotConfigured` cluster is in neither count, so the two
  numbers add up to the number of rows on screen.
- `onlyUnavailableKeepsDegradedRowsOut` — the toggle means unavailable, not "not perfectly healthy";
  a degraded cluster is still serving data.

`BytesSuite` (pure):

- `formatsBinaryUnitsAtEveryBoundary` — 0, 1, 1023, 1024, 1 MiB − 1, 1 MiB, 1 GiB, 1 TiB.
- `noneFormatsAsTheMissingMarker` — `DataTable.missing`, so one constant governs every `—` in the
  product.
- `fractionOfAZeroMaxIsZeroNotNaN` — an all-empty cluster must not produce a `NaN` bar width.

`DashboardPageSuite` (jsdom, `domtestutils`) — the assertions that only exist in the DOM:

- `anUnavailableRowIsFocusableAndClickable` — **the milestone's criterion.** The row's link is in
  the tab order, has an `href`, and dispatching a click calls `navigate` with that cluster's id.
- `anUnavailableRowShowsTheReasonVerbatim` — the chip's text contains the exact message from the
  section, with no rewording and no truncation in the DOM (CSS may ellipsise; the text node may
  not).
- `dataCellsOfAnUnavailableRowAreTheMissingMarkerNotZero` — the assertion that catches the worst
  bug this screen can have: a dead cluster reporting `0 brokers` looks like a fact.
- `topicsIsAlwaysTheMissingMarker` — even for a healthy cluster.
- `noThroughputColumnsExist` — the header row contains neither `Production` nor `Consumption`.
- `aFailingListCallKeepsThePreviousRowsUnderTheStaleOverlay` — resolve, then fail; the same row
  elements are still in the DOM and the wrapper carries the stale class and a badge.
- `theSummaryStripCountsMatchTheRowsOnScreen`.
- `theUnavailableOnlyToggleFiltersAndRestores`.
- `sortingByDiskPutsMissingValuesLastInBothDirections`.
- `theEmptyResponseRendersAnEmptyStateNotABlankTable` — no configured clusters at all yields
  `EmptyState` with a sentence naming what to do, not a table with a header and nothing under it.

`ClustersStateSuite` — rewritten around the selection state that remains; the ping cases are gone.

## Observability

- Every call reports to `HealthReporting` with **`CallScope.Feature`**. A cluster-service failure
  must never be reported as a shell failure; that is the difference between a dimmed feature and a
  full-screen "cannot reach the gateway" (ADR-032).
- `scrapedAt` is on screen whenever data is. It is the user-facing half of the 30 s staleness
  contract in `ARCHITECTURE.md` §9, and CFGOP-007's E2E asserts the row is "greyed and timestamped"
  through the `data-testid` hooks CLUI-001 defined.
- `data-testid` values this screen must expose, because other tasks assert on them:
  `page-clusters-dashboard`, `cluster-row-<clusterId>`, `cluster-row-<clusterId>-status`,
  `cluster-summary-online`, `cluster-summary-unavailable`, `clusters-table`.
- No client-side metric is sent to the server (M0's rule).

## Degraded behavior

Four distinct failures, four distinct renderings, and getting them mixed up is the failure this
screen is built to avoid:

1. **One cluster unreachable, the rest fine** — a row-level chip and `—` cells inside a 200. The
   page is not degraded; one row is.
2. **The list call fails and a previous list is held** — the whole table goes under
   `StaleDataOverlay`: dimmed, timestamped, actions disabled, rows still readable and still
   clickable.
3. **The list call fails and nothing was ever fetched** — an error region with the reason and a
   "Try again" button that re-subscribes. Not an overlay: there is nothing under it. Not a
   full-screen state: only the gateway being unreachable is ever full-screen (ADR-032).
4. **The cluster *service* is unavailable** — the shell never routes here; it renders the feature's
   `unavailableView`. `ClustersFeature.unavailableView`'s sentence is rewritten in this task to name
   what still works: the settings page, and the other services' screens.

The toggle, the sort and the search-free filtering all keep working on stale rows, because they are
client-side over rows already in hand. That is deliberate: when the data is old, rearranging it is
the last thing the user can still do, and disabling it would take away the only remaining control.

## Docs to update

- `docs/domain/cluster.md` — the dashboard's field list, and the two `—` decisions (topics until M2,
  no throughput until M8) recorded where a future reader will look for them.
- `docs/frontend/README.md` — the row-model table, as the worked example of rendering a `Section`.
