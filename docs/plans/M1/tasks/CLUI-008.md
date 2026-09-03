# CLUI-008 — Force refresh action and its 202 handling

- **ID:** CLUI-008
- **Title:** Force refresh action and its 202 handling
- **Milestone / Feature:** M1 / CL-005
- **Owner role:** Frontend Architect
- **Size:** S
- **Dependencies / blocked by:** CLUI-004 (the brokers page the button sits on), CLUI-002 (the
  client), CLAPI-002 (the endpoint). Entirely inside `frontend/ui-clusters`; no restyle constraint.

## Goal (user value)

The server refreshes a cluster's snapshot every 30 seconds, and the browser does not poll. So when
an operator has just restarted a broker and wants to know *now* whether it came back, they need a
button that says "go and look again". This is that button, and the whole difficulty is that the
server answers `202 Accepted` — *I have started* — rather than with the new data.

## Scope

1. A **Refresh** button on the brokers page and on the dashboard, beside the `scrapedAt` timestamp,
   where the question "how old is this" is already being answered.
2. On click: `POST /api/v1/clusters/{id}/refresh`. On `202`, enter a *refreshing* state and start
   re-reading the snapshot until its `scrapedAt` advances past the one that was on screen when the
   button was pressed.
3. A bounded, explicitly specified re-read schedule, ending in a stated outcome either way.
4. Every failure mode rendered: rejected, timed out, and forbidden.
5. The button is disabled while a refresh is outstanding and while the feature is not `Ready`,
   through the kernel's `ActionPermissionWrapper`, which merges the capability reason into one
   tooltip (ADR-032: write actions are gated once, with one merged explanation).

## The 202 problem, and the decision

`202 Accepted` carries no body and no completion signal. Three ways to find out when the refresh has
landed were available, and the choice needs recording because none of them is obviously right:

- **Wait a fixed time, then re-read once.** Simple and wrong: too short and the button appears not
  to work, too long and a fast cluster feels broken. The time is a property of the cluster, not of
  the product.
- **Open a stream and have the server say when it is done.** Correct, and out of proportion:
  ADR-035's streaming envelope exists, but adding a per-refresh SSE channel for a button pressed a
  few times a day is infrastructure nobody asked for, and `/internal/v1/clusters/stream` is
  service-to-service (CLAPI-003), not a browser channel.
- **Re-read the snapshot until `scrapedAt` advances, on a bounded schedule.** Chosen.

**Decision: re-read at 1 s, 3 s, 6 s, 10 s and 15 s after the 202, and stop at the first read whose
`scrapedAt` is later than the one recorded at the moment of the click.** Five reads, fifteen seconds,
and then a definite answer either way. The schedule is front-loaded because a healthy cluster
answers in well under a second and a user should see the result immediately; it stretches out
because a cluster that is slow to describe is exactly the cluster whose scrape takes seconds, and
five evenly spaced reads would spend them all before it finished.

The comparison is on `scrapedAt`, not on the payload: two consecutive scrapes of an unchanged
cluster produce identical data, and comparing payloads would report "nothing happened" for a refresh
that worked perfectly.

If fifteen seconds pass with no advance, the button leaves the refreshing state and the screen says
so: *"The refresh was accepted but the data has not been updated yet. It may still be running."*
That sentence is doing real work — it is true, it does not claim failure, and it does not silently
put the UI back as though nothing happened.

## Non-goals

- **No polling of any kind outside the refresh window.** The five reads happen after a click and
  never otherwise. The periodic re-read is CLUI-007's `RefreshRate`, off by default, and it is a
  different mechanism; a refresh tick never fires a `POST`.
- **No refresh per broker or per log directory.** The endpoint refreshes a cluster's snapshot;
  offering a narrower button would promise a granularity the server does not have.
- **No automatic refresh after an error.** ADR-032 makes retry an explicit user action, and a button
  that retries itself hides the failure it was pressed to investigate.
- **No optimistic update.** Nothing on screen changes until a read comes back. Showing a new
  timestamp because a `202` arrived would be inventing data.

## Design references

- **`docs/FEATURE_MATRIX.md` CL-005** — `POST /clusters/{id}/refresh`, 202 Accepted.
- **DEVPLAN §10 D10** — 30 s server-side, no browser polling, and "the forced-refresh button is the
  user's control". This task is the second half of that decision.
