package kui.ui.kernel.state

import scala.concurrent.duration.*

import com.raquo.laminar.api.L.*
import io.circe.parser.decode
import org.scalajs.dom

import kui.contracts.capability.*
import kui.contracts.sse.SseEventName
import kui.kernel.{ClusterId, ServiceId}
import kui.ui.kernel.api.ApiError
import kui.ui.kernel.component.Tone
import kui.ui.kernel.feature.FeatureId
import kui.ui.kernel.sse.{Sse, SseConnection, SseError, SseHandle}

/** One frame of the capability stream: either the picture, or a change to it (ADR-032). */
enum CapabilityEvent {
  case Snapshot(value: CapabilitySnapshot)
  case Delta(value: CapabilityChange)
}

object CapabilityEvent {

  /** Decodes one `capabilities` frame.
    *
    * The two shapes are told apart by trying the snapshot first and falling back to the delta, rather than by
    * a discriminator field, because the wire format is the DTOs of `libs/contracts-core` as they stand and
    * adding a discriminator would be a contract change made for the client's convenience. The shapes are not
    * ambiguous: a snapshot has `entries` and `generatedAt`, a delta has `entry`.
    */
  def decodeFrame(data: String): Either[SseError, CapabilityEvent] =
    decode[CapabilitySnapshot](data)
      .map(Snapshot.apply)
      .orElse(decode[CapabilityChange](data).map(Delta.apply))
      .left
      .map(failure => SseError.Decode(SseEventName.Capabilities, failure.getMessage))

  given CanEqual[CapabilityEvent, CapabilityEvent] = CanEqual.derived
}

/** The browser's picture of what every service can currently do.
  *
  * ## Why it is a class as well as an object
  *
  * Everything interesting about this store is about *time* — a stream that drops and is replaced by polling,
  * a toast that must not repeat within thirty seconds, a service that flaps twice in five — and a test that
  * waits real seconds for any of that is a test nobody runs. So the stream, the poller, the notification bus
  * and the timer are all constructor parameters. The application passes the real ones; a suite passes its own
  * and steps time by hand. The same arrangement `Notifications` and `Theme` already use in this module.
  *
  * @param openStream
  *   subscribes to `/api/v1/capabilities/stream`. A function, because the store opens a fresh one after the
  *   old one is closed for good.
  * @param poll
  *   `GET /api/v1/capabilities`, the fallback for when the stream cannot be established.
  * @param notifications
  *   where the transition toasts go.
  * @param schedule
  *   runs a thunk after a delay. The browser's `setTimeout`, or a test's queue.
  */
