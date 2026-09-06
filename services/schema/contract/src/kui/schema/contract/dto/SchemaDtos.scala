package kui.schema.contract.dto

import io.circe.syntax.*
import io.circe.{Codec, HCursor, Json}
import sttp.tapir.Schema as TapirSchema

import kui.contracts.KernelCodecs.given
import kui.contracts.KernelSchemas.given
import kui.kernel.Subject

/** One schema a schema depends on, on the wire.
  *
  * `version` is a plain number here rather than an opaque type, because the wire is where a value stops being
  * a domain value: `services/schema/contract` may not depend on `services/schema/domain` (rule A2), and the
  * conversion — with its validation — happens once, in the `api` module's mapping.
  */
final case class SchemaReferenceDto(name: String, subject: Subject, version: Int)

object SchemaReferenceDto {

  given Codec[SchemaReferenceDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        name <- cursor.get[String]("name")
        subject <- cursor.get[Subject]("subject")
        version <- cursor.get[Int]("version")
      } yield SchemaReferenceDto(name, subject, version),
    (reference: SchemaReferenceDto) =>
      Json.obj(
        "name" -> reference.name.asJson,
        "subject" -> reference.subject.asJson,
        "version" -> reference.version.asJson
      )
  )

  given TapirSchema[SchemaReferenceDto] = TapirSchema
    .derived[SchemaReferenceDto]
    .description("A schema this schema refers to, by the name it uses for it and the version it pins")

  given CanEqual[SchemaReferenceDto, SchemaReferenceDto] = CanEqual.derived
}

/** Every version number one subject has, in ascending order.
  *
  * Numbers and not schemas: a subject with two hundred versions holds two hundred documents, and a version
  * picker needs the numbers. The schema behind a version is one more request, made when somebody picks one.
  */
final case class SubjectVersionsDto(subject: Subject, versions: List[Int])

object SubjectVersionsDto {

  given Codec[SubjectVersionsDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        subject <- cursor.get[Subject]("subject")
        // Absent is an empty list only because `subject` is required: a truncated document still fails
        // to decode rather than arriving as a subject with no versions, which is a state a registry
        // cannot actually be in.
        versions <- cursor.getOrElse[List[Int]]("versions")(Nil)
      } yield SubjectVersionsDto(subject, versions),
    (dto: SubjectVersionsDto) => Json.obj("subject" -> dto.subject.asJson, "versions" -> dto.versions.asJson)
  )

  given TapirSchema[SubjectVersionsDto] =
    TapirSchema.derived[SubjectVersionsDto].description("A subject's version numbers, ascending")

  given CanEqual[SubjectVersionsDto, SubjectVersionsDto] = CanEqual.derived
}

/** One row of the subject list: the name, and the three facts the row's caption is built from.
  *
  * ==Every enriched field is optional, and none of them is ever zero==
  *
  * The name comes from the list; the other three come from a call per subject that the service makes only for
  * the rows of the page being returned. A row whose enrichment did not answer is still a row, with its name
  * and three absent fields — because a row that vanished because a secondary call failed would tell an
  * operator their subject had been deleted. `versionCount` is `None` and never `0`: a registry cannot hold a
  * subject with no versions, so a zero here would be a state that does not exist.
  *
  * `format` is the registry's own word for the **latest** version's schema type, for the reason
  * [[SchemaDto.schemaType]] gives: a registry KUI has never met still renders a row. Earlier versions of the
  * same subject may be written in something else, which is why this is the list's summary and not the
  * subject's definition.
  *
  * `compatibility` carries [[CompatibilityDto]] whole, `inheritedFromGlobal` included. A list that flattened
  * it would show the same word for a subject pinned to `BACKWARD` and a subject following a global
  * `BACKWARD`, and those two rows behave differently the next time the global level is changed.
  */
final case class SubjectSummaryDto(
    subject: Subject,
    format: Option[String],
    versionCount: Option[Int],
    compatibility: Option[CompatibilityDto]
)

object SubjectSummaryDto {

  given Codec[SubjectSummaryDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        subject <- cursor.get[Subject]("subject")
        format <- cursor.get[Option[String]]("format")
        versionCount <- cursor.get[Option[Int]]("versionCount")
        compatibility <- cursor.get[Option[CompatibilityDto]]("compatibility")
      } yield SubjectSummaryDto(subject, format, versionCount, compatibility),
    (dto: SubjectSummaryDto) =>
      Json.obj(
        "subject" -> dto.subject.asJson,
        "format" -> dto.format.asJson,
        "versionCount" -> dto.versionCount.asJson,
        "compatibility" -> dto.compatibility.asJson
      )
  )

  given TapirSchema[SubjectSummaryDto] = TapirSchema
    .derived[SubjectSummaryDto]
    .description("A subject list row: the name, and the facts a row shows when they could be read")

  given CanEqual[SubjectSummaryDto, SubjectSummaryDto] = CanEqual.derived
}

