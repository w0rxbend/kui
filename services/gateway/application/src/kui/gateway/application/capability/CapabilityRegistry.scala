package kui.gateway.application.capability

import java.time.Instant

import cats.effect.kernel.{Async, Clock, Ref, Resource}
import cats.effect.std.{Queue, Supervisor}
import cats.syntax.all.*
import fs2.Stream
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.UpDownCounter

import kui.contracts.capability.{CapabilityChange, CapabilityEntry, CapabilityKey, CapabilityState}
import kui.kernel.ServiceId
import kui.observability.{MetricNames, Telemetry}

/** What KUI can do right now, per service and per cluster, and every change to it.
  *
  * One place knows whether a feature is available, degraded, unavailable-since-when, or simply not
  * configured. The sidebar dims from here, the fallback panels are chosen from here, and the "retry now"
  * button pokes this. `ARCHITECTURE.md` §4.5 and §6 describe it; ADR-039 decides what it computes.
  *
  * ==Why the registry may never fail==
  *
  * `report` is total and swallows nothing important: an unexpected combination of inputs folds to a degraded
  * state rather than throwing, because a registry that crashed would take the shell's navigation with it and
  * turn a single broken service into a broken product. That is the failure mode this whole component exists
  * to prevent, so it must not be the component's own failure mode.
  */
trait CapabilityRegistry[F[_]] {

  /** Everything known right now. Used to answer `GET /api/v1/capabilities` and as the first event of the
    * change stream, so that a subscriber never has to guess what it missed before it connected.
    */
  def snapshot: F[Map[CapabilityKey, CapabilityState]]

  def state(key: CapabilityKey): F[CapabilityState]

  /** Every transition, from the moment of subscription onwards.
    *
    * A subscriber that stops reading loses its own oldest events and nothing else: it cannot slow the
    * registry down, and it cannot slow another subscriber down. A browser tab that a laptop suspended
    * mid-stream must not be able to stall the sidebar of everyone else in the building.
    */
  def changes: Stream[F, CapabilityChange]

  /** Records what the pollers and feeds observed. Total: it never fails and never blocks. */
  def report(key: CapabilityKey, state: CapabilityState): F[Unit]

  /** Forces an immediate re-check of one service and returns once its state has been recomputed, so the UI's
    * "Retry now" button is honest rather than decorative.
    */
  def probeNow(service: ServiceId): F[Unit]

  /** Hands the registry the poller's trigger.
    *
    * The registry and the poller each need the other: the poller reports into the registry, and `probeNow`
    * has to make the poller poll. Rather than merge two components that have nothing else in common, the
    * cycle is cut here, once, at construction time. Before it is called, `probeNow` is a no-op that returns
    * successfully — which is the right answer for a deployment with no poller at all.
    */
  def attachProbe(probe: ServiceId => F[Unit]): F[Unit]
}

object CapabilityRegistry {

  def resource[F[_]: Async](
      config: RegistryConfig,
      telemetry: Telemetry[F],
      logger: StructuredLogger[F]
  ): Resource[F, CapabilityRegistry[F]] =
    for {
      supervisor <- Supervisor[F](await = false)
      gauge <- Resource.eval(stateGauge[F](telemetry))
      states <- Resource.eval(Ref.of[F, Map[CapabilityKey, CapabilityState]](Map.empty))
      pending <- Resource.eval(Ref.of[F, Map[CapabilityKey, CapabilityState]](Map.empty))
      subscribers <- Resource.eval(Ref.of[F, Map[Long, Subscriber[F]]](Map.empty))
      nextId <- Resource.eval(Ref.of[F, Long](0L))
      probe <- Resource.eval(Ref.of[F, ServiceId => F[Unit]](_ => Async[F].unit))
    } yield new Impl[F](config, logger, supervisor, gauge, states, pending, subscribers, nextId, probe)

