package kui.consumer.application

import java.time.Instant

import cats.effect.IO
import cats.effect.kernel.Ref

import kui.consumer.domain.*
import kui.consumer.domain.fixtures.GroupFixtures
import kui.kernel.error.{ApplicationError, ErrorCode, KuiError}
import kui.kernel.group.GroupState
import kui.kernel.{GroupId, Offset, Secret, TopicPartition}
import kui.testkit.KuiIOSuite
import kui.testkit.fakes.FakeStructuredLogger

/** The first destructive operation in the product, and the three substitutes it ships with (ADR-047).
  *
  * Read-only refusal before any Kafka client is touched, an audit record for every attempt including the
  * refused ones, and a plan token that makes "apply" mean "apply exactly what was shown".
  */
final class MutationSuite extends KuiIOSuite {

  private val group: GroupId = GroupId.unsafe("orders-consumer")

  private val emptyGroup: ConsumerGroup =
    GroupFixtures.group(id = group.value, state = GroupState.Empty, partitions = List(GroupFixtures.state(0, Some(40L))))

  private val liveGroup: ConsumerGroup =
    GroupFixtures.group(
      id = group.value,
      state = GroupState.Stable,
      members = List(GroupFixtures.member("m-1", Set(0))),
      partitions = List(GroupFixtures.state(0, Some(40L)))
    )

  private val scope: ResetScope = ResetScope(GroupFixtures.Orders, Set(GroupFixtures.partition(0)))

  private val window: OffsetWindow = OffsetWindow(
    begin = Map(GroupFixtures.partition(0) -> Offset.unsafe(0L)),
    end = Map(GroupFixtures.partition(0) -> Offset.unsafe(100L)),
    committed = Map(GroupFixtures.partition(0) -> Offset.unsafe(40L)),
    atTimestamp = Map.empty,
    leaderless = Set.empty
  )

  private def portState(described: ConsumerGroup): ConsumerRig.PortState =
    ConsumerRig.PortState.Empty.copy(
      listing = Right(ConsumerRig.listingOf(List(described))),
      described = Right(Map(described.groupId -> described)),
      window = Right(window)
    )

  private val tokens: PlanToken[IO] = PlanToken.make[IO](Secret("a-test-key".getBytes("UTF-8")))

  /** A guard over a snapshots component that records the invalidations it was asked for. */
  private def rig(
      described: ConsumerGroup,
      readOnly: Boolean = false
  ): IO[(ConsumerRig.FakePort, ConsumerRig.RecordingAudit, MutationGuard[IO], Ref[IO, List[String]])] =
    for {
      port <- ConsumerRig.port(portState(described))
      profiles <- ConsumerRig.profiles(readOnly)
      audit <- ConsumerRig.audit
      logger <- FakeStructuredLogger[IO]
      invalidations <- Ref.of[IO, List[String]](Nil)
      snapshots = new GroupSnapshots[IO] {
        def of(cluster: kui.kernel.ClusterId) = IO.pure(None)
        def all = IO.pure(Nil)
        def previousOf(cluster: kui.kernel.ClusterId) = IO.pure(None)
        def requestRefresh(cluster: kui.kernel.ClusterId) = IO.pure(false)
        def invalidate(cluster: kui.kernel.ClusterId, reason: String) = invalidations.update(_ :+ reason)
      }
      guard = MutationGuard.make[IO](profiles, audit, snapshots, logger, IO.pure(MutationRecord.AnonymousPrincipal))
    } yield (port, audit, guard, invalidations)

  private def resetUseCase(port: GroupAdminPort[IO], guard: MutationGuard[IO], readOnly: Boolean) =
    for {
      profiles <- ConsumerRig.profiles(readOnly)
      logger <- FakeStructuredLogger[IO]
    } yield OffsetResetUseCase.make[IO](_ => port, guard, profiles, tokens, logger)