final class Capabilities(
    openStream: () => SseHandle[CapabilityEvent],
    poll: () => EventStream[Either[ApiError, CapabilitySnapshot]],
    notifications: Notification => Unit,
    schedule: (FiniteDuration, () => Unit) => Unit,
    pollInterval: FiniteDuration = Capabilities.PollInterval
) {

  private val known = Var(Map.empty[CapabilityKey, CapabilityState])

  private val connectionState = Var[SseConnection](SseConnection.Connecting)

  /** Everything the gateway has told us. Never emptied by a failure — see [[applySnapshot]]. */
  val states: Signal[Map[CapabilityKey, CapabilityState]] = known.signal

  /** What the capability stream is doing, for the banner UI-010 renders. */
  val connection: Signal[SseConnection] = connectionState.signal

  /** Whether the poller is currently standing in for the stream. */
  private var polling: Boolean = false

  private var handle: Option[SseHandle[CapabilityEvent]] = None

  /** Opens the stream and starts keeping the picture up to date. Called once, by the shell. */
  def start(): Unit = connect()

  /** One capability's state, or `None` when the gateway has not reported it yet.
    *
    * `None` is a real answer and not a missing one: it means "we have not been told", which
    * [[FeatureState.derive]] turns into `Degraded(Starting)` rather than into a claim about health.
    */
  def stateOf(feature: FeatureId, cluster: Option[ClusterId]): Signal[Option[CapabilityState]] =
    known.signal.map(_.get(keyFor(feature, cluster)))

  /** What the shell should render for one feature: the capability folded together with permission. */
  def featureState(
      feature: FeatureId,
      cluster: Option[ClusterId],
      permitted: Signal[Boolean]
  ): Signal[FeatureState] =
    stateOf(feature, cluster).combineWith(permitted).map(FeatureState.derive)

  private def keyFor(feature: FeatureId, cluster: Option[ClusterId]): CapabilityKey =
    CapabilityKey(ServiceId.unsafe(feature.serviceId), cluster)

  private def connect(): Unit = {
    val opened = openStream()
    handle = Some(opened)

    opened.events.foreach {
      // A frame we cannot read is logged and skipped, and the picture is left exactly as it was.
      // Wiping the sidebar because of one bad frame would be the worst possible failure mode: every
      // feature would go from "working" to "unknown" because of a typo in one delta.
      case Left(problem) => dom.console.warn(s"kui: ignoring an unreadable capability frame: $problem")
      case Right(CapabilityEvent.Snapshot(snapshot)) => applySnapshot(snapshot)
      case Right(CapabilityEvent.Delta(change)) => applyChange(change)
    }(using unsafeWindowOwner): Unit

    opened.connection.foreach { current =>
      connectionState.set(current)
      current match {
        // The stream is working again, so the poller stands down.
        case SseConnection.Open => polling = false
        case SseConnection.Closed(_) => beginPollingFallback()
        case SseConnection.Connecting | SseConnection.Reconnecting(_) => ()
      }
    }(using unsafeWindowOwner): Unit
  }

  /** Starts asking for the whole picture on a timer, and keeps trying to get the stream back.
    *
    * Polling is strictly worse than the stream — it is thirty seconds behind and it is a request per interval
    * per open tab — so it is a fallback and never the normal path. It exists because the alternative, when a
    * proxy refuses to hold a long-lived connection, is a sidebar frozen at whatever it happened to know when
    * the connection dropped.
    */
  private def beginPollingFallback(): Unit =
    if !polling then {
      polling = true
      tick()
    }

  private def tick(): Unit =
    if polling then {
      poll().foreach {
        case Right(snapshot) => applySnapshot(snapshot)
        // A failed poll changes nothing. Both the stream and the poller being down means the picture
        // is stale, which `connection` already says; it does not mean every feature is broken, and
        // marking them so would take a working product off the air because one endpoint is down.
        case Left(failure) => dom.console.warn(s"kui: capability poll failed: $failure")
      }(using unsafeWindowOwner): Unit

      schedule(
        pollInterval,
        () => {
          // Each tick is also another go at the stream: recovering onto it is what stops the polling.
          if polling then connect()
          tick()
        }
      )
    }

  /** Replaces the picture with a whole snapshot, reporting every transition it implies. */
  private def applySnapshot(snapshot: CapabilitySnapshot): Unit = {
    val replacement = snapshot.entries.map(entry => entry.key -> entry.state).toMap
    val previous = known.now()
    known.set(replacement)
    replacement.foreach((key, state) => reportTransition(key, previous.get(key), state))
  }

  /** Applies one delta. A key nobody has heard of is added; a known one is replaced. */
  private def applyChange(change: CapabilityChange): Unit = {
    val key = change.entry.key
    // `previous` from the wire is preferred when the gateway sent one — it is what the gateway
    // actually transitioned from, which is more truthful than what this browser happened to hold if a
    // frame was missed.
    val before = change.previous.orElse(known.now().get(key))
    known.update(_.updated(key, change.entry.state))
    reportTransition(key, before, change.entry.state)
  }

  /** Raises a toast when a capability crosses into or out of `Unavailable`.
    *
    * Only the crossing is reported. "It is still down" is not news and, on a service that flaps, would be a
    * toast every few seconds — which is why the deduplication key names the capability rather than the
    * moment: `Notifications` collapses repeats of the same key inside its window (ADR-032).
    *
    * The rule is "was not unavailable, now is" rather than strictly "was available": from the user's point of
    * view a degraded feature going down is the same event, and reporting one and not the other would make the
    * notifications depend on an intermediate state they never saw.
    */
  private def reportTransition(
      key: CapabilityKey,
      before: Option[CapabilityState],
      after: CapabilityState
  ): Unit = {
    val wasUnavailable = before.exists(Capabilities.isUnavailable)
    val isUnavailable = Capabilities.isUnavailable(after)
    val name = Capabilities.describe(key)

    if !wasUnavailable && isUnavailable && before.isDefined then
      notifications(
        Notification(
          tone = Tone.Danger,
          title = s"$name is unavailable",
          message = Capabilities.messageOf(after),
          dedupKey = Some(s"capability-lost:${Capabilities.dedupKey(key)}")
        )
      )
    else if wasUnavailable && !isUnavailable then
      notifications(
        Notification(
          tone = Tone.Success,
          title = s"$name is back",
          dedupKey = Some(s"capability-back:${Capabilities.dedupKey(key)}")
        )
      )
  }

  /** Closes the stream. For tests and for a shell that is shutting down; the application never calls it. */
  def stop(): Unit = {
    polling = false
    handle.foreach(_.close())
  }
}

