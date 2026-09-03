package kui.kafka

import cats.effect.{Ref, Sync}
import cats.syntax.all.*

import kui.kernel.ClusterId

/** An `AdminMetrics` that records what it was asked to measure instead of exporting it.
  *
  * It lives in this module's test sources rather than in `libs/testkit` because `AdminMetrics` is a
  * `libs/kafka` type, and `libs/testkit` is on the classpath of modules that layering rule A10
  * forbids from seeing a Kafka client.
  */
final class FakeAdminMetrics[F[_]: Sync] private (
    recorded: Ref[F, List[FakeAdminMetrics.Entry]]
) extends AdminMetrics[F] {

  def timed[A](cluster: ClusterId, operation: String)(fa: F[A]): F[A] =
    for {
      attempt <- fa.attempt
      _ <- recorded.update(
        _ :+ FakeAdminMetrics.Entry(cluster, operation, attempt.isRight)
      )
      result <- Sync[F].fromEither(attempt)
    } yield result

  def entries: F[List[FakeAdminMetrics.Entry]] = recorded.get
}

object FakeAdminMetrics {

  final case class Entry(cluster: ClusterId, operation: String, succeeded: Boolean)

  def create[F[_]: Sync]: F[FakeAdminMetrics[F]] =
    Ref.of[F, List[Entry]](Nil).map(new FakeAdminMetrics[F](_))
}
