package kui.topic.application

import java.time.Instant

import cats.effect.IO
import cats.effect.kernel.{Deferred, Ref}
import fs2.Stream

import kui.cache.{Snapshot, SnapshotCell}
import kui.kernel.error.{ApplicationError, ErrorCode, KuiError}
import kui.kernel.{ClusterId, TopicName}
import kui.security.Principal
import kui.security.audit.{AuditSink, MutationOutcome, MutationRecord}
import kui.testkit.KuiIOSuite
import kui.testkit.fakes.FakeStructuredLogger
import kui.topic.domain.{ClusterProfiles, ClusterRef, TopicError, TopicMutation, TopicSnapshot}

/** What the audit trail is allowed to claim about a topic delete nobody waited for.
  *
  * `MutationOutcome.Unknown`'s scaladoc in `libs/security-core` states the rule: "Kafka gives no guarantee
  * that it was *not* applied, so a record claiming either would be a lie." This guard's own comment repeated
  * the argument and then wrote `Failed`, which is exactly such a claim — it tells an operator a topic delete
  * did not happen when it may well have, and a `deleteTopics` that reached the controller before the request
  * was abandoned is not undone by KUI losing interest. `services/consumer`'s copy has always written
  * `Unknown`; this one never did, and turning the arm into `Succeeded` left `./mill services.topic.__.test`
  * at 287/287 green.
  */
final class MutationAuditSuite extends KuiIOSuite {

  private val cluster: ClusterId = ClusterId.unsafe("local")
  private val orders: TopicName = TopicName.unsafe("orders.v1")
  private val at: Instant = Instant.parse("2026-09-06T10:00:00Z")

  private val Caller: Principal = Principal.Anonymous

  private def profiles(readOnly: Boolean): ClusterProfiles[IO] =
    new ClusterProfiles[IO] {
      def all: IO[List[ClusterRef]] = IO.pure(List(ClusterRef(cluster, "Local", readOnly)))
      def get(id: ClusterId): IO[Option[ClusterRef]] = all.map(_.find(_.id == id))
      def onChange(handler: Set[ClusterId] => IO[Unit]): IO[IO[Unit]] = IO.pure(IO.unit)
    }

  private def snapshots(refreshes: Ref[IO, List[ClusterId]]): TopicSnapshots[IO] =
    new TopicSnapshots[IO] {
      def of(id: ClusterId): IO[Option[SnapshotCell[IO, TopicSnapshot]]] =
        IO.pure(Some(new SnapshotCell[IO, TopicSnapshot] {
          private val snapshot = Snapshot.online(TopicSnapshot.empty(at), at)
          def get: IO[Snapshot[TopicSnapshot]] = IO.pure(snapshot)
          def refresh: IO[Snapshot[TopicSnapshot]] = IO.pure(snapshot)
          def invalidate: IO[Snapshot[TopicSnapshot]] = IO.pure(snapshot)
          def updates: Stream[IO, Snapshot[TopicSnapshot]] = Stream.emit(snapshot)
        }))

      def requestRefresh(id: ClusterId): IO[Boolean] = refreshes.update(_ :+ id).as(true)
    }

  private def toKui(error: TopicError): KuiError = error match {
    case TopicError.NotFound(topic) =>
      ApplicationError.NotFound("topic", topic.value, ErrorCode.TopicNotFound)
    case other => ApplicationError.Refused(ErrorCode.InvalidState, other.toString)
  }

  private case class Rig(
      guard: MutationGuard[IO],
      records: Ref[IO, List[MutationRecord]],
      refreshes: Ref[IO, List[ClusterId]]
  )

  private def rig(readOnly: Boolean = false): IO[Rig] =
    for {
      logger <- FakeStructuredLogger[IO]
      records <- Ref.of[IO, List[MutationRecord]](Nil)
      refreshes <- Ref.of[IO, List[ClusterId]](Nil)
      sink = new AuditSink[IO] { def record(entry: MutationRecord): IO[Unit] = records.update(_ :+ entry) }
      guard = MutationGuard.make[IO](profiles(readOnly), snapshots(refreshes), sink, logger, toKui)
    } yield Rig(guard, records, refreshes)

  test("a cancelled mutation is recorded as unknown, and never as a success or a failure") {
    for {
      built <- rig()
      started <- Deferred[IO, Unit]
      running <- built.guard
        .guard(Caller, cluster, TopicMutation.Delete, orders.value)(
          started.complete(()) >> IO.never[Either[TopicError, Unit]]
        )
        .start
      _ <- started.get
      _ <- running.cancel
      records <- built.records.get
      refreshed <- built.refreshes.get
    } yield {
      assertEquals(records.map(_.outcome), List(MutationOutcome.Unknown))
      assertEquals(
        records.head.detail.get("reason"),
        Some("the operation was cancelled after the request was sent")
      )
      // And the snapshot is not re-scraped: a refresh is the success path's, and asking for one here
      // would claim the same thing the outcome refuses to claim.
      assertEquals(refreshed, Nil)
    }
  }

  test("a mutation whose admin client raised is recorded as failed, because KUI knows it did not complete") {
    for {
      built <- rig()
      result <- built.guard
        .guard(Caller, cluster, TopicMutation.Delete, orders.value)(
          IO.raiseError[Either[TopicError, Unit]](new RuntimeException("the admin client threw"))
        )
        .attempt
      records <- built.records.get
    } yield {
      assert(result.isLeft, "an operation that raised must not be reported as an answer")
      assertEquals(records.map(_.outcome), List(MutationOutcome.Failed))
    }
  }

  test("a mutation that succeeded is recorded as succeeded and asks for a re-scrape") {
    for {
      built <- rig()
      result <- built.guard.guard(Caller, cluster, TopicMutation.Create, orders.value)(
        IO.pure(Right(()): Either[TopicError, Unit])
      )
      records <- built.records.get
      refreshed <- built.refreshes.get
    } yield {
      assertEquals(result, Right(()))
      assertEquals(records.map(_.outcome), List(MutationOutcome.Succeeded))
      assertEquals(refreshed, List(cluster))
    }
  }

  test("a read-only cluster is refused and recorded as refused, without the operation running") {
    for {
      built <- rig(readOnly = true)
      result <- built.guard.guard(Caller, cluster, TopicMutation.Delete, orders.value)(
        IO.raiseError[Either[TopicError, Unit]](new AssertionError("the operation must not run"))
      )
      records <- built.records.get
    } yield {
      assertEquals(result.left.map(_.code), Left(ErrorCode.ReadOnly))
      assertEquals(records.map(_.outcome), List(MutationOutcome.Refused))
    }
  }
}
