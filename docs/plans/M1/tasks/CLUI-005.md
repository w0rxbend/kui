# CLUI-005 — Broker detail: log dirs and configs tabs

- **ID:** CLUI-005
- **Title:** Broker detail: log dirs and configs tabs
- **Milestone / Feature:** M1 / BR-002, BR-005, PA-003
- **Owner role:** Frontend Architect
- **Size:** L
- **Dependencies / blocked by:** CLUI-004 (the detail page skeleton, its route, the shared
  components). Entirely inside `frontend/ui-clusters`; no restyle constraint.

## Goal (user value)

One broker, in detail: where its data actually sits on disk and how big each piece is, and what it
is configured with — including which settings were changed at runtime, which came from the file,
which are still at their defaults, and which values the server refused to show because they are
secrets. Between them, the two tabs answer the two questions that bring an operator to a single
broker: *why is this disk filling up* and *is this broker configured like its peers*.

## Scope

1. Fill in the `BrokerDetailPage` skeleton from CLUI-004: the identity strip, the tabs and their
   bodies.
2. **The identity strip**: host, port, rack, whether this broker is the controller, total segment
   size and segment count. Kafbat's four-value metric row, plus the rack and the controller marker,
   because both are one lookup the user would otherwise go back to the list for.
3. **The Log directories tab** (default): one section per log directory, each with its path, its
   error if the broker reported one for that directory, and a table of its topic-partitions —
   topic, partition, size, offset lag — sortable, with magnitude bars on size. This is BR-005 and
   PA-003 in one table: PA-003 ("per-partition log-dir sizes") is delivered by BR-005's data and
   has no screen of its own, as the feature matrix already records.
4. **The Configs tab**: every broker configuration entry, searchable by key or value, ordered by
   source, with the source explained, sensitive values shown as redacted, and read-only entries
   marked. Read-only in M1 — no edit control of any kind.
5. **The tab is in the URL**, so a link to a broker's configuration is a link a person can send.
6. Both tabs load their own data, lazily: opening the page fetches log dirs; the configs call is
   made the first time the Configs tab is opened. A user who came to look at disk usage should not
   wait for, or provoke, a `describeConfigs` call.

## The tab in the URL

| Page | Pattern | `history.state` tag |
| --- | --- | --- |
| `BrokerDetail(cluster, broker, LogDirs)` | `/clusters/<id>/brokers/<brokerId>` | `clusters.broker` |
| `BrokerDetail(cluster, broker, Configs)` | `/clusters/<id>/brokers/<brokerId>/configs` | `clusters.broker` |

The default tab has no segment rather than a `/logdirs` one, so the broker's canonical URL is its
short form and CLUI-004's link needs no tab. The stored `history.state` gains a `tab` field; a
state written before this task has none and decodes to `LogDirs`, so Back across a deployment
upgrade lands on the default tab instead of "not found". That compatibility is a test case, not a
comment.

**Decision, not covered by any ADR: the tab lives in the URL rather than in feature state.** ADR-011
makes feature state per-instance and long-lived, which would have kept the tab across navigation
just as well. The URL wins because of what these two tabs are: a configuration listing is the thing
an operator pastes into a ticket or a chat message, and a link that always opens on log directories
makes the recipient hunt. The cost is one more route pattern.

## The Configs tab, exactly

**Ordering.** By source, then by key: dynamic entries first, then static, then defaults, then
unknown. The order encodes what an operator is looking for — what somebody changed — and it matches
the reference product's `CONFIG_SOURCE` priority.

| Source (as the contract reports it) | Displayed as | Order |
| --- | --- | --- |
| dynamic broker config | `Dynamic broker config` | 1 |
| dynamic default broker config | `Dynamic default broker config` | 2 |
| dynamic broker logger config | `Dynamic broker logger config` | 3 |
| static broker config | `Static broker config` | 4 |
| default config | `Default config` | 5 |
| anything else | `Unknown` | 6 |

The mapping is total: an unrecognised source string renders as `Unknown` with the raw string in the
cell's `title`, never as a blank cell and never as a crash. Kafka adds config sources between
versions, and a new one must degrade to "we do not have a name for this" rather than to an empty
column.

**Value rendering**, in this order of precedence:

1. `sensitive` — `••••••••`, with the title *"Redacted by the server. KUI never receives this
   value."* That sentence is a fact about the system worth stating on the screen: the redaction is
   CLAPI-001's, server-side, and a user who has seen a UI that merely hides a value it holds has
   reason to want the difference spelled out.
