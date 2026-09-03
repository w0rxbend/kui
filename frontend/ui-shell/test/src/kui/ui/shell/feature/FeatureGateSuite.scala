package kui.ui.shell.feature

import java.time.Instant

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.scalajs.dom

import kui.contracts.capability.{DegradedReason, ReasonCode}
import kui.ui.kernel.feature.*
import kui.ui.kernel.state.FeatureState

/** The promise of ADR-012, asserted: an unusable feature is never downloaded, and there is never a
  * blank frame.
  *
  * The import is a stub rather than a real `js.dynamicImport`, and that is the point: what is under
  * test is *when the thunk is called*, and a counter answers that exactly, where watching for a network
  * request would only tell us about jsdom.
  */
class FeatureGateSuite extends FunSuite {

  private def mounted[A](element: HtmlElement)(check: dom.Element => A): A = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container): Unit
    val rendered = render(container, element)
    try check(element.ref)
    finally {
      rendered.unmount(): Unit
      dom.document.body.removeChild(container): Unit
    }
  }

  private case object StubPage extends Page

  /** A feature whose page is one recognisable element, so a test can tell "rendered" from "not". */
  private final class StubFeature extends KuiFeature {
    def id: FeatureId = FeatureId.Clusters
    def nav: NavEntry = NavEntry(id, "Clusters", () => svg.svg(), 100, requiresCluster = false)
    def routes: List[com.raquo.waypoint.Route[? <: Page, ?]] = Nil
    def render(page: Page): HtmlElement = div(dataAttr("testid") := "stub-feature-page", "the feature")
    def unavailableView(reason: UnavailableReason, retry: Observer[Unit]): HtmlElement =
      div(dataAttr("testid") := "stub-feature-fallback", reason.message)
  }

  /** Counts the imports and lets a test decide when — and whether — one succeeds. */
  private final class CountingImport {
    var calls: Int = 0
    private var resolvers: List[KuiFeature => Unit] = Nil
    private var rejecters: List[Any => Unit] = Nil

    val thunk: () => js.Promise[KuiFeature] = () => {
      calls += 1
      new js.Promise[KuiFeature]((resolve, reject) => {
        resolvers = resolvers :+ (feature => resolve(feature): Unit)
        rejecters = rejecters :+ (cause => reject(cause): Unit)
      })
    }

    def succeed(feature: KuiFeature): Unit = resolvers.foreach(_(feature))
    def fail(cause: String): Unit = rejecters.foreach(_(new js.Error(cause)))
  }

  private def gateFor(
      state: Signal[FeatureState],
      imports: CountingImport,
      whatStillWorks: Signal[List[String]] = Val(Nil)
  ): (HtmlElement, LazyFeature) = {
    val feature = new ImportedFeature(FeatureId.Clusters, imports.thunk)
    val element = div(
      child <-- FeatureGate(
        feature = feature,
        featureLabel = "Clusters",
        state = state,
        page = Val(StubPage),
        probe = Observer.empty[Unit],
        whatStillWorks = whatStillWorks
      )
    )
    (element, feature)
  }

  /** Mounts, runs an assertion that has to wait for a promise, and unmounts once it has finished.
    *
    * The waiting is real and unavoidable: a resolved `js.Promise` runs its continuation on the browser's
    * microtask queue, and `ImportedFeature` adds a second hop of its own when it turns the promise into a
    * `Future`. Asserting immediately after `resolve` therefore looks at the DOM as it was one tick ago.
    * Yielding a few times is what lets the queue drain. The unmount happens after the assertion rather
    * than in a `finally`, or the element would be torn down while the test still needed it.
    */
  private def mountedAsync(element: HtmlElement, whenMounted: () => Unit)(
      check: dom.Element => Unit
  ): Future[Unit] = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container): Unit
    val rendered = render(container, element)

    // Only now, with the element in the document, has the gate started the import — so this is the
    // earliest moment at which a test can resolve it.
    whenMounted()

    settled()
      .map(_ => check(element.ref))
      .andThen { case _ =>
        rendered.unmount(): Unit
        dom.document.body.removeChild(container): Unit
      }
  }

  /** Yields to the microtask queue a few times over. */
  private def settled(): Future[Unit] =
    (1 to 5).foldLeft(Future.successful(()))((waiting, _) => waiting.flatMap(_ => Future.successful(())))

  private def has(root: dom.Element, testId: String): Boolean =
    root.querySelector(s"[data-testid='$testId']") != null

  test("loadsTheFeatureWhenTheStateBecomesAnythingButNotConfigured") {
    val imports = new CountingImport
    val state = Var[FeatureState](FeatureState.NotConfigured)
    val (element, _) = gateFor(state.signal, imports)

    mounted(element) { root =>
      // Nothing is fetched while the feature is not part of this deployment. That is the ADR-012
      // promise: a feature nobody can use costs nobody a byte.
      assertEquals(imports.calls, 0)
      assert(has(root, "feature-notice"))

      state.set(FeatureState.Ready)
      assertEquals(imports.calls, 1)

      // Degraded loads too: the page works, it is just slow, and refusing to load it would turn a
      // slow feature into a missing one.
      state.set(FeatureState.NotConfigured)
      state.set(
        FeatureState.Degraded(DegradedReason(ReasonCode.UpstreamTimeout, "Slow.", None, None))
      )
      assertEquals(imports.calls, 1, "the import is memoised, so a second state change re-imports nothing")
    }
  }

  test("rendersTheFallbackPanelWhenUnavailableWithoutLoadingTheFeature") {
    // Clicking a dimmed entry must not download the module. Downloading it would mean every user who
    // clicks a broken feature also pays for a request that cannot help them.
    val imports = new CountingImport
    val state = Var[FeatureState](
      FeatureState.Unavailable(
        ReasonCode.UpstreamUnavailable,
        "The cluster service is not responding.",
        Some(Instant.parse("2026-09-03T09:00:00Z"))
      )
    )
    val (element, _) = gateFor(state.signal, imports)

    mounted(element) { root =>
      assertEquals(imports.calls, 0)
      assert(has(root, "feature-fallback"))
      assert(root.textContent.contains("The cluster service is not responding."))
    }
  }

  test("showsANamedSpinnerWhileLoadingAndNeverABlankFrame") {
    val imports = new CountingImport
    val state = Var[FeatureState](FeatureState.Ready)
    val (element, _) = gateFor(state.signal, imports)

    mounted(element) { root =>
      // The import is in flight and nothing has resolved. There must still be something on screen,
      // and it must name the feature: a bare spinner leaves a user on a slow connection unable to
      // tell whether the thing they clicked is the thing that is loading.
      assert(has(root, "feature-loading"), s"expected a spinner, got ${root.innerHTML}")
      assert(root.textContent.contains("Loading Clusters"))
      assert(root.querySelector("[data-testid='feature-loading']").getAttribute("role") == "status")
    }
  }

  test("rendersTheFeatureOnceTheImportResolves") {
    val imports = new CountingImport
    val (element, _) = gateFor(Val(FeatureState.Ready), imports)

    mountedAsync(element, () => imports.succeed(new StubFeature)) { root =>
      assert(has(root, "stub-feature-page"), s"expected the feature's page, got ${root.innerHTML}")
    }
  }

  test("aFailedImportRendersTheFallbackWithARetryThatWorks") {
    val imports = new CountingImport
    val (element, _) = gateFor(Val(FeatureState.Ready), imports)

    mountedAsync(element, () => imports.fail("network error")) { root =>
      assert(has(root, "feature-fallback"), s"expected the fallback, got ${root.innerHTML}")
      // A failed import is a network problem, not an unhealthy service, and the panel says so
      // rather than blaming the cluster.
      assert(root.textContent.contains("could not be downloaded"))
      assertEquals(imports.calls, 1)

      // The retry re-runs the import rather than probing the gateway, because the gateway was
      // never the problem.
      val retry = root.querySelector("[data-testid='fallback-retry']")
      retry.dispatchEvent(new dom.MouseEvent("click", new dom.MouseEventInit { bubbles = true })): Unit
      assertEquals(imports.calls, 2)
    }
  }
}
