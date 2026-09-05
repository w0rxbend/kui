package kui.ui.kernel.feature

// The browser's own microtask queue. There is one thread here, so this is not a thread pool: it is
// simply where a completed promise's continuation runs.
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.util.{Failure, Success}

import com.raquo.laminar.api.L.*

/** How far along a feature's download is. */
enum LoadState[+A] {

  /** Nothing has been asked for. The browser has not fetched a byte of this feature. */
  case NotLoaded extends LoadState[Nothing]

  /** The import is in flight. */
  case Loading extends LoadState[Nothing]

  case Loaded[A](value: A) extends LoadState[A]

  /** The import failed. Recoverable: `retry()` tries again. */
  case Failed(cause: String) extends LoadState[Nothing]
}

/** One feature's download, as a state machine.
  *
  * ## Why this exists
  *
  * ADR-012's whole point is that a feature which is unavailable, not configured, or simply not visited is
  * never downloaded. That means the shell holds a *thunk* rather than a feature, and something has to own the
  * transition from "thunk" to "feature": when it starts, whether it has started already, and what happens
  * when the network drops the chunk half way through.
  *
  * The failure case is not hypothetical. A dynamic import is an HTTP request made minutes after the page
  * loaded, over whatever connection the user has now; it fails often enough that "the route renders nothing"
  * is a real user experience. `Failed` exists so the shell can render the feature's fallback panel with a
  * working retry instead.
  */
trait LazyFeature {

  def id: FeatureId

  def state: Signal[LoadState[KuiFeature]]

  /** Starts the download if it has not started. Idempotent: ten calls import once. */
  def load(): Unit

  /** Clears a `Failed` state and tries again. Does nothing in any other state. */
  def retry(): Unit
}

/** A `LazyFeature` over a thunk that produces a promise.
  *
  * The memoisation is the important part and it is deliberately not "remember the promise": it is "do not
  * call the thunk again while a call is outstanding or has succeeded". Remembering only the promise would
  * make a failed import permanent, which is exactly the case `retry` exists for.
  */
final class ImportedFeature(
    val id: FeatureId,
    importFeature: () => js.Promise[KuiFeature],
    timeoutMillis: Double = ImportedFeature.DefaultTimeoutMillis,
    schedule: (Double, () => Unit) => Unit = ImportedFeature.browserTimer
) extends LazyFeature {

  private val current = Var[LoadState[KuiFeature]](LoadState.NotLoaded)

  /** Which attempt is outstanding. Incremented by every `start`, so a timer left over from an abandoned
    * attempt cannot fail the attempt that replaced it.
    */
  private var attempt: Int = 0

  val state: Signal[LoadState[KuiFeature]] = current.signal

  def load(): Unit =
    current.now() match {
      case LoadState.NotLoaded => start()
      // Already in flight, already here, or failed and waiting for an explicit retry. In none of
      // those does calling the thunk again do anything useful.
      case _ => ()
    }

  def retry(): Unit =
    current.now() match {
      // Straight to `start`, which sets `Loading` as its first act. Passing through `NotLoaded` on the
      // way would publish a frame saying "nothing has been requested" to every observer, and an
      // observer whose job is to start the import when it sees `NotLoaded` — which is exactly what
      // `FeatureGate` is — would then start a second one alongside this one.
      case LoadState.Failed(_) => start()
      case _ => ()
    }

  private def start(): Unit = {
    attempt += 1
    val thisAttempt = attempt

    current.set(LoadState.Loading)

    // The bound on `Loading`. A dynamic import is an HTTP request, and a request does not only
    // succeed or fail: it can also hang. A captive portal, a proxy holding the connection open, a
    // chunk that 200s and then stalls mid-body -- in every one of those the promise never settles,
    // `onComplete` never runs, and without this timer the state stays `Loading` for the life of the
    // tab. That is the permanent "Loading Messages…" spinner seen in the browser: not a rendering
    // fault, a state machine with no exit from its middle state.
    schedule(timeoutMillis, () => timeOut(thisAttempt))

    importFeature().toFuture.onComplete {
      // `settle` and not `current.set`: an import that finally arrives after we gave up on it must
      // not overwrite the failure the user is now looking at with a retry button, because the
      // element the feature would render was never built and the panel would vanish into nothing.
      case Success(feature) => settle(thisAttempt, LoadState.Loaded(feature))
      case Failure(cause) =>
        // The message reaches the user through the feature's fallback panel, so it has to be a
        // sentence rather than a stack trace.
        settle(thisAttempt, LoadState.Failed(Option(cause.getMessage).getOrElse(cause.toString)))
    }
  }

  /** Records an outcome, unless the attempt it belongs to has already been superseded or given up on. */
  private def settle(thisAttempt: Int, outcome: LoadState[KuiFeature]): Unit =
    if attempt == thisAttempt && isLoading then current.set(outcome)

  private def timeOut(thisAttempt: Int): Unit =
    if attempt == thisAttempt && isLoading then
      current.set(LoadState.Failed(ImportedFeature.timedOut(timeoutMillis)))

  private def isLoading: Boolean =
    current.now() match {
      case LoadState.Loading => true
      case LoadState.NotLoaded | LoadState.Loaded(_) | LoadState.Failed(_) => false
    }
}

object ImportedFeature {

  /** How long a feature's module may take to arrive before the shell calls it a failure.
    *
    * Twenty seconds, and the number is a judgement rather than a measurement: a feature module is tens of
    * kilobytes, so on any connection that is working at all it arrives in well under a second, and a wait
    * this long has almost certainly not got a download at the end of it. Erring long is the safe direction —
    * a spinner replaced by a retry button one second too early is a worse experience than one second of extra
    * patience, because pressing retry starts the download again from nothing.
    */
  val DefaultTimeoutMillis: Double = 20000

  /** What the fallback panel says when the wait ran out. A sentence, because it is shown to a person. */
  def timedOut(millis: Double): String =
    s"it did not arrive within ${(millis / 1000).round} seconds"

  /** The real timer. A parameter on the class rather than a call to `setTimeout` in the body, so a test can
    * decide when the deadline passes instead of waiting out a real one.
    */
  def browserTimer(millis: Double, run: () => Unit): Unit =
    js.timers.setTimeout(millis)(run()): Unit
}

/** A `LazyFeature` for an id nothing is registered under.
  *
  * It reports `Failed` rather than staying `NotLoaded` for ever, because "this build has no such feature" is
  * a definite answer and the shell can render it: the fallback panel says the feature is not part of this
  * deployment, which is true and actionable, where a route that renders nothing is neither.
  */
final class MissingFeature(val id: FeatureId) extends LazyFeature {

  private val failure: LoadState[KuiFeature] =
    LoadState.Failed(s"No feature is registered for '${id.value}' in this build.")

  val state: Signal[LoadState[KuiFeature]] = Val(failure)

  def load(): Unit = ()

  def retry(): Unit = ()
}
