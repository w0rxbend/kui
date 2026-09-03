package kui.kernel

/** Which way a sorted list runs. */
enum SortOrder {
  case Asc, Desc

  /** The lowercase name the wire uses (`sort=name:asc`). Spelled out rather than derived from the case name
    * so that renaming the case cannot silently change the query-string contract.
    */
  def wire: String = this match {
    case Asc => "asc"
    case Desc => "desc"
  }
}

object SortOrder {

  def fromWire(raw: String): Option[SortOrder] = raw match {
    case "asc" => Some(Asc)
    case "desc" => Some(Desc)
    case _ => None
  }

  given CanEqual[SortOrder, SortOrder] = CanEqual.derived
}

/** One sort instruction: which field, which direction.
  *
  * `Field` is a type parameter rather than a `String` so that each list endpoint can name the fields it can
  * actually sort by as an enum, and an unknown field becomes a decode failure at the edge instead of a
  * silently ignored parameter.
  */
final case class Sort[Field](field: Field, order: SortOrder)

/** How many items one page holds: 1 to 500, defaulting to 25 (ADR-026).
  *
  * The maximum exists because these lists are built in memory: a request for a million rows is not a big
  * page, it is an outage.
  */
opaque type PageSize = Int

object PageSize {
  val Default: PageSize = 25
  val Max: PageSize = 500

  def from(n: Int): Either[ValidationError, PageSize] = Checks.within("pageSize", 1, Max)(n)

  /** Wraps a value that has already been validated somewhere else. Never call it on user input. */
  def unsafe(n: Int): PageSize = n

  extension (size: PageSize) def value: Int = size

  given Ordering[PageSize] = Ordering.Int
  given CanEqual[PageSize, PageSize] = CanEqual.derived
}

/** Which page of a list, and how big. Pages are numbered from one, because that is what the page number in a
  * user interface means; the conversion to a zero-based index happens once, in [[Page.of]], and nowhere else.
  */
final case class PageRequest(page: PositiveInt, pageSize: PageSize) {

  /** The zero-based index of this page's first item in the full list.
    *
    * The multiplication is done in 64 bits and clamped. A bookmark to page 100 000 000 with 500 items per
    * page overflows a 32-bit product, and an overflowed offset is negative, which `slice` reads as "start at
    * the beginning" — so an absurd page number would have quietly returned the first page instead of nothing.
    */
  def offset: Int = math.min((page.value.toLong - 1L) * pageSize.value.toLong, Int.MaxValue.toLong).toInt
}

object PageRequest {

  val Default: PageRequest = PageRequest(PositiveInt.One, PageSize.Default)

  def from(page: Int, pageSize: Int): Either[ValidationError, PageRequest] =
    for {
      validPage <- PositiveInt.from(page).left.map {
        case ValidationError.Range(_, min, max, got) => ValidationError.Range("page", min, max, got)
        case other => other
      }
      validSize <- PageSize.from(pageSize)
    } yield PageRequest(validPage, validSize)

  given CanEqual[PageRequest, PageRequest] = CanEqual.derived
}

/** An opaque continuation token.
  *
  * In M0 it is only a validated string: nothing produces one yet. From M3 it carries the signed cursor of
  * ADR-026, and the point of the type is that no code between the producer and the consumer ever looks inside
  * it, so the format stays free to change.
  *
  * The 32 KiB bound is not arbitrary: a cursor grows with the partition count, and a token past this size is
  * refused with `KUI-CURSOR-TOO-LARGE` rather than being put into a URL that a proxy will truncate.
  */
opaque type PageToken = String

object PageToken {
  private val MaxLength: Int = 32 * 1024

  def from(raw: String): Either[ValidationError, PageToken] =
    Checks.bounded("pageToken", MaxLength)(raw)

  /** Wraps a value that has already been validated somewhere else. Never call it on user input. */
  def unsafe(raw: String): PageToken = raw

  extension (token: PageToken) def value: String = token

  given CanEqual[PageToken, PageToken] = CanEqual.derived
}

/** One page of a list, plus what a client needs to ask for the next one.
  *
  * `totalItems` is an `Option` because a stream cannot count what it has not read yet; for a list built in
  * memory it is always present and always the size of the list **after every filter has been applied**. That
  * is the whole reason [[Page.of]] exists: the implementation this project is modelled on counts before
  * filtering, so its page count is wrong whenever a filter removes anything, and the only reliable fix is to
  * make the arithmetic impossible to get wrong by doing it in one place.
  */
final case class Page[A](
    items: List[A],
    page: Int,
    pageSize: Int,
    totalItems: Option[Long],
    nextPageToken: Option[PageToken]
) {

  /** Converts the items and keeps the pagination metadata, which is what an `api` layer does when it maps a
    * page of domain values to a page of DTOs.
    */
  def map[B](f: A => B): Page[B] = copy(items = items.map(f))

  def isEmpty: Boolean = items.isEmpty
}

object Page {

  /** Cuts one page out of a list that has already been filtered and sorted.
    *
    * Filtering must happen before this call, never after: `totalItems` is the size of what is passed in, so
    * filtering afterwards would report a total the caller cannot page through.
    *
    * A page past the end of the list is an empty page, not an error. A client that holds a bookmark to page 9
    * of a list that has since shrunk should see "nothing here", not a failure.
    */
  def of[A](all: List[A], request: PageRequest): Page[A] =
    Page(
      items = all.slice(request.offset, request.offset + request.pageSize.value),
      page = request.page.value,
      pageSize = request.pageSize.value,
      totalItems = Some(all.size.toLong),
      nextPageToken = None
    )

  /** An empty page in answer to a request — a section that is unavailable, a list that is not configured —
    * that still tells the client which page it asked for.
    */
  def empty[A](request: PageRequest): Page[A] =
    Page(Nil, request.page.value, request.pageSize.value, Some(0L), None)
}
