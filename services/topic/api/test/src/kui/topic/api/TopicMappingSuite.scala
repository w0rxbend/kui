package kui.topic.api

import munit.FunSuite

import kui.kernel.{BrokerId, Page, PageRequest, PartitionId, SortOrder, TopicName}
import kui.topic.contract.TopicSortField as WireSortField
import kui.topic.domain.*

/** That the wire says what the domain meant.
  *
  * The two halves of this suite are different in kind. The field-by-field cases assert that the mapping
  * renames rather than recomputes: every number on a row was derived by the domain from the partitions it was
  * built out of, and a mapper that summed anything itself would give a screen a second opinion about a fact.
  *
  * The sort-field cases assert the *seam*. `TopicSortField` is declared twice — once in the contract, which
  * the browser compiles, and once in the domain, which owns the orderings — because build rules A1 and A2
  * forbid either module from importing the other. This module is the only one that sees both, and these are
  * the assertions that keep the two enums in step.
  */
final class TopicMappingSuite extends FunSuite {

  private def partition(id: Int, leader: Option[Int], offsets: Option[(Long, Long)]): PartitionView =
    PartitionView
      .from(
        PartitionId.unsafe(id),
        leader.map(BrokerId.unsafe),
        replicas = List(BrokerId.unsafe(1), BrokerId.unsafe(2)),
        inSync = List(BrokerId.unsafe(1)),
        earliestOffset = offsets.map(_._1),
        latestOffset = offsets.map(_._2),
        sizeBytes = Some(2048L)
      )
      .fold(error => fail(error.message), identity)

  private val healthy = partition(0, Some(1), Some((0L, 100L)))
  private val leaderless = partition(1, None, None)

  test("a row is the domain's numbers, renamed and not recomputed") {
    val summary = TopicSummary.of(TopicName.unsafe("orders"), isInternal = false, List(healthy, leaderless))
    val row = TopicMapping.row(summary)

    assertEquals(row.name, summary.name)
    assertEquals(row.internal, summary.isInternal)
    assertEquals(row.partitionCount, summary.partitionCount)
    assertEquals(row.replicationFactor, summary.replicationFactor)
    assertEquals(row.outOfSyncReplicas, summary.outOfSyncReplicas)
    assertEquals(row.offlinePartitions, summary.offlinePartitions)
    assertEquals(row.messageCount, summary.messageCount)
    assertEquals(row.sizeBytes, summary.sizeBytes)
  }

  test("a topic with a leaderless partition reports no message count on the wire either") {
    // The milestone's central refusal, carried all the way out. A count summed over the partitions that
    // did answer would be wrong rather than incomplete, and only one of those two starts an investigation.
    val summary = TopicSummary.of(TopicName.unsafe("orders"), isInternal = false, List(healthy, leaderless))

    assertEquals(TopicMapping.row(summary).messageCount, None)
    assertEquals(TopicMapping.row(summary).offlinePartitions, 1)
  }

  test("a leaderless partition has a null leader on the wire, never Kafka's -1") {
    assertEquals(TopicMapping.partition(leaderless).leader, None)
    assertEquals(TopicMapping.partition(healthy).leader, Some(BrokerId.unsafe(1)))
  }

  test("a replica carries the in-sync flag, so a screen cannot render five of three in sync") {
    val replicas = TopicMapping.partition(healthy).replicas

    assertEquals(replicas.map(_.broker), List(BrokerId.unsafe(1), BrokerId.unsafe(2)))
    assertEquals(replicas.map(_.inSync), List(true, false))
    assertEquals(replicas.count(_.leader), 1)
  }

  test("a page is mapped item by item and its metadata is carried across untouched") {
    // The api layer never slices, sorts or counts. `Page.of` computed the total over the *filtered* list,
    // and re-deriving any part of it here would be a second chance to reproduce the reference product's
    // page-count defect.
    val rows = (1 to 30).toList.map(index =>
      TopicSummary.of(TopicName.unsafe(s"topic-$index"), isInternal = false, List(healthy))
    )
    val page = Page.of(rows, PageRequest.Default)
    val dto = TopicMapping.page(page)

    assertEquals(dto.items.size, 25)
    assertEquals(dto.page.page, 1)
    assertEquals(dto.page.pageSize, 25)
    assertEquals(dto.page.totalItems, Some(30L))
    assertEquals(dto.page.pageCount, Some(2))
    assertEquals(dto.items.map(_.name), page.items.map(_.name))
  }

