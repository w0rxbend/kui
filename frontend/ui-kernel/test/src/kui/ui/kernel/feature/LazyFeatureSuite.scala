package kui.ui.kernel.feature

import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.*
import munit.FunSuite

final class LazyFeatureSuite extends FunSuite {

  private val owner = new ManualOwner

  /** A thunk that counts how often it was called and can be made to fail.
    *
    * A stub, not a real `js.dynamicImport`: what is under test is the state machine around the
    * import, and a real dynamic import in a unit test would be testing the linker.
    */
  private final class CountingThunk(feature: KuiFeature) {
    var calls: Int          = 0
    var shouldFail: Boolean = false

    def apply(): js.Promise[KuiFeature] = {
      calls += 1
      if shouldFail then js.Promise.reject(js.Error("network"))
      else js.Promise.resolve[KuiFeature](feature)
    }
  }

  private def stubFeature: KuiFeature = new KuiFeature {
    def id: FeatureId   = FeatureId.Clusters
    def nav: NavEntry   = NavEntry(FeatureId.Clusters, "Clusters", () => Icons.dot, 0, requiresCluster = false)
    def routes: List[com.raquo.waypoint.Route[? <: Page, ?]] = Nil
    def render(page: Page): HtmlElement                      = div()
    def unavailableView(reason: UnavailableReason, retry: Observer[Unit]): HtmlElement = div(reason.message)
  }

  test("neverLoadsUntilLoadIsCalled") {
    // The whole point of ADR-012: a feature nobody asked for costs no bytes.
    val thunk   = new CountingThunk(stubFeature)
    val feature = new ImportedFeature(FeatureId.Clusters, () => thunk())
    val state   = feature.state.observe(using owner)

    assertEquals(thunk.calls, 0)
    assertEquals(state.now(), LoadState.NotLoaded)
  }

  test("loadIsIdempotentAndImportsOnce") {
    val thunk   = new CountingThunk(stubFeature)
    val feature = new ImportedFeature(FeatureId.Clusters, () => thunk())

    (1 to 10).foreach(_ => feature.load())

    assertEquals(thunk.calls, 1)
  }

  test("stateTransitionsAreNotLoadedThenLoadingThenLoaded") {
    val thunk   = new CountingThunk(stubFeature)
    val feature = new ImportedFeature(FeatureId.Clusters, () => thunk())
    val seen    = Var(List.empty[String])

    feature.state.foreach {
      case LoadState.NotLoaded  => seen.update(_ :+ "NotLoaded")
      case LoadState.Loading    => seen.update(_ :+ "Loading")
      case LoadState.Loaded(_)  => seen.update(_ :+ "Loaded")
      case LoadState.Failed(_)  => seen.update(_ :+ "Failed")
    }(using owner): Unit

    feature.load()

    // The promise resolves on the microtask queue, so the assertion has to wait for it.
    settled().map(_ => assertEquals(seen.now(), List("NotLoaded", "Loading", "Loaded")))
  }

  test("aRejectedPromiseBecomesFailedAndRetryWorks") {
    // A dynamic import is an HTTP request made minutes after the page loaded, over whatever
    // connection the user has now. It failing is ordinary, and it must not leave a blank route.
    val thunk   = new CountingThunk(stubFeature)
    val feature = new ImportedFeature(FeatureId.Clusters, () => thunk())
    val state   = feature.state.observe(using owner)

    thunk.shouldFail = true
    feature.load()

    settled()
      .map { _ =>
        assert(isFailed(state.now()), state.now().toString)

        thunk.shouldFail = false
        feature.retry()
      }
      .flatMap(_ => settled())
      .map { _ =>
        assert(isLoaded(state.now()), state.now().toString)
        assertEquals(thunk.calls, 2)
      }
  }

  test("retry does nothing when the feature is not in a failed state") {
    val thunk   = new CountingThunk(stubFeature)
    val feature = new ImportedFeature(FeatureId.Clusters, () => thunk())

    feature.retry()
    assertEquals(thunk.calls, 0)

    feature.load()
    feature.retry()
    assertEquals(thunk.calls, 1)
  }

  test("an unregistered feature reports a definite failure rather than waiting for ever") {
    // "This build has no such feature" is an answer the shell can render. A route that stays blank
    // is not.
    val missing = new MissingFeature(FeatureId.Clusters)
    val state   = missing.state.observe(using owner)

    missing.load()

    assert(isFailed(state.now()), state.now().toString)
  }

  test("the registry hands out the same loader for the same id") {
    // A fresh loader per call would mean the shell asking twice imports twice, and the memoisation
    // inside the loader would protect nothing.
    val thunk    = new CountingThunk(stubFeature)
    val registry = new Features(Map(FeatureId.Clusters -> (() => thunk())))

    registry.lazyFeature(FeatureId.Clusters).load()
    registry.lazyFeature(FeatureId.Clusters).load()

    assertEquals(thunk.calls, 1)
  }

  test("a loaded feature appears in the registry's loaded list") {
    val thunk    = new CountingThunk(stubFeature)
    val registry = new Features(Map(FeatureId.Clusters -> (() => thunk())))
    val loaded   = registry.loaded.observe(using owner)

    assertEquals(loaded.now(), Nil)
    registry.lazyFeature(FeatureId.Clusters).load()

    settled().map(_ => assertEquals(loaded.now().map(_.id), List(FeatureId.Clusters)))
  }

  private def isFailed(state: LoadState[KuiFeature]): Boolean =
    state match { case LoadState.Failed(_) => true; case _ => false }

  private def isLoaded(state: LoadState[KuiFeature]): Boolean =
    state match { case LoadState.Loaded(_) => true; case _ => false }

  /** Waits for everything already queued on the microtask queue to run. */
  private def settled(): Future[Unit] = Future.unit.flatMap(_ => Future.unit).flatMap(_ => Future.unit)
}

/** A stand-in icon, so the test does not depend on the kernel's icon set. */
private object Icons {
  def dot: SvgElement = svg.svg()
}
