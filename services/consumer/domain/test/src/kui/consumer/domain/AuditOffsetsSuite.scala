package kui.consumer.domain

import munit.FunSuite

import kui.kernel.{Offset, PartitionId, TopicName, TopicPartition}

/** How an offset reset is written down, which is the one mutation in the product with a scalar before and
  * after.
  *
  * `AuditOffsets.render`'s own comment says the rendering is sorted "so two records of the same change render
  * identically and a diff between them means something". Nothing asserted it: `MutationSuite`'s fixtures each
  * reset a single partition, so reversing the sort left `./mill services.consumer.__.test` at 185/185 green.
  * Two partitions is all it takes to tell the two orders apart, and a group consuming twelve is ordinary.
  */
final class AuditOffsetsSuite extends FunSuite {

  private def partition(topic: String, index: Int): TopicPartition =
    TopicPartition(TopicName.unsafe(topic), PartitionId.unsafe(index))

  test("a rendered reset is in partition order, whatever order the map iterated in") {
    val offsets = AuditOffsets.of(
      Map(
        partition("orders", 2) -> Offset.unsafe(900L),
        partition("orders", 0) -> Offset.unsafe(100L),
        partition("orders", 1) -> Offset.unsafe(500L)
      )
    )

    assertEquals(AuditOffsets.render(offsets), Some("orders-0=100,orders-1=500,orders-2=900"))
  }

  test("two renderings of one change are the same string, which is what makes a diff mean anything") {
    val first = Map(partition("orders", 0) -> Offset.unsafe(1L), partition("orders", 1) -> Offset.unsafe(2L))
    val again = Map(partition("orders", 1) -> Offset.unsafe(2L), partition("orders", 0) -> Offset.unsafe(1L))

    assertEquals(AuditOffsets.render(AuditOffsets.of(first)), AuditOffsets.render(AuditOffsets.of(again)))
  }

  test("a group consuming two topics keeps both partition zeroes, rather than overwriting one") {
    val offsets = AuditOffsets.of(
      Map(partition("orders", 0) -> Offset.unsafe(10L), partition("payments", 0) -> Offset.unsafe(20L))
    )

    assertEquals(offsets.size, 2)
    assertEquals(AuditOffsets.render(offsets), Some("orders-0=10,payments-0=20"))
  }

  test("a listing that came back short is not a cluster whose groups were deleted") {
    // `GroupListingPage.isComplete` shares this file, and nothing asserted it either: hard-wiring it to
    // `true` left `./mill services.consumer.__.test` at 185/185 green, after which a page that lost a
    // coordinator renders exactly like a complete one.
    assertEquals(GroupListingPage.complete(Nil).isComplete, true)
    assertEquals(GroupListingPage(Nil, incompleteCoordinators = 1).isComplete, false)
  }

  test("a mutation with nothing before it renders no string, which is not the empty one") {
    // `None` and `Some("")` reach the audit trail as an absent field and as a present, empty one, and a
    // reader of the trail cannot tell "there were no offsets" from "the offsets rendered to nothing".
    assertEquals(AuditOffsets.render(Map.empty), None)
  }
}
