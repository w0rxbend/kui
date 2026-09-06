package kui.topic.infrastructure

import java.time.Instant

import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}

import cats.Applicative
import cats.effect.kernel.{Clock, Temporal}
import cats.effect.std.Supervisor
import cats.effect.{IO, Ref}
import cats.syntax.all.*

import kui.kernel.{ClusterId, TopicName}
import kui.testkit.KuiIOSuite
import kui.topic.domain.{
  ScrapeResult,
  TopicAdmin,
  TopicConfigView,
  TopicDetail,
  TopicError,
  TopicSnapshot,
  TopicSummary
}

/** The threading of one scrape into the next.
  *
  * `TopicSnapshot.of` is tested on its own and takes the predecessor as an argument, so its suite can only
  * assert the arithmetic once somebody has chosen what to hand it. Choosing is this file's job, and the rule
  * `scrape`'s own comment states — "only a scrape that produced a snapshot becomes the next one's
  * predecessor" — is what makes the produce-rate column correct after an outage rather than only after a
  * restart. Before this suite, `grep -rn LiveTopicSnapshots --include=*.scala` found the object and
  * `TopicWiring` and nothing else.
  */
final class LiveTopicSnapshotsSuite extends KuiIOSuite {

  private val cluster = ClusterId.unsafe("local")
  private val orders = TopicName.unsafe("orders")
  private val start = Instant.parse("2026-09-06T09:00:00Z")

  /** A `TopicAdmin` that answers `scrape` from a script, one entry per call. */
  private final class ScriptedAdmin(remaining: Ref[IO, List[Either[TopicError, ScrapeResult]]])
      extends TopicAdmin[IO] {

    def scrape(cluster: ClusterId): IO[Either[TopicError, ScrapeResult]] =
      remaining.modify {
        case head :: tail => (tail, head)
        case Nil => (Nil, Left(TopicError.Unreachable("the script ran out", retryable = false)))
      }

    def detail(cluster: ClusterId, topic: TopicName): IO[Either[TopicError, TopicDetail]] =
      IO.raiseError(new UnsupportedOperationException("detail"))

    def config(cluster: ClusterId, topic: TopicName): IO[Either[TopicError, TopicConfigView]] =
      IO.raiseError(new UnsupportedOperationException("config"))
  }

  /** A clock the test moves by hand.
    *
    * Handed to `scrape` explicitly rather than through `TestControl`, which would be a new dependency on a
    * module this packet does not own. The instants are what the produce rate is divided by, so a rate is
    * asserted as a number here instead of merely as "present".
    */
  private final class StoppedClock(at: Ref[IO, Instant]) extends Clock[IO] {
    def applicative: Applicative[IO] = Applicative[IO]
    def monotonic: IO[FiniteDuration] = realTime
    def realTime: IO[FiniteDuration] = at.get.map(now => FiniteDuration(now.toEpochMilli, MILLISECONDS))
  }

  /** One row whose logs have been written as far as `endOffset`. The produce rate differences these. */
  private def scraped(endOffset: Long): Either[TopicError, ScrapeResult] =
    Right(
      ScrapeResult(
        List(
          TopicSummary(
            name = orders,
            isInternal = false,
            partitionCount = 1,
            replicationFactor = Some(1),
            outOfSyncReplicas = 0,
            offlinePartitions = 0,
            messageCount = Some(endOffset),
            sizeBytes = Some(endOffset * 100L),
            endOffsetTotal = Some(endOffset)
          )
        ),
        Map.empty
      )
    )

  private def rateOf(snapshot: TopicSnapshot): Option[Double] = snapshot.get(orders).flatMap(_.produceRate)

  /** Runs the script through `scrape`, ten seconds apart, keeping whatever each pass produced.
    *
    * A scrape that failed raises a `SnapshotLoadFailure`, which is what the cell would catch, so it is
    * caught here the same way and appears as a `None` in the result.
    */
  private def scrapes(script: List[Either[TopicError, ScrapeResult]]): IO[List[Option[TopicSnapshot]]] =
    for {
      remaining <- Ref.of[IO, List[Either[TopicError, ScrapeResult]]](script)
      previous <- Ref.of[IO, Option[TopicSnapshot]](None)
      now <- Ref.of[IO, Instant](start)
      admin = new ScriptedAdmin(remaining)
      clock = new StoppedClock(now)
      taken <- script.traverse(_ =>
        LiveTopicSnapshots
          .scrape[IO](cluster, admin, previous)(using Temporal[IO], clock)
          .attempt
          .map(_.toOption) <* now.update(_.plusSeconds(10L))
      )
    } yield taken

  test("theFirstScrapeHasNoPredecessorAndClaimsNoRate") {
    scrapes(List(scraped(100L))).map(taken => assertEquals(taken.flatten.map(rateOf), List(None)))
  }

  test("theSecondScrapeIsDifferencedAgainstTheFirst") {
    scrapes(List(scraped(100L), scraped(200L))).map { taken =>
      // A hundred records over the ten seconds between the two scrapes.
      assertEquals(taken.flatten.map(rateOf), List(None, Some(10.0d)))
    }
  }

  test("aFailedScrapeDoesNotBecomeTheNextOnesPredecessor") {
    // The rule the `Ref` exists for. Had the failed pass cleared the predecessor, the third scrape would
    // have had nothing to subtract from and the column would go blank after every outage rather than after
    // every restart. The surviving predecessor is the first scrape, twenty seconds back, so two hundred
    // records over twenty seconds is ten a second — measured across the gap, which is how long it was.
    val script = List(scraped(100L), Left(TopicError.Unreachable("kafka", retryable = true)), scraped(300L))

    scrapes(script).map { taken =>
      assertEquals(taken.map(_.isDefined), List(true, false, true))
      assertEquals(taken.flatten.map(rateOf), List(None, Some(10.0d)))
    }
  }

  test("aClusterNothingWasBuiltForIsUnknownRatherThanEmpty") {
    // `None` and not an empty snapshot: an empty topic list reads as "this cluster has no topics", which is
    // a different and much more alarming statement than "KUI has never heard of this cluster".
    Supervisor[IO].use { supervisor =>
      val registry = LiveTopicSnapshots.make[IO](Map.empty, supervisor)

      for {
        cell <- registry.of(cluster)
        refreshed <- registry.requestRefresh(cluster)
      } yield {
        assert(cell.isEmpty, "a cluster with no cell has no snapshot")
        assert(!refreshed, "a cluster with no cell cannot be refreshed")
      }
    }
  }
}
