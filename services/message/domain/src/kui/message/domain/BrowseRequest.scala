package kui.message.domain

import cats.data.NonEmptySet

import kui.kernel.browse.{Direction, IsolationLevel, SeekMode}
import kui.kernel.error.{DomainError, FieldError, KuiError}
import kui.kernel.serde.SerdeName
import kui.kernel.{ClusterId, PartitionId, TopicName}

/** How many records one browse may ask for, and the most it is allowed to ask for.
  *
  * Configuration, not constants (`kui.message.limit`, `kui.message.maxLimit`): the numbers differ between a
  * laptop and a deployment in front of a hundred people, and an operator who wants a smaller ceiling should
  * be able to say so. `Default` is what a test or a caller with no configuration in scope uses.
  */
final case class BrowseLimits(default: Int, max: Int)

object BrowseLimits {

  /** The reference product's measured values (`research/kafbat/api-analysis.md` Finding 5.2), kept because
    * they are tuned by people using them, not chosen here.
    */
  val Default: BrowseLimits = BrowseLimits(default = 100, max = 500)

  given CanEqual[BrowseLimits, BrowseLimits] = CanEqual.derived
}

/** A smart filter, named by its id and carrying its source.
  *
  * The two travel together for a reason worth stating: the id addresses a compiled program in a cache, and
  * the cache is per replica. A request that carried only an id would work on the replica that compiled the
  * filter and fail on its neighbour, which is the reference product's behaviour and shows up as a filter that
  * works until a load balancer moves you (ADR-017). With the source attached, a replica that has never seen
  * the id compiles it and answers.
  *
  * @param id
  *   `libs/filter`'s `FilterId`: the first sixteen hexadecimal characters of `sha256(source)`. It is carried
  *   here as a `String` because that opaque type is computed with `java.security.MessageDigest` and so cannot
  *   live in the cross-compiled `libs/kernel` that rule A1 confines this module to. The application layer,
  *   which may see `libs/filter`, converts it once. Validated here to the shape `FilterId.fromString`
  *   accepts, so an id that could never have been minted is refused at the edge rather than silently missing
  *   in a cache
  */
final case class FilterRef private (id: String, source: Option[String])

object FilterRef {

  private val IdLength: Int = 16

  def of(id: String, source: Option[String]): Either[KuiError, FilterRef] =
    if isWellFormed(id) then Right(FilterRef(id, source))
    else
      Left(
        DomainError.InvariantViolation(
          "the filter id is not one KUI could have minted",
          List(FieldError.of("filterId", s"$IdLength lowercase hexadecimal characters"))
        )
      )

  private def isWellFormed(id: String): Boolean =
    id.length == IdLength && id.forall(c => (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))

  given CanEqual[FilterRef, FilterRef] = CanEqual.derived
}

/** A browse, validated.
  *
  * The limit rules live here rather than in the API layer because the streaming endpoint and the page
  * endpoint must agree about them, and two validations of one rule is how they stop agreeing.
  *
  * The constructor is private and [[BrowseRequest.of]] is the only way in, so every value of this type is one
  * the rest of the milestone may act on without re-checking: `limit` is inside the configured bounds, a
  * partition subset is non-empty if it is present at all, and a live browse has no start position.
  */
final case class BrowseRequest private (
    cluster: ClusterId,
    topic: TopicName,
    seek: SeekMode,
    direction: Direction,
    partitions: Option[NonEmptySet[PartitionId]],
    limit: Int,
    isolation: IsolationLevel,
    keySerde: Option[SerdeName],
    valueSerde: Option[SerdeName],
    stringFilter: Option[String],
    filter: Option[FilterRef],
    live: Boolean
)

object BrowseRequest {

