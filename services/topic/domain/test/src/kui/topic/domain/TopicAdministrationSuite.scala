package kui.topic.domain

import java.time.Instant

import kui.kernel.TopicName

/** The rules that hold before anything reaches a cluster.
  *
  * They are here rather than in a route suite because every one of them is a statement about the operation
  * and not about HTTP: "a partition count can only go up" is true of a `curl`, of a future MCP tool and of the
  * screen, and a rule asserted only through a route is a rule the next caller can walk around.
  */
final class TopicAdministrationSuite extends munit.FunSuite {

  private val orders: TopicName = TopicName.unsafe("orders.v1")
  private val now: Instant = Instant.parse("2026-09-04T09:00:00Z")

  // ------------------------------------------------------------------------------------ create

  test("absentPartitionsMeanTheBrokerDefaultAndNotOne") {
    // The whole point of the `Option`. A spec that filled in `1` here would create single-replica,
    // single-partition topics on a cluster whose operator had deliberately configured otherwise, and
    // nothing downstream could tell that KUI, rather than the operator, had chosen.
    val spec = NewTopicSpec.of(orders, None, None, Map.empty)

    assertEquals(spec.map(_.partitions), Right(None))
    assertEquals(spec.map(_.replicationFactor), Right(None))
  }

  test("aPartitionCountOutsideTheBoundsIsRefusedRatherThanClamped") {
    // Refused, not clamped: silently creating a hundred partitions when ten thousand and one were asked
    // for is a topic the operator did not ask for and cannot tell apart from one they did.
    List(0, -1, NewTopicSpec.MaxPartitions + 1).foreach { count =>
      val refusal = NewTopicSpec.of(orders, Some(count), None, Map.empty)
      assert(refusal.isLeft, s"$count partitions should be refused")
      assertEquals(refusal.left.toOption.map(_.details.flatMap(_.field)), Some(List("partitions")))
    }

    assert(NewTopicSpec.of(orders, Some(NewTopicSpec.MaxPartitions), None, Map.empty).isRight)
  }

  test("aReplicationFactorAboveKafkasOwnCeilingIsRefused") {
    assert(NewTopicSpec.of(orders, None, Some(NewTopicSpec.MaxReplicationFactor + 1), Map.empty).isLeft)
    assert(NewTopicSpec.of(orders, None, Some(0), Map.empty).isLeft)
    assertEquals(
      NewTopicSpec.of(orders, None, Some(3), Map.empty).map(_.replicationFactor),
      Right(Some(3.toShort))
    )
  }

  test("anUnknownConfigurationKeyIsTheBrokersToRefuseAndNotThisModules") {
    // Deliberate: Kafka's set of topic settings changes with every release, so a list held here would go
    // stale and start refusing settings the broker accepts. Only a *blank* key is refused, because that
    // one cannot be a setting on any broker.
    assert(NewTopicSpec.of(orders, None, None, Map("not.a.real.kafka.setting" -> "7")).isRight)
    assert(NewTopicSpec.of(orders, None, None, Map(" " -> "7")).isLeft)
  }

  // ------------------------------------------------------------------------------ configuration

  test("aKeyCannotBeSetAndRemovedByTheSameChange") {
    val refusal = TopicConfigChange.of(Map("retention.ms" -> "1"), Set("retention.ms"))

    assert(refusal.isLeft)
    assert(
      refusal.left.toOption.exists(_.message.contains("retention.ms")),
      // Kafka's own error for sending both names neither the key nor the caller's mistake.
      refusal.left.toOption.map(_.message).toString
    )
  }

  test("anEmptyStringIsAValueAndNotARemoval") {
    // The reason `remove` is a separate field. Several Kafka settings accept an empty value, so a shape
    // that read "" as "put it back to the default" would make a real value unreachable.
    val change = TopicConfigChange.of(Map("some.setting" -> ""), Set.empty)

    assertEquals(change.map(_.set), Right(Map("some.setting" -> "")))
    assertEquals(change.map(_.remove), Right(Set.empty[String]))
  }

