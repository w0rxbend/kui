# CLUI-007 — Settings page: theme, timezone, refresh rate, table density

- **ID:** CLUI-007
- **Title:** Settings page: theme, timezone, refresh rate, table density
- **Milestone / Feature:** M1 / KU-012, AU-005
- **Owner role:** Frontend Architect
- **Size:** M
- **Dependencies / blocked by:** CLUI-001 (`Timestamps`, which the timezone preference formats
  through). **Sequencing constraint:** this task edits `frontend/ui-shell` and `frontend/ui-kernel`,
  which another swarm is restyling. Do not start until that work has landed on `main` (DEVPLAN §6.1,
  risk R-9).

## Goal (user value)

Four preferences an operator sets once and forgets: whether the interface is light or dark, which
timezone every timestamp in the product is shown in, whether tables are comfortable or dense, and
whether a screen re-reads the server's snapshot on its own. The timezone is the one that matters
most and is the reason this ships in M1 rather than later: every cluster screen shows a `scrapedAt`,
and an operator comparing it against a broker log needs both in the same zone.

M0 shipped a settings stub with a theme selector and a build number. This turns it into the real
page.

## Scope

1. **`Timezone`** — a new kernel preference: an IANA zone id, defaulting to the browser's own zone,
   persisted in `localStorage`. Every timestamp in the product formats through it.
2. **`RefreshRate`** — a new kernel preference: `Off` (the default), 30 s, 1 min, 5 min.
3. **The settings page**, rebuilt: an Appearance card (theme, accent, density), a Time card
   (timezone), a Data card (refresh rate), and the existing About card.
4. **Wiring the preferences into the cluster screens**: the pages CLUI-003 … CLUI-005 already take a
   `zone: Signal[String]`; `ClustersFeature` now passes `Timezone.choice.signal` instead of the
   system zone. `RefreshRate` drives a timer that invalidates the module's `QueryCache` entries.
5. **Nothing about accounts.** AU-005's row is "user menu: logout, theme, timezone", and the row's
   own note says the logout item appears in M6. M1 ships theme and timezone.

## The refresh-rate control, and a decision that needs stating

DEVPLAN §10 D10 decides that **the browser does not poll**: the server refreshes its cluster
snapshot every 30 seconds, the browser reads it once and shows `scrapedAt`, and the user's control
is an explicit refresh button. A settings control called "refresh rate" therefore needs an explicit
reconciliation, or a worker will reasonably conclude that one of the two documents is wrong.

**Decision: the setting is off by default, and what it re-reads is the server's snapshot, not the
cluster.** D10's target is a browser poll that reaches a broker — Kafbat's five-second interval,
multiplied by every open tab, producing load on the cluster that no user asked for, and its
full-page loader on every refetch. Re-reading an already-computed server-side snapshot at a
user-chosen interval is a different thing: it costs one cached HTTP response, it cannot reach a
broker at all, and it is off unless somebody turns it on. The shortest interval offered is 30
seconds, matching the server's own refresh, because a faster one would return identical bytes.

Two rules make the difference visible rather than a matter of trust:

- **It never sets a forced refresh going.** The refresh *button* (CLUI-008) is what asks the server
  to re-scrape; the refresh *rate* only re-reads. A timer that could trigger a broker scrape would
  be exactly the thing D10 forbids, with a different name.
- **A refetch never shows a loader over data that is already on screen.** The old rows stay, the new
  ones replace them when they arrive. This is the defect `research/kafbat/ui-analysis.md` records
  verbatim for Kafbat's broker page, and reproducing it would make the setting actively harmful.

## The timezone control

- The list is `Intl.supportedValuesOf('timeZone')` plus `UTC`, with each entry labelled
  `UTC+02:00 Europe/Warsaw` and sorted by offset then name — the reference product's arrangement,
  which puts the zones a user is likely to want near the one they are in.
