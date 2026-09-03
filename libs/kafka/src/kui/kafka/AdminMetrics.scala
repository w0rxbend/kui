package kui.kafka

import java.util.concurrent.TimeoutException as JavaTimeoutException

import cats.effect.Async
import cats.syntax.all.*
import org.apache.kafka.common.errors.{ApiException, TimeoutException}
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.Histogram

import kui.kernel.ClusterId
import kui.observability.{MetricNames, Telemetry, UpstreamOutcome}

/** The measurement hook for every admin call.
  *
  * It is a parameter of the pool rather than a call at each port method, because "measure every call to
  * another system" (`ARCHITECTURE.md` §13) is a promise that is only kept if there is exactly one place a
  * call can go through. `ClusterAdmin` has a dozen methods; twelve opportunities to forget is twelve too
  * many.
  */
trait AdminMetrics[F[_]] {

  /** Times `fa` and records it under `cluster`, `operation` and the outcome it ended with. */
  def timed[A](cluster: ClusterId, operation: String)(fa: F[A]): F[A]
}

object AdminMetrics {

  /** For a process with no telemetry configured, and for tests that assert behaviour rather than measurement.
    */
  def noop[F[_]]: AdminMetrics[F] = new AdminMetrics[F] {
    def timed[A](cluster: ClusterId, operation: String)(fa: F[A]): F[A] = fa
  }

  /** Records `kui.kafka.admin.duration` in seconds.
    *
    * The unit is seconds and the attribute vocabulary is `UpstreamOutcome`'s, both so that a Kafka call and
    * an HTTP call can be read on one dashboard: an operator asking "what is slow" should not have to know
    * which protocol the slowness was on before they can ask.
    */
  def otel[F[_]: Async](telemetry: Telemetry[F]): F[AdminMetrics[F]] =
    for {
      meter <- telemetry.meter("kui.kafka")
      histogram <- meter
        .histogram[Double](MetricNames.KafkaAdminDuration)
        .withUnit("s")
        .withDescription("How long a Kafka admin call took, and how it ended")
        .create
    } yield new Otel[F](histogram)

  /** How an admin call ended, in the six values an operator groups by.
    *
    * The distinction that earns its keep is timeout versus unreachable versus client error: "we gave up
    * waiting", "the connection is broken" and "the broker refused this request" have different causes and
    * different fixes, and a dashboard that collapses them says only that something failed.
    */
  def outcomeOf(result: Either[Throwable, Any]): UpstreamOutcome = result match {
    case Right(_) => UpstreamOutcome.Success
    case Left(failure) =>
      KafkaFutures.unwrap(failure) match {
        case _: TimeoutException => UpstreamOutcome.Timeout
        case _: JavaTimeoutException => UpstreamOutcome.Timeout
        case reconnect if AdminInvalidation.isReconnectClass(reconnect) =>
          UpstreamOutcome.Unreachable
        // Everything Kafka models as an API-level error is a statement about the request — an
        // unknown topic, a missing authorization — not about the broker's health.
        case _: ApiException => UpstreamOutcome.ClientError
        case _ => UpstreamOutcome.ServerError
      }
  }

  final private class Otel[F[_]: Async](histogram: Histogram[F, Double]) extends AdminMetrics[F] {

    def timed[A](cluster: ClusterId, operation: String)(fa: F[A]): F[A] =
      for {
        startedAt <- Async[F].monotonic
        // `guaranteeCase` would also fire on cancellation, where there is no outcome to report and
        // the histogram would gain a bucket for calls that never happened. `attempt` records the
        // two outcomes that exist.
        attempt <- fa.attempt
        endedAt <- Async[F].monotonic
        _ <- record(cluster, operation, outcomeOf(attempt), (endedAt - startedAt).toNanos / 1e9)
        result <- Async[F].fromEither(attempt)
      } yield result

    private def record(
        cluster: ClusterId,
        operation: String,
        outcome: UpstreamOutcome,
        seconds: Double
    ): F[Unit] =
      histogram.record(
        seconds,
        Attribute(MetricNames.Attr.Cluster, cluster.value),
        Attribute(MetricNames.Attr.Operation, operation),
        Attribute(MetricNames.Attr.Outcome, outcome.wire)
      )
  }
}
