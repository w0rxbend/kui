package kui.message.domain

import java.time.Instant

import cats.data.NonEmptySet

import kui.kernel.browse.IsolationLevel
import kui.kernel.error.{DomainError, FieldError, KuiError}
import kui.kernel.serde.SerdeName
import kui.kernel.{ClusterId, Offset, PartitionId, TopicName}

/** An optional narrowing of what a page is taken from, applied before any paging arithmetic.
  *
  * A user looking at a table of a busy topic usually wants "yesterday afternoon", not "page 4 800". The
  * window is resolved to offsets first and the pages are then cut out of what is left, so page 1 means the
  * newest record *in the window* rather than the newest record in the topic.
  */
enum PageWindow {
  case Everything
  case Between(from: Option[Instant], to: Option[Instant])
  case FromOffset(offset: Offset)
}

object PageWindow {
  given CanEqual[PageWindow, PageWindow] = CanEqual.derived
}

/** One page of the table view: newest first, counted per partition, and computed with no server state at all.
  *
  * The page mode deliberately has no cursor (ADR-026). It is arithmetic over the partitions' current end
  * offsets, which means any replica can answer any page and a restart loses nothing — and it also means the
  * offsets shift while producers write. That is documented behaviour rather than a hidden flaw: the response
  * carries the offsets it actually used, and the UI shows them.
  */
final case class PageRequest private (
    cluster: ClusterId,
    topic: TopicName,
    page: Int,
    pageSizePerPartition: Int,
    partitions: Option[NonEmptySet[PartitionId]],
    window: PageWindow,
    isolation: IsolationLevel,
    keySerde: Option[SerdeName],
    valueSerde: Option[SerdeName]
)

object PageRequest {

  def of(
      cluster: ClusterId,
      topic: TopicName,
      page: Option[Int],
      pageSizePerPartition: Option[Int],
      partitions: Option[Set[PartitionId]],
      window: PageWindow,
      isolation: Option[IsolationLevel],
      keySerde: Option[SerdeName],
      valueSerde: Option[SerdeName],
      limits: BrowseLimits = BrowseLimits.Default
  ): Either[KuiError, PageRequest] = {
    val requestedPage = page.getOrElse(1)
    if requestedPage < 1 then
      // Page 0 and page -1 are refused rather than clamped to 1: a caller that computed a page number and
      // got zero has a bug, and answering it with the first page hides that bug behind plausible data.
      Left(
        DomainError.InvariantViolation(
          "pages are numbered from one",
          List(FieldError.of("page", "a whole number of one or more"))
        )
      )
    else
      partitionSubset(partitions).map { chosen =>
        PageRequest(
          cluster = cluster,
          topic = topic,
          page = requestedPage,
          pageSizePerPartition = BrowseRequest.clamp(pageSizePerPartition, limits),
          partitions = chosen,
          window = window,
          isolation = isolation.getOrElse(IsolationLevel.Default),
          keySerde = keySerde,
          valueSerde = valueSerde
        )
      }
  }

  private def partitionSubset(
      partitions: Option[Set[PartitionId]]
  ): Either[KuiError, Option[NonEmptySet[PartitionId]]] =
    partitions match {
      case None => Right(None)
      case Some(chosen) =>
        NonEmptySet.fromSet(scala.collection.immutable.SortedSet.from(chosen)) match {
          case Some(nonEmpty) => Right(Some(nonEmpty))
          case None =>
            Left(
              DomainError.InvariantViolation(
                "a partition subset was given but it is empty",
                List(FieldError.of("partitions", "at least one partition when the parameter is present"))
              )
            )
        }
    }

  given CanEqual[PageRequest, PageRequest] = CanEqual.derived
}