- The control is a searchable list, because there are several hundred entries and a plain select is
  unusable at that size.
- The default is the browser's own zone, detected once. It is stored only when the user chooses one:
  an unset preference follows the machine, so an operator who travels does not have yesterday's zone
  frozen in.
- A stored zone the runtime does not recognise falls back to `UTC` and the control shows `UTC`
  selected. `Timestamps` already implements that fallback; this is the same rule at the control.
- **`Intl.supportedValuesOf` may be absent.** It is not universal, and a runtime without it must not
  produce an empty list. The fallback is a fixed list of the browser's own zone plus `UTC`, so the
  control always offers at least the two zones that matter.

## Non-goals

- **No login, no logout, no account section.** M6 (DEVPLAN §3: `kui.auth.type` stays `disabled`).
- **No server-side storage of preferences.** All four live in this browser. The feature matrix says
  so for KU-012, and there is no per-user store until M6.
- **No per-screen refresh-rate override.** Kafbat stores one per resource; KUI has one, globally,
  until a screen exists that genuinely wants a different cadence.
- **No date-format preference**, no 12/24-hour choice, no locale selection. One format, ISO-shaped,
  in the chosen zone. Adding a format preference multiplies the timestamp test matrix and nothing in
  M1 asks for it.
- **No new visual design.** The page uses the cards and controls the restyle produced.

## Design references

- **DEVPLAN §10 D10** — the polling decision this task reconciles with, above.
- **ADR-024** — preferences that are purely visual are an attribute on `<html>` and a stylesheet
  rule, never a value computed in Scala. Theme, accent and density already work that way; timezone
  and refresh rate do not, because neither is a matter of appearance.
- **ADR-011** — Laminar, `Var`-held state, no global mutable singletons beyond the kernel's own
  preferences.
- **ADR-032** — the stale rule the refresh rate interacts with: a refetch must never remove data
  that is on screen.
- **`research/kafbat/ui-analysis.md`** — `UserTimezone` (the list source, the label shape, the
  sort, the persistence) and `RefreshRateSelect` (the option set and the default of Off).
- **`research/design/REFERENCE.md`** — density is a switch, not a theme: a `compact` flag changes
  table row padding from 15 px to 9 px and nothing else. The settings page presents it that way,
  as a two-state switch, not as a size scale.
- `frontend/ui-kernel/src/kui/ui/kernel/theme/Appearance.scala` — the `RootPreference` /
  `RootPreference.persisted` pattern the two new preferences follow. Describe intent, not line
  numbers: the restyle may have moved them.

## Files to create

```
frontend/ui-kernel/src/kui/ui/kernel/prefs/Timezone.scala
frontend/ui-kernel/src/kui/ui/kernel/prefs/RefreshRate.scala
frontend/ui-kernel/src/kui/ui/kernel/prefs/TimeZoneList.scala
frontend/ui-kernel/test/src/kui/ui/kernel/prefs/TimezoneSuite.scala
frontend/ui-kernel/test/src/kui/ui/kernel/prefs/RefreshRateSuite.scala
frontend/ui-shell/test/src/kui/ui/shell/page/SettingsPageSuite.scala
```

## Files to change

```
frontend/ui-shell/src/kui/ui/shell/page/SettingsPage.scala   (the four cards)
frontend/ui-shell/src/kui/ui/shell/Main.scala                (install the new preferences)
frontend/ui-shell/src/kui/ui/shell/Messages.scala
frontend/ui-shell/resources/css/30-shell.css
frontend/ui-clusters/src/kui/ui/clusters/ClustersFeature.scala  (pass the zone; drive the timer)
docs/frontend/README.md
```

`ClustersFeature` is in this task's file list because the preferences are only real once something
reads them, and a preference nothing reads is untestable end to end. The edit is two lines: the zone
signal, and the refresh timer's subscription.

## Public Scala signatures to implement

