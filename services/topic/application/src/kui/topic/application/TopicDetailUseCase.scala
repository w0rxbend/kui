package kui.topic.application

import java.time.Instant

import cats.effect.kernel.Concurrent
import cats.syntax.all.*

import kui.kernel.{ClusterId, TopicName}
import kui.topic.domain.{TopicAdmin, TopicDetail, TopicError, TopicSnapshot, TopicSummary}

/** A value, plus whether it was read now or recovered from the last snapshot.
  *
  * The edge turns this into `Section.Ok` or `Section.Stale`. Making it explicit here rather than letting the
  * `api` layer infer it from a timestamp stops that layer from guessing, and a guess there is how a page ends
  * up either claiming stale data is current or refusing to show data it has.
  */
enum Fresh[+A] {
  case Live[A](value: A) extends Fresh[A]
  case FromSnapshot[A](value: A, scrapedAt: Instant, reason: String) extends Fresh[A]

  /** The value itself, whichever way it was obtained.
    *
    * Named `get` and not `value`, because both cases already have a field of that name and a method of the
    * same name on the enum would clash with them.
    */
  def get: A = this match {
    case Live(value) => value
    case FromSnapshot(value, _, _) => value
  }

  def isLive: Boolean = this match {
    case Live(_) => true
    case FromSnapshot(_, _, _) => false
  }
}

trait TopicDetailUseCase[F[_]] {

  /** One topic's detail.
    *
    * Read **live** rather than from the snapshot, and this is the one place in M2 where a request costs an
    * admin call. A list is a thousand rows a user scans; a detail page is one topic a user is looking at
    * because something is wrong with it, and showing them a minute-old partition assignment during an
    * incident is the wrong trade. The cost is bounded by the number of humans looking at topic pages, not by
    * the number of topics.
    *
    * When the live read fails, the snapshot's row is the fallback, marked with the snapshot's own `scrapedAt`
    * and the reason the live read failed. A red page when KUI has a perfectly good answer thirty seconds old
    * is the behaviour ADR-032's stale rule exists to prevent.
    */
  def detail(cluster: ClusterId, topic: TopicName): F[Either[TopicError, Fresh[TopicDetail]]]
}

object TopicDetailUseCase {

  def make[F[_]: Concurrent](
      admin: TopicAdmin[F],
      snapshots: TopicSnapshots[F]
  ): TopicDetailUseCase[F] =
    new TopicDetailUseCase[F] {

      def detail(cluster: ClusterId, topic: TopicName): F[Either[TopicError, Fresh[TopicDetail]]] =
        admin.detail(cluster, topic).flatMap {
          case Right(live) => Fresh.Live(live).asRight[TopicError].pure[F].widen
          // A topic that does not exist and a cluster that does not exist are answers, not failures, and the
          // snapshot must not be allowed to overrule either of them. A topic deleted since the last scrape
          // would otherwise be resurrected by its own fallback, which is worse than a 404: the page would
          // show partitions for something that is gone.
          case Left(definitive: TopicError.NotFound) => definitive.asLeft[Fresh[TopicDetail]].pure[F].widen
          case Left(definitive: TopicError.ClusterNotFound) =>
            definitive.asLeft[Fresh[TopicDetail]].pure[F].widen
          case Left(failure) => fallback(cluster, topic, failure)
        }

      /** The snapshot's row for this topic, if there is a snapshot and it knows the topic. */
      private def fallback(
          cluster: ClusterId,
          topic: TopicName,
          failure: TopicError
      ): F[Either[TopicError, Fresh[TopicDetail]]] =
        snapshots.of(cluster).flatMap {
          case None => failure.asLeft[Fresh[TopicDetail]].pure[F].widen
          case Some(cell) =>
            cell.get.map { snapshot =>
              (snapshot.value, snapshot.scrapedAt) match {
                case (Some(taken), Some(at)) =>
                  rowOf(taken, topic) match {
                    case Some(row) => Right(Fresh.FromSnapshot(detailOf(row), at, failure.message))
                    // The snapshot is real and does not contain this topic. That is a 404 and not the live
                    // read's failure: KUI has evidence the topic is absent.
                    case None => Left(TopicError.NotFound(topic))
                  }
                case _ => Left(failure)
              }
            }
        }

      private def rowOf(snapshot: TopicSnapshot, topic: TopicName): Option[TopicSummary] = snapshot.get(topic)

      /** A detail page built from a list row.
        *
        * The partitions are genuinely unknown — the list snapshot holds counts, not partition assignments,
        * because ten thousand topics of fifty partitions each is half a million objects held to render six
        * columns. So the fallback page carries the summary and an empty partition table, and the screen shows
        * its stale badge over exactly what KUI knows rather than inventing rows.
        */
      private def detailOf(row: TopicSummary): TopicDetail =
        TopicDetail(summary = row, partitions = Nil, cleanupPolicy = None, segmentCount = None)
    }

  /** Whether a snapshot has anything worth falling back to.
    *
    * Pure and exposed so that the rule is asserted directly rather than through four effectful scenarios: a
    * fallback needs a value *and* the instant it was taken, and a snapshot that is still initialising has
    * neither.
    */
  def hasFallback[A](snapshot: kui.cache.Snapshot[A]): Boolean =
    snapshot.value.isDefined && snapshot.scrapedAt.isDefined
}
