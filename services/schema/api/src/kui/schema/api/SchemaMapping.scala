package kui.schema.api

import kui.kernel.error.{ApplicationError, KuiError}
import kui.kernel.{PageRequest, PageSize, PositiveInt, Subject}
import kui.schema.contract.SubjectListParams
import kui.schema.contract.dto.*
import kui.schema.domain.*

/** Application types to wire types, and wire types to application types (ADR-033).
  *
  * This is the only module allowed to see both, which is what makes the mapping possible without breaking the
  * dependency rules — and it is deliberately dull. Nothing here computes anything: it renames fields, unwraps
  * opaque types on the way out and validates raw values on the way in. A calculation that appeared here would
  * be a calculation the domain could not test.
  */
object SchemaMapping {

  def schema(schema: RegisteredSchema): SchemaDto =
    SchemaDto(
      subject = schema.subject,
      version = schema.version.value,
      id = schema.id.value,
      schemaType = schema.format.label,
      definition = schema.definition,
      references = schema.references.map(reference =>
        SchemaReferenceDto(reference.name, reference.subject, reference.version.value)
      )
    )

  def versions(subject: Subject, versions: List[SchemaVersion]): SubjectVersionsDto =
    SubjectVersionsDto(subject, versions.map(_.value))

  /** The registry-wide level, which nobody inherits. */
  def global(level: CompatibilityLevel): CompatibilityDto =
    CompatibilityDto(level.wire, inheritedFromGlobal = false)

  def subjectCompatibility(compatibility: SubjectCompatibility): CompatibilityDto =
    CompatibilityDto(compatibility.level.wire, compatibility.inheritedFromGlobal)

  def verdict(verdict: CompatibilityVerdict): CompatibilityCheckDto =
    CompatibilityCheckDto(verdict.compatible, verdict.messages)

  /** The query string as a domain query, with the page size **clamped** rather than refused.
    *
    * Answering "you asked for 900 rows and the limit is 500" with a 400 makes every caller write clamping
    * code the server could have written once. Answering with 500 rows and a `pageSize` of 500 in the response
    * tells them the same thing and still works. A page *number* below one is clamped to one for the same
    * reason.
    */
  def query(params: SubjectListParams): SubjectQuery =
    SubjectQuery(
      search = params.q.map(_.trim).filter(_.nonEmpty),
      order = params.direction,
      page = PageRequest(
        PositiveInt.from(math.max(params.page, 1)).getOrElse(PositiveInt.One),
        PageSize.from(math.min(math.max(params.pageSize, 1), PageSize.Max.value)).getOrElse(PageSize.Default)
      )
    )

  /** The version path segment as a selector.
    *
    * A segment that is neither a number nor `latest` is a `400` naming both, and deliberately not a silent
    * fall back to the latest version: a typo that quietly returns the newest schema shows the operator the
    * wrong document with nothing anywhere saying so.
    */
  def version(raw: String): Either[KuiError, VersionSelector] =
    VersionSelector.parse(raw).left.map(message => ApplicationError.Invalid(message, Nil))

  /** A requested compatibility level, refused with the list of the seven KUI knows.
    *
    * The list is in the message because the alternative — "'BAKCWARD' is not a compatibility level" — leaves
    * the reader to guess whether KUI's spelling matches their registry's.
    */
  def level(raw: String): Either[KuiError, CompatibilityLevel] =
    CompatibilityLevel
      .fromWire(raw)
      .toRight(
        ApplicationError.Invalid(
          s"'$raw' is not a compatibility level; the levels are " +
            CompatibilityLevel.values.map(_.wire).mkString(", "),
          Nil
        )
      )

  /** A proposed schema from the browser.
    *
    * The schema type is read through [[SchemaFormat.fromRegistry]], which accepts anything: a registry KUI
    * has never met may know a language KUI does not, and refusing to *ask* it about that language would make
    * KUI the thing standing between an operator and their own registry. The word travels to the registry
    * unchanged, and the registry refuses it if it is nonsense.
    */
  def proposed(request: CompatibilityCheckRequest): ProposedSchema =
    ProposedSchema(
      format = SchemaFormat.fromRegistry(Some(request.schemaType)),
      definition = request.definition,
      references = request.references.flatMap(reference =>
        SchemaVersion
          .from(reference.version)
          .toOption
          .map(version => SchemaReference(reference.name, reference.subject, version))
      )
    )
}