  /** Builds a browse, clamping what can be clamped and refusing what cannot.
    *
    * `limit` is clamped rather than refused: absent, zero, negative or above the ceiling all become a legal
    * page. That is the reference product's rule and it is kept deliberately — a user who types a million
    * means "as many as you will give me", and answering that with a 400 is a worse answer than answering it
    * with a page. Everything that is not a quantity is refused instead, because there is no sensible value to
    * substitute for a contradiction.
    *
    * @param direction
    *   `None` means "whichever the seek mode implies" ([[SeekMode.defaultDirection]]). An explicit direction
    *   always wins, so a caller can browse forwards from `Latest` and wait for new records if it means to
    */
  def of(
      cluster: ClusterId,
      topic: TopicName,
      seek: SeekMode,
      direction: Option[Direction],
      partitions: Option[Set[PartitionId]],
      limit: Option[Int],
      isolation: Option[IsolationLevel],
      keySerde: Option[SerdeName],
      valueSerde: Option[SerdeName],
      stringFilter: Option[String],
      filter: Option[FilterRef],
      live: Boolean,
      limits: BrowseLimits = BrowseLimits.Default
  ): Either[KuiError, BrowseRequest] =
    for {
      chosen <- partitionSubset(partitions)
      _ <- liveModeHasNoStartPosition(live, seek)
    } yield BrowseRequest(
      cluster = cluster,
      topic = topic,
      seek = seek,
      direction = directionFor(live, seek, direction),
      partitions = chosen,
      limit = clamp(limit, limits),
      isolation = isolation.getOrElse(IsolationLevel.Default),
      keySerde = keySerde,
      valueSerde = valueSerde,
      stringFilter = stringFilter.filter(_.nonEmpty),
      filter = filter,
      live = live
    )

  /** Which way a browse reads, with tailing overruling everything.
    *
    * A tail runs *forwards*, always. This is not a preference: the default direction of `Latest` — which is
    * the seek a tail uses, and the one the Follow control sets — is `Backward`, so a live browse that took
    * the ordinary default would be asked to read backwards from the end of the log. Backwards from the end is
    * an empty range, and the browse would deliver nothing and then wait forever for records it had already
    * decided not to read. Forwards from the end is what "follow" means: nothing yet, and then everything that
    * is written from now on.
    *
    * An explicit `direction=BACKWARD` sent alongside `live=true` is overruled rather than refused, because
    * the two are not a contradiction the caller stated — `BrowseQuery` sends the seek and the flag, and the
    * direction is the service's own inference from the seek.
    */
  def directionFor(live: Boolean, seek: SeekMode, direction: Option[Direction]): Direction =
    if live then Direction.Forward else direction.getOrElse(SeekMode.defaultDirection(seek))

  /** The clamping table, in one place: absent, zero, negative and over-large all land on a legal page size.
    */
  def clamp(limit: Option[Int], limits: BrowseLimits): Int =
    limit match {
      case Some(value) if value > 0 && value <= limits.max => value
      case Some(value) if value > limits.max => limits.max
      case _ => limits.default
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
            // An empty subset is not "every partition": it is a request that can only ever return nothing,
            // and answering it with the whole topic would be surprising in the expensive direction.
            Left(
              DomainError.InvariantViolation(
                "a partition subset was given but it is empty",
                List(FieldError.of("partitions", "at least one partition when the parameter is present"))
              )
            )
        }
    }

  private def liveModeHasNoStartPosition(live: Boolean, seek: SeekMode): Either[KuiError, Unit] =
    seek match {
      case SeekMode.Beginning | SeekMode.Latest => Right(())
      case _ if !live => Right(())
      case _ =>
        // Tailing starts at the end and waits. Accepting an offset here and then ignoring it would show the
        // user a live view they believe is anchored somewhere it is not.
        Left(
          DomainError.InvariantViolation(
            "a live browse cannot start from an offset or a timestamp",
            List(FieldError.of("seek", "one of BEGINNING or LATEST when live is true"))
          )
        )
    }

  given CanEqual[BrowseRequest, BrowseRequest] = CanEqual.derived
}