2. A key ending in `.bytes` — formatted through `Bytes.format`, with the raw number in the title.
3. A key ending in `.ms` — formatted as a duration (`30 s`, `7 d`), with the raw number in the
   title.
4. Anything else — the value verbatim, wrapping rather than truncating. A `listeners` value is long
   and cutting it off hides the part that differs between brokers.
5. An empty value — the missing marker with the title *"Set to the empty string"*, which is not the
   same thing as absent and must not look the same.

**Search** is client-side over the entries already fetched, case-insensitive, matching key or value,
debounced 200 ms. It is one input, not a filter panel: there are a few hundred entries and the
question is always "what is `log.retention` set to".

**Read-only entries** carry a `read only` tag. No edit control appears anywhere — not enabled, not
disabled. DEVPLAN §3 puts broker config edits in M5 behind read-only mode and audit, and a disabled
edit button is a promise the product has not made.

## The Log directories tab, exactly

- One card per log directory. The path is the card's heading, rendered in a monospaced face and
  selectable — an operator is going to copy it into an `ssh` command.
- A directory the broker reported an error for shows that error in the danger colour in place of its
  table, with the directory still named. A broker with one failed disk and three good ones must show
  three tables and one error, not one page-level failure.
- The per-directory table: topic, partition, size (formatted, with a magnitude bar scaled within
  that directory), offset lag. Sortable, default by size descending, because the question that
  brings a user here is "what is taking up the space".
- A totals line per directory: number of topic-partitions and total size.
- No pagination. A busy broker has thousands of partitions; the table scrolls. Virtualization is
  SF-003 in M2 (DEVPLAN §3) and this table is the M2 task's first customer — record that in
  `TECH_DEBT.md` rather than solving it here.

## Non-goals

- **No config editing, no `alterBrokerConfig`, no inline edit affordance.** M5.
- **No metrics tab.** There is no metrics service until M8. The tab is absent, not disabled: ADR-032
  hides what is `NotConfigured` rather than dimming it, and a permanently disabled tab is noise on
  every visit for the next seven milestones.
- **No log-dir reassignment**, no `alterReplicaLogDir`. Not in M1's scope at all.
- **No polling.** D10.
- **No virtualization.** M2, SF-003.
- **No synonyms view.** BR-002's row mentions synonyms; the contract carries what CLAPI-001 puts in
  it, and if synonyms are present they are not rendered in M1 — a nested expansion is a screen of
  its own and nothing in the exit criteria needs it. Record it in `TECH_DEBT.md`.

## Design references

- **`research/kafbat/ui-analysis.md`** "Broker details" — the metric row, the tab set, the log-dir
  columns, the config source priority and the source-name map, the sensitive-value masking, the
  `*.bytes` / `*.ms` formatting, and the search placeholder wording. Also its recorded defect: a
  full-page loader on every refetch. KUI does not refetch and must not reproduce it.
- **`research/kafka/admin-capabilities.md`** §1 — `describeConfigs` and `describeLogDirs`: what they
  return, which per-key errors a partly-authorised cluster produces, and which fields a 2.8 broker
  cannot supply. It decides which cells can be missing; nothing else does.
- **`research/design/REFERENCE.md`** — cards for grouped content; monospaced values; magnitude bars;
  the compact density switch; status as a filled chip.
- **ADR-030** — capability gating, not version assumptions: a field the broker cannot supply is `—`.
- **ADR-032** — the stale rule and the fallback treatment.
- **ADR-024** — no colour or measurement computed in Scala; class names as constants.

## Files to create

```
frontend/ui-clusters/src/kui/ui/clusters/brokers/BrokerConfigsTab.scala
frontend/ui-clusters/src/kui/ui/clusters/brokers/BrokerLogDirsTab.scala
frontend/ui-clusters/src/kui/ui/clusters/brokers/ConfigEntry.scala
frontend/ui-clusters/src/kui/ui/clusters/brokers/LogDirView.scala
frontend/ui-clusters/src/kui/ui/clusters/component/Durations.scala
frontend/ui-clusters/test/src/kui/ui/clusters/brokers/ConfigEntrySuite.scala
frontend/ui-clusters/test/src/kui/ui/clusters/brokers/LogDirViewSuite.scala
frontend/ui-clusters/test/src/kui/ui/clusters/brokers/BrokerDetailPageSuite.scala
frontend/ui-clusters/test/src/kui/ui/clusters/component/DurationsSuite.scala
```