  test("a plan resolves the spec against live offsets and returns what would be written") {
    for {
      rigged <- rig(emptyGroup)
      (port, _, guard, _) = rigged
      reset <- resetUseCase(port, guard, readOnly = false)
      planned <- reset.plan(ConsumerRig.Cluster, group, scope, ResetSpec.ToEarliest)
    } yield planned match {
      case Right(PlannedReset(plan, token, _)) =>
        assertEquals(plan.partitions.map(_.proposed.value), List(0L))
        assertEquals(plan.partitions.map(_.current.map(_.value)), List(Some(40L)))
        assert(token.nonEmpty)
      case Left(error) => fail(s"planning failed: $error")
    }
  }

  test("a read-only cluster is refused at plan time, so no plan is ever shown that cannot be applied") {
    for {
      rigged <- rig(emptyGroup, readOnly = true)
      (port, _, guard, _) = rigged
      reset <- resetUseCase(port, guard, readOnly = true)
      planned <- reset.plan(ConsumerRig.Cluster, group, scope, ResetSpec.ToEarliest)
    } yield assertEquals(planned.left.map(_.code), Left(ErrorCode.ReadOnly))
  }

  test("a group that does not exist is a 404, checked by listing rather than by describing") {
    for {
      rigged <- rig(emptyGroup)
      (port, _, guard, _) = rigged
      _ <- port.state.update(_.copy(listing = Right(GroupListingPage.complete(Nil))))
      reset <- resetUseCase(port, guard, readOnly = false)
      planned <- reset.plan(ConsumerRig.Cluster, group, scope, ResetSpec.ToEarliest)
    } yield assertEquals(planned.left.map(_.code), Left(ErrorCode.GroupNotFound))
  }

  test("a group with members is refused with the code that names the remedy") {
    for {
      rigged <- rig(liveGroup)
      (port, _, guard, _) = rigged
      reset <- resetUseCase(port, guard, readOnly = false)
      planned <- reset.plan(ConsumerRig.Cluster, group, scope, ResetSpec.ToEarliest)
    } yield {
      assertEquals(planned.left.map(_.code), Left(ErrorCode.GroupNotEmpty))
      assert(planned.left.exists(_.message.contains("stop its consumers")))
    }
  }

  test("apply writes exactly the offsets the plan named, and records them") {
    for {
      rigged <- rig(emptyGroup)
      (port, audit, guard, invalidations) = rigged
      reset <- resetUseCase(port, guard, readOnly = false)
      planned <- reset.plan(ConsumerRig.Cluster, group, scope, ResetSpec.ToEarliest)
      token = planned.map(_.token).getOrElse(fail("no plan"))
      applied <- reset.apply(ConsumerRig.Cluster, group, token)
      state <- port.state.get
      records <- audit.written.get
      invalidated <- invalidations.get
    } yield {
      assert(applied.isRight, s"apply failed: $applied")
      assertEquals(state.applied.map((id, offsets) => id -> offsets.values.map(_.value).toList), List(group -> List(0L)))
      assertEquals(records.size, 1)
      assertEquals(records.head.operation, MutationKind.ResetOffsets.operation)
      assertEquals(records.head.outcome, MutationOutcome.Succeeded)
      assertEquals(records.head.before, Map("orders-0" -> 40L))
      assertEquals(records.head.after, Map("orders-0" -> 0L))
      assertEquals(invalidated.size, 1)
    }
  }

  test("a group that gained a member between planning and applying is refused before the write") {
    for {
      rigged <- rig(emptyGroup)
      (port, _, guard, _) = rigged
      reset <- resetUseCase(port, guard, readOnly = false)
      planned <- reset.plan(ConsumerRig.Cluster, group, scope, ResetSpec.ToEarliest)
      token = planned.map(_.token).getOrElse(fail("no plan"))
      // The race the two-phase flow widens, and the reason the precondition is checked twice.
      _ <- port.state.update(_.copy(described = Right(Map(group -> liveGroup))))
      applied <- reset.apply(ConsumerRig.Cluster, group, token)
      state <- port.state.get
    } yield {
      assertEquals(applied.left.map(_.code), Left(ErrorCode.GroupNotEmpty))
      assertEquals(state.applied, Nil, clue = "offsets were written to a group that had come back to life")
    }
  }

