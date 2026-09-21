package kui.metrics.infrastructure.prometheus

import cats.Applicative
import cats.effect.Async
import cats.effect.kernel.Outcome
import cats.effect.syntax.all.*
import cats.syntax.all.*
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.{Counter, Histogram, Meter, UpDownCounter}

import kui.kernel.ClusterId
import kui.observability.MetricNames

enum QueryOperation {
  case Instant, Range

  def wire: String = this match {
    case Instant => "instant"
    case Range => "range"
  }
}

enum QueryOutcome {
  case Success, Failure, Timeout, CircuitOpen, Cancelled

  def wire: String = this match {
    case Success => "success"
    case Failure => "failure"
    case Timeout => "timeout"
    case CircuitOpen => "circuit_open"
    case Cancelled => "cancelled"
  }
}

enum QueryLimit {
  case ResponseBytes, Series, Samples, Points, Labels, LabelBytes

  def wire: String = this match {
    case ResponseBytes => "response_bytes"
    case Series => "series"
    case Samples => "samples"
    case Points => "points"
    case Labels => "labels"
    case LabelBytes => "label_bytes"
  }
}

final case class PrometheusQueryContext(
    source: ClusterId,
    query: QueryId,
    operation: QueryOperation
)

trait PrometheusQueryMetrics[F[_]] {
  def observe[A](context: PrometheusQueryContext)(fa: F[A])(classify: A => QueryOutcome): F[A]

  def response(context: PrometheusQueryContext, bytes: Long, series: Long, samples: Long): F[Unit]

  def cache(
      context: PrometheusQueryContext,
      access: QueryCacheAccess,
      freshness: QueryCacheFreshness
  ): F[Unit]

  def limitRejected(context: PrometheusQueryContext, limit: QueryLimit): F[Unit]

  def diagnostics(context: PrometheusQueryContext, diagnostics: QueryDiagnostics): F[Unit]
}

object PrometheusQueryMetrics {
  val metricNames: List[String] = List(
    MetricNames.PrometheusQueryDuration,
    MetricNames.PrometheusQueryRequests,
    MetricNames.PrometheusResponseBytes,
    MetricNames.PrometheusResponseSeries,
    MetricNames.PrometheusResponseSamples,
    MetricNames.PrometheusQueryInFlight,
    MetricNames.PrometheusQueryCacheAccess,
    MetricNames.PrometheusQueryCoalesced,
    MetricNames.PrometheusQueryLimitRejected,
    MetricNames.PrometheusQueryDiagnostics
  )

  def noop[F[_]: Applicative]: PrometheusQueryMetrics[F] = new PrometheusQueryMetrics[F] {
    def observe[A](context: PrometheusQueryContext)(fa: F[A])(classify: A => QueryOutcome): F[A] = fa

    def response(context: PrometheusQueryContext, bytes: Long, series: Long, samples: Long): F[Unit] =
      Applicative[F].unit

    def cache(
        context: PrometheusQueryContext,
        access: QueryCacheAccess,
        freshness: QueryCacheFreshness
    ): F[Unit] = Applicative[F].unit

    def limitRejected(context: PrometheusQueryContext, limit: QueryLimit): F[Unit] = Applicative[F].unit

    def diagnostics(context: PrometheusQueryContext, diagnostics: QueryDiagnostics): F[Unit] =
      Applicative[F].unit
  }

  def otel4s[F[_]: Async](meter: Meter[F]): F[PrometheusQueryMetrics[F]] =
    for {
      duration <- meter
        .histogram[Double](MetricNames.PrometheusQueryDuration)
        .withUnit("s")
        .withDescription("End-to-end logical Prometheus query latency")
        .create
      requests <- counter(meter, MetricNames.PrometheusQueryRequests, "Logical Prometheus query calls")
      responseBytes <- histogram(
        meter,
        MetricNames.PrometheusResponseBytes,
        "Accepted Prometheus response bytes"
      )
      responseSeries <- histogram(
        meter,
        MetricNames.PrometheusResponseSeries,
        "Series in accepted Prometheus responses"
      )
      responseSamples <- histogram(
        meter,
        MetricNames.PrometheusResponseSamples,
        "Samples in accepted Prometheus responses"
      )
      inFlight <- meter
        .upDownCounter[Long](MetricNames.PrometheusQueryInFlight)
        .withDescription("Logical Prometheus query callers currently in flight")
        .create
      cacheAccess <- counter(meter, MetricNames.PrometheusQueryCacheAccess, "Prometheus query cache accesses")
      coalesced <- counter(meter, MetricNames.PrometheusQueryCoalesced, "Coalesced Prometheus query callers")
      rejected <- counter(
        meter,
        MetricNames.PrometheusQueryLimitRejected,
        "Prometheus response limit rejections"
      )
      diagnostics <- counter(
        meter,
        MetricNames.PrometheusQueryDiagnostics,
        "Prometheus warning and info entries"
      )
    } yield new Otel(
      duration,
      requests,
      responseBytes,
      responseSeries,
      responseSamples,
      inFlight,
      cacheAccess,
      coalesced,
      rejected,
      diagnostics
    )