## Files to change

```
frontend/ui-clusters/src/kui/ui/clusters/brokers/BrokerDetailPage.scala  (skeleton → tabs)
frontend/ui-clusters/src/kui/ui/clusters/ClustersRoutes.scala            (the tab segment and codec)
frontend/ui-clusters/src/kui/ui/clusters/ClustersFeature.scala
frontend/ui-clusters/src/kui/ui/clusters/Messages.scala
frontend/ui-clusters/resources/css/41-clusters-brokers.css
docs/frontend/README.md
TECH_DEBT.md                                                             (two rows; see below)
```

## Public Scala signatures to implement

```scala
package kui.ui.clusters.brokers

/** Which tab of the broker detail page is open. Part of the page id, so it is in the URL. */
enum BrokerTab(val segment: Option[String], val label: String) {
  case LogDirs extends BrokerTab(None, "Log directories")
  case Configs extends BrokerTab(Some("configs"), "Configs")
}

object BrokerTab {
  /** Parses the trailing segment; anything unrecognised is `LogDirs`, so a hand-edited URL lands
    * on the page rather than on "not found".
    */
  def fromSegment(segment: Option[String]): BrokerTab
  given CanEqual[BrokerTab, BrokerTab] = CanEqual.derived
}
```

```scala
package kui.ui.clusters.brokers

/** One configuration entry, reduced to what the table draws. */
final case class ConfigEntry(
    name: String,
    value: ConfigValue,
    source: ConfigSource,
    readOnly: Boolean
)

/** How a value is to be rendered — decided once, as data, so the rule is testable without a DOM. */
enum ConfigValue {
  case Redacted
  case Bytes(raw: String, formatted: String)
  case Duration(raw: String, formatted: String)
  case Empty
  case Plain(text: String)
}

/** A known source, or one Kafka has added that KUI has no name for. */
enum ConfigSource(val label: String, val order: Int) {
  case DynamicBroker extends ConfigSource("Dynamic broker config", 1)
  case DynamicDefaultBroker extends ConfigSource("Dynamic default broker config", 2)
  case DynamicBrokerLogger extends ConfigSource("Dynamic broker logger config", 3)
  case StaticBroker extends ConfigSource("Static broker config", 4)
  case Default extends ConfigSource("Default config", 5)
  case Unknown(raw: String) extends ConfigSource("Unknown", 6)
}

object ConfigEntry {
  /** Every entry, ordered by source then key. Total over any source string. */
  def of(response: BrokerConfigsResponse): List[ConfigEntry]
  /** Case-insensitive match on key or value. A redacted value never matches on its value: it is
    * not on the client to be searched, and matching on `••••` would be a lie.
    */
  def matches(entry: ConfigEntry, term: String): Boolean
  /** The rendering rule, in precedence order. */
  def valueOf(name: String, raw: Option[String], sensitive: Boolean): ConfigValue
}
```

```scala
package kui.ui.clusters.brokers

/** One log directory, ready to draw. */
final case class LogDirView(
    path: String,
    error: Option[String],
    partitions: List[LogDirPartition],
    totalBytes: Long,
    largestPartitionBytes: Long
)

final case class LogDirPartition(topic: String, partition: Int, sizeBytes: Long, offsetLag: Long)

object LogDirView {
  def of(response: BrokerLogDirsResponse): List[LogDirView]
}
```

```scala
package kui.ui.clusters.component

object Durations {
  /** `900 ms`, `30 s`, `5 min`, `7 d` — the largest whole unit that does not lose precision;
    * otherwise the raw milliseconds with `ms`. `-1` renders as `unlimited`, which is what Kafka
    * means by it in a retention setting.
    */
  def fromMillis(raw: String): Option[String]
}
```

```scala
package kui.ui.clusters.brokers

object BrokerDetailPage {
  def apply(
      cluster: ClusterId,
      broker: BrokerId,
      tab: Signal[BrokerTab],
      selectTab: BrokerTab => Unit,   // pushes a route, so the URL changes with the tab
      queries: ClustersQueries,
      zone: Signal[String]
  ): HtmlElement
}
```

`selectTab` navigates rather than setting a `Var`, which is what keeps the URL and the visible tab
from being two independent truths.

## Library coordinates