/** One version of one subject: the schema text, and what it is written in.
  *
  * `definition` is the schema **verbatim**, exactly as the registry stores it — not reformatted, not
  * re-serialised, not pretty-printed. An operator comparing this panel with their registry's own screen has
  * to see the same characters, and a schema whose fields KUI reordered is a schema they cannot diff.
  *
  * `schemaType` is the registry's own word (`AVRO`, `JSON`, `PROTOBUF`, or something a non-Confluent registry
  * invented) rather than an enum, so that a registry KUI has never met still renders a row instead of failing
  * to decode. Clients switch on the three they know and print anything else.
  */
final case class SchemaDto(
    subject: Subject,
    version: Int,
    id: Int,
    schemaType: String,
    definition: String,
    references: List[SchemaReferenceDto]
)

object SchemaDto {

  given Codec[SchemaDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        subject <- cursor.get[Subject]("subject")
        version <- cursor.get[Int]("version")
        id <- cursor.get[Int]("id")
        schemaType <- cursor.get[String]("schemaType")
        definition <- cursor.get[String]("definition")
        references <- cursor.getOrElse[List[SchemaReferenceDto]]("references")(Nil)
      } yield SchemaDto(subject, version, id, schemaType, definition, references),
    (dto: SchemaDto) =>
      Json.obj(
        "subject" -> dto.subject.asJson,
        "version" -> dto.version.asJson,
        "id" -> dto.id.asJson,
        "schemaType" -> dto.schemaType.asJson,
        "definition" -> dto.definition.asJson,
        "references" -> dto.references.asJson
      )
  )

  given TapirSchema[SchemaDto] =
    TapirSchema.derived[SchemaDto].description("One registered version of a subject, with its schema text")

  given CanEqual[SchemaDto, SchemaDto] = CanEqual.derived
}

/** A compatibility level, and whether it is this subject's own.
  *
  * `inheritedFromGlobal` is the field that stops a screen from lying. A registry answers "no level here" for
  * a subject that follows the global setting, and a response that reported the global level without saying
  * where it came from would invite an operator to press Save on a value they were only reading — writing an
  * override that permanently detaches the subject from the global setting they thought they were confirming.
  *
  * It is always `false` on the registry-wide level, which is nobody's inheritance.
  */
final case class CompatibilityDto(level: String, inheritedFromGlobal: Boolean)

object CompatibilityDto {

  given Codec[CompatibilityDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        level <- cursor.get[String]("level")
        inherited <- cursor.getOrElse[Boolean]("inheritedFromGlobal")(false)
      } yield CompatibilityDto(level, inherited),
    (dto: CompatibilityDto) =>
      Json.obj(
        "level" -> dto.level.asJson,
        "inheritedFromGlobal" -> dto.inheritedFromGlobal.asJson
      )
  )

  given TapirSchema[CompatibilityDto] = TapirSchema
    .derived[CompatibilityDto]
    .description("The compatibility level in force, and whether the subject inherits it from the global one")

  given CanEqual[CompatibilityDto, CompatibilityDto] = CanEqual.derived
}

/** What to set a compatibility level to.
  *
  * A string and not an enum on the wire, for the reason [[SchemaDto.schemaType]] gives: a registry may know a
  * level this build does not. An unrecognised value is refused by the server with a message naming the seven
  * KUI knows, which is a better failure than a decoder rejecting the request before anything can say why.
  */
final case class SetCompatibilityRequest(level: String)

object SetCompatibilityRequest {

  given Codec[SetCompatibilityRequest] = Codec.from(
    (cursor: HCursor) => cursor.get[String]("level").map(SetCompatibilityRequest.apply),
    (request: SetCompatibilityRequest) => Json.obj("level" -> request.level.asJson)
  )

  given TapirSchema[SetCompatibilityRequest] = TapirSchema
    .derived[SetCompatibilityRequest]
    .description(
      "The level to set: BACKWARD, BACKWARD_TRANSITIVE, FORWARD, FORWARD_TRANSITIVE, FULL, " +
        "FULL_TRANSITIVE or NONE"
    )

  given CanEqual[SetCompatibilityRequest, SetCompatibilityRequest] = CanEqual.derived
}

/** A schema somebody wants checked before they register it. */
final case class CompatibilityCheckRequest(
    schemaType: String,
    definition: String,
    references: List[SchemaReferenceDto]
)

object CompatibilityCheckRequest {

  given Codec[CompatibilityCheckRequest] = Codec.from(
    (cursor: HCursor) =>
      for {
        // Absent means Avro, which is the registry's own convention for a missing schema type and is
        // therefore what a client that copied a registry payload will send.
        schemaType <- cursor.getOrElse[String]("schemaType")("AVRO")
        definition <- cursor.get[String]("definition")
        references <- cursor.getOrElse[List[SchemaReferenceDto]]("references")(Nil)
      } yield CompatibilityCheckRequest(schemaType, definition, references),
    (request: CompatibilityCheckRequest) =>
      Json.obj(
        "schemaType" -> request.schemaType.asJson,
        "definition" -> request.definition.asJson,
        "references" -> request.references.asJson
      )
  )

