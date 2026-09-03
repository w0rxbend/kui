package kui.http.upstream

import java.time.Instant

import scala.concurrent.duration.FiniteDuration

import cats.effect.kernel.{Clock, Outcome, Ref, Temporal}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.Topic

import kui.kernel.PositiveInt

/** Whether KUI is currently willing to call an upstream. */
enum CircuitState {
  case Closed, Open, HalfOpen

  /** The numeric form the `kui.upstream.circuit.state` gauge carries, so a dashboard can graph it. */
  def code: Long = this match {
    case Closed => 0L
    case Open => 1L
    case HalfOpen => 2L
  }
}

object CircuitState {
  given CanEqual[CircuitState, CircuitState] = CanEqual.derived
}

/** One transition, published so the capability registry can grey a feature out the moment KUI stops calling
  * the thing behind it.
  */
final case class CircuitEvent(
    upstream: String,
    state: CircuitState,
    at: Instant,
    lastError: Option[String]
)

object CircuitEvent {
  given CanEqual[CircuitEvent, CircuitEvent] = CanEqual.derived
}

/** Stops calling an upstream that is failing, and finds out when it has recovered.
  *
  * The problem it solves is not the failing upstream — that is already broken — but what happens to *KUI*
  * while it is broken. Without a breaker, every request queues behind a connection that will time out, KUI's
  * own threads fill up waiting, and a dead schema registry takes the topic list down with it. With one, calls
  * fail immediately and cheaply, and the parts of the UI that do not need that upstream keep working.
  *
  * The three states:
  *
  *   - **closed** — calls go through. `failureThreshold` failures *in a row* opens it; a single success
  *     anywhere in the run resets the count, because an upstream that answers one call in three is degraded,
  *     not down, and cutting it off entirely would lose the two that worked.
  *   - **open** — calls fail immediately with `CircuitOpen`, without touching the network, for
  *     `resetTimeout`.
  *   - **half-open** — exactly one call is allowed through as a probe. It succeeds and the circuit closes; it
  *     fails and the circuit reopens with a fresh timer. Every other caller during that window is still
  *     refused, because sending a thousand probes at a recovering upstream is how it goes down again.
  */
trait CircuitBreaker[F[_]] {

  /** Runs `call` if the circuit allows it, and records what happened.
    *
    * Fails with [[CircuitOpenException]] without running `call` at all when the circuit is open — asserted in
    * the suite by counting how many requests reach the backend.
    *
    * `succeeded` decides whether a value that came back counts as a success. It has to be a parameter rather
    * than "did it throw", because an upstream that answers `503` to everything is as down as one that refuses
    * connections, and a breaker that only noticed exceptions would never open for it.
    */
  def protect[A](call: F[A])(succeeded: A => Boolean): F[A]

  /** [[protect]] where any value that comes back counts as a success. */
  def protect[A](call: F[A]): F[A] = protect(call)(_ => true)

  def state: F[CircuitState]

  /** Every transition, and only transitions: a circuit that stays open publishes nothing. */
  def events: Stream[F, CircuitEvent]
}

/** Thrown by [[CircuitBreaker.protect]] when the circuit is open.
  *
  * It carries the `KuiError` that a caller will eventually return, so nothing has to translate it twice.
  */
final case class CircuitOpenException(upstream: String, since: Instant)
    extends Exception(s"calls to $upstream are suspended while it recovers")

object CircuitBreaker {

  /** What the breaker remembers between calls. */
  final private case class Memory(
      state: CircuitState,
      consecutiveFailures: Int,
      openedAt: Option[Instant],
      probeInFlight: Boolean,
      lastError: Option[String]
  )

  private val initial: Memory = Memory(CircuitState.Closed, 0, None, probeInFlight = false, None)

  /** A breaker for one upstream.
    *
    * @param failureThreshold
    *   consecutive failures that open the circuit
    * @param resetTimeout
    *   how long it stays open before admitting a probe
    */
  def make[F[_]: Temporal](
      upstream: String,
      failureThreshold: PositiveInt,
      resetTimeout: FiniteDuration
  ): F[CircuitBreaker[F]] =
    for {
      memory <- Ref.of[F, Memory](initial)
      topic <- Topic[F, CircuitEvent]
    } yield new Impl[F](upstream, failureThreshold.value, resetTimeout, memory, topic)