  test("the detail document caps its partitions and reports the cap separately") {
    val limit = kui.topic.contract.dto.TopicDetailResponse.EmbeddedPartitionLimit
    val many = (0 until limit + 1).toList.map(id => partition(id, Some(1), Some((0L, 1L))))
    val exact = (0 until limit).toList.map(id => partition(id, Some(1), Some((0L, 1L))))

    val (bigDto, bigTruncated) =
      TopicMapping.detail(TopicDetail.of(TopicName.unsafe("orders"), isInternal = false, many))
    val (exactDto, exactTruncated) =
      TopicMapping.detail(TopicDetail.of(TopicName.unsafe("orders"), isInternal = false, exact))

    assertEquals(bigDto.partitions.size, limit)
    assertEquals(bigTruncated, true)
    // A topic with exactly the limit is not truncated, which is why the flag is sent rather than derived.
    assertEquals(exactDto.partitions.size, limit)
    assertEquals(exactTruncated, false)
  }

  test("the partitions endpoint's mapping is uncapped") {
    val limit = kui.topic.contract.dto.TopicDetailResponse.EmbeddedPartitionLimit
    val many = (0 until limit + 5).toList.map(id => partition(id, Some(1), Some((0L, 1L))))
    val detail = TopicDetail.of(TopicName.unsafe("orders"), isInternal = false, many)

    assertEquals(TopicMapping.partitions(detail).size, limit + 5)
  }

  test("a config entry keeps the domain's derived default and its own source spelling") {
    val entry = TopicConfigEntry(
      name = "retention.ms",
      value = Some("604800000"),
      source = ConfigSource.DynamicTopic,
      isSensitive = false,
      isReadOnly = false,
      documentation = Some("how long a segment is kept"),
      synonyms = List(ConfigSynonym("retention.ms", Some("-1"), ConfigSource.Default))
    )

    val dto = TopicMapping.configEntry(entry)

    assertEquals(dto.defaultValue, Some("-1"))
    assertEquals(dto.source, "dynamic-topic")
    assertEquals(dto.value, Some("604800000"))
  }

  test("a sensitive entry has no value and no default, on both sides") {
    val entry = TopicConfigEntry(
      name = "ssl.key.password",
      value = Some("SENTINEL-c0ffee"),
      source = ConfigSource.DynamicTopic,
      isSensitive = true,
      isReadOnly = true,
      documentation = None,
      synonyms = List(ConfigSynonym("ssl.key.password", Some("hunter2"), ConfigSource.Default))
    )

    val dto = TopicMapping.configEntry(entry)

    // The domain already refuses the default; the DTO's own encoder drops the value on the way to JSON.
    assertEquals(dto.defaultValue, None)
    assertEquals(dto.sensitive, true)

    import io.circe.syntax.*
    assert(!dto.asJson.noSpaces.contains("SENTINEL-c0ffee"), dto.asJson.noSpaces)
    assert(!dto.asJson.noSpaces.contains("hunter2"), dto.asJson.noSpaces)
  }

  test("an empty settings view and a refused one stay different all the way to the wire") {
    assertEquals(TopicMapping.configView(TopicConfigView.of(Nil)).status, "entries")
    assertEquals(TopicMapping.configView(TopicConfigView.NotPermitted("no")).status, "not_permitted")
  }

  test("every wire sort field maps to a domain field and back to itself") {
    // The seam. Add a case on either side and `TopicMapping` stops compiling until it is added on the
    // other; this asserts that the two ends of the round trip agree, which a compiler cannot.
    WireSortField.values.foreach { field =>
      assertEquals(TopicMapping.wireSortField(TopicMapping.sortField(field)), field, field.toString)
    }
  }

  test("every domain sort field maps to a wire field and back to itself") {
    TopicSortField.values.foreach { field =>
      assertEquals(TopicMapping.sortField(TopicMapping.wireSortField(field)), field, field.toString)
    }
  }

  test("the two enums have the same size and the same wire spellings") {
    // If they ever differ, one side can express an order the other cannot, and a `sort` a browser offers
    // becomes a 400 nobody can explain.
    assertEquals(WireSortField.values.length, TopicSortField.values.length)
    assertEquals(
      WireSortField.values.map(_.wire).toList.sorted,
      TopicSortField.values.map(_.wire).toList.sorted
    )
    WireSortField.values.foreach(field =>
      assertEquals(TopicMapping.sortField(field).wire, field.wire, field.wire)
    )
  }

  test("the sort direction is carried across untouched") {
    val ascending = kui.kernel.Sort(WireSortField.Size, SortOrder.Asc)
    val descending = kui.kernel.Sort(WireSortField.Size, SortOrder.Desc)

    assertEquals(TopicMapping.sort(ascending).order, SortOrder.Asc)
    assertEquals(TopicMapping.sort(descending).order, SortOrder.Desc)
    assertEquals(TopicMapping.sort(descending).field, TopicSortField.Size)
  }
}
