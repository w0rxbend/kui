package kui.consumer.infrastructure

import java.time.Instant

import cats.effect.IO
import cats.effect.kernel.Ref

import kui.consumer.domain.{OffsetWindow, ResetScope}
import kui.kafka.admin.*
import kui.kafka.{BatchResult, SkipReason}
import kui.kernel.cluster.{AdminTuning, BootstrapServers, ClientProperties, ClusterConnection, ClusterSecurity}
import kui.kernel.error.{InfrastructureError, KuiError}
import kui.kernel.group.{GroupProtocol, GroupState, LagAnomaly}
import kui.kernel.{ClusterId, GroupId, Offset, PartitionId, TopicName, TopicPartition}
import kui.testkit.KuiIOSuite
import kui.testkit.fakes.FakeStructuredLogger

/** The seam: `libs/kafka`'s results on one side, the consumer domain on the other.
  *
  * Both sides are unit-tested on their own, which is exactly the condition under which this seam breaks
  * silently. The assertions here are about the composition — that a group's commits and the log ends its lag
  * is measured against come from one pass, that a leaderless partition arrives as `NO_LEADER` and not as a
  * zero, and that a cluster which answers half the calls still produces a page.
  */
final class KafkaGroupAdminPortSuite extends KuiIOSuite {

  private val connection: ClusterConnection = ClusterConnection(
    id = ClusterId.unsafe("prod"),
    bootstrapServers = BootstrapServers.unsafe("broker:9092"),
    security = ClusterSecurity.Plaintext,
    overrides = ClientProperties.empty,
    admin = AdminTuning.default
  )

  private val orders: GroupId = GroupId.unsafe("orders-consumer")
  private val topic: TopicName = TopicName.unsafe("orders")

  private def partition(n: Int): TopicPartition = TopicPartition(topic, PartitionId.unsafe(n))

  private def member(held: Set[Int]): GroupMember =
    GroupMember.of("m-1", None, "client", "10.0.0.7", MemberAssignment(held.map(partition)), None)

  private def description(members: List[GroupMember], state: GroupState = GroupState.Stable): GroupDescription =
    GroupDescription(
      groupId = orders,
      isSimple = false,
      state = state,
      protocol = GroupProtocol.Classic,
      partitionAssignor = "range",
      members = members,
      coordinator = Some(GroupCoordinator(kui.kernel.BrokerId.unsafe(1), "broker-1", 9092)),
      authorizedOperations = None
    )

  /** A `GroupAdmin` whose every answer is a field, so a test says what the cluster said. */
  final private class FakeAdmin(
      val listing: Ref[IO, Either[KuiError, GroupListingResult]],
      val described: Ref[IO, Either[KuiError, BatchResult[GroupId, GroupDescription]]],
      val committed: Ref[IO, Either[KuiError, BatchResult[GroupId, List[CommittedOffset]]]],
      val altered: Ref[IO, List[(GroupId, Map[TopicPartition, Offset])]]
  ) extends GroupAdmin[IO] {

    def listGroups(conn: ClusterConnection, states: Set[GroupState]) = listing.get

    def describeGroups(conn: ClusterConnection, ids: List[GroupId], includeAuthorizedOperations: Boolean) =
      described.get

    def committedOffsets(
        conn: ClusterConnection,
        groups: List[GroupId],
        partitions: Option[Set[TopicPartition]],
        requireStable: Boolean
    ) = committed.get

    def alterOffsets(conn: ClusterConnection, group: GroupId, offsets: Map[TopicPartition, Offset]) =
      altered.update(_ :+ (group -> offsets)).as(Right(()))

    def deleteOffsets(conn: ClusterConnection, group: GroupId, partitions: Set[TopicPartition]) =
      IO.pure(Right(()))

    def deleteGroups(conn: ClusterConnection, ids: List[GroupId]) =
      IO.pure(Right(BatchResult.complete(ids.map(_ -> ()).toMap)))
  }

