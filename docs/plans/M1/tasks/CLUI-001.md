# CLUI-001 — `ui-kernel`: `StaleDataOverlay` and stale retention in `QueryCache`

- **ID:** CLUI-001
- **Title:** `ui-kernel`: `StaleDataOverlay` and stale retention in `QueryCache`
- **Milestone / Feature:** M1 / KU-010
- **Owner role:** Frontend Architect
- **Size:** M
- **Dependencies / blocked by:** none in the task graph. **One sequencing constraint that is not a
  dependency:** another swarm is restyling `frontend/ui-kernel` to the design of
  `research/design/REFERENCE.md`. Do not start this task until that work has landed on `main`
  (DEVPLAN §6.1, risk R-9). Verify with `git log --oneline -- frontend/ui-kernel` before the first
  edit; the restyle commits are the ones whose subjects say `restyle` / `design`.

## Goal (user value)

When the service behind a screen goes away, the numbers the user was already looking at stay on
screen — visibly old, with the time they were fetched, and with every action that would change
something disabled — instead of being replaced by an empty table or an error page. That is
ADR-032's stale-data rule (DC-H3) and it is the single behaviour that makes "a cluster being down
never takes the page down" true in practice rather than in principle.

M0 shipped half of it: `ClustersState` keeps its last successful list on a failure, and
`ClustersPage` dims the table. That is one screen's private convention. Every M1 screen needs the
same thing, so it becomes one kernel component and one documented `QueryCache` guarantee, and the
convention becomes a checked contract.

## Scope

1. **`StaleDataOverlay`** — a kernel component that wraps arbitrary content and, when it is told
   the content is stale, renders it dimmed and non-interactive behind a badge that says when the
   content was fetched and why it is not being refreshed. It never hides the content, never
   replaces it with a spinner, and never unmounts it.
2. **`Timestamps`** — a pure formatting helper: an `Instant` plus an IANA zone id, rendered
   absolutely (`2026-09-03 14:05:11 UTC+02:00`) and relatively (`8 minutes ago`). The overlay's
   badge shows the relative form with the absolute form as its `title`, because "8 minutes ago" is
   what a user reads and the exact instant is what they paste into a ticket. It takes the zone as a
   parameter and reads no preference: CLUI-007 owns the preference and passes it in, so this
   component has no dependency on a task scheduled after it.
3. **Stale retention in `QueryCache`, made explicit.** The cache already keeps a failed key's last
   good value (`CacheEntry` holds `Either[ApiError, A]` and a refetch replaces the whole entry). It
   does *not* today expose "the last value that was a `Right`, and when it was fetched" separately
   from "the current outcome", so a caller cannot render last-good data next to a current error
   without holding a shadow copy — which is exactly what `ClustersState` does by hand. This task
   adds that as a first-class read: `QueryCache.lastGood(key)` and `QueryCache.state(key)`, and a
   guarantee, tested, that a failing refetch never discards a previously resolved value.
4. **The dimming is CSS, not opacity computed in Scala** (ADR-024): the overlay writes a class and
   `aria-busy`, and `21-kernel-overlays.css` decides what stale looks like.

## Non-goals

- **No polling, no auto-retry.** The overlay shows state; it does not fetch. ADR-032 makes retry an
  explicit user action, and DEVPLAN §10 D10 forbids browser-side polling of cluster data.
- **No timezone preference, no settings control.** CLUI-007.
- **No `BoundedCache`, no eviction-policy change.** The existing bound and reference counting stay
  exactly as UI-006 built them.
- **No screen changes.** `ClustersPage` keeps its M0 body; CLUI-003 replaces it. Migrating
  `ClustersState` onto `QueryCache` is not part of this task.
- **No i18n.** Sentences live in the kernel's own message object, English only (ADR-024, UI-006's
  precedent).

## Design references

- **ADR-032** — the rendering rules, and the stale-data rule in the Decision list: "stale data from
  the session stays on screen greyed with its timestamp; actions disabled".
- **ADR-024** — CSS custom properties and semantic tokens only; no component-scoped tokens; no
  colour value written in Scala.