  private def counter[F[_]](meter: Meter[F], name: String, description: String): F[Counter[F, Long]] =
    meter.counter[Long](name).withDescription(description).create

  private def histogram[F[_]](
      meter: Meter[F],
      name: String,
      description: String
  ): F[Histogram[F, Long]] =
    meter.histogram[Long](name).withDescription(description).create

  final private class Otel[F[_]: Async](
      duration: Histogram[F, Double],
      requests: Counter[F, Long],
      responseBytes: Histogram[F, Long],
      responseSeries: Histogram[F, Long],
      responseSamples: Histogram[F, Long],
      inFlight: UpDownCounter[F, Long],
      cacheAccess: Counter[F, Long],
      coalesced: Counter[F, Long],
      rejected: Counter[F, Long],
      diagnosticEntries: Counter[F, Long]
  ) extends PrometheusQueryMetrics[F] {

    def observe[A](context: PrometheusQueryContext)(fa: F[A])(classify: A => QueryOutcome): F[A] =
      Async[F].uncancelable { poll =>
        for {
          startedAt <- Async[F].monotonic
          _ <- inFlight.add(1L, baseAttributes(context)*)
          result <- poll(fa).guaranteeCase(outcome => complete(context, startedAt, outcome, classify))
        } yield result
      }

    def response(context: PrometheusQueryContext, bytes: Long, series: Long, samples: Long): F[Unit] = {
      val attributes = baseAttributes(context)
      responseBytes.record(bytes.max(0L), attributes*) *>
        responseSeries.record(series.max(0L), attributes*) *>
        responseSamples.record(samples.max(0L), attributes*)
    }

    def cache(
        context: PrometheusQueryContext,
        access: QueryCacheAccess,
        freshness: QueryCacheFreshness
    ): F[Unit] = {
      val attributes =
        baseAttributes(context) :+ Attribute(MetricNames.Attr.State, cacheState(access, freshness))
      cacheAccess.inc(attributes*) *>
        (if access == QueryCacheAccess.Coalesced then coalesced.inc(baseAttributes(context)*)
         else Async[F].unit)
    }

    def limitRejected(context: PrometheusQueryContext, limit: QueryLimit): F[Unit] =
      rejected.inc((baseAttributes(context) :+ Attribute(MetricNames.Attr.Limit, limit.wire))*)

    def diagnostics(context: PrometheusQueryContext, diagnostics: QueryDiagnostics): F[Unit] =
      recordDiagnostic(context, "warning", diagnostics.warningCount) *>
        recordDiagnostic(context, "info", diagnostics.infoCount)

    private def complete[A](
        context: PrometheusQueryContext,
        startedAt: scala.concurrent.duration.FiniteDuration,
        result: Outcome[F, Throwable, A],
        classify: A => QueryOutcome
    ): F[Unit] =
      for {
        endedAt <- Async[F].monotonic
        outcome <- result match {
          case Outcome.Succeeded(value) =>
            value.flatMap(result => Async[F].delay(classify(result)).handleError(_ => QueryOutcome.Failure))
          case Outcome.Errored(_) => QueryOutcome.Failure.pure[F]
          case Outcome.Canceled() => QueryOutcome.Cancelled.pure[F]
        }
        attributes = baseAttributes(context) :+ Attribute(MetricNames.Attr.Outcome, outcome.wire)
        _ <- inFlight.add(-1L, baseAttributes(context)*)
        _ <- duration.record((endedAt - startedAt).toNanos / 1e9, attributes*)
        _ <- requests.inc(attributes*)
      } yield ()

    private def recordDiagnostic(context: PrometheusQueryContext, kind: String, count: Int): F[Unit] =
      if count <= 0 then Async[F].unit
      else
        diagnosticEntries.add(
          count.toLong,
          (baseAttributes(context) :+ Attribute(MetricNames.Attr.Kind, kind))*
        )
  }

  private def baseAttributes(context: PrometheusQueryContext): List[Attribute[String]] = List(
    Attribute(MetricNames.Attr.Source, context.source.value),
    Attribute(MetricNames.Attr.Query, context.query.value),
    Attribute(MetricNames.Attr.Operation, context.operation.wire)
  )

  private def cacheState(access: QueryCacheAccess, freshness: QueryCacheFreshness): String = {
    val accessValue = access match {
      case QueryCacheAccess.Loaded => "loaded"
      case QueryCacheAccess.Hit => "hit"
      case QueryCacheAccess.Coalesced => "coalesced"
    }
    val freshnessValue = freshness match {
      case QueryCacheFreshness.Fresh => "fresh"
      case QueryCacheFreshness.Stale => "stale"
    }
    s"${accessValue}_$freshnessValue"
  }
}