  final private class FakeOffsets(
      ends: Either[KuiError, BatchResult[TopicPartition, Offset]],
      begins: Either[KuiError, BatchResult[TopicPartition, Offset]],
      offline: Set[TopicPartition]
  ) extends OffsetLookup[IO] {
    def endOffsets(conn: ClusterConnection, partitions: Set[TopicPartition]) = IO.pure(ends)
    def beginningOffsets(conn: ClusterConnection, partitions: Set[TopicPartition]) = IO.pure(begins)
    def offsetsForTimes(conn: ClusterConnection, timestamps: Map[TopicPartition, Long]) =
      IO.pure(Right(BatchResult.complete(timestamps.map((p, _) => p -> Option(Offset.unsafe(7L))))))
    def leaderless(conn: ClusterConnection, partitions: Set[TopicPartition]) = IO.pure(Right(offline))
  }

  private def rig(
      described: Either[KuiError, BatchResult[GroupId, GroupDescription]] =
        Right(BatchResult.complete(Map(orders -> description(List(member(Set(0, 1))))))),
      committed: Either[KuiError, BatchResult[GroupId, List[CommittedOffset]]] =
        Right(
          BatchResult.complete(
            Map(orders -> List(CommittedOffset(TopicPartition(TopicName.unsafe("orders"), PartitionId.unsafe(0)), Offset.unsafe(90L), None, None)))
          )
        ),
      ends: Either[KuiError, BatchResult[TopicPartition, Offset]] =
        Right(BatchResult.complete(Map(TopicPartition(TopicName.unsafe("orders"), PartitionId.unsafe(0)) -> Offset.unsafe(100L)))),
      endsSkipped: Map[TopicPartition, SkipReason] = Map.empty,
      offline: Set[TopicPartition] = Set.empty
  ): IO[(kui.consumer.domain.GroupAdminPort[IO], FakeAdmin)] =
    for {
      listing <- Ref.of[IO, Either[KuiError, GroupListingResult]](
        Right(GroupListingResult.complete(List(GroupListing(orders, isSimple = false, GroupState.Stable, GroupProtocol.Classic))))
      )
      describedRef <- Ref.of[IO, Either[KuiError, BatchResult[GroupId, GroupDescription]]](described)
      committedRef <- Ref.of[IO, Either[KuiError, BatchResult[GroupId, List[CommittedOffset]]]](committed)
      altered <- Ref.of[IO, List[(GroupId, Map[TopicPartition, Offset])]](Nil)
      admin = new FakeAdmin(listing, describedRef, committedRef, altered)
      lookup = new FakeOffsets(ends.map(batch => BatchResult(batch.values, endsSkipped)), ends, offline)
      logger <- FakeStructuredLogger[IO]
    } yield (KafkaGroupAdminPort.make[IO](admin, lookup, connection, logger), admin)

  test("a described group carries its members, its commits and the lag between them") {
    for {
      rigged <- rig()
      (port, _) = rigged
      described <- port.describe(List(orders))
    } yield described match {
      case Right(groups) =>
        val group = groups(orders)
        assertEquals(group.members.map(_.memberId), List("m-1"))
        assertEquals(group.subscriptions.map(_.topic), List(topic))
        assertEquals(group.lagTotal.value, Some(10L))
        assert(group.completeness.isComplete)
      case Left(error) => fail(s"describe failed: $error")
    }
  }

  test("a partition a member holds but has never committed on still has a row, with NO_COMMIT") {
    for {
      rigged <- rig()
      (port, _) = rigged
      described <- port.describe(List(orders))
    } yield {
      val states = described.map(_(orders).partitions).getOrElse(Nil)
      assertEquals(states.size, 2)
      val uncommitted = states.find(_.partition.value == 1).getOrElse(fail("partition 1 is missing"))
      assertEquals(uncommitted.lag.anomalies, Set(LagAnomaly.NoCommit))
      assertEquals(uncommitted.lag.value, None)
    }
  }

