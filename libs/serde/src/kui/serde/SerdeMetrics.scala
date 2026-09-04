package kui.serde

import cats.Applicative
import cats.effect.Async
import cats.syntax.all.*
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.{Counter, Meter}

import kui.kernel.TopicName
import kui.observability.MetricNames

/** What the serde layer reports about itself.
  *
  * An interface for the same reason `CacheMetrics` is one: the serdes record without importing OpenTelemetry,
  * and a suite asserts "the failure was counted" with a counting fake rather than an SDK and an exporter.
  *
  * There is deliberately **no log line per decode failure**. A topic whose records KUI cannot read produces
  * one failure per record, and at ten thousand records that is ten thousand log lines describing the same
  * misconfiguration. The failure travels on the record instead, where the user who can fix it sees it, and
  * the counter is what tells the operator it is happening at all.
  */
trait SerdeMetrics[F[_]] {

  /** One record's intended decoder did not work and the fallback was used. */
  def deserializeFailed(serde: SerdeName, target: Target, topic: TopicName): F[Unit]

  /** A payload nobody configured a serde for was decoded by an auto-detected one. */
  def autodetected(serde: SerdeName): F[Unit]

  /** Turning what a user typed into bytes failed.
    *
    * `reason` separates the user's typo from KUI's outage: `validation`, `registry` or `encode`. Without it
    * an operator seeing this counter climb cannot tell whether to look at their registry or at nothing.
    */
  def serializeFailed(serde: SerdeName, topic: TopicName, reason: SerializeFailureReason): F[Unit]
}

/** Why a serialize failed, as the value of the `reason` attribute. */
enum SerializeFailureReason(val label: String) {

  /** What the user typed does not satisfy the schema. Their problem, and the message names the field. */
  case Validation extends SerializeFailureReason("validation")

  /** The Schema Registry could not be reached or answered unusably. KUI's problem. */
  case Registry extends SerializeFailureReason("registry")

  /** The value was valid and the encoder still could not produce bytes. Nobody's problem yet; a bug. */
  case Encode extends SerializeFailureReason("encode")
}

object SerializeFailureReason {
  given CanEqual[SerializeFailureReason, SerializeFailureReason] = CanEqual.derived
}

object SerdeMetrics {

  def noop[F[_]: Applicative]: SerdeMetrics[F] = new SerdeMetrics[F] {
    def deserializeFailed(serde: SerdeName, target: Target, topic: TopicName): F[Unit] = Applicative[F].unit
    def autodetected(serde: SerdeName): F[Unit] = Applicative[F].unit
    def serializeFailed(serde: SerdeName, topic: TopicName, reason: SerializeFailureReason): F[Unit] =
      Applicative[F].unit
  }

  def otel4s[F[_]: Async](meter: Meter[F]): F[SerdeMetrics[F]] =
    for {
      failures <- meter
        .counter[Long](MetricNames.SerdeDeserializeFailures)
        .withDescription("Records whose intended serde could not decode them, so the fallback was used")
        .create
      detected <- meter
        .counter[Long](MetricNames.SerdeAutodetected)
        .withDescription("Payloads decoded by an auto-detected serde rather than a configured one")
        .create
      serializeFailures <- meter
        .counter[Long](MetricNames.SerdeSerializeFailures)
        .withDescription("Produce payloads that could not be turned into bytes")
        .create
    } yield new Otel[F](failures, detected, serializeFailures)

  final private class Otel[F[_]](
      failures: Counter[F, Long],
      detected: Counter[F, Long],
      serializeFailures: Counter[F, Long]
  ) extends SerdeMetrics[F] {

    def deserializeFailed(serde: SerdeName, target: Target, topic: TopicName): F[Unit] =
      failures.inc(
        Attribute(MetricNames.Attr.Serde, serde.value),
        Attribute(MetricNames.Attr.Target, target.label),
        Attribute(MetricNames.Attr.Topic, topic.value)
      )

    def autodetected(serde: SerdeName): F[Unit] =
      detected.inc(Attribute(MetricNames.Attr.Serde, serde.value))

    def serializeFailed(serde: SerdeName, topic: TopicName, reason: SerializeFailureReason): F[Unit] =
      serializeFailures.inc(
        Attribute(MetricNames.Attr.Serde, serde.value),
        Attribute(MetricNames.Attr.Topic, topic.value),
        Attribute(MetricNames.Attr.Reason, reason.label)
      )
  }
}