  test("aChangeThatChangesNothingIsRefused") {
    assert(TopicConfigChange.of(Map.empty, Set.empty).isLeft)
  }

  // --------------------------------------------------------------------------------- partitions

  test("aPartitionCountCanOnlyEverGoUp") {
    // Kafka has no call that removes a partition. Refusing here rather than letting the broker refuse is
    // what puts a sentence about the operator's topic on the screen instead of an exception's class name.
    assert(PartitionPlan.of(orders, current = 6, target = 6, now).isLeft)
    assert(PartitionPlan.of(orders, current = 6, target = 3, now).isLeft)
    assertEquals(PartitionPlan.of(orders, current = 6, target = 12, now).map(_.added), Right(6))
  }

  test("everyPartitionPlanCarriesTheKeyRoutingWarning") {
    // The one that changes behaviour for ever, and the reason this operation is classified destructive.
    // It is on the *plan*, not in the documentation, because an operator confirms the plan.
    val plan = PartitionPlan.of(orders, current = 3, target = 6, now)

    assertEquals(plan.map(_.warnings.map(_.code)), Right(List(PlanWarning.KeyRouting)))
    assert(plan.exists(_.warnings.exists(_.message.contains("cannot be undone"))))
  }

  // ------------------------------------------------------------------------------------ delete

  test("aDeletionPlanSaysWhenTheTopicWillComeStraightBack") {
    // The behaviour KUI's own message browser was bitten by: with auto-creation on, the first client to
    // name the topic recreates it with the broker's defaults and none of its configuration.
    val plan = DeletionPlan.of(orders, partitions = 3, records = Some(16L), autoCreateEnabled = Some(true), now)

    assert(plan.warnings.map(_.code).contains(PlanWarning.AutoCreate))
    assert(
      plan.warnings.exists(_.message.contains("auto.create.topics.enable=true")),
      plan.warnings.map(_.message).toString
    )
  }

  test("notCheckingAndCheckingAndFindingItOffAreDifferentAnswers") {
    // "We did not check" must not be rendered as a promise that the topic stays deleted.
    val unknown = DeletionPlan.of(orders, 3, Some(0L), autoCreateEnabled = None, now)
    val off = DeletionPlan.of(orders, 3, Some(0L), autoCreateEnabled = Some(false), now)

    assert(unknown.warnings.map(_.code).contains(PlanWarning.AutoCreateUnknown))
    assert(!off.warnings.map(_.code).contains(PlanWarning.AutoCreateUnknown))
    assert(!off.warnings.map(_.code).contains(PlanWarning.AutoCreate))
  }

  test("anUncountableTopicGetsNoRecordWarningRatherThanAMadeUpNumber") {
    // `records = None` means at least one partition could not be counted. A number smaller than the truth,
    // shown to somebody deciding whether to delete, is worse than no number at all.
    val plan = DeletionPlan.of(orders, 3, records = None, autoCreateEnabled = Some(false), now)

    assertEquals(plan.warnings, Nil)
    assertEquals(plan.records, None)
  }

  test("theRecordCountWarningReadsAsASentenceForOneAndForMany") {
    val one = DeletionPlan.of(orders, 1, Some(1L), Some(false), now).warnings.map(_.message).mkString
    val many = DeletionPlan.of(orders, 3, Some(16L), Some(false), now).warnings.map(_.message).mkString

    assert(one.contains("1 record across 1 partition are"), one)
    assert(many.contains("16 records across 3 partitions are"), many)
  }

  // ------------------------------------------------------------------------------------ markers

  test("everyMutationIsClassifiedAndOnlyTheIrreversibleOnesAreDestructive") {
    // The classification M5's read-only policy and M6's RBAC both key on (ADR-047). Create and configure
    // change a cluster and can be undone by doing the opposite; growing and deleting cannot.
    assertEquals(
      TopicMutation.All.filter(_.destructive).map(_.operation).sorted,
      List("topic.delete", "topic.partitions.increase")
    )
    assertEquals(TopicMutation.All.map(_.operation).distinct.size, TopicMutation.All.size)
  }
}
