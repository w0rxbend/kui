package kui.schema.domain

import kui.kernel.{SchemaId, Subject, ValidationError}

/** Which schema language a registered schema is written in.
  *
  * The registry spells these `AVRO`, `JSON` and `PROTOBUF`, and **omits the field entirely when the answer is
  * Avro** — the format predates the other two. The omission is handled here, once, rather than at each call
  * site, because a missing field that silently means something specific is the kind of thing that gets
  * handled correctly in two places out of three.
  *
  * `Unknown` is a case rather than a decode failure. Registries that are not Confluent's own — Apicurio, Red
  * Hat's, Karapace — have added types over the years, and a schema service that refused to list a subject
  * because one of its versions was written in a language KUI has never heard of would take the whole screen
  * down for a row it could have shown as "PROTOBUF-ish, KUI does not know this one". The raw word the
  * registry used is kept so the screen can print it.
  */
enum SchemaFormat {
  case Avro, Json, Protobuf
  case Unknown(raw: String)

  /** The registry's own spelling, kept so an operator comparing KUI's screen with their registry sees the
    * same word.
    */
  def label: String = this match {
    case Avro => "AVRO"
    case Json => "JSON"
    case Protobuf => "PROTOBUF"
    case Unknown(raw) => raw
  }

  /** Whether KUI can do anything with this schema beyond showing its text.
    *
    * Only the display path cares today, but the produce form will: offering "validate against the schema" for
    * a language nothing here can parse is a promise the button cannot keep.
    */
  def isKnown: Boolean = this match {
    case Unknown(_) => false
    case _ => true
  }
}

object SchemaFormat {

  /** The registry's `schemaType` field, whose absence means Avro. */
  def fromRegistry(raw: Option[String]): SchemaFormat =
    raw.map(_.trim.toUpperCase) match {
      case None | Some("") | Some("AVRO") => Avro
      case Some("JSON") => Json
      case Some("PROTOBUF") => Protobuf
      case Some(other) => Unknown(other)
    }

  given CanEqual[SchemaFormat, SchemaFormat] = CanEqual.derived
}

/** The schema id type is `libs/kernel`'s [[kui.kernel.SchemaId]] and not one declared here.
  *
  * A registry id is written into every record's five-byte header, so it appears in the message browser, in
  * the serde layer and in this service, none of which may depend on the others. It belongs to the shared
  * vocabulary, and a second opaque type with the same name in this module would be a value the wire codec
  * could not accept without an unwrap-and-rewrap nobody would think to check.
  */

/** A subject's version number: 1, 2, 3… as the registry counts them.
  *
  * Version numbers start at 1 and never reach 0. `-1` is the registry's own spelling of "the latest one",
  * which this type deliberately cannot hold: "give me version -1" and "give me the latest version" are
  * different requests, and conflating them is how a screen ends up displaying a version number nobody can
  * find in their registry. [[VersionSelector]] carries that distinction instead.
  */
opaque type SchemaVersion = Int

object SchemaVersion {

  def from(raw: Int): Either[ValidationError, SchemaVersion] =
    if raw >= 1 then Right(raw)
    else Left(ValidationError.Range("version", Some("1"), None, raw.toString))

  /** Wraps a number the registry itself produced. Never call it on user input. */
  def unsafe(raw: Int): SchemaVersion = raw

  extension (version: SchemaVersion) def value: Int = version

  given Ordering[SchemaVersion] = Ordering.Int
  given CanEqual[SchemaVersion, SchemaVersion] = CanEqual.derived
}

/** Which version of a subject a request is about.
  *
  * `Latest` is not `Numbered(largest)`. Only the registry knows which version is latest at the moment of the
  * call, so resolving it here would mean answering with a version that was current when KUI last looked.
  */
enum VersionSelector {
  case Latest
  case Numbered(version: SchemaVersion)

  /** How the registry's own URL spells it: `latest`, or the number. */
  def path: String = this match {
    case Latest => "latest"
    case Numbered(version) => version.value.toString
  }
}

object VersionSelector {

  /** `latest`, or a number. Anything else is a bad request rather than a silent fallback to latest: a typo
    * that quietly returns the newest schema is a schema panel showing the wrong thing with nothing to notice.
    */
  def parse(raw: String): Either[String, VersionSelector] =
    raw.trim match {
      case "latest" => Right(Latest)
      case other =>
        other.toIntOption
          .toRight(s"'$other' is neither a version number nor the word 'latest'")
          .flatMap(SchemaVersion.from(_).left.map(_.message))
          .map(Numbered.apply)
    }

  given CanEqual[VersionSelector, VersionSelector] = CanEqual.derived
}

/** One schema this schema depends on, by name and version.
  *
  * References are how a schema reuses a type defined in another subject — an `Address` record shared by three
  * event types, say. They matter to a compatibility check, because checking a schema without the schemas it
  * refers to checks something the registry would never store.
  */
final case class SchemaReference(name: String, subject: Subject, version: SchemaVersion)

object SchemaReference {
  given CanEqual[SchemaReference, SchemaReference] = CanEqual.derived
}

/** One version of one subject, as the registry holds it.
  *
  * `definition` is the schema text **verbatim** — the Avro JSON, the JSON Schema document, the `.proto`
  * source — and not a parsed form. Parsing belongs to whichever codec is about to use it, and keeping the
  * text is what lets the schema panel show an operator the same characters their registry shows them, down to
  * the field order and the whitespace.
  */
final case class RegisteredSchema(
    subject: Subject,
    version: SchemaVersion,
    id: SchemaId,
    format: SchemaFormat,
    definition: String,
    references: List[SchemaReference]
)

object RegisteredSchema {
  given CanEqual[RegisteredSchema, RegisteredSchema] = CanEqual.derived
}

/** A schema somebody is proposing, which the registry has never seen.
  *
  * It has no id and no version because it has not been registered — that is the whole point of checking it
  * first. The type exists so that "a schema" and "a schema somebody typed into a box" cannot be passed to
  * each other's functions.
  */
final case class ProposedSchema(
    format: SchemaFormat,
    definition: String,
    references: List[SchemaReference]
)

object ProposedSchema {
  given CanEqual[ProposedSchema, ProposedSchema] = CanEqual.derived
}