  final private class Impl[F[_]: Temporal](
      upstream: String,
      failureThreshold: Int,
      resetTimeout: FiniteDuration,
      memory: Ref[F, Memory],
      topic: Topic[F, CircuitEvent]
  ) extends CircuitBreaker[F] {

    def state: F[CircuitState] = memory.get.map(_.state)

    def events: Stream[F, CircuitEvent] = topic.subscribeUnbounded

    def protect[A](call: F[A])(succeeded: A => Boolean): F[A] =
      Clock[F].realTimeInstant.flatMap { now =>
        admit(now).flatMap {
          case Left(openSince) => Temporal[F].raiseError[A](CircuitOpenException(upstream, openSince))
          case Right(isProbe) =>
            // `guaranteeCase` and not a plain `flatMap`: `attempt` sees a value or an error, but
            // never a cancellation, and a cancelled probe that released nothing would leave the
            // claim set with no path that could ever clear it. A call timeout cancels, and so does
            // a client disconnect, so that is the *likeliest* fate of a probe at a hung upstream.
            call.attempt
              .guaranteeCase {
                case Outcome.Canceled() => onCancelled(isProbe)
                case _ => Temporal[F].unit
              }
              .flatMap {
                case Right(value) if succeeded(value) => onSuccess() *> Temporal[F].pure(value)
                case Right(value) =>
                  onFailure(isProbe, UnsuccessfulResponse) *> Temporal[F].pure(value)
                case Left(error) => onFailure(isProbe, error) *> Temporal[F].raiseError[A](error)
              }
        }
      }

    /** Decides, atomically, whether this caller may proceed and whether it is the probe.
      *
      * Atomically matters: `half-open admits exactly one probe` is only true if the check and the claim
      * happen in one step. Two callers arriving in the same instant must not both come back holding the
      * probe.
      */
    private def admit(now: Instant): F[Either[Instant, Boolean]] =
      memory
        .modify { current =>
          current.state match {
            case CircuitState.Closed => (current, Right(false))

            case CircuitState.Open =>
              val since = current.openedAt.getOrElse(now)
              if now.toEpochMilli - since.toEpochMilli >= resetTimeout.toMillis then
                (current.copy(state = CircuitState.HalfOpen, probeInFlight = true), Right(true))
              else (current, Left(since))

            case CircuitState.HalfOpen =>
              if current.probeInFlight then (current, Left(current.openedAt.getOrElse(now)))
              else (current.copy(probeInFlight = true), Right(true))
          }
        }
        .flatMap { decision =>
          // The transition into half-open is a transition like any other, and the registry needs it.
          decision match {
            case Right(true) => publish(CircuitState.HalfOpen, now, None).as(decision)
            case _ => Temporal[F].pure(decision)
          }
        }

    /** A success clears everything: the failure run, the probe claim and the open timer.
      *
      * Clearing the *run* rather than decrementing it is the decision worth knowing. An upstream that answers
      * one call in three is degraded, not down, and a counter that never reset would eventually open the
      * circuit on it and lose the two calls that were working.
      */
    private def onSuccess(): F[Unit] =
      Clock[F].realTimeInstant.flatMap { now =>
        memory
          .modify { current =>
            (initial, Option.when(current.state != CircuitState.Closed)(CircuitState.Closed))
          }
          .flatMap {
            case Some(next) => publish(next, now, None)
            case None => Temporal[F].unit
          }
      }

    private def onFailure(isProbe: Boolean, error: Throwable): F[Unit] =
      Clock[F].realTimeInstant.flatMap { now =>
        val reason = Option(error.getMessage).getOrElse(error.getClass.getSimpleName)

        memory
          .modify { current =>
            val failures = current.consecutiveFailures + 1

            if isProbe || failures >= failureThreshold then
              // A failed probe reopens with a *fresh* timer: the upstream had its chance and is
              // still unwell, so the next probe should be another full reset away, not immediate.
              (
                Memory(CircuitState.Open, failures, Some(now), probeInFlight = false, Some(reason)),
                Some(CircuitState.Open)
              )
            else
              (
                current.copy(consecutiveFailures = failures, lastError = Some(reason)),
                Option.when(current.state != CircuitState.Closed)(CircuitState.Closed)
              )
          }
          .flatMap {
            case Some(next) => publish(next, now, Some(reason))
            case None => Temporal[F].unit
          }
      }

    /** Releases a probe claim whose call was cancelled before it could answer.
      *
      * A cancelled probe told us nothing about the upstream, so the circuit goes back to open with
      * a fresh timer rather than closing: the upstream still has to earn its way back with a probe
      * that actually completes. The consecutive-failure run is left alone, because a cancellation
      * is our decision, not the upstream's, and counting it as a failure would open circuits on
      * upstreams that were merely called by a client that hung up.
      *
      * A cancelled non-probe call changes nothing: it never claimed anything.
      */
    private def onCancelled(isProbe: Boolean): F[Unit] =
      Temporal[F].whenA(isProbe) {
        Clock[F].realTimeInstant.flatMap { now =>
          memory.update(current =>
            current.copy(
              state = CircuitState.Open,
              openedAt = Some(now),
              probeInFlight = false,
              lastError = Some(ProbeCancelled)
            )
          ) *> publish(CircuitState.Open, now, Some(ProbeCancelled))
        }
      }

    /** Hands a transition to the subscribers without ever making the caller wait for them.
      *
      * `publish` runs on the request fiber that was calling the upstream, inside the bulkhead
      * permit. `subscribe(n)` gives each subscriber a bounded, *backpressuring* queue, so a
      * subscriber that stopped draining — the capability fold behind a stalled log appender, say —
      * would not miss transitions, it would stop every in-flight upstream call. Subscribers are
      * therefore unbounded: the only things published here are transitions, which are rare by
      * construction, and every subscriber is a stream whose lifetime is a resource that
      * unsubscribes when it ends.
      */
    private def publish(state: CircuitState, at: Instant, lastError: Option[String]): F[Unit] =
      topic.publish1(CircuitEvent(upstream, state, at, lastError)).void
  }

  /** The stand-in cause recorded when a probe was cancelled before the upstream answered. */
  private val ProbeCancelled: String = "the probe was cancelled before the upstream answered"

  /** The stand-in cause recorded when a response arrived but did not count as a success.
    *
    * It never reaches a caller — the response itself is returned unchanged — and exists only so that the
    * transition log has something to say about why the circuit opened.
    */
  private val UnsuccessfulResponse: Throwable =
    new RuntimeException("the upstream answered, but not successfully")
}
