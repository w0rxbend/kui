package kui.filter

import cats.Applicative
import cats.effect.Async
import cats.syntax.all.*
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.{Counter, Meter}

import kui.observability.MetricNames

/** What the filter layer reports.
  *
  * An interface, like `CacheMetrics` and `SerdeMetrics`, so the engine records without importing
  * OpenTelemetry and a suite can assert "that timeout was counted" against a counting fake.
  *
  * `kui.filter.errors` is the same number the `consumed` stream event reports as `filterErrors`. That is
  * asserted rather than assumed: the number on the user's screen and the number on the operator's dashboard
  * disagreeing about the same browse is the kind of discrepancy that costs an afternoon.
  */
trait FilterMetrics[F[_]] {

  /** A compilation, by outcome: `success` or `failure`. */
  def compiled(outcome: String): F[Unit]

  /** A record the filter could not decide about, by kind: `runtime` or `timeout`. */
  def errored(kind: String): F[Unit]
}

object FilterMetrics {

  def noop[F[_]: Applicative]: FilterMetrics[F] = new FilterMetrics[F] {
    def compiled(outcome: String): F[Unit] = Applicative[F].unit
    def errored(kind: String): F[Unit] = Applicative[F].unit
  }

  def otel4s[F[_]: Async](meter: Meter[F]): F[FilterMetrics[F]] =
    for {
      compilations <- meter
        .counter[Long](MetricNames.FilterCompile)
        .withDescription("Smart-filter compilations, by outcome")
        .create
      errors <- meter
        .counter[Long](MetricNames.FilterErrors)
        .withDescription("Records a smart filter could not decide about, by kind")
        .create
    } yield new Otel[F](compilations, errors)

  final private class Otel[F[_]](compilations: Counter[F, Long], errors: Counter[F, Long])
      extends FilterMetrics[F] {

    def compiled(outcome: String): F[Unit] =
      compilations.inc(Attribute(MetricNames.Attr.Outcome, outcome))

    def errored(kind: String): F[Unit] =
      errors.inc(Attribute(MetricNames.Attr.Kind, kind))
  }
}
