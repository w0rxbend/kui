package kui.gateway.application.capability

import java.time.Instant

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import cats.effect.kernel.{Async, Clock, Ref, Resource}
import cats.effect.std.{Random, Semaphore, Supervisor}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger

import kui.contracts.capability.{CapabilityKey, ClusterCapability, ReasonCode}
import kui.contracts.health.ReadinessReport
import kui.gateway.application.client.{CallContext, ServiceClient, ServiceClients}
import kui.http.health.HealthEndpoints
import kui.kernel.error.{ErrorCode, InfrastructureError, KuiError}
import kui.kernel.{CorrelationId, ServiceId}
import kui.security.Principal

/** Forces one service to be re-checked right now. */
trait Trigger[F[_]] {

  /** Polls the service and returns once the registry has been updated, so a UI that awaits this and then
    * reads the state cannot read a stale one.
    */
  def probe(service: ServiceId): F[Unit]
}

/** One fiber per configured service, each asking "are you ready, and what can you do?" every interval.
  *
  * This is how the registry learns the truth by itself. Nobody has to press refresh and nobody has to report
  * an outage: a service that dies is noticed within one interval, and a service that recovers is noticed on
  * the next successful poll.
  */
object ReadinessPoller {

  /** No poll may take longer than this, whatever the interval is.
    *
    * A readiness check that has not answered in five seconds has answered: the service is not healthy.
    * Waiting the full interval instead would mean a hung service was reported one interval late, and the
    * fiber would spend its whole life inside a call that is never coming back.
    */
  val MaxPollTimeout: FiniteDuration = 5.seconds

  /** At most one warning per service per minute.
    *
    * A service that is down fails every poll. At one line per failure and a ten-second interval that is 360
    * identical lines an hour per service, which buries every other line in the log — the classic way an
    * incident makes its own diagnosis harder.
    */
  val WarnEvery: FiniteDuration = 1.minute

  /** No telemetry parameter, deliberately. Every readiness call goes out through the same `ServiceClient` as
    * any other call, so OBS-002's `kui.upstream.duration` already records it with `upstream = <serviceId>`. A
    * poller-specific metric would measure the same calls a second time under a second name, and an operator
    * comparing the two would find them disagreeing at the edges.
    */
  def resource[F[_]: Async](
      clients: ServiceClients[F],
      signals: CapabilitySignals[F],
      interval: FiniteDuration,
      logger: StructuredLogger[F]
  ): Resource[F, Trigger[F]] =
    for {
      supervisor <- Supervisor[F](await = false)
      random <- Resource.eval(Random.scalaUtilRandom[F])
      pollers <- Resource.eval(clients.all.traverse(pollerFor(_, signals, interval, logger)))
      byService = pollers.map(poller => poller.service -> poller).toMap
      _ <- Resource.eval(pollers.traverse_(poller => start(poller, supervisor, random, interval)))
    } yield new Trigger[F] {
      // A probe for a service this deployment does not have is not an error. The registry answers
      // `NotConfigured` for it either way, and making the retry button throw would be worse than making
      // it do nothing.
      def probe(service: ServiceId): F[Unit] =
        byService.get(service).traverse_(_.pollNow)
    }

  private def start[F[_]: Async](
      poller: ServicePoller[F],
      supervisor: Supervisor[F],
      random: Random[F],
      interval: FiniteDuration
  ): F[Unit] =
    supervisor
      .supervise(
        // Jitter. Eleven services polled on the same tick means eleven simultaneous requests every ten
        // seconds forever, which is a self-inflicted load spike with a period; spreading the first poll
        // across the interval turns it into a steady trickle.
        random.betweenLong(0L, interval.toMillis).flatMap { offset =>
          Async[F].sleep(FiniteDuration(offset, java.util.concurrent.TimeUnit.MILLISECONDS)) *>
            poller.loop(interval)
        }
      )
      .void

  private def pollerFor[F[_]: Async](
      client: ServiceClient[F],
      signals: CapabilitySignals[F],
      interval: FiniteDuration,
      logger: StructuredLogger[F]
  ): F[ServicePoller[F]] =
    for {
      window <- Ref.of[F, LatencyWindow](LatencyWindow.empty())
      lastWarn <- Ref.of[F, Option[Instant]](None)
      inFlight <- Semaphore[F](1)
    } yield new ServicePoller[F](client, signals, interval, logger, window, lastWarn, inFlight)