None new. `Tabs`, `Card`, `DataTable`, `MagnitudeBar`, `TextInput`, `Tag`, `Breadcrumbs`,
`EmptyState` and `Tooltip` are already in `frontend/ui-kernel`; `domtestutils::19.0.0` is already on
this module's test classpath.

## Acceptance criteria

```
$ ./mill frontend.uiClusters.compile
$ ./mill frontend.uiClusters.test
$ ./mill frontend.uiClusters.checkFormat && ./mill frontend.uiClusters.fix --check
$ ./mill frontend.uiShell.checkBundleShape
$ ./mill __.compile
```

All clean.

Manual, once, against the Compose stack of CFGOP-006:

```
$ open http://localhost:8080/ui/clusters/<cluster>/brokers/1
```

Expected: the log-directories tab, one card per directory, partitions sorted largest first, sizes
that add up to the totals line and to the `Disk` cell for that broker on the brokers list. Clicking
`Configs` changes the URL to `.../brokers/1/configs`; the browser Back button returns to the log
directories without a full reload. In the configs table, dynamic entries are at the top, a
`sasl.jaas.config`-shaped entry shows `••••••••`, `log.retention.ms` shows a formatted duration with
the raw number in its tooltip, and typing `retention` in the search box narrows the table.

```
$ curl -s localhost:8080/api/v1/clusters/<cluster>/brokers/1/configs | grep -ci password
```

Expected `0` — restating, at the screen that would display it, the milestone's secret-leak criterion
that CLAPI-001 owns.

## Tests required

`ConfigEntrySuite` (pure):

- `sensitiveBeatsEveryOtherRule` — a sensitive key ending in `.ms` renders `Redacted`, not a
  duration. Precedence is the rule most likely to be implemented in the wrong order.
- `bytesAndMillisecondsAreFormattedFromTheKeySuffix`, with the raw value retained.
- `anEmptyValueIsNotTheSameAsAMissingOne`.
- `unknownSourcesOrderLastAndKeepTheirRawName` — the forward-compatibility case.
- `entriesAreOrderedBySourceThenKey`, asserted on a shuffled input.
- `searchMatchesKeyAndValueCaseInsensitively`.
- `searchNeverMatchesARedactedValue`.

`DurationsSuite` (pure): `0`, `1`, `999`, `1000`, `60000`, `86_400_000`, `604_800_000`, `-1`, and a
non-numeric value (which yields `None`, so the entry falls through to `Plain`).

`LogDirViewSuite` (pure):

- `totalsAreTheSumOfThePartitions`.
- `aDirectoryWithAnErrorKeepsItsPathAndHasNoPartitions`.
- `theLargestPartitionIsTheBarScale`, and a directory of all-zero sizes produces no `NaN`.

`BrokerDetailPageSuite` (jsdom):

- `theDefaultTabIsLogDirsAndItsUrlHasNoTabSegment`.
- `openingConfigsNavigatesAndRendersTheConfigTable` — `selectTab` is called, and the table appears
  when the signal changes.
- `theConfigsCallIsNotMadeUntilTheConfigsTabIsOpened` — asserted by counting requests on a stub
  client. This is the lazy-loading requirement, and it is invisible in the DOM.
- `aStoredStateWithNoTabDecodesToLogDirs` — the upgrade-compatibility case for `history.state`.
- `aBrokerWithOneFailedLogDirStillShowsTheOthers` — three tables and one error, not one failure.
- `noEditControlExistsInTheConfigsTable` — no `button` inside a config row, in any state. The
  assertion that keeps M5's affordance from arriving early and disabled.
- `noMetricsTabExists`.
- `aSensitiveValueIsNeverInTheDom` — the fixture's plaintext token appears nowhere in
  `document.body.innerHTML`. It cannot, because the server redacted it; asserting it here is what
  catches a future change that starts sending it.
- `anUnavailableConfigsSectionLeavesTheLogDirsTabWorking` — the two tabs fail independently.
- `aStaleSectionPutsThatTabsBodyUnderTheOverlayAndNotTheWholePage`.

## Observability

- Calls report with `CallScope.Feature`.
- `data-testid` hooks: `page-clusters-broker`, `broker-tab-logdirs`, `broker-tab-configs`,
  `broker-logdir-<index>`, `broker-config-row-<name>`, `broker-config-search`.
- Each tab shows its own section's `scrapedAt`, because the two are fetched at different moments and
  one timestamp for both would be wrong for one of them.