```scala
package kui.ui.kernel.prefs

/** The timezone every timestamp in the product is rendered in. */
object Timezone {

  val StorageKey: String = "kui.timezone"

  /** The chosen zone, or the browser's own when nothing has been chosen. Writing persists. */
  def choice: Var[String]

  /** Every zone the runtime offers, as `(id, label)` sorted by offset then id, where a label reads
    * `UTC+02:00 Europe/Warsaw`. Falls back to the browser's own zone plus `UTC` when
    * `Intl.supportedValuesOf` is not available.
    */
  def available(): List[(String, String)]
}
```

```scala
package kui.ui.kernel.prefs

/** How often a screen re-reads the server's snapshot on its own. Never a broker scrape. */
enum RefreshRate(val storageValue: String, val label: String, val interval: Option[FiniteDuration]) {
  case Off extends RefreshRate("off", "Off", None)
  case Every30s extends RefreshRate("30s", "Every 30 seconds", Some(30.seconds))
  case Every1m extends RefreshRate("1m", "Every minute", Some(1.minute))
  case Every5m extends RefreshRate("5m", "Every 5 minutes", Some(5.minutes))
}

object RefreshRate {
  val StorageKey: String = "kui.refreshRate"

  /** Anything unrecognised reads as `Off`: a corrupted preference must not start a timer. */
  def fromStorage(raw: String): RefreshRate
  def choice: Var[RefreshRate]

  /** A stream that ticks at the chosen interval and emits nothing at all while the choice is
    * `Off`. Changing the choice restarts the timer rather than leaving the old one running.
    */
  def ticks: EventStream[Unit]

  given CanEqual[RefreshRate, RefreshRate] = CanEqual.derived
}
```

```scala
package kui.ui.kernel.prefs

object TimeZoneList {
  /** `UTC+02:00 Europe/Warsaw` for a zone id at a given instant. */
  def label(zoneId: String, at: Instant): String
  /** Ordering: by current UTC offset, then by id. */
  def ordering(at: Instant): Ordering[String]
}
```

```scala
package kui.ui.shell.page

object SettingsPage {
  /** Every preference is passed in rather than read from the objects, so the suite drives the page
    * with its own `Var`s and no `localStorage`.
    */
  def apply(
      theme: Var[ThemeChoice],
      accent: Var[AccentChoice],
      density: Var[DensityChoice],
      timezone: Var[String],
      zones: List[(String, String)],
      refreshRate: Var[RefreshRate],
      buildVersion: Signal[String]
  ): HtmlElement
}
```

## Library coordinates

None new. `io.github.cquiroz::scala-java-time::2.7.0` — already on `frontend/ui-kernel`
(`DEPENDENCY_MATRIX.md`) — supplies `ZoneId` and the offset arithmetic `TimeZoneList` needs.
`Intl.supportedValuesOf` is reached through a small `scala.scalajs.js.Dynamic` facade in
`TimeZoneList`, which is where ADR-025 says a facade for a browser API belongs; no facade library is
added.

## Acceptance criteria

```
$ ./mill frontend.uiKernel.compile
$ ./mill frontend.uiKernel.test
$ ./mill frontend.uiShell.test
$ ./mill frontend.uiClusters.test
$ ./mill frontend.uiKernel.checkFormat && ./mill frontend.uiKernel.fix --check
$ ./mill frontend.uiShell.checkFormat && ./mill frontend.uiShell.fix --check
```

All clean.

Manual, once, against CFGOP-006's Compose stack:

- Setting the timezone to `Asia/Tokyo` changes the `scrapedAt` on the brokers page immediately, with
  no reload, and the change survives a reload.
- Setting the density to compact narrows every table row and changes nothing else.
- With the refresh rate `Off` — the default — the browser's network panel shows no repeated request
  to `/api/v1/clusters` while the dashboard sits open for two minutes.
- Setting it to "Every 30 seconds" produces one request every 30 seconds, and the table never blanks
  or shows a loader between them.