  test("a token for another group does not apply here, and says nothing about why") {
    for {
      rigged <- rig(emptyGroup)
      (port, _, guard, _) = rigged
      reset <- resetUseCase(port, guard, readOnly = false)
      planned <- reset.plan(ConsumerRig.Cluster, group, scope, ResetSpec.ToEarliest)
      token = planned.map(_.token).getOrElse(fail("no plan"))
      applied <- reset.apply(ConsumerRig.Cluster, GroupId.unsafe("someone-else"), token)
    } yield {
      assertEquals(applied.left.map(_.code), Left(ErrorCode.Validation))
      // The message must not say which half was wrong: that is an oracle.
      assert(applied.left.exists(!_.message.toLowerCase.contains("signature")))
    }
  }

  test("an expired token is refused") {
    val plan = ResetPlan(
      group,
      scope,
      ResetSpec.ToEarliest,
      List(PlannedPartition(GroupFixtures.partition(0), None, Offset.unsafe(0L), None)),
      Nil,
      ConsumerRig.At
    )

    for {
      token <- tokens.mint(ConsumerRig.Cluster, plan, Instant.parse("2026-01-01T00:00:00Z"))
      verified <- tokens.verify(ConsumerRig.Cluster, group, token, Instant.parse("2026-01-02T00:00:00Z"))
    } yield assertEquals(verified.left.map(_.code), Left(ErrorCode.Validation))
  }

  test("a token minted for another cluster does not apply to this one") {
    val plan = ResetPlan(
      group,
      scope,
      ResetSpec.ToEarliest,
      List(PlannedPartition(GroupFixtures.partition(0), None, Offset.unsafe(0L), None)),
      Nil,
      ConsumerRig.At
    )

    for {
      token <- tokens.mint(kui.kernel.ClusterId.unsafe("staging"), plan, ConsumerRig.At.plusSeconds(300))
      verified <- tokens.verify(ConsumerRig.Cluster, group, token, ConsumerRig.At)
    } yield assertEquals(verified.left.map(_.code), Left(ErrorCode.Validation))
  }

  test("a tampered token is refused rather than applied with the offsets somebody edited in") {
    val plan = ResetPlan(
      group,
      scope,
      ResetSpec.ToEarliest,
      List(PlannedPartition(GroupFixtures.partition(0), None, Offset.unsafe(0L), None)),
      Nil,
      ConsumerRig.At
    )

    for {
      token <- tokens.mint(ConsumerRig.Cluster, plan, ConsumerRig.At.plusSeconds(300))
      forged = token.take(4) + "X" + token.drop(5)
      verified <- tokens.verify(ConsumerRig.Cluster, group, forged, ConsumerRig.At)
    } yield assert(verified.isLeft)
  }

  test("a refused mutation is audited too, and the Kafka client is never touched") {
    for {
      rigged <- rig(emptyGroup, readOnly = true)
      (port, audit, guard, _) = rigged
      logger <- FakeStructuredLogger[IO]
      deleteGroup = DeleteGroupUseCase.make[IO](_ => port, guard, logger)
      result <- deleteGroup.delete(ConsumerRig.Cluster, group)
      state <- port.state.get
      records <- audit.written.get
    } yield {
      assertEquals(result.left.map(_.code), Left(ErrorCode.ReadOnly))
      assertEquals(state.deletedGroups, Nil, clue = "a read-only cluster was asked to delete a group")
      assertEquals(records.size, 1)
      assert(records.head.outcome.isInstanceOf[MutationOutcome.Refused])
    }
  }

  test("deleting a group records where its offsets were, so the record answers what was lost") {
    for {
      rigged <- rig(emptyGroup)
      (port, audit, guard, _) = rigged
      logger <- FakeStructuredLogger[IO]
      deleteGroup = DeleteGroupUseCase.make[IO](_ => port, guard, logger)
      result <- deleteGroup.delete(ConsumerRig.Cluster, group)
      state <- port.state.get
      records <- audit.written.get
    } yield {
      assert(result.isRight)
      assertEquals(state.deletedGroups, List(group))
      assertEquals(records.head.before, Map("orders-0" -> 40L))
      assertEquals(records.head.after, Map.empty[String, Long])
    }
  }

