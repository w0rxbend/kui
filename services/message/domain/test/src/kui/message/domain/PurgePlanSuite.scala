package kui.message.domain

import java.time.Instant

import kui.kernel.{Offset, PartitionId, TopicName}

/** What a purge plan says before anything is deleted.
  *
  * These are rules about the operation rather than about HTTP, which is why they are asserted here: "a
  * partition that is already empty is not asked to delete anything" is true of a `curl`, of a future MCP tool
  * and of the screen, and a rule asserted only through a route is one the next caller walks around.
  */
final class PurgePlanSuite extends munit.FunSuite {

  private val topic = TopicName.unsafe("orders.v1")
  private val at = Instant.parse("2026-09-04T09:00:00Z")

  private def partition(id: Int, low: Long, high: Long): PlannedPurge =
    PlannedPurge(PartitionId.unsafe(id), Offset.unsafe(low), Offset.unsafe(high))

  test("theRecordCountIsTheWindowAndNotTheEndOffset") {
    // A partition whose retention has already moved the start forward holds `high - low` records, not
    // `high`. Reporting the end offset would tell an operator they are about to lose a great deal more
    // than they are.
    val plan = PurgePlan.of(topic, List(partition(0, 900L, 1000L)), Some("delete"), at)

    assertEquals(plan.records, 100L)
  }

  test("anAlreadyEmptyPartitionIsNotAskedToDeleteAnything") {
    // Asking Kafka to delete before a partition's own low watermark is a call that changes nothing and can
    // still fail, so the plan leaves it out of the work while keeping it in the report.
    val plan = PurgePlan.of(topic, List(partition(0, 5L, 5L), partition(1, 0L, 3L)), Some("delete"), at)

    assertEquals(plan.partitions.size, 2)
    assertEquals(plan.deletions.keySet.map(_.value), Set(1))
  }

  test("aTopicWithNothingInItIsANoOpAndCarriesNoWarnings") {
    // No records, so nothing to warn about — and a screen that rendered a "cannot be undone" warning over
    // an operation that changes nothing would be teaching operators to ignore the warning.
    val plan = PurgePlan.of(topic, List(partition(0, 4L, 4L)), Some("delete"), at)

    assert(plan.isNoOp)
    assertEquals(plan.warnings, Nil)
    assertEquals(plan.deletions, Map.empty[PartitionId, Offset])
  }

  test("aPurgeAlwaysSaysThatCommittedOffsetsAreNotMoved") {
    // The thing operators are most often surprised by: the group is left pointing below the new start of
    // the log and follows its own auto.offset.reset, which by default skips to the end.
    val plan = PurgePlan.of(topic, List(partition(0, 0L, 10L)), Some("delete"), at)

    assert(plan.warnings.map(_.code).contains(PurgeWarning.ConsumerOffsets))
    assert(plan.warnings.map(_.code).contains(PurgeWarning.RecordsLost))
  }

  test("aCompactedTopicIsWarnedAboutBeforeTheBrokerRefusesIt") {
    // Kafka refuses `deleteRecords` on a topic that is only compacted, with an exception that names
    // nothing an operator can act on. Saying so in the plan turns a failed apply into a decision.
    val compacted = PurgePlan.of(topic, List(partition(0, 0L, 10L)), Some("compact"), at)
    val both = PurgePlan.of(topic, List(partition(0, 0L, 10L)), Some("compact,delete"), at)
    val unknown = PurgePlan.of(topic, List(partition(0, 0L, 10L)), None, at)

    assert(compacted.warnings.map(_.code).contains(PurgeWarning.Compacted))
    // `compact,delete` does delete, so the purge will work and the warning would be noise.
    assert(!both.warnings.map(_.code).contains(PurgeWarning.Compacted))
    // KUI could not read the policy: it says nothing rather than guessing either way.
    assert(!unknown.warnings.map(_.code).contains(PurgeWarning.Compacted))
  }

  test("thePartitionsAreOrderedSoTwoReadingsOfTheSamePlanLookTheSame") {
    // The plan is signed, and a token is only as good as the guarantee that the same plan renders to the
    // same bytes.
    val plan = PurgePlan.of(topic, List(partition(2, 0L, 1L), partition(0, 0L, 1L)), Some("delete"), at)

    assertEquals(plan.partitions.map(_.partition.value), List(0, 2))
  }
}
