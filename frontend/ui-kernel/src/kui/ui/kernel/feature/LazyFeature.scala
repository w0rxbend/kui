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
final class ImportedFeature(val id: FeatureId, importFeature: () => js.Promise[KuiFeature])
    extends LazyFeature {

  private val current = Var[LoadState[KuiFeature]](LoadState.NotLoaded)

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
    current.set(LoadState.Loading)

    importFeature().toFuture.onComplete {
      case Success(feature) => current.set(LoadState.Loaded(feature))
      case Failure(cause) =>
        // The message reaches the user through the feature's fallback panel, so it has to be a
        // sentence rather than a stack trace.
        current.set(LoadState.Failed(Option(cause.getMessage).getOrElse(cause.toString)))
    }
  }
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