- Setting it back to `Off` stops the requests without a reload.

That third and fourth check together are the evidence that D10 is honoured: the default is no
traffic, and the opt-in is bounded and visible.

## Tests required

`TimezoneSuite` (jsdom):

- `anUnsetPreferenceFollowsTheBrowsersZone`.
- `aChosenZonePersistsAndIsReadBack`.
- `anUnknownStoredZoneFallsBackToUtc`.
- `theListIsSortedByOffsetThenId`.
- `theListFallsBackToTheBrowserZoneAndUtcWhenIntlOffersNothing` — driven by a stub for the facade.
- `labelsHaveTheOffsetAndTheId`.

`RefreshRateSuite` (jsdom):

- `offEmitsNothing` — the assertion that matters most; a timer that ticks while the setting is off
  is the failure D10 exists to prevent.
- `changingTheRateRestartsTheTimerAndDoesNotLeaveTheOldOneRunning` — asserted by counting ticks over
  a stubbed clock after two changes.
- `anUnknownStoredValueReadsAsOff`.
- `thePersistedChoiceSurvivesAReadBack`.

`SettingsPageSuite` (jsdom, shell):

- `everyPreferenceHasALabelledControlAndTheCurrentValueSelected`.
- `changingAControlWritesToItsVarAndNothingElse` — four cases; a settings page that writes two
  preferences from one control is a bug that only shows up as a mystery later.
- `theTimezoneControlIsSearchableAndFiltersByIdAndOffset`.
- `theRefreshRateDefaultsToOffInTheRenderedControl`.
- `theAboutCardStillShowsTheBuild` — the M0 behaviour must survive the rebuild.
- `theSettingsPageRendersWithEveryServiceDown` — driven with no capabilities at all. Settings needs
  only the gateway (ADR-032, IA.3's first row), and CFGOP-007 asserts exactly this end to end.

`ClustersStateSuite` / `DashboardPageSuite` (existing, one case each):

- `aRefreshTickInvalidatesTheCacheAndDoesNotBlankTheTable`.

## Observability

- No metric, no server call. Preferences are local by design.
- `data-testid` hooks: `page-settings`, `settings-theme`, `settings-accent`, `settings-density`,
  `settings-timezone`, `settings-refresh-rate`, `settings-build`. `settings-theme` and
  `settings-build` already exist in M0 and must keep their names — CFGOP-007's E2E asserts the
  settings page is usable while the cluster service is stopped, and renaming a hook to tidy it up
  would break that silently.
- Every timestamp in the product renders through `Timestamps` with this preference. That is the
  observability requirement: one zone, one formatter, no screen doing its own.

## Degraded behavior

- **`localStorage` unavailable** (private window, enterprise policy) — every preference becomes an
  in-memory value that works for the session and is forgotten on reload. The page shows no warning:
  a private window is not an error state, and the existing theme preference already behaves this
  way.
- **A corrupted stored value** — read as the default, silently, for all four. The alternative — an
  error on a settings page — is a page a user cannot use to fix the problem.
- **`Intl.supportedValuesOf` missing** — the timezone list degrades to two entries and the control
  still works.
- **Every service down** — the settings page is fully usable. It reads nothing from any service, and
  that is a requirement rather than an accident: it is one of the two screens M1's fault-isolation
  criterion names as having to survive the cluster service being stopped.
- **The refresh timer while a call is in flight** — a tick that lands on an outstanding request is
  dropped, not queued. `QueryCache` already reference-counts and de-duplicates; the timer must not
  work around it.

## Docs to update

- `docs/frontend/README.md` — the preference inventory: what is stored, under which key, what
  happens when storage is unavailable, and the rule that a preference is passed into a component
  rather than read from a global inside it (which is what makes the suites above possible).
- `docs/operations/configuration.md` — one line: user preferences are browser-local in M1 and are
  not an operator concern; there is nothing to configure and nothing to back up.

## Deviations

Commit `4c9d9f4`.

1. **`ClustersFeature` was not edited, and the two cluster-side tests were not written.** The spec
   lists `frontend/ui-clusters/src/kui/ui/clusters/ClustersFeature.scala` because "a preference
   nothing reads is untestable end to end", and asks for
   `aRefreshTickInvalidatesTheCacheAndDoesNotBlankTheTable`. The screens that would read the zone and
   the caches the tick would invalidate are CLUI-002 through CLUI-005, and none of them exists yet —
   the cluster contract's read DTOs (CLAPI-001/002) had not landed when this ran. Wiring a zone
   signal into a page that renders no timestamps, and a timer into a cache with no entries, would be
   two lines that assert nothing. **Owed:** those two lines and the one named test, in CLUI-003, in
   the commit that first renders a `scrapedAt`.

2. **`SearchableSelect` is a new kernel component, not a local one.** The spec calls for a
   searchable timezone control and the kernel has none — `Select` wraps the native `<select>`, which
   is the wrong control at several hundred entries, and the kernel's own note defers a combobox to
   M2. Per the area's standing instruction (a screen that needs something the kernel lacks adds it to
   the kernel), it was built in `kui.ui.kernel.component` with the full ARIA combobox contract, a row
   in `A11ySuite`'s table and a seven-case suite of its own. M2's topic filter now has it already.

