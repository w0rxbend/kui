package kui.kafka.admin

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite

import org.apache.kafka.clients.admin.Admin

import kui.kafka.AdminClientPool
import kui.kernel.ClusterId
import kui.kernel.cluster.ClusterConnection
import kui.kernel.error.ErrorCode
import kui.kernel.group.{GroupProtocol, GroupState}
import kui.kernel.{GroupId, PartitionId, TopicName, TopicPartition}

/** The rules the group result types carry in their own shape, asserted where they are stated.
  *
  * None of these need a broker. They are the decisions a caller would otherwise have to remember —
  * "both halves of the precondition", "`None` is not an empty set" — and each one has a screen behind it.
  */
final class GroupTypesSuite extends FunSuite {

  private val group: GroupId = GroupId.unsafe("orders-consumer")

  /** A pool no stub may touch. If a "not yet implemented" method ever reaches the broker, this fails loudly
    * rather than opening a client in a unit test.
    */
  private val unusablePool: AdminClientPool[IO] = new AdminClientPool[IO] {
    def run[A](connection: ClusterConnection, operation: String)(call: Admin => IO[A]): IO[A] =
      IO.raiseError(new AssertionError(s"a stubbed GroupAdmin method opened an admin client for $operation"))
    def invalidate(id: ClusterId): IO[Unit] = IO.unit
    def evict(id: ClusterId): IO[Unit] = IO.unit
  }

  private def partition(n: Int): TopicPartition =
    TopicPartition(TopicName.unsafe("orders"), PartitionId.unsafe(n))

  private def member(id: String, held: Set[TopicPartition]): GroupMember =
    GroupMember.of(id, None, "client", "10.0.0.1", MemberAssignment(held), None)

  private def description(state: GroupState, members: List[GroupMember]): GroupDescription =
    GroupDescription.dead(group).copy(state = state, members = members)

  test("permitsOffsetChange needs both halves, not either one") {
    assert(!description(GroupState.Empty, List(member("m-1", Set(partition(0))))).permitsOffsetChange)
    assert(!description(GroupState.Stable, Nil).permitsOffsetChange)
    assert(description(GroupState.Empty, Nil).permitsOffsetChange)
  }

  test("a fabricated dead group permits an offset change") {
    val fabricated = GroupDescription.dead(group)
    assertEquals(fabricated.state, GroupState.Dead)
    assertEquals(fabricated.members, Nil)
    assert(fabricated.permitsOffsetChange)
  }

  test("no authorizer and an empty permission set are different values") {
    val aclsOff = GroupDescription.dead(group)
    val nothingAllowed = aclsOff.copy(authorizedOperations = Some(Set.empty[GroupOperation]))
    assertNotEquals(aclsOff.authorizedOperations, nothingAllowed.authorizedOperations)
    assertEquals(aclsOff.authorizedOperations, None)
  }

  test("a target assignment equal to the current one collapses to None") {
    val held = Set(partition(0), partition(1))
    val settled = GroupMember.of("m-1", None, "c", "h", MemberAssignment(held), Some(MemberAssignment(held)))
    assertEquals(settled.targetAssignment, None)
    assert(!settled.isRebalancing)

    val moving =
      GroupMember.of("m-1", None, "c", "h", MemberAssignment(held), Some(MemberAssignment(Set(partition(0)))))
    assertEquals(moving.targetAssignment, Some(MemberAssignment(Set(partition(0)))))
    assert(moving.isRebalancing)
  }

  test("a listing carries Unknown rather than failing when the broker reports no state") {
    val listing = GroupListing(group, isSimple = false, GroupState.Unknown, GroupProtocol.Classic)
    assertEquals(listing.state, GroupState.Unknown)
    assert(!GroupState.permitsOffsetChange(listing.state))
  }

  test("every GroupAdmin method has a body in KafkaGroupAdmin, stubbed or implemented") {
    val port = KafkaGroupAdmin[IO](unusablePool)
    val declared = classOf[GroupAdmin[?]].getDeclaredMethods
      .filter(m => java.lang.reflect.Modifier.isAbstract(m.getModifiers))
      .map(_.getName)
      .toSet
    val implemented = port.getClass.getMethods.map(_.getName).toSet
    assertEquals(declared.diff(implemented), Set.empty[String])

    // A stub answers with a typed value, never an exception and never an empty success.
    val answer = port.deleteGroups(null, Nil).unsafeRunSync()
    assertEquals(answer.left.map(_.code), Left(ErrorCode.Unsupported))
  }
}
