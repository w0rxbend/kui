package kui.message.infrastructure

import cats.Applicative
import cats.effect.Async
import cats.syntax.all.*
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.{Counter, Meter}

import kui.kernel.serde.Target
import kui.kernel.{ClusterId, TopicName}
import kui.observability.MetricNames

/** What the masking wiring reports: `kui.masking.applied` (DM-001, ADR-023).
  *
  * An interface rather than a `Meter` at the call site, for the reason `FilterMetrics` and `CacheMetrics`
  * are: the adapter records without importing OpenTelemetry, and a suite can assert "that browse was masked"
  * against a counting fake instead of against an exporter.
  *
  * ==What is counted, and what deliberately is not==
  *
  * A **read** on which masking applied — one increment per read per target, decided once before the first
  * record is fetched — and never a count of fields or records masked. `MetricNames.MaskingApplied` says why
  * and it is the sharper half of this whole feature: the number of fields a rule touched is a function of the
  * payload, so a per-field counter would publish the shape of protected data onto a dashboard that is
  * routinely less protected than the data itself. A topic whose card-number field is present on 3% of records
  * would say so, in a series anyone with Prometheus access can read.
  *
  * The name was declared in wave 1 and had no writer until this wiring. It is the first one.
  */
trait MaskingMetrics[F[_]] {

  /** One read of `topic` on `cluster` whose `target` half had at least one masking rule in force. */
  def applied(cluster: ClusterId, topic: TopicName, target: Target): F[Unit]
}

object MaskingMetrics {

  def noop[F[_]: Applicative]: MaskingMetrics[F] =
    (_, _, _) => Applicative[F].unit

  def otel4s[F[_]: Async](meter: Meter[F]): F[MaskingMetrics[F]] =
    meter
      .counter[Long](MetricNames.MaskingApplied)
      .withDescription("Reads on which a masking rule was in force, by cluster, topic and target")
      .create
      .map(new Otel[F](_))

  final private class Otel[F[_]](applications: Counter[F, Long]) extends MaskingMetrics[F] {

    def applied(cluster: ClusterId, topic: TopicName, target: Target): F[Unit] =
      applications.inc(
        Attribute(MetricNames.Attr.Cluster, cluster.value),
        Attribute(MetricNames.Attr.Topic, topic.value),
        Attribute(MetricNames.Attr.Target, target.label)
      )
  }
}
