package kui.e2e

import scala.concurrent.duration.DurationInt

/** The M0 happy path, in a real browser, against the packaged all-in-one jar.
  *
  * Every one of these is a claim the milestone makes, and every one of them is the kind of claim that
  * a unit test cannot check because it is about what a browser does with what the server sent.
  */
final class ShellSmokeSuite extends AllInOneE2ESuite {

  test("shell loads with navigation and version") {
    val page = shell.open("/ui/")

    val labels = page.navigation.labels
    assert(labels.contains("Clusters"), s"the sidebar does not list the Clusters feature: $labels")
    assert(labels.contains("Settings"), s"the sidebar does not list Settings: $labels")

    val version = page.version
    assert(version.nonEmpty, "the header shows no build version")
  }

  // MARKED FAILING BECAUSE THE PRODUCT CURRENTLY FAILS IT. This is not a broken test: it is
  // ADR-012's user-visible promise, checked in a browser for the first time, and the answer came
  // back "no". `main.js` contains a *static* `import` of the clusters feature's module, so the
  // browser downloads the whole feature during the first paint — for every user, including one
  // whose deployment has no cluster service at all.
  //
  // `checkBundleShape` passes at the same time, and that is not a contradiction: it checks that the
  // feature's code is in a file of its own and is not copied into `main.js`, which is true. What no
  // build-time check noticed is that `main.js` then *imports that file eagerly*, which a browser
  // obeys. Splitting a module and deferring its download are two different things, and only a
  // browser can tell you whether the second one happened.
  //
  // `.fail` is MUnit's marker for a known defect: the suite stays green while the defect stands, and
  // the day somebody fixes lazy loading this test fails *because it passed*, which is what makes them
  // delete the marker instead of quietly re-introducing the regression. See TECH_DEBT.md TD-E2E-1.
  test("clusters module is not fetched on first paint".fail) {
    val requests = scala.collection.mutable.ListBuffer.empty[String]
    browser().onRequest(request => requests.append(request.url()))

    val _ = shell.open("/ui/")

    // ADR-012's promise, checked where it is actually made: the browser must not have downloaded the
    // feature's JavaScript module before anyone asked for the feature. A unit test cannot see this —
    // it is a property of what the linker emitted and what the browser chose to fetch.
    val featureModules = requests.filter(isClustersModule).toList
    assert(
      featureModules.isEmpty,
      s"the clusters module was fetched on first paint, which defeats lazy loading: $featureModules"
    )
  }

  test("clicking Clusters fetches the module once and renders the page") {
    val requests = scala.collection.mutable.ListBuffer.empty[String]
    val page = shell.open("/ui/")
    browser().onRequest(request => requests.append(request.url()))

    page.navigation.click("clusters")
    waitForCondition("the clusters dashboard to render after clicking its entry") {
      page.clusters.isVisible
    }

    // "Once" is the assertion that survives the defect the previous test records. Today the feature's
    // modules have already been fetched during the first paint, so clicking fetches none of them
    // again; once lazy loading works, clicking fetches each of them exactly once. Both are correct,
    // and what is forbidden in either world — the same module fetched twice, which is a feature being
    // re-downloaded on every navigation — is what this checks.
    val featureModules = requests.filter(isClustersModule).toList
    assertEquals(
      featureModules.distinct.size,
      featureModules.size,
      s"the same feature module was fetched more than once: $featureModules"
    )
  }

  test("the dashboard round-trips through the gateway to the cluster service") {
    // Replaces M0's "ping round-trips". The sample Ping feature was deleted with CLAPI-004, and the
    // chain it proved — browser, contract client, gateway routing, signed principal, service, back
    // again — is now proved by the real dashboard rendering its summary strip. The all-in-one
    // fixture is started with no clusters configured, so the honest expectation is a rendered page
    // reporting zero of each, and *not* an error panel: "no clusters are configured" and "KUI could
    // not reach its own cluster service" must not look the same.
    val page = shell.open("/ui/clusters")
    waitForCondition("the clusters dashboard to render on a deep link") { page.clusters.isVisible }

    waitForCondition("the summary strip to be filled by the first successful load") {
      page.clusters.onlineCount.isDefined
    }

    assertEquals(page.clusters.error, None, "the first load of the dashboard failed")
    assertEquals(page.clusters.onlineCount, Some("0"))
    assertEquals(page.clusters.unavailableCount, Some("0"))
    assertEquals(page.clusters.rowIds, Nil, "the fixture configures no clusters")
  }

  test("deep link to /ui/clusters works on a cold load") {
    // A cold load, and that is the point: the router has to resolve the feature's route pattern
    // before the feature's own JavaScript has been downloaded, or a bookmark is a 404.
    val page = shell.open("/ui/clusters")

    waitForCondition("the clusters dashboard to render from a cold deep link") { page.clusters.isVisible }
    assert(page.navigation.labels.contains("Clusters"), "the navigation is missing on a deep link")
  }

  test("unknown url shows the 404 page with navigation intact") {
    val page = shell.open("/ui/nope")

    waitForCondition("the 404 page to render") { page.errorPage.isNotFound }
    assert(
      page.navigation.labels.contains("Settings"),
      "the 404 page rendered without the navigation, which turns a typo into a dead end"
    )
    assert(page.errorPage.hasHomeLink, "the 404 page offers no way back")
  }

  test("the capability API reports the sample service available") {
    val page = shell.open("/ui/")

    waitForCondition("the Clusters entry to settle into its normal state") {
      page.navigation.entry("clusters").exists(entry => !entry.dimmed && !entry.disabled)
    }

    // The gateway reports `degraded / waiting for the first readiness check` until its first poll of
    // the service has completed, which is the honest answer and not a failure — so the wait is for
    // the first poll to have happened, with the default ten-second interval plus room for a slow
    // machine. Asserting immediately would be asserting on start-up timing.
    waitForCondition("the gateway's first readiness poll of the cluster service to complete", 40.seconds) {
      Capabilities.of(allInOne().baseUrl, "cluster").exists(_.status == "available")
    }

    // Asserted from the same test as the UI state, deliberately. Either alone can be right while the
    // product is wrong: an API that says "available" under a dimmed entry is a browser that stopped
    // listening, and a normal entry over an "unavailable" API is a browser showing something it made up.
    val capability = Capabilities.of(allInOne().baseUrl, "cluster")
    val document = Capabilities.raw(allInOne().baseUrl)
    assertEquals(
      capability.map(_.status),
      Some("available"),
      s"the capability API and the sidebar disagree. The document said: $document"
    )
  }

  /** Whether a request is for the lazily loaded clusters feature.
    *
    * Matched on the linker's output naming rather than on an exact file name: the Scala.js linker
    * derives a module's file name from the package it was split for, and pinning the exact string
    * would make this test fail on a Scala.js upgrade that changed the scheme rather than on a
    * regression in lazy loading.
    */
  private def isClustersModule(url: String): Boolean =
    url.endsWith(".js") && url.contains("clusters")
}
