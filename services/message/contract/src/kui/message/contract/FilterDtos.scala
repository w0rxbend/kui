package kui.message.contract

import io.circe.syntax.*
import io.circe.{Codec, HCursor, Json}
import sttp.tapir.Schema

/** A smart filter a caller wants to reuse (MS-007, ADR-017).
  *
  * A filter expression can be long, and it is sent on every page of a browse. Registering it once and quoting
  * the returned id keeps a URL short enough to be a link a person can send to a colleague — which is the
  * behaviour ADR-017 asks for and the reason the id exists at all.
  *
  * @param source
  *   the expression itself. It is stored and evaluated by the service, never by the browser
  * @param name
  *   what to call it in the saved-filter list. `None` is an unnamed, one-off registration: a filter typed
  *   into the bar and used immediately is registered too, because the browse endpoint takes an id
  */
final case class FilterRegistrationDto(source: String, name: Option[String])

object FilterRegistrationDto {

  given Codec[FilterRegistrationDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        source <- cursor.get[String]("source")
        name <- cursor.get[Option[String]]("name")
      } yield FilterRegistrationDto(source, name),
    (dto: FilterRegistrationDto) =>
      Json.obj(
        "source" -> dto.source.asJson,
        "name" -> dto.name.asJson
      )
  )

  given Schema[FilterRegistrationDto] =
    Schema.derived[FilterRegistrationDto].description("A filter expression to register for reuse")

  given CanEqual[FilterRegistrationDto, FilterRegistrationDto] = CanEqual.derived
}

/** The handle a registered filter is quoted by.
  *
  * A string on the wire and nothing more. The service decides how the id is derived — a content hash, so that
  * registering the same expression twice yields the same id and the store does not grow without bound — and a
  * client that parsed it would be depending on a decision the service is free to change.
  */
final case class FilterIdDto(id: String)

object FilterIdDto {

  given Codec[FilterIdDto] = Codec.from(
    (cursor: HCursor) => cursor.get[String]("id").map(FilterIdDto.apply),
    (dto: FilterIdDto) => Json.obj("id" -> dto.id.asJson)
  )

  given Schema[FilterIdDto] =
    Schema.derived[FilterIdDto].description("An opaque handle for a registered filter expression")

  given CanEqual[FilterIdDto, FilterIdDto] = CanEqual.derived
}

/** Try a filter against one record before running it over a topic (MS-007, KU-016).
  *
  * The record is supplied by the caller rather than read from Kafka, which is what makes this endpoint cheap
  * and what makes it safe to expose: it evaluates an expression against a document the caller already has.
  * The endpoint is nonetheless cluster-scoped and declares a permission, because the expression language can
  * be a probe and an unauthenticated evaluator is an unauthenticated compute service.
  */
final case class FilterTestDto(source: String, record: MessageDto)

object FilterTestDto {

  given Codec[FilterTestDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        source <- cursor.get[String]("source")
        record <- cursor.get[MessageDto]("record")
      } yield FilterTestDto(source, record),
    (dto: FilterTestDto) =>
      Json.obj(
        "source" -> dto.source.asJson,
        "record" -> dto.record.asJson
      )
  )

  given Schema[FilterTestDto] =
    Schema.derived[FilterTestDto].description("Evaluate a filter expression against one supplied record")

  given CanEqual[FilterTestDto, FilterTestDto] = CanEqual.derived
}

/** What the filter did with the record.
  *
  * Three outcomes, not two. An expression that *throws* — a field that does not exist, a type mismatch — is
  * neither a match nor a non-match, and collapsing it into `matched: false` is how a filter that errors on
  * every record silently looks like a filter that matches nothing.
  *
  * @param error
  *   the evaluation failure, when there was one. `matched` is false whenever this is set, so a client that
  *   only reads `matched` is wrong in the safe direction rather than the dangerous one
  */
final case class FilterTestResultDto(matched: Boolean, error: Option[String])

object FilterTestResultDto {

  given Codec[FilterTestResultDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        matched <- cursor.get[Boolean]("matched")
        error <- cursor.get[Option[String]]("error")
      } yield FilterTestResultDto(matched, error),
    (dto: FilterTestResultDto) =>
      Json.obj(
        "matched" -> dto.matched.asJson,
        "error" -> dto.error.asJson
      )
  )

  given Schema[FilterTestResultDto] = Schema
    .derived[FilterTestResultDto]
    .description("Whether the filter matched, or the failure that means it answered neither way")

  given CanEqual[FilterTestResultDto, FilterTestResultDto] = CanEqual.derived
}