## Degraded behavior

- **Per tab, independently.** The two tabs read two endpoints; one failing must not blank the other.
  This is the same partial-aggregation rule the dashboard follows, applied within a page.
- **Per log directory, independently.** A directory-level error is shown in place of that
  directory's table, with the path still visible.
- **Per config key.** A partly-authorised cluster returns per-key errors
  (`research/kafka/admin-capabilities.md` §1); an entry KUI could not read renders its key with the
  missing marker and the error in its title. It is not dropped from the table: an absent row is
  indistinguishable from a setting that does not exist.
- **Section `Stale`** — that tab's body goes under `StaleDataOverlay` with its own `fetchedAt`;
  search and sorting keep working on the rows in hand.
- **Section `Unavailable`** — that tab's body is an explanation with the reason verbatim and a
  "Try again" button; the other tab and the identity strip are unaffected.
- **A broker id in the URL that does not exist** — "Broker `<id>` is not in this cluster", with a
  link back to the broker list. Not a 404 route: the cluster is real and the user is one click from
  what they wanted.

## Docs to update

- `docs/domain/cluster.md` — the config-source vocabulary and its order, and the statement that
  sensitive values are redacted server-side and never reach the browser.
- `docs/frontend/README.md` — the tab-in-the-URL decision and its `history.state` compatibility
  rule, as the pattern later features copy.
- `TECH_DEBT.md` — two rows: the unvirtualized log-dir table (owner: SF-003, M2) and config synonyms
  carried by the contract but not rendered (owner: M5, with the config edit screen).

## Deviations

Commit `16e1f9d`.

1. **There is no per-partition table, because there is no per-partition data.** `LogDirDto` carries a
   directory's path, error, total and usable bytes, topic count and partition count — and no
   topic-partition entries at all. BR-005's and PA-003's breakdown ("which topic is filling this
   disk") therefore has nothing behind it. The screen shows what exists — one card per directory, its
   path, its error, how full it is with a bar, and its counts — rather than fabricating rows or
   inventing a second request, which this task's own rules forbid. **Owed:** `TECH_DEBT.md` TD-017
   records the contract field, TD-018 the virtualization it will then need.

2. **`LogDirView` therefore has a different shape** from the sketch: `totalBytes`, `usableBytes`, a
   derived `usedBytes` and a `usedFraction`, instead of `partitions` and `largestPartitionBytes`.
   `usedFraction` is `None` rather than zero on a broker too old to report sizes — an empty bar reads
   as a disk with room on it, which is the opposite of a safe thing to leave on an operator's screen.

3. **`ConfigSource.fromWire` matches case- and punctuation-insensitively.** The same source has been
   spelled `DYNAMIC_BROKER_CONFIG` and `dynamic broker config` by different producers, and showing
   "Unknown" for one of them would report a version difference as a problem.

4. **`ConfigEntry` carries `documentation`**, rendered as a tooltip on the setting's name. The DTO has
   the field and the `docs` query flag exists to fill it; discarding it here would have made the flag
   pointless.

5. **Search matches a redacted entry on its key alone**, never on a value. Matching the mask
   characters would be a lie, and the real value is not in the browser.

6. **`TabBody` is a shared helper the spec does not name.** Both tabs need the same four section
   renderings with their own timestamps; writing it twice is how two tabs on one page end up
   disagreeing about what "unavailable" looks like.

7. **The tab's URL uses two route patterns with partial encoders.** A single pattern with an optional
   segment encodes a page that names a tab to the tabless URL, so a link to a broker's configuration
   opens on its log directories. That bug existed briefly and
   `theTabIsInTheUrlSoAConfigsLinkOpensOnConfigs` is what found it.

8. **`aStoredStateWithNoTabDecodesToLogDirs` is asserted against `ClustersRoutes.decodePage`
   directly**, which is where the compatibility actually lives.

## Implementation report

```
./mill frontend.uiClusters.compile        SUCCESS
./mill frontend.uiClusters.test           0 failed, 85 tests
                                          ConfigEntrySuite        9
                                          LogDirViewSuite         7
                                          DurationsSuite          4
                                          BrokerDetailPageSuite  13
./mill frontend.uiClusters.checkFormat    SUCCESS
./mill frontend.uiClusters.fix --check    SUCCESS
./mill frontend.uiShell.checkBundleShape  1 feature module split out
./mill checkArchitecture                  75 modules, no layering violations
```