- **ADR-016** — every cache states a staleness contract; this task writes the browser half of it.
- **`research/kafbat/ui-analysis.md`** IA.3, common rule 3 ("Cached data") — the badge wording
  pattern *"Last updated 12:03 — service unavailable"* and "actions remain disabled". IA.4 lists
  `StaleDataOverlay` as a kernel component with no Kafbat equivalent.
- **`research/design/REFERENCE.md`** — the badge is a filled chip using a container colour and its
  paired text colour (`--warnc` / `--warn`), never a bare dot; radius and density come from the
  token set. The design has no stale state of its own, so the chip treatment is the design's
  status-chip pattern applied to a state the design does not draw.
- **`docs/frontend/README.md`**, `frontend/ui-kernel/src/kui/ui/kernel/component/*` — the existing
  component conventions this must match: an `apply` returning `HtmlElement`, `Signal`-valued inputs,
  an optional `testId`, class names as constants in `KernelCss`.

## Files to create

```
frontend/ui-kernel/src/kui/ui/kernel/component/StaleDataOverlay.scala
frontend/ui-kernel/src/kui/ui/kernel/time/Timestamps.scala
frontend/ui-kernel/test/src/kui/ui/kernel/component/StaleDataOverlaySuite.scala
frontend/ui-kernel/test/src/kui/ui/kernel/time/TimestampsSuite.scala
```

## Files to change

```
frontend/ui-kernel/src/kui/ui/kernel/query/QueryCache.scala   (add `state`, `lastGood`)
frontend/ui-kernel/src/kui/ui/kernel/css/KernelCss.scala      (the overlay's class names)
frontend/ui-kernel/resources/css/21-kernel-overlays.css       (the overlay's rules)
frontend/ui-kernel/test/src/kui/ui/kernel/query/QueryCacheSuite.scala  (retention tests)
docs/frontend/README.md                                       (when to use the overlay)
```

## Public Scala signatures to implement

```scala
package kui.ui.kernel.component

/** Why the content under the overlay is not being refreshed. Rendered verbatim after the state
  * word, exactly as ADR-032 requires the registry's reason to be rendered.
  */
final case class StaleReason(state: String, detail: Option[String])

object StaleReason {
  /** "Unavailable" plus the registry's reason string. */
  def unavailable(detail: String): StaleReason = StaleReason("Unavailable", Some(detail))
  /** "Degraded" plus the registry's reason string. */
  def degraded(detail: String): StaleReason = StaleReason("Degraded", Some(detail))
  /** The last request failed but the feature is not reported down: no state word to add. */
  def lastRequestFailed(detail: String): StaleReason = StaleReason("Not refreshed", Some(detail))
}

object StaleDataOverlay {

  /** Wraps `content`, dimming it and disabling interaction whenever `stale` holds a reason.
    *
    * @param content   rendered once and never unmounted. The whole point is that what the user was
    *                  looking at is still there.
    * @param stale     `None` means fresh: no badge, no dimming, no `aria-busy`.
    * @param fetchedAt when the content under the overlay was last successfully fetched. `None`
    *                  renders "never refreshed" rather than a fabricated time.
    * @param zone      the IANA zone id the badge formats in, supplied by the caller.
    * @param now       the clock, so the relative form is testable without waiting.
    */
  def apply(
      content: HtmlElement,
      stale: Signal[Option[StaleReason]],
      fetchedAt: Signal[Option[Instant]],
      zone: Signal[String],
      now: () => Instant = () => Instant.now(),
      testId: Option[String] = None
  ): HtmlElement
}
```