- **ADR-032** — actions gated once through `ActionPermissionWrapper`; reasons rendered verbatim;
  retry is explicit.
- **ADR-034** — the error envelope, and `ErrorEnvelope.statusOf` as the single mapping from error
  code to status, which is what makes the failure branches below distinguishable by code rather than
  by parsing a message.
- **ADR-016** — the invalidation trigger for the cluster snapshot is named in `ARCHITECTURE.md` §9
  as `POST /clusters/{id}/refresh`; this is its only caller.
- **`research/design/REFERENCE.md`** — the button is a standard secondary action with the refresh
  icon; a busy control keeps its label and gains a spinner rather than changing its text to a verb
  the user did not press.

## Files to create

```
frontend/ui-clusters/src/kui/ui/clusters/component/RefreshButton.scala
frontend/ui-clusters/src/kui/ui/clusters/RefreshFlow.scala
frontend/ui-clusters/test/src/kui/ui/clusters/RefreshFlowSuite.scala
frontend/ui-clusters/test/src/kui/ui/clusters/component/RefreshButtonSuite.scala
```

## Files to change

```
frontend/ui-clusters/src/kui/ui/clusters/brokers/BrokersPage.scala    (mount the button)
frontend/ui-clusters/src/kui/ui/clusters/dashboard/DashboardPage.scala (mount the button)
frontend/ui-clusters/src/kui/ui/clusters/Messages.scala
frontend/ui-clusters/resources/css/40-clusters.css
```

## Public Scala signatures to implement

```scala
package kui.ui.clusters

/** What a forced refresh is doing right now. */
enum RefreshStatus {
  case Idle
  /** Accepted; waiting for `scrapedAt` to advance past `baseline`. */
  case Running(baseline: Option[Instant], attempt: Int)
  /** The snapshot advanced. Held briefly so the user sees that it worked. */
  case Completed(at: Instant)
  /** Fifteen seconds passed with no advance. Not a failure — an unknown. */
  case TimedOut
  /** The POST itself was refused. */
  case Rejected(error: ApiError)
}

/** The forced-refresh state machine, with no DOM and no real clock in it.
  *
  * Separate from the button so the schedule can be tested by stepping a fake clock, which is the
  * only way the "fifteen seconds and then a definite answer" contract is checkable at all.
  */
final class RefreshFlow(
    cluster: ClusterId,
    queries: ClustersQueries,
    /** When the currently displayed snapshot was scraped, read at the moment of the click. */
    scrapedAt: Signal[Option[Instant]],
    /** The re-read schedule. Exposed so the suite can shorten it; never overridden in production. */
    schedule: List[FiniteDuration] = RefreshFlow.DefaultSchedule
)(using owner: Owner) {

  val status: Signal[RefreshStatus]

  /** Whether a click is possible: idle, and the feature is `Ready`. */
  val enabled: Signal[Boolean]

  /** Sends the POST. A second call while `Running` does nothing. */
  def request(): Unit
}

object RefreshFlow {
  val DefaultSchedule: List[FiniteDuration] =
    List(1.second, 3.seconds, 6.seconds, 10.seconds, 15.seconds)

  /** The sentence for each status, in one place, so the button and any future caller say the same
    * thing.
    */
  def describe(status: RefreshStatus): Option[String]
}
```

```scala
package kui.ui.clusters.component

object RefreshButton {
  /** @param capability the feature's state, merged into one tooltip with the flow's own reason by
    *                   `ActionPermissionWrapper`.
    */
  def apply(
      flow: RefreshFlow,
      capability: Signal[FeatureState],
      testId: Option[String] = None
  ): HtmlElement
}
```

## Library coordinates

None new. `Button`, `Icon.refresh`, `ActionPermissionWrapper` and `Toast` are already in
`frontend/ui-kernel`; `domtestutils::19.0.0` is already on this module's test classpath after
CLUI-003.

## Acceptance criteria

```
$ ./mill frontend.uiClusters.compile
$ ./mill frontend.uiClusters.test
$ ./mill frontend.uiClusters.checkFormat && ./mill frontend.uiClusters.fix --check
```

All clean.

Manual, once, against CFGOP-006's Compose stack, with the browser's network panel open:

- Pressing Refresh on the brokers page issues exactly one `POST .../refresh`, which answers `202`.
- Between one and three `GET .../brokers` follow, and the button returns to idle as soon as the
  displayed timestamp advances. It does not keep reading after that.