  /** One subscriber's bounded mailbox. `None` ends the stream. */
  final private case class Subscriber[F[_]](queue: Queue[F, Option[CapabilityChange]])

  private def stateGauge[F[_]: Async](telemetry: Telemetry[F]): F[UpDownCounter[F, Long]] =
    telemetry
      .meter("kui.gateway.capability")
      .flatMap(
        _.upDownCounter[Long](MetricNames.CapabilityState)
          .withDescription("How many capability keys are currently in each state")
          .create
      )

  final private class Impl[F[_]: Async](
      config: RegistryConfig,
      logger: StructuredLogger[F],
      supervisor: Supervisor[F],
      gauge: UpDownCounter[F, Long],
      states: Ref[F, Map[CapabilityKey, CapabilityState]],
      pending: Ref[F, Map[CapabilityKey, CapabilityState]],
      subscribers: Ref[F, Map[Long, Subscriber[F]]],
      nextId: Ref[F, Long],
      probe: Ref[F, ServiceId => F[Unit]]
  ) extends CapabilityRegistry[F] {

    def snapshot: F[Map[CapabilityKey, CapabilityState]] = states.get

    def state(key: CapabilityKey): F[CapabilityState] =
      states.get.map(_.getOrElse(key, CapabilityState.NotConfigured))

    def attachProbe(next: ServiceId => F[Unit]): F[Unit] = probe.set(next)

    def probeNow(service: ServiceId): F[Unit] = probe.get.flatMap(_.apply(service))

    def report(key: CapabilityKey, next: CapabilityState): F[Unit] =
      Clock[F].realTimeInstant.flatMap { now =>
        decide(key, next).flatMap {
          // Any report other than the outage itself cancels a pending outage. Without this, a service
          // that failed once and recovered inside the debounce window would still be published as down
          // when the window closed -- the sidebar would go red for a service that was already fine
          // again, which is precisely the flicker the debounce exists to remove.
          case Decision.Ignore => pending.update(_ - key)
          case Decision.Publish(previous) => commit(key, next, previous, now)
          case Decision.Hold => hold(key, next)
        }
      }

    /** Whether this report is news, and if so whether it may be published at once (ADR-039 §4).
      *
      * Slow to fail, instant to recover. A single dropped readiness poll is not an outage and publishing it
      * as one makes the sidebar flicker; waiting one interval costs ten seconds of notice on a real outage,
      * which nobody notices against the outage itself. Recovery is the opposite: someone is staring at a
      * fallback panel waiting for exactly this, so it goes out immediately.
      */
    private def decide(key: CapabilityKey, next: CapabilityState): F[Decision] =
      states.get.map { current =>
        val previous = current.get(key)
        if previous.contains(next) then Decision.Ignore
        else if isOutage(next) && previous.contains(CapabilityState.Available) then Decision.Hold
        else Decision.Publish(previous)
      }

    private def isOutage(state: CapabilityState): Boolean =
      state match {
        case CapabilityState.Unavailable(_, _, _) => true
        case _ => false
      }

    /** Remembers the pending outage and, one debounce later, publishes it if it is still true.
      *
      * The fiber is supervised so that releasing the registry cancels it; a pending outage that outlived its
      * registry would publish into a closed topic.
      */
    private def hold(key: CapabilityKey, next: CapabilityState): F[Unit] =
      pending.update(_.updated(key, next)) *>
        supervisor
          .supervise(
            Async[F].sleep(config.debounce) *> confirm(key, next)
          )
          .void

    private def confirm(key: CapabilityKey, next: CapabilityState): F[Unit] =
      pending.modify(held => (held - key, held.get(key))).flatMap {
        // Still the same pending outage, so it has persisted for a whole debounce window and is real.
        case Some(held) if held == next =>
          Clock[F].realTimeInstant
            .flatMap(now => states.get.flatMap(current => commit(key, next, current.get(key), now)))
        // Something else happened in the meantime — a recovery, or a different failure — and that
        // something else has already been decided on its own terms.
        case _ => Async[F].unit
      }

    private def commit(
        key: CapabilityKey,
        next: CapabilityState,
        previous: Option[CapabilityState],
        now: Instant
    ): F[Unit] =
      states.update(_.updated(key, next)) *>
        pending.update(_ - key) *>
        record(previous, next) *>
        log(key, previous, next) *>
        publish(CapabilityChange(CapabilityEntry(key, next, now), previous))

    /** One gauge that moves between buckets, so `sum by (state)` is the number of keys in each. */
    private def record(previous: Option[CapabilityState], next: CapabilityState): F[Unit] =
      previous.traverse_(state => gauge.dec(Attribute(MetricNames.Attr.State, state.status))) *>
        gauge.inc(Attribute(MetricNames.Attr.State, next.status))

    /** One line per transition and none while a state is steady. A log that repeats itself every ten seconds
      * for every healthy service is a log nobody reads.
      */
    private def log(
        key: CapabilityKey,
        previous: Option[CapabilityState],
        next: CapabilityState
    ): F[Unit] =
      logger.info(
        Map(
          MetricNames.Attr.Service -> key.service.value,
          MetricNames.Attr.Cluster -> key.cluster.fold("-")(_.value),
          "from" -> previous.fold("unknown")(_.status),
          "to" -> next.status,
          MetricNames.Attr.Reason -> reasonOf(next)
        )
      )(
        s"${key.service.value} is now ${next.status}"
      )

    private def reasonOf(state: CapabilityState): String =
      state match {
        case CapabilityState.Degraded(reason) => reason.code.wire
        case CapabilityState.Unavailable(reason, _, _) => reason.wire
        case _ => "-"
      }

    def changes: Stream[F, CapabilityChange] =
      Stream
        .resource(subscribe)
        .flatMap(subscriber => Stream.fromQueueNoneTerminated(subscriber.queue))

    private def subscribe: Resource[F, Subscriber[F]] =
      Resource
        .make(
          for {
            id <- nextId.getAndUpdate(_ + 1)
            queue <- Queue.bounded[F, Option[CapabilityChange]](math.max(1, config.subscriberQueueSize))
            subscriber = Subscriber(queue)
            _ <- subscribers.update(_.updated(id, subscriber))
          } yield (id, subscriber)
        )((id, subscriber) =>
          // `tryOffer` and never `offer`. Release runs when the consumer is already going away, and a
          // subscriber that fell far enough behind has a full mailbox: a blocking offer there would hang
          // the very cancellation that is trying to clean it up, so the one subscriber this component is
          // designed to survive would be the one that could deadlock it.
          subscribers.update(_ - id) *> subscriber.queue.tryOffer(None).void
        )
        .map(_._2)

    /** Offers to every subscriber without ever waiting for one.
      *
      * `tryOffer` and not `offer`: a full mailbox means that subscriber has stopped reading, and the only two
      * options are to drop its oldest event or to stop the registry. Dropping is right — the subscriber will
      * resynchronise from the next snapshot it asks for — and it is logged, because a subscriber that drops
      * events regularly is a bug somewhere else.
      */
    private def publish(change: CapabilityChange): F[Unit] =
      subscribers.get.flatMap(_.values.toList.traverse_(offer(_, change)))

    private def offer(subscriber: Subscriber[F], change: CapabilityChange): F[Unit] =
      subscriber.queue.tryOffer(Some(change)).flatMap {
        case true => Async[F].unit
        case false =>
          subscriber.queue.tryTake *>
            logger.warn(
              Map(MetricNames.Attr.Service -> change.entry.key.service.value)
            )(
              "a capability subscriber is not keeping up; its oldest change was dropped"
            ) *>
            subscriber.queue.tryOffer(Some(change)).void
      }
  }

  /** What to do with one report. */
  private enum Decision {
    case Ignore
    case Publish(previous: Option[CapabilityState])
    case Hold
  }
}