3. **`RootPreference.persisted` widened from `private[theme]` to `private[kernel]`.** The two new
   preferences are stored exactly the way the appearance ones are but are not matters of appearance,
   so they live in `kui.ui.kernel.prefs` and need the helper across a package boundary. One qualifier
   changed, with the reason in its scaladoc; no behaviour moved.

4. **`TimeZoneList.entries` takes the runtime's list as a parameter** rather than `available()` being
   the only entry point. That is what makes
   `theListFallsBackToTheBrowserZoneAndUtcWhenIntlOffersNothing` testable without a browser that
   lacks `Intl.supportedValuesOf`, and it is where the extra case
   `aZoneTheRuntimeDoesNotKnowIsNotOffered` came from: a runtime that names a zone it cannot then
   resolve would otherwise put a broken choice in the list. `RefreshRate.ticksOf` takes its timer for
   the same reason — the suite counts ticks instead of waiting five real minutes.

5. **The offset helpers live on `Timestamps`, not on `TimeZoneList`.** `Timestamps.offsetSeconds`,
   `offsetLabel` and `isKnownZone` are public there because that is where the browser's zone database
   is already reached (CLUI-001's deviation 1); a second facade would be two places that disagree
   about what a zone is.

6. **`changingAControlWritesToItsVarAndNothingElse` is two tests, not one.** The timezone control is
   a combobox and is driven by keystrokes rather than by a `change` event, so it has its own case,
   `changingTheTimezoneWritesOnlyTheTimezone`, making the same assertion.

7. **The manual checks against the Compose stack were not run.** CFGOP-006 has not landed. The
   default-is-silent half is asserted in `RefreshRateSuite.offEmitsNothing`, which is stronger than
   the network-panel observation it stands in for: it asserts no timer is *created*, not merely that
   no request is seen.

## Implementation report

```
./mill frontend.uiKernel.compile        SUCCESS
./mill frontend.uiKernel.test           0 failed
                                        TimezoneSuite            7
                                        RefreshRateSuite         5
                                        SearchableSelectSuite    7
                                        A11ySuite               14 (13 + the combobox row)
./mill frontend.uiShell.test            0 failed, 12 suites
                                        SettingsPageSuite        7
./mill frontend.uiKernel.checkFormat    SUCCESS
./mill frontend.uiKernel.fix --check    SUCCESS
./mill frontend.uiShell.fix --check     SUCCESS
./mill checkArchitecture                75 modules, no layering violations
```