object Capabilities {

  /** How often the fallback asks for the whole picture. */
  val PollInterval: FiniteDuration = 30.seconds

  def isUnavailable(state: CapabilityState): Boolean = state match {
    case CapabilityState.Unavailable(_, _, _) => true
    case CapabilityState.Available | CapabilityState.Degraded(_) | CapabilityState.NotConfigured => false
  }

  def messageOf(state: CapabilityState): Option[String] = state match {
    case CapabilityState.Unavailable(_, message, _) => Some(message)
    case CapabilityState.Degraded(reason) => Some(reason.message)
    case CapabilityState.Available | CapabilityState.NotConfigured => None
  }

  /** What to call a capability in a sentence a user reads. */
  def describe(key: CapabilityKey): String =
    key.cluster.fold(key.service.value)(cluster => s"${key.service.value} on ${cluster.value}")

  /** One key, as a string, for `Notification.dedupKey`. */
  def dedupKey(key: CapabilityKey): String =
    s"${key.service.value}/${key.cluster.fold("-")(_.value)}"
}

/** The application's one capability store, wired to the browser's timer and the real notification bus. */
object CapabilityStore {

  /** The event name the capability stream uses for both snapshots and deltas (ADR-035). */
  val EventName: String = SseEventName.Capabilities

  private var instance: Option[Capabilities] = None

  /** Starts the store.
    *
    * `poll` is a function rather than an endpoint for the same reason `AuthState.refresh` is: the
    * `/api/v1/capabilities` endpoint is defined in the gateway's contract module, which sits above the kernel
    * and which the kernel must not depend on. The shell supplies it.
    */
  def start(
      streamUrl: String,
      poll: () => EventStream[Either[ApiError, CapabilitySnapshot]]
  ): Unit = {
    val started = new Capabilities(
      openStream = () =>
        Sse.eventSource(streamUrl, List(EventName))((_, data) => CapabilityEvent.decodeFrame(data)),
      poll = poll,
      notifications = NotificationBus.push,
      schedule = (delay, action) => dom.window.setTimeout(() => action(), delay.toMillis.toDouble): Unit
    )
    started.start()
    instance = Some(started)
  }

  /** Everything currently known. Empty before [[start]], which is a picture the sidebar renders as
    * `Degraded(Starting)` rather than as an empty list.
    */
  def states: Signal[Map[CapabilityKey, CapabilityState]] =
    instance.fold(Signal.fromValue(Map.empty))(_.states)

  def connection: Signal[SseConnection] =
    instance.fold(Signal.fromValue(SseConnection.Connecting))(_.connection)

  def stateOf(feature: FeatureId, cluster: Option[ClusterId]): Signal[Option[CapabilityState]] =
    instance.fold(Signal.fromValue(None))(_.stateOf(feature, cluster))

  def featureState(
      feature: FeatureId,
      cluster: Option[ClusterId],
      permitted: Signal[Boolean]
  ): Signal[FeatureState] =
    instance.fold(permitted.map(FeatureState.derive(None, _)))(_.featureState(feature, cluster, permitted))
}
