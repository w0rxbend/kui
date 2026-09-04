package kui.topic.application

import cats.Monad
import cats.syntax.all.*

import kui.kernel.{ClusterId, TopicName}
import kui.topic.domain.{TopicAdmin, TopicConfigView, TopicError}

/** One topic's configuration, for the Settings tab.
  *
  * It is a thin use case on purpose: the port already refuses to turn an unreadable configuration into an
  * error, and the domain already derives a default from the broker's own synonym chain. What is left is the
  * one rule that belongs to neither of them — the entries are sorted by name, always — and the decision to
  * read live rather than from the snapshot.
  *
  * The tab is a reference list somebody scans alphabetically, and a broker-dependent order would make two
  * clusters look different for no reason at all. Sorting is applied here, once, rather than left to the
  * screen, so that every consumer of this use case — the HTTP layer today, an export tomorrow — gets the same
  * order without having to know it should.
  */
trait TopicConfigUseCase[F[_]] {

  /** One topic's configuration.
    *
    * The `Right` case is a [[TopicConfigView]] and not a bare list, because an empty list is a valid answer
    * that means one of two things the caller must be able to tell apart: the broker reports no configuration
    * for this topic, or the caller may see the topic but not its configuration. An empty table reads as the
    * first when it means the second.
    *
    * "Not permitted" is deliberately not a `TopicError.Forbidden`. An error would give the whole topic page a
    * 403, and the partitions the user is perfectly entitled to see would disappear along with the tab they
    * are not.
    */
  def config(cluster: ClusterId, topic: TopicName): F[Either[TopicError, TopicConfigView]]
}

object TopicConfigUseCase {

  def make[F[_]: Monad](admin: TopicAdmin[F]): TopicConfigUseCase[F] =
    new TopicConfigUseCase[F] {

      def config(cluster: ClusterId, topic: TopicName): F[Either[TopicError, TopicConfigView]] =
        admin.config(cluster, topic).map(_.map(sorted))
    }

  /** Sorts the permitted case and leaves the refusal alone.
    *
    * Pure and package-visible so that the ordering rule is asserted directly, without an effect and without a
    * fake in the way of the one line being tested.
    */
  private[application] def sorted(view: TopicConfigView): TopicConfigView = view match {
    case TopicConfigView.Entries(values) => TopicConfigView.of(values)
    case refusal @ TopicConfigView.NotPermitted(_) => refusal
  }
}
