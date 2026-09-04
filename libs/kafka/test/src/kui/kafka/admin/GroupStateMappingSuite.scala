package kui.kafka.admin

import munit.ScalaCheckSuite
import org.apache.kafka.common.{GroupState as KafkaGroupState, GroupType}
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import kui.kernel.group.{GroupProtocol, GroupState}

/** The one place in KUI that reads a Kafka group enum, tested where it is.
  *
  * Every assertion here is about totality or about a single mapping whose wrong answer would put a false
  * statement on a screen. They need no broker: a broker in every one of these states is far harder to arrange
  * than a constructor call, which is why the mapping is a pure function in the first place.
  */
final class GroupStateMappingSuite extends ScalaCheckSuite {

  property("every Kafka group state maps to a KUI state without throwing") {
    forAll(Gen.oneOf(KafkaGroupState.values.toList)) { raw =>
      val mapped = AdminConversions.toGroupState(Some(raw))
      assert(GroupState.All.contains(mapped))
    }
  }

  test("a state the broker did not report is Unknown, never Dead") {
    assertEquals(AdminConversions.toGroupState(None), GroupState.Unknown)
  }

  test("Kafka's own UNKNOWN is KUI's Unknown") {
    assertEquals(AdminConversions.toGroupState(Some(KafkaGroupState.UNKNOWN)), GroupState.Unknown)
  }

  test("the KIP-848 rebalance states fold onto the classic pair an operator already reads") {
    assertEquals(
      AdminConversions.toGroupState(Some(KafkaGroupState.ASSIGNING)),
      GroupState.PreparingRebalance
    )
    assertEquals(
      AdminConversions.toGroupState(Some(KafkaGroupState.RECONCILING)),
      GroupState.CompletingRebalance
    )
  }

  property("every Kafka group type maps to a protocol without throwing") {
    forAll(Gen.oneOf(GroupType.values.toList)) { raw =>
      val mapped = AdminConversions.toGroupProtocol(Some(raw))
      assert(GroupProtocol.All.contains(mapped))
    }
  }

  test("an absent type is Unknown, and a Streams group is not a protocol KUI claims to know") {
    assertEquals(AdminConversions.toGroupProtocol(None), GroupProtocol.Unknown)
    assertEquals(AdminConversions.toGroupProtocol(Some(GroupType.STREAMS)), GroupProtocol.Unknown)
  }

  test("share groups and Streams groups are not consumer groups; a typeless listing is") {
    assert(!AdminConversions.isConsumerGroup(Some(GroupType.SHARE)))
    assert(!AdminConversions.isConsumerGroup(Some(GroupType.STREAMS)))
    assert(AdminConversions.isConsumerGroup(Some(GroupType.CLASSIC)))
    assert(AdminConversions.isConsumerGroup(Some(GroupType.CONSUMER)))
    // A broker older than 3.8 reports no type, and had only consumer groups to report.
    assert(AdminConversions.isConsumerGroup(None))
  }

  test("the mapping is injective on the states KUI names, so two states cannot collapse into one chip") {
    val named = List(
      KafkaGroupState.STABLE,
      KafkaGroupState.EMPTY,
      KafkaGroupState.DEAD,
      KafkaGroupState.PREPARING_REBALANCE,
      KafkaGroupState.COMPLETING_REBALANCE
    ).map(raw => AdminConversions.toGroupState(Some(raw)))

    assertEquals(named.distinct.size, named.size)
  }

  test("every KUI state except Unknown is produced by some Kafka value") {
    val produced = KafkaGroupState.values.toList.map(raw => AdminConversions.toGroupState(Some(raw))).toSet
    assertEquals(GroupState.All.toSet.diff(produced), Set.empty[GroupState])
  }
}
