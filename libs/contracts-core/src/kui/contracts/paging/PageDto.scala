package kui.contracts.paging

import io.circe.syntax.*
import io.circe.{Codec, Decoder, Encoder, HCursor, Json}
import sttp.tapir.Schema

import kui.contracts.KernelCodecs.given
import kui.contracts.KernelSchemas.given
import kui.kernel.{Page, PageToken}

/** What a client is told about the page it just received.
  *
  * `totalItems` is the size of the list **after every filter has been applied**, and `pageCount` is *derived*
  * from it here rather than sent as a field of its own.
  *
  * That is not tidiness. The implementation this project is modelled on computes its page count before its
  * internal-topic filter runs, so the count it reports disagrees with the rows it sends whenever the filter
  * removes anything: a user hides internal topics, sees "Page 1 of 3", clicks page 2 and gets an empty screen
  * (`research/kafbat/api-analysis.md` §3.3). One number computed from another cannot disagree with it, and
  * that is the only fix that stays fixed.
  *
  * @param page
  *   which page this is, numbered from one, because that is what a page number in a user interface means
  * @param pageSize
  *   how many items a full page holds — the size that was asked for, not the size that arrived, so that a
  *   short last page does not look like a changed setting
  * @param totalItems
  *   how many items there are after filtering, or `None` when nothing counted them. A cursor-paged stream
  *   (ADR-026, M3) cannot count what it has not read yet, and reporting a zero there would be a lie
  * @param nextPageToken
  *   the cursor for the next page, for the endpoints that have one. Always `None` for the offset-paged lists
  *   of M2; the field is here because it is `PageDto`'s field, shared with the endpoints of M3 that do fill
  *   it, and a second page shape for cursors would be exactly the duplication this module exists to prevent
  */
final case class PageInfo(
    page: Int,
    pageSize: Int,
    totalItems: Option[Long],
    nextPageToken: Option[PageToken]
) {

  /** How many pages there are, when anything counted the items.
    *
    * Ceiling division, and never less than one: an empty list is "page 1 of 1", not "page 1 of 0". A client
    * rendering "Page 1 of 0" makes a user think something is broken when the honest answer is "there is
    * nothing here".
    */
  def pageCount: Option[Int] =
    totalItems.map { total =>
      if pageSize <= 0 then 1
      else math.max(1, math.ceil(total.toDouble / pageSize.toDouble).toInt)
    }
}

object PageInfo {

  /** `pageCount` is written out but never read back: it is derived, and a producer that disagreed with the
    * derivation would be silently believed if the decoder took its word for it.
    */
  given Codec[PageInfo] = Codec.from(
    (cursor: HCursor) =>
      for {
        page <- cursor.get[Int]("page")
        pageSize <- cursor.get[Int]("pageSize")
        totalItems <- cursor.get[Option[Long]]("totalItems")
        nextPageToken <- cursor.get[Option[PageToken]]("nextPageToken")
      } yield PageInfo(page, pageSize, totalItems, nextPageToken),
    (info: PageInfo) =>
      Json.obj(
        "page" -> info.page.asJson,
        "pageSize" -> info.pageSize.asJson,
        "totalItems" -> info.totalItems.asJson,
        "pageCount" -> info.pageCount.asJson,
        "nextPageToken" -> info.nextPageToken.asJson
      )
  )

  given Schema[PageInfo] = Schema
    .derived[PageInfo]
    .description("Where this page sits in the list; pageCount is derived from totalItems and pageSize")

  given CanEqual[PageInfo, PageInfo] = CanEqual.derived
}

/** One page of a list on the wire: `{items, page: {…}}` (ADR-026).
  *
  * It is a *rendering* of `libs/kernel`'s [[kui.kernel.Page]] and nothing more. It does not compute totals,
  * cut pages or sort — if it ever needs to, something upstream has already gone wrong — and [[PageDto.of]] is
  * the only constructor from the kernel type, so that mapping exists once.
  */
final case class PageDto[A](items: List[A], page: PageInfo)

object PageDto {

  given [A: {Encoder, Decoder}] => Codec[PageDto[A]] = Codec.from(
    (cursor: HCursor) =>
      for {
        // Absent rather than `[]` is treated as an empty page. A missing `items` is what a producer that
        // forgot the field looks like, and M1's second integration defect was exactly this shape read the
        // other way: a browser defaulting a missing field to nothing and rendering "no rows" with no error
        // anywhere. Here the default is safe because `page` is required, so a truncated document still
        // fails to decode.
        items <- cursor.getOrElse[List[A]]("items")(Nil)
        page <- cursor.get[PageInfo]("page")
      } yield PageDto(items, page),
    (dto: PageDto[A]) =>
      Json.obj(
        "items" -> dto.items.asJson,
        "page" -> dto.page.asJson
      )
  )

  given [A: Schema] => Schema[PageDto[A]] = Schema
    .derived[PageDto[A]]
    .description("One page of a list: the items, and where the page sits in the whole")

  /** The only constructor from the kernel type, so the mapping happens in one place.
    *
    * The item function is applied to the page's items and to nothing else: the pagination metadata is carried
    * across untouched, because it was computed by `Page.of` over the filtered list and re-deriving any part
    * of it here would be a second opportunity to get it wrong.
    */
  def of[A, B](page: Page[A])(f: A => B): PageDto[B] =
    PageDto(
      items = page.items.map(f),
      page = PageInfo(page.page, page.pageSize, page.totalItems, page.nextPageToken)
    )

  given [A] => CanEqual[PageDto[A], PageDto[A]] = CanEqual.derived
}