  given TapirSchema[CompatibilityCheckRequest] = TapirSchema
    .derived[CompatibilityCheckRequest]
    .description("A proposed schema to check against a subject. Nothing is registered")

  given CanEqual[CompatibilityCheckRequest, CompatibilityCheckRequest] = CanEqual.derived
}

/** A schema somebody wants registered under a subject.
  *
  * The same three fields as [[CompatibilityCheckRequest]] and deliberately a separate type. They are two
  * different requests with two different consequences — one asks a question and one changes the registry —
  * and a shared body would put one schema name on both operations in every generated client, which is how a
  * caller ends up sending the wrong one. It is also the field a refusal points at: a rejection arrives as
  * `KUI-VALIDATION` whose `details[0]` names `definition` and carries the registry's own sentence.
  */
final case class RegisterSchemaRequest(
    schemaType: String,
    definition: String,
    references: List[SchemaReferenceDto]
)

object RegisterSchemaRequest {

  given Codec[RegisterSchemaRequest] = Codec.from(
    (cursor: HCursor) =>
      for {
        // Absent means Avro, which is the registry's own convention and therefore what a client that
        // copied a registry payload will send. Same rule as the compatibility check's.
        schemaType <- cursor.getOrElse[String]("schemaType")("AVRO")
        definition <- cursor.get[String]("definition")
        references <- cursor.getOrElse[List[SchemaReferenceDto]]("references")(Nil)
      } yield RegisterSchemaRequest(schemaType, definition, references),
    (request: RegisterSchemaRequest) =>
      Json.obj(
        "schemaType" -> request.schemaType.asJson,
        "definition" -> request.definition.asJson,
        "references" -> request.references.asJson
      )
  )

  given TapirSchema[RegisterSchemaRequest] = TapirSchema
    .derived[RegisterSchemaRequest]
    .description("The schema to register under this subject, and what it is written in")

  given CanEqual[RegisterSchemaRequest, RegisterSchemaRequest] = CanEqual.derived
}

/** What the registry stored: the id it gave the schema, and the version the subject is now on.
  *
  * ==Why `version` is nullable and `id` is not==
  *
  * The registry's registration response is `{"id": N}` and carries no version at all; the number is a second
  * question, and a second call can fail once the first has already succeeded. When it does, the schema **is**
  * registered and KUI does not know its number. Reporting a failure would send an operator to register it
  * again, and inventing "the previous latest plus one" would print a version that may not exist — so the
  * field is absent and the screen says the number could not be read, which is this product's rule about an
  * unmeasured figure arriving on a wire for the first time.
  *
  * It is the uncommon branch: both calls go to the same registry over the same connection pool, one
  * immediately after the other.
  */
final case class RegisteredVersionDto(subject: Subject, id: Int, version: Option[Int])

object RegisteredVersionDto {

  given Codec[RegisteredVersionDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        subject <- cursor.get[Subject]("subject")
        id <- cursor.get[Int]("id")
        version <- cursor.get[Option[Int]]("version")
      } yield RegisteredVersionDto(subject, id, version),
    (dto: RegisteredVersionDto) =>
      Json.obj(
        "subject" -> dto.subject.asJson,
        "id" -> dto.id.asJson,
        "version" -> dto.version.asJson
      )
  )

  given TapirSchema[RegisteredVersionDto] = TapirSchema
    .derived[RegisteredVersionDto]
    .description(
      "The registered schema's id, and the version it became where the registry reported one"
    )

  given CanEqual[RegisteredVersionDto, RegisteredVersionDto] = CanEqual.derived
}

/** The registry's verdict on a proposed schema.
  *
  * `messages` are the **registry's** explanations, passed through rather than summarised: they name the field
  * that broke the rule, which is the only part of the answer somebody can act on. An empty list beside
  * `compatible: false` is a real state — older registries answer without explanations — and a client should
  * render "the registry rejected this and gave no reason" rather than an empty panel.
  */
final case class CompatibilityCheckDto(compatible: Boolean, messages: List[String])

object CompatibilityCheckDto {

  given Codec[CompatibilityCheckDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        compatible <- cursor.get[Boolean]("compatible")
        messages <- cursor.getOrElse[List[String]]("messages")(Nil)
      } yield CompatibilityCheckDto(compatible, messages),
    (dto: CompatibilityCheckDto) =>
      Json.obj("compatible" -> dto.compatible.asJson, "messages" -> dto.messages.asJson)
  )

  given TapirSchema[CompatibilityCheckDto] = TapirSchema
    .derived[CompatibilityCheckDto]
    .description("Whether the registry would accept the proposed schema, and why not when it would not")

  given CanEqual[CompatibilityCheckDto, CompatibilityCheckDto] = CanEqual.derived
}
