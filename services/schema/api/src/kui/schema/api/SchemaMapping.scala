package kui.schema.api

import kui.kernel.error.{ApplicationError, KuiError}
import kui.kernel.{PageRequest, PageSize, PositiveInt, Subject}
import kui.schema.contract.dto.*
import kui.schema.contract.{SchemaEndpoints, SubjectListParams}
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

  /** A subject list row.
    *
    * The three enriched fields travel exactly as they arrived, `None` included. An absent field is the wire's
    * way of saying nobody found out, and defaulting one here — a zero version count, the registry's
    * documented compatibility default — would be this module computing something, which is the one thing it
    * is not allowed to do.
    */
  def summary(summary: SubjectSummary): SubjectSummaryDto =
    SubjectSummaryDto(
      subject = summary.subject,
      format = summary.format.map(_.label),
      versionCount = summary.versionCount,
      compatibility = summary.compatibility.map(subjectCompatibility)
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

  /** What the registry stored. The absent version travels as absent.
    *
    * `version.map(_.value)` and never a `getOrElse(0)` or a `-1`: the registry numbers versions from one, so
    * any number this could invent is a version somebody could go looking for. `None` on the wire is the
    * screen's cue to say the schema registered and the number could not be read.
    */
  def registered(registered: RegisteredVersion): RegisteredVersionDto =
    RegisteredVersionDto(
      subject = registered.subject,
      id = registered.id.value,
      version = registered.version.map(_.value)
    )

  /** A schema somebody wants registered.
    *
    * The same reading as [[proposed]] and deliberately a separate method over a separate request type: the
    * two are one shape today and are not one decision, and a shared mapper is how a change meant for a
    * question ends up changing what gets written.
    */
  def toRegister(request: RegisterSchemaRequest): ProposedSchema =
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

  /** The query string as a domain query, with the page size **clamped** rather than refused.
    *
    * Answering "you asked for 900 rows and the limit is 100" with a 400 makes every caller write clamping
    * code the server could have written once. Answering with 100 rows and a `pageSize` of 100 in the response
    * tells them the same thing and still works. A page *number* below one is clamped to one for the same
    * reason.
    *
    * The ceiling is `SchemaEndpoints.MaxPageSize` and not `PageSize.Max`, because this list's page size is
    * not a slice of a list KUI already holds — it is the number of registry requests the answer costs, three
    * per row. The kernel's 500 is the bound on how much memory a page is; a hundred is the bound on what one
    * screen may ask of a single-writer registry.
    *
    * Zero rows is the one page size that is not clamped up. It is the drawer badge's request: the total,
    * counted, with nothing enriched. Clamping it to one would put back the four registry requests that the
    * caller asked not to pay — see `SchemaEndpoints.CountOnlyPageSize`. The `PageRequest` still carries a
    * legal page size because [[kui.kernel.PageSize]] has no zero; `SubjectQuery.countOnly` is what the
    * catalogue reads. The size it carries is still used — `SubjectCatalog.page` hands it to `Page.of`, which
    * cuts a page and then has its items replaced by `Nil` — so it is harmless rather than unused, and this
    * sentence used to claim the second thing.
    *
    * **Exactly** zero, and not "zero or less". A negative page size used to fall into the count-only branch
    * through a `<=`, which made `?pageSize=-1` a wire behaviour no parameter description mentioned and no
    * case covered: a caller with an off-by-one got a total and no rows and no complaint. A number below the
    * range is now clamped up to one row, which is the same rule the page *number* follows.
    */
  def query(params: SubjectListParams): SubjectQuery =
    SubjectQuery(
      search = params.q.map(_.trim).filter(_.nonEmpty),
      order = params.direction,
      page = PageRequest(
        PositiveInt.from(math.max(params.page, 1)).getOrElse(PositiveInt.One),
        PageSize
          .from(math.min(math.max(params.pageSize, 1), SchemaEndpoints.MaxPageSize))
          .getOrElse(PageSize.Default)
      ),
      countOnly = params.pageSize == SchemaEndpoints.CountOnlyPageSize
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