  /** Turns a failed poll into the reason code the user will read.
    *
    * The mapping is by error case rather than by HTTP status because the two are not the same question: a
    * timeout and a refused connection are both "unreachable" on the wire and mean different things to whoever
    * has to fix it.
    */
  def reasonOf(error: KuiError): ReasonCode =
    error match {
      case InfrastructureError.CircuitOpen(_, _) => ReasonCode.CircuitOpen
      case InfrastructureError.Timeout(_, _) => ReasonCode.UpstreamTimeout
      case InfrastructureError.AuthFailed(_) => ReasonCode.UpstreamAuth
      case other if other.code == ErrorCode.Unsupported => ReasonCode.NotConfigured
      case _ => ReasonCode.UpstreamUnavailable
    }

  /** The gateway polls on nobody's behalf, so the call carries the anonymous principal. The health and
    * capability endpoints are unauthenticated by design, so there is nothing to sign.
    */
  private def pollContext(correlationId: CorrelationId): CallContext =
    CallContext(Principal.Anonymous, correlationId, None)

  final private class ServicePoller[F[_]: Async](
      client: ServiceClient[F],
      signals: CapabilitySignals[F],
      interval: FiniteDuration,
      logger: StructuredLogger[F],
      window: Ref[F, LatencyWindow],
      lastWarn: Ref[F, Option[Instant]],
      inFlight: Semaphore[F]
  ) {

    val service: ServiceId = client.service

    /** The scheduled loop. A tick that finds a poll already running skips rather than queueing, so a service
      * slower than the interval is polled once at a time instead of accumulating a backlog of calls that will
      * all time out together.
      */
    def loop(every: FiniteDuration): F[Unit] =
      (pollIfFree *> Async[F].sleep(every)).foreverM

    /** Waits for any poll in flight, then polls. Used by `probeNow`, whose caller is a person who just
      * pressed a button and is entitled to a fresh answer rather than a skipped tick.
      */
    def pollNow: F[Unit] = inFlight.permit.use(_ => poll)

    private def pollIfFree: F[Unit] =
      inFlight.tryAcquire.flatMap {
        case true => poll.guarantee(inFlight.release)
        case false => Async[F].unit
      }

    private val budget: FiniteDuration = interval.min(MaxPollTimeout)

    private def poll: F[Unit] =
      for {
        correlationId <- kui.observability.Correlation.newRandom[F]
        context = pollContext(correlationId)
        startedAt <- Clock[F].monotonic
        readiness <- timed(client.callPublic(HealthEndpoints.ready, ())(context))
        endedAt <- Clock[F].monotonic
        latency <- window.updateAndGet(_.record(endedAt - startedAt)).map(_.p95)
        capabilities <- timed(client.callPublic(HealthEndpoints.capabilities, ())(context))
        _ <- apply(readiness, capabilities, latency)
        _ <- warnOnFailure(readiness, capabilities)
      } yield ()

    /** Every poll has its own deadline, and a poll that misses it is a failure rather than a hang. */
    private def timed[A](call: F[Either[KuiError, A]]): F[Either[KuiError, A]] =
      call
        .timeoutTo(
          budget,
          Async[F]
            .pure(Left(InfrastructureError.Timeout(service.value, budget.toMillis)): Either[KuiError, A])
        )
        // A poll must never take the poller down with it. An exception here would kill the fiber and the
        // service would stay at whatever state it was last reported as, for ever, with nothing saying so.
        .handleError(error =>
          Left(
            InfrastructureError
              .Unreachable(service.value, Option(error.getMessage).getOrElse(error.getClass.getSimpleName))
          )
        )

    /** Folds the two answers into the inputs.
      *
      * Readiness wins over the capability report: a service that says it is not ready must not receive
      * traffic, whatever else it claims about itself. When readiness succeeds but the capability call fails,
      * the previous capability payload is kept rather than cleared — losing it would turn a momentary blip
      * into "this service can do nothing", which is a much stronger claim than the evidence supports.
      */
    private def apply(
        readiness: Either[KuiError, ReadinessReport],
        capabilities: Either[KuiError, kui.contracts.capability.ServiceCapabilities],
        latency: Option[FiniteDuration]
    ): F[Unit] =
      Clock[F].realTimeInstant.flatMap { now =>
        val signal = readiness match {
          case Left(error) => ReadinessSignal.NotReady(reasonOf(error), error.message, now)
          case Right(report) if !report.ready =>
            ReadinessSignal.NotReady(ReasonCode.UpstreamUnavailable, notReadyMessage(report), now)
          case Right(_) => ReadinessSignal.Ready
        }

        val serviceKey = CapabilityKey(service, None)

        for {
          _ <- signals.update(serviceKey) { inputs =>
            inputs.copy(
              readiness = Some(signal),
              serviceReport = capabilities.toOption
                .map(reported => summarise(reported))
                .orElse(inputs.serviceReport),
              p95 = latency
            )
          }
          // Whether or not the capability call answered, the readiness verdict reaches every cluster key
          // this service has: a service that cannot answer cannot vouch for any of its clusters, and a
          // sidebar showing three healthy clusters belonging to a service that is not there is a lie.
          _ <- capabilities.toOption match {
            case Some(reported) => perCluster(reported, signal, latency)
            case None => carryReadiness(signal, latency)
          }
        } yield ()
      }

    /** What the service says about itself, as opposed to about its clusters.
      *
      * **It no longer takes the worst of the clusters, and that is a behaviour change.** The M0 version
      * folded every cluster into one verdict and reported `unavailable` if any single one was; combined with
      * the capability fold, one unreachable Kafka cluster dimmed the cluster feature for every user of every
      * other cluster - the exact failure DEVPLAN D4 forbids and the reason ADR-039 §6 is worded the way it
      * is. The service's own status now comes from its readiness and its circuit; each cluster's status lives
      * on that cluster's key.
      *
      * `configured` still reflects whether the service has any cluster at all, because a service with none
      * genuinely has nothing cluster-scoped to offer, and `features` is the union: a feature any cluster
      * supports is a feature the service can perform.
      */
    private def summarise(
        reported: kui.contracts.capability.ServiceCapabilities
    ): ClusterCapability =
      ClusterCapability(
        // A service that reports no clusters at all is still configured *as a service*: the M0 rule, kept.
        // A KUI nobody has configured a cluster in has a perfectly working cluster service, and reporting
        // it as not configured would grey the feature out on a deployment where nothing is wrong.
        configured = reported.clusters.isEmpty || reported.clusters.values.exists(_.configured),
        features = reported.clusters.values.toList.flatMap(_.features).distinct.sorted,
        status = CapabilityFold.Status.Available
      )

    /** One key per cluster the service reported, and a retirement for every cluster it stopped reporting.
      *
      * A service that cannot answer cannot vouch for any of its clusters, so its readiness signal is written
      * to every cluster key as well: the alternative is a sidebar showing three healthy clusters belonging to
      * a service that is not there.
      */
    private def perCluster(
        reported: kui.contracts.capability.ServiceCapabilities,
        signal: ReadinessSignal,
        latency: Option[FiniteDuration]
    ): F[Unit] = {
      val live = reported.clusters.map((id, capability) => CapabilityKey(service, Some(id)) -> capability)

      for {
        _ <- live.toList.traverse_((key, capability) =>
          signals.update(key)(
            _.copy(readiness = Some(signal), serviceReport = Some(capability), p95 = latency)
          )
        )
        known <- signals.keysOf(service)
        // Anything this service used to report and no longer does. Reported as not configured rather than
        // dropped: ADR-032 is explicit that "not configured" is not a failure, and the switcher greys such
        // a cluster with no error styling instead of leaving it looking broken for ever.
        retired = known.filter(key => key.cluster.isDefined && !live.contains(key))
        _ <- retired.toList.traverse_(key =>
          signals.update(key)(
            _.copy(serviceReport = Some(ClusterCapability(configured = false, Nil, "not_configured")))
          )
        )
      } yield ()
    }

    /** The readiness verdict, onto every cluster key already known, leaving each one's last payload alone.
      *
      * Keeping the payload is the existing rule applied per key: losing it would turn a momentary blip into
      * "this service can do nothing for any cluster", which is a much stronger claim than the evidence
      * supports.
      */
    private def carryReadiness(signal: ReadinessSignal, latency: Option[FiniteDuration]): F[Unit] =
      signals
        .keysOf(service)
        .flatMap(
          _.filter(_.cluster.isDefined).toList
            .traverse_(key => signals.update(key)(_.copy(readiness = Some(signal), p95 = latency)))
        )

    private def notReadyMessage(report: ReadinessReport): String = {
      val failed = report.checks.filterNot(_.healthy).map(_.name)
      if failed.isEmpty then "the service reports itself not ready"
      else s"the service reports these checks failing: ${failed.mkString(", ")}"
    }

    private def warnOnFailure(
        readiness: Either[KuiError, ?],
        capabilities: Either[KuiError, ?]
    ): F[Unit] =
      readiness.left.toOption.orElse(capabilities.left.toOption).traverse_(rateLimitedWarn)

    private def rateLimitedWarn(error: KuiError): F[Unit] =
      Clock[F].realTimeInstant.flatMap { now =>
        lastWarn
          .modify {
            case Some(at) if now.toEpochMilli - at.toEpochMilli < WarnEvery.toMillis => (Some(at), false)
            case _ => (Some(now), true)
          }
          .flatMap {
            case false => Async[F].unit
            case true =>
              logger.warn(
                Map(
                  kui.observability.MetricNames.Attr.Service -> service.value,
                  kui.observability.MetricNames.Attr.Reason -> error.code.wire
                )
              )(s"the readiness poll of ${service.value} failed: ${error.message}")
          }
      }
  }
}
