# Testing KUI

This page is about the **end-to-end tests**: the ones that drive a real browser against a really
running KUI. Unit tests need no page of their own — they are ordinary MUnit suites next to the code
they test, and `./mill __.test` runs them.

End-to-end tests are different enough to be worth writing down. They start processes, they talk to a
browser, they are the slowest thing in the build, and they are the only place where the product's
central promise can actually be checked.

---

## The three rules

Every end-to-end test in this repository follows these. They exist because a browser suite that is
slow or flaky gets muted, and a muted suite is worse than none: it looks like coverage.

### 1. No `Thread.sleep` waiting for the application

Use `waitForCondition`, which polls a predicate up to a deadline and **fails with a sentence naming
what never happened**:

```scala
waitForCondition("the Clusters entry to be dimmed after kui-cluster stopped", 9.seconds) {
  shell.navigation.entry("clusters").exists(_.dimmed)
}
```

A fixed sleep is too short on a loaded CI runner and too long on a developer's laptop, and it is
usually both in the same suite. The message is not decoration — when the wait times out, it is the
entire content of the failure report, and "timed out after 9 seconds waiting for: the Clusters entry
to be dimmed after kui-cluster stopped" is a bug report, while "assertion failed" is a request to go
and reproduce it.

There is exactly one `Thread.sleep` in the module, inside `AllInOneFixture`'s poll for
`/health/ready`, because at that point there is no browser yet for Playwright to poll through.

### 2. Selectors are `data-testid` or ARIA roles

Never a class name, and never a rendered sentence unless the test is *about* that sentence.

The frontend is restyled between milestones. A test that reads `.kui-sidebar__link--dimmed` has to be
rewritten by whoever changes the stylesheet, and that is how end-to-end suites get deleted. A test
that reads `[data-testid='nav-clusters']` survives any restyle, because a test id is not a design
decision.

Where a state has no test id yet, **add the attribute; do not reach for a class**. The sidebar carries
`data-state` (`ready`, `degraded`, `unavailable`, `forbidden`, `notconfigured`) for exactly this
reason: it is ADR-032's five rendering rules stated in a form a test can read and a restyle cannot
break.

### 3. Each test starts from a fresh browser context

So no test can depend on another having run first. A context is a whole profile — its own cookies,
its own `localStorage` — and creating one costs milliseconds.

The one exception is a suite whose tests are consecutive *steps of one scenario*: `ClusterServiceDownSuite`
asserts that the page was never reloaded across eight steps, which cannot be asked of a page that was
thrown away between them. Such a suite sets `override protected def pagePerTest = false`, in one
visible line, rather than quietly opening a page of its own.

---

## Running them

```bash
# Everything. Builds the all-in-one jar and starts the Compose stack as needed.
./mill e2e.test

# One suite.
./mill e2e.test.testOnly kui.e2e.ShellSmokeSuite

# With each test's name and duration printed.
./mill e2e.test -v
```

**First run only:** download the browser Playwright drives. The library only speaks to the Chromium
revision it shipped with, so this is "install *the* browser", not "install a browser":

```bash
./mill e2e.installBrowser              # a developer machine
./mill e2e.installBrowser --with-deps  # a fresh CI runner: also installs the shared libraries
```

It lands in `~/.cache/ms-playwright` and is shared by every later run. CI caches that directory keyed
on the Playwright version, or every job pays a 300 MB download.

`./mill show versions.playwright` and `./mill show versions.playwrightBrowser` print the pinned pair.
They move together: bumping one without the other gives a library that refuses to drive the browser
it finds.

### When it skips

Both are **loud skips**, never silent passes:

| Missing | What happens |
| --- | --- |
| the Chromium build | every suite skips, printing the `installBrowser` command |
| Docker | the Compose suites skip, printing that the fault-isolation criterion is **unverified, not passing** |
| the all-in-one jar (running the suites outside Mill) | the all-in-one suites skip, printing that they must be run through `./mill e2e.test` |

An end-to-end suite that quietly tested nothing and reported green is the failure mode this is written
to avoid.

### When one fails

Look in `out/e2e-artifacts/<Suite>/<test-name>/` before re-running anything. Every failure writes:

- `screenshot.png` — the full page at the moment of failure
- `console.log` — everything the browser's console said, including uncaught errors
- `failed-requests.log` — every response with a status of 400 or worse
- `server.log` (all-in-one suites) — the process's own output
- `kui-gateway.log`, `kui-cluster.log`, `capabilities.json` (Compose suites)

Those last three are the set that tells a UI bug from a gateway bug from a service bug. The point of
attaching them is that nobody should ever have to reproduce an end-to-end failure in order to
understand it.

---

## How the module is laid out

