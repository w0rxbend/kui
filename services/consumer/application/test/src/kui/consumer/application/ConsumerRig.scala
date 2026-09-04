package kui.consumer.application

import java.time.Instant

import scala.concurrent.duration.*

import cats.effect.kernel.Ref
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import fs2.Stream

import kui.cache.CacheMetrics
import kui.consumer.domain.*
import kui.consumer.domain.fixtures.GroupFixtures
import kui.kernel.error.KuiError
import kui.kernel.group.GroupState
import kui.kernel.{ClusterId, GroupId, Offset, TopicName, TopicPartition}
import kui.security.audit.{AuditSink, MutationRecord}
import kui.testkit.fakes.FakeStructuredLogger

/** The fakes every application suite here builds on.
  *
  * All of them are programmable `Ref`s rather than mocks with expectations: a suite says what the cluster
  * answers and then asserts what the use case did with it, which is the only kind of assertion that survives
  * a refactor of the code between them.
  */
object ConsumerRig {

  val Cluster: ClusterId = ClusterId.unsafe("prod")

  val At: Instant = GroupFixtures.At

  def profileView(readOnly: Boolean = false): ClusterProfileView =
    ClusterProfileView(Cluster, "Production", readOnly)

  final class FakeProfiles(state: Ref[IO, ClusterProfileView]) extends ClusterProfileSource[IO] {
    def profileOf(cluster: ClusterId): IO[Either[KuiError, ClusterProfileView]] =
      state.get.map(_.asRight[KuiError])
    def all: IO[List[ClusterProfileView]] = state.get.map(List(_))
    def changes: Stream[IO, ClusterId] = Stream.empty
    def setReadOnly(readOnly: Boolean): IO[Unit] = state.update(_.copy(readOnly = readOnly))
  }

  def profiles(readOnly: Boolean = false): IO[FakeProfiles] =
    Ref.of[IO, ClusterProfileView](profileView(readOnly)).map(new FakeProfiles(_))

  /** Everything the port can be told to answer, and a record of what it was asked to do. */
  final case class PortState(
      listing: Either[KuiError, GroupListingPage],
      described: Either[KuiError, Map[GroupId, ConsumerGroup]],
      window: Either[KuiError, OffsetWindow],
      applied: List[(GroupId, Map[TopicPartition, Offset])],
      deletedOffsets: List[(GroupId, Set[TopicPartition])],
      deletedGroups: List[GroupId],
      describes: Int
  )

  object PortState {
    val Empty: PortState = PortState(
      listing = GroupListingPage.complete(Nil).asRight,
      described = Map.empty[GroupId, ConsumerGroup].asRight,
      window = OffsetWindow.Empty.asRight,
      applied = Nil,
      deletedOffsets = Nil,
      deletedGroups = Nil,
      describes = 0
    )
  }

  final class FakePort(val state: Ref[IO, PortState]) extends GroupAdminPort[IO] {

    def list(states: Set[GroupState]): IO[Either[KuiError, GroupListingPage]] =
      state.get.map(_.listing)

    def describe(ids: List[GroupId]): IO[Either[KuiError, Map[GroupId, ConsumerGroup]]] =
      state.updateAndGet(s => s.copy(describes = s.describes + 1)).map(_.described.map(_.view.filterKeys(ids.toSet).toMap))

    def exists(id: GroupId): IO[Either[KuiError, Boolean]] =
      state.get.map(_.listing.map(_.groups.exists(_.groupId == id)))

    def offsetWindow(
        group: GroupId,
        scope: ResetScope,
        at: Option[Instant]
    ): IO[Either[KuiError, OffsetWindow]] = state.get.map(_.window)

    def applyOffsets(group: GroupId, offsets: Map[TopicPartition, Offset]): IO[Either[KuiError, Unit]] =
      state.update(s => s.copy(applied = s.applied :+ (group -> offsets))).as(().asRight[KuiError])

    def deleteOffsets(group: GroupId, partitions: Set[TopicPartition]): IO[Either[KuiError, Unit]] =
      state.update(s => s.copy(deletedOffsets = s.deletedOffsets :+ (group -> partitions))).as(().asRight[KuiError])

    def deleteGroup(id: GroupId): IO[Either[KuiError, Unit]] =
      state.update(s => s.copy(deletedGroups = s.deletedGroups :+ id)).as(().asRight[KuiError])
  }

  def port(initial: PortState = PortState.Empty): IO[FakePort] =
    Ref.of[IO, PortState](initial).map(new FakePort(_))

  final class RecordingAudit(val written: Ref[IO, List[MutationRecord]]) extends AuditSink[IO] {
    def record(record: MutationRecord): IO[Unit] = written.update(_ :+ record)
  }

  def audit: IO[RecordingAudit] = Ref.of[IO, List[MutationRecord]](Nil).map(new RecordingAudit(_))

  /** A snapshots component over one fake port, refreshing at `interval`. */
  def snapshots(
      port: GroupAdminPort[IO],
      profiles: ClusterProfileSource[IO],
      interval: FiniteDuration = 30.seconds
  ): Resource[IO, GroupSnapshots[IO]] =
    Resource
      .eval(FakeStructuredLogger[IO])
      .flatMap(logger =>
        GroupSnapshots.resource[IO](profiles, _ => port, interval, CacheMetrics.noop[IO], logger)
      )

  /** A group with a computable lag, for a listing. */
  def group(
      id: String,
      state: GroupState = GroupState.Stable,
      committed: Long = 90L,
      end: Long = 100L,
      members: Int = 1
  ): ConsumerGroup =
    GroupFixtures.group(
      id = id,
      state = state,
      members = (1 to members).toList.map(n => GroupFixtures.member(s"m-$n", Set(0))),
      partitions = List(GroupFixtures.state(0, Some(committed), end = Some(end), memberId = Some("m-1")))
    )

  def listingOf(groups: List[ConsumerGroup], incompleteCoordinators: Int = 0): GroupListingPage =
    GroupListingPage(groups.map(_.summary), incompleteCoordinators)

  val Orders: TopicName = GroupFixtures.Orders
}