- Stopping the broker and pressing Refresh: the `POST` still returns `202`, the reads happen, the
  timestamp does not advance, and after fifteen seconds the page says the refresh was accepted but
  the data has not been updated. The rows that were on screen are still on screen.
- Stopping the cluster service and pressing Refresh: the button is already disabled, with the
  capability reason in its tooltip.

## Tests required

`RefreshFlowSuite` (pure, with a stepped clock and a stub client — no DOM):

- `aSuccessfulRefreshStopsAtTheFirstAdvancedTimestamp` — the stub advances `scrapedAt` before the
  second read; exactly two reads are issued, not five.
- `theScheduleIsExactlyOneThreeSixTenFifteen` — asserted against the read timings, so a change to
  the schedule is a deliberate act rather than a side effect.
- `noAdvanceInFifteenSecondsEndsInTimedOut`, and issues exactly five reads.
- `anIdenticalPayloadWithAnAdvancedTimestampCountsAsSuccess` — the case that would fail if anyone
  compared payloads instead of timestamps.
- `aSecondClickWhileRunningIsIgnored` — one `POST`, not two.
- `aRejectedPostEndsInRejectedAndIssuesNoReads`.
- `aFlowWhoseOwnerIsKilledMidScheduleIssuesNoFurtherReads` — the navigate-away case; a timer that
  outlives its page is a request nobody is waiting for.
- `noPostIsEverSentWithoutAClick` — the standing guarantee against D10 being violated by accident.

`RefreshButtonSuite` (jsdom):

- `theButtonIsDisabledWhileRunningAndReEnablesOnCompletion`.
- `theButtonIsDisabledWhenTheFeatureIsNotReadyWithOneMergedTooltip`.
- `theTimedOutSentenceIsShownAndTheRowsAreStillThere` — the assertion that this failure mode does
  not clear the screen.
- `aRejectedRefreshShowsTheEnvelopeMessageVerbatim`.
- `theBusyButtonKeepsItsLabelAndGainsASpinner` — the design's rule, and the reason is practical: a
  label that changes to "Refreshing…" moves the button's neighbours.

## Observability

- The `POST` and every read report to `HealthReporting` with `CallScope.Feature`.
- `data-testid` hooks: `cluster-refresh-button`, `cluster-refresh-status`. CFGOP-007 uses the first
  to assert the button is disabled when the cluster service is stopped.
- `RefreshStatus` is rendered next to the timestamp rather than as a toast. A toast for a user's own
  button press is a notification about something the user just did and is already watching; toasts
  are for outcomes that arrive after attention has moved on (ADR-032's rule that reasons are never
  toasts on their own). The one exception: `Rejected` also raises a toast, because a rejection may
  arrive after the user has scrolled away.

## Degraded behavior

- **The `POST` is refused because the cluster is unreachable.** The envelope's message is shown
  verbatim next to the button. Nothing on screen is cleared: the rows the user was reading are what
  they still have.
- **The `POST` is refused with a permission error.** `ErrorEnvelope.statusOf` gives a 403; the
  button becomes permanently disabled for this session with the envelope's message as its tooltip,
  rather than staying clickable and failing every time.
- **The cluster service goes down mid-schedule.** The reads fail, the flow ends in `TimedOut`, and
  the page falls under the stale overlay through the mechanism CLUI-001 built. The refresh does not
  invent a separate error state for something the page already reports.
- **The user navigates away mid-schedule.** The `Owner` is killed and the remaining reads are never
  issued. This is why the flow is built on Airstream timers rather than `js.timers`: the lifetime is
  the element's.
- **The server accepts and never finishes.** `TimedOut`, with a sentence that says what is actually
  known. The button returns to idle so the user can press it again; the product does not decide on
  their behalf that it is broken.

## Docs to update

- `docs/frontend/README.md` — the 202 pattern: how a browser observes an accepted-but-asynchronous
  action, with the re-read schedule and the "compare the timestamp, not the payload" rule. It is
  the first of several such actions (M2's topic operations are the next) and each one re-deriving it
  is how they end up behaving differently.
- `docs/domain/cluster.md` — one line under the refresh endpoint: what the browser does with the
  202, so the server side's authors know what their `scrapedAt` is being compared against.