```
e2e/src/kui/e2e/                     reusable machinery, compiled as main sources
  BaseE2ESuite.scala                 browser lifecycle, waiting, failure artifacts
  AllInOneE2ESuite.scala             + the all-in-one jar, started once per suite
  ComposeE2ESuite.scala              + the distributed Compose stack
  FaultIsolationScenario.scala       the reusable fault-isolation story
  Capabilities.scala, Http.scala     reading the API from a test
  fixtures/                          AllInOneFixture, BrowserFixture, ComposeFixture
  pages/                             one page object per screen
e2e/test/src/kui/e2e/                the suites
```

Page objects are main sources rather than test sources on purpose: they are shared by several suites
and several milestones, and keeping them out of the test source set is what stops them being
copy-pasted into individual suites.

### Adding a page object

One class per screen. It holds selectors and returns plain Scala values; it contains no assertions.

```scala
package kui.e2e.pages

import com.microsoft.playwright.Page

/** Say what the screen is, and why the thing you exposed is what a test needs to see. */
final class TopicsPage(page: Page) {
  def isVisible: Boolean = page.locator("[data-testid='page-topics']").count() > 0
  def rowNames: List[String] =
    page.locator("[data-testid='topics-table'] tbody td").allInnerTexts().asScala.toList
}
```

Then hang it off `ShellPage`, which is the entry point every suite already has:

```scala
def topics: TopicsPage = new TopicsPage(page)
```

If the screen has no test id for the thing you need, add one to the Laminar element (`dataAttr("testid") := "…"`).
That is a legitimate frontend change; changing a test to read a class name is not.

---

## Fault isolation: the test that is the point of the product

`ClusterServiceDownSuite` is the milestone's exit criterion, and it is the one to copy for every
service that arrives later. It runs against the **distributed** Compose stack, because only separate
processes can have one of them stopped — in the all-in-one shape, "stopping a service" would mean
calling a method that pretends to be down.

The story, and what each step proves:

| Step | Assertion |
| --- | --- |
| stack up, browser at `/ui/` | the Clusters entry is normal *and* `/api/v1/capabilities` says `available` — either alone can be right while the product is wrong |
| `docker compose stop kui-cluster` | the entry is **dimmed and still clickable** (ADR-032: a disabled entry has nowhere to put the reason, the "since", the retry or the "what still works") |
| — | the capability API reports `unavailable` with a reason code and a `since` |
| click the dimmed entry | the fallback panel renders with all four, and its `since` is **the gateway's timestamp**, not one the browser invented |
| navigate to Settings | Settings works normally: the outage did not spread to the frame |
| click Retry while still down | the probe runs, the panel stays, and one press raises at most one toast |
| `docker compose start kui-cluster` | the entry returns to normal **and the page was never reloaded** |
| ping again | the whole chain works: browser → gateway → service → table |

### The no-reload sentinel

The recovery assertion is the subtle one. Nothing observable from outside the browser distinguishes
"the application updated itself" from "the application reloaded and looks the same" — and the
difference is everything, because a reload discards every other feature's loaded state and the user's
place in the application.

So the first step writes a random value into `window.__kuiLoadedAt` and the recovery step reads it
back. A page load destroys it. Equality is the proof.

### Timing

The gateway polls each service every ten seconds in the shipped configuration. `docker-compose.e2e.yml`
lowers that to three seconds **for the tests only**, and the production default is asserted separately
by CFG-001's defaults test — so the suite cannot come to depend on a non-default configuration without
the default itself still being checked somewhere. Every deadline in the scenario is `readinessInterval * 3`
or a stated multiple of it, never a constant.

### Copying it for a new service

`FaultIsolationScenario` is the whole story as data. A new milestone's suite is the four values that
differ:

```scala
private val scenario = FaultIsolationScenario(
  serviceContainer = "kui-topics",   // the container to stop
  serviceId = "topics",              // as /api/v1/capabilities names it
  featureId = "topics",              // as the nav test id ends: data-testid="nav-topics"
  featureLabel = "Topics",           // as the sidebar shows it
  unaffectedCheck = checkSettingsStillWorks,
  readinessInterval = 3.seconds
)
```

`serviceId` and `featureId` are separate because they are not always the same word: the `clusters`
feature is served by the `cluster` service, and conflating them makes an assertion look up a service
that does not exist and cheerfully conclude nothing.

Then either call `scenario.run(this, stack(), shell)` for the guarantee in one test, or call the steps
as separate tests — `start`, `stopService`, `capabilityReportsUnavailable`, `fallbackPanel`,
`unaffectedCheck`, `retryWhileDown`, `recover` — for a run log that says *which* step broke.

---

## Known gap

`ShellSmokeSuite`'s "clusters module is not fetched on first paint" is marked `.fail`: the product
currently fails it. The feature is linked into a JavaScript module of its own and then statically
imported by `main.js`, so the browser downloads it during the first paint anyway. See **TD-016**.

MUnit's `.fail` marker is the honest way to record that: the suite stays green while the defect
stands, and the day somebody fixes lazy loading the test fails *because it passed*, which makes them
delete the marker instead of quietly reintroducing the regression.
