package kui.topic.api

import io.scalaland.chimney.dsl.*

import kui.contracts.paging.PageDto
import kui.contracts.topic.{PartitionDto, ReplicaDto, TopicConfigEntryDto, TopicDetailDto, TopicRowDto}
import kui.kernel.{Page, Sort}
import kui.topic.contract.TopicSortField as WireSortField
import kui.topic.contract.dto.{TopicConfigViewDto, TopicDetailResponse}
import kui.topic.domain.{
  PartitionView,
  Replica,
  TopicConfigEntry,
  TopicConfigView,
  TopicDetail,
  TopicSortField,
  TopicSummary
}

/** Domain to wire, in the one layer allowed to see both (ADR-033, rule A3).
  *
  * Nothing here decides anything. Every number on a row was computed by the domain, from the partitions it
  * was built out of, and this file renames fields and nothing else — because the moment a mapper starts
  * summing, a screen has a second opinion about a fact and the two eventually differ.
  *
  * ==The sort field is mapped in both directions, exhaustively, on purpose==
  *
  * `TopicSortField` is declared twice: once in `services/topic/contract`, which the browser compiles, and
  * once in `services/topic/domain`, which owns the `Ordering`s. Rule A2 keeps the domain out of the contract
  * and rule A1 keeps the contract out of the domain, so neither can import the other. This module is the only
  * one that sees both, and [[sortField]] is an exhaustive match in each direction: add a field on either side
  * and this file stops compiling until it is added on the other. That is a seam with a compiler standing on
  * it, which is a different thing from two enums nobody is checking.
  */
object TopicMapping {

  /** One list row. */
  def row(summary: TopicSummary): TopicRowDto =
    summary
      .into[TopicRowDto]
      .withFieldRenamed(_.isInternal, _.internal)
      .transform

  /** One page of rows, with the pagination metadata carried across untouched.
    *
    * `PageDto.of` is the only constructor from the kernel's `Page`, and this layer never slices, sorts or
    * counts: the total was computed by `Page.of` over the *filtered* list, and re-deriving any part of it
    * here would be a second opportunity to reproduce the reference product's page-count defect.
    */
  def page(items: Page[TopicSummary]): PageDto[TopicRowDto] = PageDto.of(items)(row)

  def replica(value: Replica): ReplicaDto =
    ReplicaDto(broker = value.broker, leader = value.isLeader, inSync = value.isInSync)

  /** One partition. `messageCount` is the domain's derivation, not a subtraction repeated here. */
  def partition(view: PartitionView): PartitionDto =
    PartitionDto(
      partition = view.partition,
      leader = view.leader,
      replicas = view.replicas.map(replica),
      earliestOffset = view.earliestOffset,
      latestOffset = view.latestOffset,
      messageCount = view.messageCount,
      sizeBytes = view.sizeBytes
    )

  /** One topic's detail document, with its partition list capped.
    *
    * The cap is applied here rather than in the use case because it is a property of *this representation*:
    * the partitions endpoint returns the same domain value in full. The flag is computed from the domain's
    * own count against the limit and sent, never left for a reader to derive from the length of the list — a
    * topic with exactly the limit is not truncated, and a reader deriving the flag would say it was.
    */
  def detail(value: TopicDetail): (TopicDetailDto, Boolean) = {
    val limit = TopicDetailResponse.EmbeddedPartitionLimit
    val embedded = value.partitions.take(limit)

    val dto = TopicDetailDto(
      row = row(value.summary),
      partitions = embedded.map(partition),
      cleanupPolicy = value.cleanupPolicy,
      segmentCount = value.segmentCount
    )

    (dto, value.partitions.sizeIs > limit)
  }

  /** Every partition of a topic, uncapped: this is the endpoint that exists so the cap has somewhere to send
    * a caller who needs the rest.
    */
  def partitions(value: TopicDetail): List[PartitionDto] = value.partitions.map(partition)

  /** One configuration key.
    *
    * `defaultValue` and `sensitive` are the domain's answers, not this layer's. In particular the DTO's own
    * encoder drops a sensitive value on the way out, so the field is copied rather than blanked here: two
    * places defending the same secret is two places to get it wrong, and the encoder is the one that cannot
    * be bypassed by a caller building the document another way.
    */
  def configEntry(entry: TopicConfigEntry): TopicConfigEntryDto =
    TopicConfigEntryDto(
      name = entry.name,
      value = entry.value,
      defaultValue = entry.defaultValue,
      source = entry.source.token,
      sensitive = entry.isSensitive,
      readOnly = entry.isReadOnly,
      documentation = entry.documentation
    )

  /** The Settings tab's two cases, kept apart all the way to the wire.
    *
    * An empty entry list and a refusal are different documents, because they are different facts: "this topic
    * has no overrides" and "you may not read this topic's settings" send a reader to two different places.
    */
  def configView(view: TopicConfigView): TopicConfigViewDto = view match {
    case TopicConfigView.Entries(values) => TopicConfigViewDto.Entries(values.map(configEntry))
    case TopicConfigView.NotPermitted(detail) => TopicConfigViewDto.NotPermitted(detail)
  }

  /** The wire's sort field as the domain's. Exhaustive; see this object's comment. */
  def sortField(field: WireSortField): TopicSortField = field match {
    case WireSortField.Name => TopicSortField.Name
    case WireSortField.Partitions => TopicSortField.Partitions
    case WireSortField.ReplicationFactor => TopicSortField.ReplicationFactor
    case WireSortField.OutOfSyncReplicas => TopicSortField.OutOfSyncReplicas
    case WireSortField.Size => TopicSortField.Size
    case WireSortField.MessageCount => TopicSortField.MessageCount
  }

  /** The domain's sort field as the wire's. The other half, so that the two enums are pinned together in both
    * directions rather than only in the one this milestone happens to use.
    */
  def wireSortField(field: TopicSortField): WireSortField = field match {
    case TopicSortField.Name => WireSortField.Name
    case TopicSortField.Partitions => WireSortField.Partitions
    case TopicSortField.ReplicationFactor => WireSortField.ReplicationFactor
    case TopicSortField.OutOfSyncReplicas => WireSortField.OutOfSyncReplicas
    case TopicSortField.Size => WireSortField.Size
    case TopicSortField.MessageCount => WireSortField.MessageCount
  }

  def sort(value: Sort[WireSortField]): Sort[TopicSortField] =
    Sort(sortField(value.field), value.order)
}
