# CFGOP-007 — Fault-isolation E2E: the cluster service stopped, and a dead cluster row

- **ID:** CFGOP-007
- **Title:** Fault-isolation E2E: the cluster service stopped, and a dead cluster row
- **Milestone / Feature:** M1 / KU-010, KU-033, CL-001, CL-003, OT-009
- **Owner role:** QA Engineer
- **Context / service:** `e2e`
- **Size:** L
- **Dependencies / blocked by:** CFGOP-006, CLUI-005

## Goal (user value)

Two of M1's exit criteria, proven in a browser against real separate processes:

- stopping `kui-cluster` leaves the shell, the settings page and the other clusters' **cached
  rows — greyed and timestamped** — usable;
- a dashboard with three configured clusters, one unreachable, populates two rows, shows
  `Unavailable: <reason>` on the third, keeps it clickable, and returns within the per-service
  timeout rather than within the dead cluster's.

## Scope

Three suites in the `e2e` module, built on the fixtures M0 left behind
(`ComposeFixture`, `FaultIsolationScenario`, the page objects) and the stack CFGOP-006 deploys.

1. **`ClusterServiceDownSuite`** — M0's `FaultIsolationScenario` instantiated for the real
   cluster feature, plus the M1-specific half that M0 could not test: stale data stays on screen,
   greyed, with its `scrapedAt` visible, and its actions disabled (KU-010, ADR-032's rule DC-H3).
2. **`DeadClusterRowSuite`** — three configured clusters, one pointing at a host that does not
   resolve; the dashboard's per-row assertions and the response-time bound.
3. **`StoreOutageSuite`** — the store broker stopped while KUI runs: clusters keep resolving,
   the store's capability reports `Degraded` with a reason, and the one write endpoint rejects
   rather than losing a write (fault-injection scenario 3, DEVPLAN §7).

Plus the Compose override and page-object additions those three need.

## Non-goals

- **No new fault-injection framework.** `ComposeFixture` already does `stop`, `start`, `pause`
  and `unpause`; that is the whole vocabulary.
- **No visual regression.** Deferred since E2E-001; still deferred.
- **No cross-browser.** Chromium only, as in M0.
- **No slow-broker scenario in the browser.** The circuit-breaker bound is asserted with
  `TestControl` in CLAPI-007, where a regression that serialises the gateway's calls fails
  deterministically. Reproducing it here would add a timing-dependent browser test that asserts
  the same thing less reliably (R-8's mitigation is explicitly the unit-level assertion).
- **No assertions about *which* Kafka error text appears.** The reason string comes from
  `KafkaErrorMapper` (KAFKA-005) and is asserted there. Here the assertion is that a reason is
  present, non-empty, and not a stack trace.

## Design references

`docs/plans/M0/tasks/E2E-001.md` (the three rules every E2E test in this repository obeys, and
the artifact-on-failure requirement) and `E2E-002.md` (the scenario shape this extends —
`FaultIsolationScenario` was written in M0 to be instantiated here, and this is the instantiation
it was designed for), ADR-032 (the five rendering rules and DC-H3, the stale-data rule),
ADR-037 (the per-upstream timeout that bounds the dashboard — DEVPLAN §10 D9 says the assertion
is expressed against the *configured* value, never a literal), ADR-039 §6 and DEVPLAN §10 D4
(an unreachable managed cluster is a `Section` inside a 200, not a dimmed capability — this suite
is where that distinction is verified in the product rather than in a unit test), ADR-042 §8
(store-unreachable behaviour), `research/kafbat/ui-analysis.md` "Dashboard" and "Brokers".

## Files to create or change

```
e2e/src/kui/e2e/pages/DashboardPage.scala                  (new)
e2e/src/kui/e2e/pages/BrokersPage.scala                    (new)
e2e/src/kui/e2e/pages/StaleBanner.scala                    (new)
e2e/src/kui/e2e/ClusterRowSnapshot.scala                   (new)
e2e/test/src/kui/e2e/ClusterServiceDownSuite.scala         (new)
e2e/test/src/kui/e2e/DeadClusterRowSuite.scala             (new)
e2e/test/src/kui/e2e/StoreOutageSuite.scala                (new)
deployment/compose/docker-compose.e2e.yml                  (change: the three clusters)
deployment/compose/kui-cluster-e2e.yaml                    (new: the E2E cluster list)
docs/testing.md                                            (the M1 scenarios)
build.mill                                                 (e2e.test forkEnv: the new override)
```

`e2e/test/` does not exist yet in the working tree — the M0 `e2e` module has its `src` half only.
Creating the test source directory is part of this task. If the M0 suites
(`ShellSmokeSuite`, `ThemeSuite`, `ClusterServiceDownSuite`, `CircuitBreakerSuite`) land there
first from another task, **do not rewrite them**: `ClusterServiceDownSuite` is then extended
rather than created, and its M0 cases stay exactly as they are.

## The E2E cluster list

`docker-compose.e2e.yml` already exists as an override that lowers the readiness interval to
three seconds. It gains a second override, and the reasoning belongs in the file's comment in the
same voice as the first one: the E2E stack mounts `kui-cluster-e2e.yaml` instead of
`kui-cluster.yaml`, and that file configures three clusters:

| Id | Bootstrap servers | What it is for |
| --- | --- | --- |
| `local-a` | `kafka:9092` | a healthy row |
| `local-b` | `kafka:9092` | a second healthy row, so "two rows populate" is literally true |
| `no-such` | `no-such-broker:9092` | the unreachable row |

`local-a` and `local-b` are the same physical broker under two configured names. That is a real
supported configuration — ADR-031 says KUI records the Kafka-reported cluster id and *warns* when
two entries point at the same cluster, which this stack therefore also exercises for free — and
it is honest: the exit criterion asks for two rows that populate, not for two brokers.

`no-such-broker` does not resolve, so the failure is a DNS failure and it is fast. That is
deliberate: this suite asserts the *shape* of the unavailable row, and the timing bound. The slow
case — a host that accepts TCP and never answers — is CLAPI-007's `TestControl` test, where it
can be made deterministic.

## Public Scala signatures to implement

```scala
package kui.e2e.pages

import com.microsoft.playwright.Page

final class DashboardPage(page: Page) {
  def open(): DashboardPage
  def rows: List[ClusterRowSnapshot]
  def row(clusterId: String): ClusterRowSnapshot
  def clickRow(clusterId: String): BrokersPage
  def forceRefresh(clusterId: String): Unit
  def staleBanner: Option[StaleBanner]
}

final class BrokersPage(page: Page) {
  def brokerIds: List[Int]
  def isStale: Boolean
  def scrapedAt: Option[String]
  def actionsEnabled: Boolean
}

final class StaleBanner(page: Page) {
  def text: String
  def timestamp: String
  def ariaLive: String
}

package kui.e2e

/** One dashboard row, reduced to the facts ADR-032 and DEVPLAN §10 D4 make assertions about.
  *
  * It deliberately mirrors `NavEntrySnapshot` from E2E-001, for the same reason: an assertion
  * that reads like the ADR is an assertion a reviewer can check against the ADR.
  */
final case class ClusterRowSnapshot(
    clusterId: String,
    status: String,            // "online" | "degraded" | "unavailable" | "initializing"
    reason: Option[String],
    brokerCount: Option[Int],
    scrapedAt: Option[String],
    greyed: Boolean,
    clickable: Boolean,
    metricColumnsRenderedAsDash: Boolean
)
```

## The scenarios, step by step

### `ClusterServiceDownSuite`

| Step | Assertion |
| --- | --- |
| stack up, dashboard open | three rows; `local-a` and `local-b` online with a broker count and a `scrapedAt` |
| navigate to `local-a`'s brokers, so the data is cached in the browser | the broker list renders |
| `docker stop kui-cluster` | within readiness interval + 5 s: the Clusters nav entry is dimmed and still clickable; exactly one toast |
| the brokers page, still open | **the previously loaded rows are still on screen**, greyed, with the stale banner showing the original `scrapedAt`; every action control is disabled (KU-010, DC-H3) |
| — | the stale banner's `aria-live` is `polite` and its text names the time, not "error" |
| navigate to Settings | Settings works normally |
| `GET /api/v1/capabilities` | the `cluster` capability is `unavailable` with a `reason` and a `since` |
| `docker start kui-cluster` | within the readiness interval: rows repopulate, `scrapedAt` advances, the stale banner disappears, and **the page was never reloaded** (the `window.__kuiLoadedAt` sentinel from E2E-002) |

### `DeadClusterRowSuite`

| Step | Assertion |
| --- | --- |
| stack up, dashboard open | exactly three rows |
| — | `local-a` and `local-b`: `status = online`, a broker count ≥ 1, a `scrapedAt` |
| — | `no-such`: `status = unavailable`, a non-empty `reason` that is not a stack trace and contains no `java.` prefix, `greyed = true`, **`clickable = true`** |
| click `no-such` | the brokers page renders the feature's fallback panel with the reason and a retry, not a blank page and not a 500 |
| `GET /api/v1/capabilities` | the `cluster` capability is **`available`** — DEVPLAN §10 D4: one operator's bad broker address must not dim the sidebar for everyone |
| time `GET /api/v1/clusters` | the wall-clock duration is below the configured `kui.gateway.services.cluster.timeout`, read from the stack's own configuration rather than written into the test (D9) |
| — | every metric column on both healthy rows renders `—`, not `0` |

### `StoreOutageSuite`

| Step | Assertion |
| --- | --- |
| stack up, dashboard open, all rows resolved | baseline |
| `docker stop kui-kafka` | within the readiness interval + 5 s: rows still resolve — the cluster list comes from the last replayed state, not from the store |
| `GET /api/v1/capabilities` | a store-backed capability reports `degraded` with a reason (OT-009, ADR-042 §8) |
| `PUT /internal/v1/clusters/local-a` | rejected with a named error; the response is not a 200 and not a hang |
| `docker start kui-kafka` | the capability returns to `available` without a restart |

## Decisions taken here

**D-1 — the timing bound is read from the stack, not hard-coded.** The suite parses
`deployment/compose/kui.yaml` for `kui.gateway.services.cluster.timeout` and asserts the measured
duration is below it, with a 20 % margin for the browser and Compose's own overhead. DEVPLAN §10
D9 requires exactly this: a later change to the default must not silently invalidate the test.

**D-2 — `no-such-broker` rather than a container that hangs.** Reasoned above. Recorded here
because "why doesn't the E2E test cover the slow case" is the first question a reviewer asks.

**D-3 — the stale-data assertions are on the *brokers* page, not the dashboard.** The dashboard
re-renders from the gateway aggregation, which has its own per-row status; the brokers page is
the one that holds a cached response with nothing to replace it, which is the situation KU-010
and DC-H3 describe. Asserting on the dashboard would be asserting something weaker and calling it
KU-010.

**D-4 — `local-a` and `local-b` are the same broker.** Reasoned above. If CLDOM/CLAPI's duplicate
`KafkaClusterId` warning (ADR-031) turns out to render as anything stronger than an informational
badge, this suite asserts that badge rather than working around it — and the finding goes to
CFGOP-008 as a documentation change, not to this file as a workaround.

**D-5 — three suites, not one.** `testParallelism` is already false for the `e2e` module because
every Compose suite drives the same named containers. Three suites each with its own stack
lifecycle is roughly six minutes; one suite would be faster and would make a failure in the store
scenario impossible to distinguish from a failure in the service-down scenario. Six minutes is
affordable; an unattributable E2E failure is not.

**D-6 — every wait is `waitForCondition` with a timeout of `readinessInterval * 3` and a message
naming the expected state.** E2E-001's rule, restated because this is where a violation of it
would cost the most: a flaky milestone-criterion suite is a milestone criterion nobody believes.

## Library coordinates

None new:

```
com.microsoft.playwright:playwright:1.62.0        (with Chromium 151.0.7922.34, pinned together)
org.scalameta::munit::1.3.6
io.circe::circe-core::0.14.16, circe-parser
```

## Acceptance criteria

```
$ ./mill e2e.test
kui.e2e.ClusterServiceDownSuite:
  + three rows resolve while everything is up
  + stopping the cluster service dims the entry within the readiness interval
  + cached broker rows stay on screen, greyed, with their original scrapedAt
  + stale actions are disabled and the banner is announced politely
  + settings and the shell keep working while the service is down
  + the capability API reports unavailable with a reason and a since
  + starting the service restores the rows with no page reload
kui.e2e.DeadClusterRowSuite:
  + two rows populate and the third shows Unavailable with a reason
  + the unavailable row is greyed and still clickable
  + clicking it renders the fallback panel, not a blank page
  + one unreachable cluster does not dim the cluster capability
  + the dashboard responds within the configured per-service timeout
  + metric columns render as a dash and not as zero
kui.e2e.StoreOutageSuite:
  + clusters keep resolving while the store broker is down
  + the store capability reports degraded with a reason
  + a write is rejected rather than lost
  + the capability recovers when the broker comes back
```

This output **is** M1 exit criteria 3 and 4 and the store half of criterion 9. Attach the run log
to `STATUS.md` (CFGOP-008). Runtime budget: the whole module under eight minutes on CI; record
the actual time.

## Tests required

The three suites above, with exactly the cases listed. In addition:

- The `no-such` row's `reason` is asserted **negatively** as well as positively: it does not
  contain `Exception`, does not contain `java.`, and is under 200 characters. A reason that is a
  stack trace passes a "reason is present" assertion and fails a user.
- The response-time assertion is made three times and the **median** is compared, not a single
  sample. One sample on a loaded CI machine is a coin flip; three medians is not, and it is still
  a bound rather than a benchmark.

## Observability

On failure the suite attaches, per E2E-001's rule and extended for M1: the full-page screenshot,
the browser console, **all three** containers' logs (`kui-gateway`, `kui-cluster`, `kui-kafka`),
the last `/api/v1/capabilities` response, the last `/api/v1/clusters` response, and the Playwright
trace. Six artifacts, because M1 has three processes and a broker to tell apart, and re-running an
eight-minute suite to find out which one broke is the cost this replaces.

## Degraded behavior

If Docker or the Playwright browser is unavailable, the module's tests skip **loudly**, CI marks
the milestone criteria as unverified, and the milestone is not green. A skipped fault-isolation
suite is not a passing one — this is the criterion the whole product's central promise rests on.

## Docs to update

`docs/testing.md`: the M1 scenarios, the three-cluster E2E stack and what each row is for, and
the template a future milestone copies for its own service (M0's `E2E-002` promised this
template; M1 is the first milestone to actually reuse it, so fix whatever the second use reveals
about it).

## Deviations

Recorded during implementation.