  test("deleting offsets uses the group's own commits, not a topic's partition list") {
    for {
      rigged <- rig(emptyGroup)
      (port, _, guard, _) = rigged
      logger <- FakeStructuredLogger[IO]
      deleteOffsets = DeleteOffsetsUseCase.make[IO](_ => port, guard, logger)
      result <- deleteOffsets.delete(ConsumerRig.Cluster, group, GroupFixtures.Orders)
      state <- port.state.get
    } yield {
      assertEquals(result.map(_.partitions), Right(Set(GroupFixtures.partition(0))))
      assertEquals(state.deletedOffsets.map((_, partitions) => partitions), List(Set(GroupFixtures.partition(0))))
    }
  }

  test("deleting offsets for a topic the group never committed on changes nothing and is not an error") {
    for {
      rigged <- rig(emptyGroup)
      (port, _, guard, _) = rigged
      logger <- FakeStructuredLogger[IO]
      deleteOffsets = DeleteOffsetsUseCase.make[IO](_ => port, guard, logger)
      result <- deleteOffsets.delete(ConsumerRig.Cluster, group, kui.kernel.TopicName.unsafe("untouched"))
      state <- port.state.get
    } yield {
      assertEquals(result.map(_.partitions), Right(Set.empty[TopicPartition]))
      assertEquals(state.deletedOffsets, Nil)
    }
  }

  test("a mutation that fails is audited as a failure rather than silently dropped") {
    for {
      rigged <- rig(emptyGroup)
      (port, audit, guard, _) = rigged
      failing = new GroupAdminPort[IO] {
        def list(states: Set[GroupState]) = port.list(states)
        def describe(ids: List[GroupId]) = port.describe(ids)
        def exists(id: GroupId) = port.exists(id)
        def offsetWindow(g: GroupId, s: ResetScope, at: Option[Instant]) = port.offsetWindow(g, s, at)
        def applyOffsets(g: GroupId, offsets: Map[TopicPartition, Offset]) =
          IO.pure(Left(ApplicationError.Refused(ErrorCode.GroupNotEmpty, "the broker said no"): KuiError))
        def deleteOffsets(g: GroupId, partitions: Set[TopicPartition]) = port.deleteOffsets(g, partitions)
        def deleteGroup(id: GroupId) =
          IO.pure(Left(ApplicationError.Refused(ErrorCode.GroupNotEmpty, "the broker said no"): KuiError))
      }
      logger <- FakeStructuredLogger[IO]
      deleteGroup = DeleteGroupUseCase.make[IO](_ => failing, guard, logger)
      result <- deleteGroup.delete(ConsumerRig.Cluster, group)
      records <- audit.written.get
    } yield {
      assertEquals(result.left.map(_.code), Left(ErrorCode.GroupNotEmpty))
      assertEquals(records.size, 1)
      assert(records.head.outcome.isInstanceOf[MutationOutcome.Refused])
    }
  }

  test("no audit record carries anything but the group, the offsets and the outcome") {
    for {
      rigged <- rig(emptyGroup)
      (port, audit, guard, _) = rigged
      logger <- FakeStructuredLogger[IO]
      deleteGroup = DeleteGroupUseCase.make[IO](_ => port, guard, logger)
      _ <- deleteGroup.delete(ConsumerRig.Cluster, group)
      records <- audit.written.get
    } yield {
      val record = records.head
      assertEquals(record.cluster, ConsumerRig.Cluster)
      assertEquals(record.resource, group.value)
      assertEquals(record.principal, MutationRecord.AnonymousPrincipal)
      // The record is a flat set of scalars and offset maps; there is no field on it that could
      // hold a connection, a property map or a secret.
      assert(record.before.values.forall(_ >= 0L))
    }
  }
}
