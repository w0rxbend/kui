package kui.message.application.cursor

import java.time.Instant

import scala.concurrent.duration.FiniteDuration

import kui.kernel.browse.{Direction, IsolationLevel}
import kui.kernel.serde.SerdeName
import kui.kernel.{ClusterId, Offset, PartitionId, TopicName}
import kui.message.domain.BrowseRequest

/** Everything "next page" needs, carried by the client instead of remembered by the server.
  *
  * The reference product keeps paging state in a process-local cache keyed by a random id. That works on one
  * process and fails on two: the id a browser was handed by one replica means nothing to its neighbour, and a
  * filter registered before a rolling restart stops existing. The symptom is a "next page" button that works
  * until a load balancer moves you.
  *
  * So the cursor *is* the state. It is signed rather than encrypted — offsets are not secret, and the cursor
  * is bound to a cluster and topic the caller already named in the URL, so encryption would buy nothing and
  * make key rotation worse (ADR-026; the decision is recorded here rather than assumed).
  *
  * `v` is checked before anything else is trusted, and an unknown version is rejected rather than ignored: a
  * cursor from a future release means a field this build does not know about, and reading it as if it were
  * absent is how a browse silently starts from the wrong offset.
  */
final case class BrowseCursor(
    v: Int,
    cluster: ClusterId,
    topic: TopicName,
    direction: Direction,
    perPartitionNext: Map[PartitionId, Offset],
    filterId: Option[String],
    keySerde: Option[SerdeName],
    valueSerde: Option[SerdeName],
    limit: Int,
    isolation: IsolationLevel,
    expiresAt: Instant
)

object BrowseCursor {

  /** The only version this build mints. */
  val Version: Int = 1

  /** How long a cursor stays usable. One hour: long enough that a person can read a page, follow a link and
    * come back, short enough that a bookmarked cursor does not resurrect a browse of a topic whose retention
    * has since moved past every offset in it.
    */
  val DefaultTtlSeconds: Long = 3600L

  /** The cursor that continues a **forward** browse: each partition resumes at `lastSeen + 1`.
    *
    * Forward and backward boundaries are different numbers for the same place, and this is the one piece of
    * arithmetic in paging that duplicates or skips exactly one record per page when it is wrong. It is a
    * constructor rather than a caller's `+ 1` for that reason: there is one place to get it right, and one
    * test pinned on it.
    */
  def afterForward(
      request: BrowseRequest,
      lastSeen: Map[PartitionId, Offset],
      now: Instant,
      ttl: FiniteDuration
  ): BrowseCursor =
    from(
      request,
      lastSeen.map((partition, offset) => partition -> Offset.unsafe(offset.value + 1L)),
      now,
      ttl
    )

  /** The cursor that continues a **backward** browse: each partition's next window *ends* where this one
    * began, and the range is half-open, so the boundary is the first offset seen and not one below it.
    */
  def beforeBackward(
      request: BrowseRequest,
      firstSeen: Map[PartitionId, Offset],
      now: Instant,
      ttl: FiniteDuration
  ): BrowseCursor = from(request, firstSeen, now, ttl)

  private def from(
      request: BrowseRequest,
      next: Map[PartitionId, Offset],
      now: Instant,
      ttl: FiniteDuration
  ): BrowseCursor =
    BrowseCursor(
      v = Version,
      cluster = request.cluster,
      topic = request.topic,
      direction = request.direction,
      perPartitionNext = next,
      filterId = request.filter.map(_.id),
      keySerde = request.keySerde,
      valueSerde = request.valueSerde,
      limit = request.limit,
      isolation = request.isolation,
      expiresAt = now.plusSeconds(ttl.toSeconds)
    )

  given CanEqual[BrowseCursor, BrowseCursor] = CanEqual.derived
}