```scala
package kui.ui.kernel.time

object Timestamps {

  /** `2026-09-03 14:05:11 UTC+02:00` in the given IANA zone. An unknown or unsupported zone id
    * falls back to `UTC` rather than throwing: a bad stored preference must not blank a page.
    */
  def absolute(at: Instant, zone: String): String

  /** `just now`, `8 minutes ago`, `3 hours ago`, `2 days ago`; `in 5 seconds` for a future
    * instant (clock skew between browser and server is real and must not render as a negative).
    */
  def relative(at: Instant, now: Instant): String

  /** The badge's own line: `Last updated 8 minutes ago`, or `Never refreshed`. */
  def lastUpdated(at: Option[Instant], now: Instant): String

  /** The zone the browser is in, from `Intl.DateTimeFormat().resolvedOptions().timeZone`,
    * falling back to `"UTC"` when the runtime does not supply one.
    */
  def systemZone(): String
}
```

```scala
package kui.ui.kernel.query

/** What a caller needs in order to draw a screen: the current outcome, the last value that was
  * ever good, and when that value was fetched. All three at once, because rendering the stale rule
  * needs all three and deriving them separately is what leads to a shadow copy per screen.
  */
final case class QueryState[A](
    pending: Boolean,
    outcome: Option[Either[ApiError, A]],
    lastGood: Option[A],
    lastGoodAt: Option[js.Date]
) {
  /** True when there is something to show and the newest thing we know is a failure. */
  def isStale: Boolean = lastGood.isDefined && outcome.exists(_.isLeft)
}

trait QueryCache[K, A] {
  // ... existing members unchanged ...

  /** The full state of one key. Subscribing has exactly the same fetch-on-demand and
    * reference-counting behaviour as `get`.
    */
  def state(key: K): Signal[QueryState[A]]

  /** The last successfully fetched value for a key, which a failing refetch never clears. */
  def lastGood(key: K): Signal[Option[A]]
}
```

`get` keeps its current signature and its current meaning. `state` is derived from the same entry,
so a screen that watches both makes one request.

## Library coordinates

No new dependencies. The module already has `com.raquo::laminar::17.2.1`,
`com.raquo::airstream::17.2.1`, `org.scala-js::scalajs-dom::2.8.1` and
`io.github.cquiroz::scala-java-time::2.7.0` (`DEPENDENCY_MATRIX.md` lines for `frontend/*` and
`frontend/ui-kernel`); the test module already has `com.raquo::domtestutils::19.0.0`. `Instant` and
`ZoneId` come from `scala-java-time`, which is why the browser can have them at all.

`Timestamps.absolute` formats through `java.time.format.DateTimeFormatter` from `scala-java-time`
rather than through the browser's `Intl`, so that the JVM and the browser can be given the same
instant in a test and produce the same string. `systemZone()` is the one place that reads `Intl`,
through a facade, because `scala-java-time` cannot know what the operating system is set to.

## Acceptance criteria

```
$ ./mill frontend.uiKernel.compile
$ ./mill frontend.uiKernel.test
```

Both clean; the second reports the new suites passing. `-Werror` is on, so an unused import or a
discarded value fails the first command.

```
$ ./mill frontend.uiKernel.checkFormat
$ ./mill frontend.uiKernel.fix --check
$ ./mill checkArchitecture
```

All three clean. Then, because the kernel and the JVM cannot share one Mill invocation
(`CLAUDE.md`):

```
$ ./mill __.compile
```

clean, proving nothing downstream broke on the `QueryCache` change.

Expected output of the test command is MUnit's summary with no failures and the four suite names
present: `StaleDataOverlaySuite`, `TimestampsSuite`, `QueryCacheSuite`, and every suite that
already existed in the module.

## Tests required

`StaleDataOverlaySuite` (jsdom, `domtestutils` — the module's test env is already
`KuiJsDomTests`):

- `freshContentHasNoBadgeAndNoAriaBusy` — with `stale = None` the wrapper carries neither the stale
  class nor `aria-busy`, and no badge element exists.
- `staleContentKeepsEveryChildInTheDom` — the exact child nodes present before the transition are
  the same nodes after it. This is the assertion the whole task exists for: it fails if anyone ever
  "optimises" the overlay into a conditional render.
- `staleContentIsMarkedBusyAndInert` — the wrapper gains `aria-busy="true"`, and every `button` and
  `a` beneath it is `disabled` / `aria-disabled="true"` and is not reachable by tab.
