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

  /** A thunk whose promise never settles: the hung request, which is a different failure from a rejected
    * one and the one the timeout exists for.
    */
  private final class NeverSettlingThunk {
    var calls: Int = 0

    def apply(): js.Promise[KuiFeature] = {
      calls += 1
      new js.Promise[KuiFeature]((_, _) => ())
    }
  }

  /** A thunk the test resolves by hand, so "the module arrived, but late" can be staged exactly. */
  private final class SettlableThunk(feature: KuiFeature) {
    private var resolve: js.Function1[KuiFeature, ?] = _ => ()

    def apply(): js.Promise[KuiFeature] =
      new js.Promise[KuiFeature]((onResolve, _) => resolve = onResolve)

    def succeed(): Unit = resolve(feature): Unit
  }

  /** A stand-in for `setTimeout` that hands the test the deadline instead of waiting for it. */
  private final class DeferredTimer {
    private var pending: List[() => Unit] = Nil
    var lastDelay: Option[Double]         = None

    def schedule(millis: Double, run: () => Unit): Unit = {
      lastDelay = Some(millis)
      pending = pending :+ run
    }

    /** Runs every timer that has been scheduled, as the browser would when the delay is up. */
    def elapse(): Unit = {
      val due = pending
      pending = Nil
      due.foreach(_())
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

  test("a module that never arrives becomes a failure instead of a spinner with no end") {
    // Seen in a real browser: a navigation left "Loading Messages…" on screen permanently. A dynamic
    // import is an HTTP request, and a request can hang rather than fail -- a proxy holding the
    // connection open, a chunk that starts and stalls. The promise then never settles, so nothing
    // ever moved this state machine out of `Loading` and the user had a spinner for the life of the
    // tab, with nothing to press.
    val never   = new NeverSettlingThunk
    val fire    = new DeferredTimer
    val feature = new ImportedFeature(FeatureId.Clusters, () => never(), 20000, fire.schedule)
    val state   = feature.state.observe(using owner)

    feature.load()
    assertEquals(state.now(), LoadState.Loading)

    // The deadline passes with the promise still outstanding.
    fire.elapse()

    assert(isFailed(state.now()), state.now().toString)
    assertEquals(fire.lastDelay, Some(20000d))

    // And the failure is recoverable the same way a rejected import is, so the fallback panel's
    // retry button is a real offer rather than decoration.
    feature.retry()
    assertEquals(state.now(), LoadState.Loading)
    assertEquals(never.calls, 2)
  }

  test("an import that arrives after the deadline does not overwrite the failure on screen") {
    // By the time the timeout fires the user is looking at a panel saying what did not load, with a
    // retry. A late arrival must not silently swap that for a feature the user did not ask for again.
    val slow    = new SettlableThunk(stubFeature)
    val fire    = new DeferredTimer
    val feature = new ImportedFeature(FeatureId.Clusters, () => slow(), 20000, fire.schedule)
    val state   = feature.state.observe(using owner)

    feature.load()
    fire.elapse()
    assert(isFailed(state.now()), state.now().toString)

    slow.succeed()

    settled().map(_ => assert(isFailed(state.now()), state.now().toString))
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