  test("a leaderless partition is NO_LEADER and contributes nothing, and the group says it was excluded") {
    for {
      rigged <- rig(endsSkipped = Map(partition(0) -> SkipReason.NoLeader))
      (port, _) = rigged
      described <- port.describe(List(orders))
    } yield {
      val group = described.getOrElse(fail("describe failed"))(orders)
      val offline = group.partitions.find(_.partition.value == 0).getOrElse(fail("partition 0 is missing"))

      assertEquals(offline.lag.anomalies, Set(LagAnomaly.NoLeader))
      assertEquals(group.lagTotal.value, None)
      assert(group.completeness.excludedPartitions.contains(partition(0)))
    }
  }

  test("committed offsets KUI may not read degrade the page rather than failing it") {
    for {
      rigged <- rig(committed = Left(kui.kernel.error.ApplicationError.Forbidden("no READ on the topic")))
      (port, _) = rigged
      described <- port.describe(List(orders))
    } yield {
      val group = described.getOrElse(fail("describe failed"))(orders)
      assert(!group.completeness.committedOffsetsKnown)
      assertEquals(group.members.size, 1, clue = "the members were lost with the offsets")
    }
  }

  test("log ends KUI cannot read leave every lag undefined, and say so") {
    for {
      rigged <- rig(ends = Left(InfrastructureError.Unreachable("kafka", "no leader anywhere")))
      (port, _) = rigged
      described <- port.describe(List(orders))
    } yield {
      val group = described.getOrElse(fail("describe failed"))(orders)
      assert(!group.completeness.endOffsetsKnown)
      assertEquals(group.lagTotal.value, None)
    }
  }

  test("existence is answered from the listing, so a group that is not listed does not exist") {
    for {
      rigged <- rig()
      (port, admin) = rigged
      present <- port.exists(orders)
      _ <- admin.listing.set(Right(GroupListingResult.complete(Nil)))
      absent <- port.exists(orders)
    } yield {
      assertEquals(present, Right(true))
      assertEquals(absent, Right(false))
    }
  }

  test("a fabricated dead group still exists as a describe, and still does not exist as a listing") {
    for {
      rigged <- rig(described = Right(BatchResult.complete(Map(orders -> GroupDescription.dead(orders)))))
      (port, admin) = rigged
      _ <- admin.listing.set(Right(GroupListingResult.complete(Nil)))
      described <- port.describe(List(orders))
      exists <- port.exists(orders)
    } yield {
      // This pair is the whole reason existence is checked by listing: the describe is perfectly
      // happy to answer for a group that is not there.
      assertEquals(described.map(_(orders).state), Right(GroupState.Dead))
      assertEquals(exists, Right(false))
    }
  }

  test("an offset window gathers begin, end, committed and the offline set in one pass") {
    for {
      rigged <- rig(offline = Set(partition(1)))
      (port, _) = rigged
      window <- port.offsetWindow(orders, ResetScope(topic, Set(partition(0), partition(1))), None)
    } yield window match {
      case Right(OffsetWindow(begin, end, committed, _, leaderless)) =>
        assertEquals(end.get(partition(0)).map(_.value), Some(100L))
        assertEquals(begin.get(partition(0)).map(_.value), Some(100L))
        assertEquals(committed.get(partition(0)).map(_.value), Some(90L))
        assertEquals(leaderless, Set(partition(1)))
      case Left(error) => fail(s"the window failed: $error")
    }
  }

  test("a timestamp window resolves offsets only for the partitions that have a leader") {
    for {
      rigged <- rig(offline = Set(partition(1)))
      (port, _) = rigged
      window <- port.offsetWindow(
        orders,
        ResetScope(topic, Set(partition(0), partition(1))),
        Some(Instant.parse("2026-01-01T00:00:00Z"))
      )
    } yield assertEquals(window.map(_.atTimestamp.keySet), Right(Set(partition(0))))
  }

  test("applying offsets passes them through unchanged") {
    for {
      rigged <- rig()
      (port, admin) = rigged
      _ <- port.applyOffsets(orders, Map(partition(0) -> Offset.unsafe(5L)))
      written <- admin.altered.get
    } yield assertEquals(written.map((g, offsets) => g -> offsets.values.map(_.value).toList), List(orders -> List(5L)))
  }
}