- `theBadgeNamesTheStateAndTheReasonVerbatim` — for `StaleReason.unavailable("connection refused")`
  the badge's text contains `Unavailable` and `connection refused` with no rewording.
- `theBadgeShowsRelativeTimeWithTheAbsoluteTimeAsTitle`.
- `neverFetchedRendersNeverRefreshed` — `fetchedAt = None` produces `Never refreshed`, never
  `Last updated a moment ago`.
- `theBadgeIsAnnouncedOnce` — the badge is `role="status"` with `aria-live="polite"`, so a screen
  reader is told the data went stale without the whole table being re-announced.

`TimestampsSuite` (jsdom, but DOM-free assertions):

- `absoluteFormatsInTheGivenZone` — the same `Instant` in `UTC`, `Europe/Warsaw` and
  `America/New_York` produces three different, exactly specified strings.
- `anUnknownZoneFallsBackToUtcRatherThanThrowing` — `"Mars/Olympus"` yields the UTC rendering.
- `relativeCoversEveryBoundary` — a table: 0 s, 30 s, 60 s, 119 s, 2 min, 59 min, 60 min, 23 h,
  24 h, 8 d, each with its expected string, singular and plural both present.
- `aFutureInstantReadsAsFutureNotNegative`.
- `lastUpdatedOfNoneIsNeverRefreshed`.

`QueryCacheSuite` (existing suite, new cases):

- `aFailingRefetchKeepsTheLastGoodValue` — resolve a key, invalidate, fail the refetch: `state`
  reports `outcome = Left`, `lastGood = Some(previous)`, `isStale = true`, and `lastGoodAt` is
  unchanged.
- `aSucceedingRefetchReplacesTheLastGoodValueAndItsTimestamp`.
- `aKeyThatHasOnlyEverFailedHasNoLastGoodAndIsNotStale` — the first-load failure is an error page's
  job, not the overlay's; `isStale` must be false so a screen can tell the two apart.
- `stateAndGetShareOneFetch` — subscribing to both for one key issues exactly one request, proving
  `state` is derived from the same entry and not a second one.
- `lastGoodSurvivesTheNegativeTtlRefetch` — the 5 s negative TTL causes a refetch; a second failure
  still does not clear `lastGood`.

## Observability

Browser-side only, and it is the overlay's own rendering: the badge *is* the observability signal
for this state, which is why it is `role="status"`. No metric is sent to the server (M0's rule,
UI-006). `HealthReporting` is untouched — the overlay reports nothing and decides nothing; it draws
what it is handed.

One requirement on the markup so that the E2E suite can assert it without guessing: the wrapper
carries `data-testid="<testId>"` and the badge `data-testid="<testId>-stale-badge"`, and the stale
class name is a constant in `KernelCss`. CFGOP-007 asserts "greyed and timestamped" through those
two hooks.

## Degraded behavior

This component *is* the degraded behaviour, so its own failure modes are what matter:

- **No `fetchedAt`** — renders `Never refreshed`. It must never invent a time, and it must never
  suppress the badge, because "we have nothing and cannot refresh" is worse news than "this is old",
  not better.
- **An unusable zone id** — falls back to UTC and still renders. A corrupted preference in
  `localStorage` must not blank a page.
- **Clock skew** — a `fetchedAt` in the future renders as `in N seconds`, never as
  `-8 minutes ago`.
- **A `stale` signal that flickers** — the overlay applies state directly with no debounce.
  Debouncing capability transitions is ADR-039's job on the server; doing it a second time here
  would put two different truths on the screen.

## Docs to update

- `docs/frontend/README.md` — a new section: what the stale rule is, when a screen wraps a region
  in `StaleDataOverlay` versus rendering a fallback panel (the answer: overlay when there is
  previously fetched data in this session, fallback panel when there is not), and the fact that the
  overlay disables actions so a screen must not also disable them by hand.
- `docs/frontend/tokens.md` — if the badge introduces a foreground/background pair that the contrast
  table does not already list, add the row. `ContrastSuite` reads that table and will fail without
  it.
