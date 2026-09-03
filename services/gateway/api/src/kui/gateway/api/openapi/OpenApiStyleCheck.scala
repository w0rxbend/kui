package kui.gateway.api.openapi

import sttp.apispec.openapi.{OpenAPI, Operation}

/** One way the merged document falls short of what KUI promises an integrator. */
enum StyleViolation {
  case MissingOperationId(path: String, method: String)
  case MissingSummary(operationId: String)
  case MissingTag(operationId: String)
  case DuplicateOperationId(operationId: String)
  case NonKebabPath(path: String)
  case ErrorResponseIsNotTheEnvelope(operationId: String, status: Int)

  def message: String = this match {
    case MissingOperationId(path, method) =>
      s"$method $path has no operation id of the form <service>.<operation>; add " +
        ".name(\"cluster.listTopics\") to the endpoint"
    case MissingSummary(id) => s"'$id' has no summary; add .summary(\"...\") to the endpoint"
    case MissingTag(id) => s"'$id' has no tag, so the docs cannot group it under its service"
    case DuplicateOperationId(id) => s"the operation id '$id' appears more than once"
    case NonKebabPath(path) => s"'$path' is not lowercase kebab-case"
    case ErrorResponseIsNotTheEnvelope(id, status) =>
      s"'$id' answers $status with something other than the ErrorEnvelope every KUI error uses (ADR-034)"
  }
}

object StyleViolation {
  given CanEqual[StyleViolation, StyleViolation] = CanEqual.derived
}

/** The house rules for the published API, checked on the merged document in CI (ADR-003).
  *
  * They are not aesthetic. Each one is a promise an integrator relies on and cannot check for themselves: an
  * operation id is the method name a generated client gets, a summary is the only prose most people read, a
  * tag is what puts an endpoint in the right section, a consistent path style is what makes a URL guessable,
  * and one error shape is what lets a client write error handling once instead of per endpoint.
  *
  * Checked here rather than in review because a review catches the endpoint someone thought about, and the
  * problem is always the one nobody did.
  */
object OpenApiStyleCheck {

  val EnvelopeSchema: String = "ErrorEnvelope"

  /** Path segments may be lowercase letters, digits and dashes; `{parameters}` are exempt, since their names
    * are Scala identifiers and are camelCase by convention throughout Tapir.
    */
  private val KebabSegment = "^[a-z0-9]+(-[a-z0-9]+)*$".r

  def check(doc: OpenAPI): List[StyleViolation] = {
    val operations: List[(String, String, Operation)] =
      doc.paths.pathItems.toList.flatMap((path, item) => methodsOf(path, item))

    val ids: List[String] = operations.flatMap(_._3.operationId)
    val duplicates: List[String] =
      ids.groupBy(identity).collect { case (id, more) if more.sizeIs > 1 => id }.toList.sorted

    val perPath = doc.paths.pathItems.keys.toList.filterNot(isKebab).map(StyleViolation.NonKebabPath.apply)

    val perOperation = operations.flatMap(entry => violations(entry._1, entry._2, entry._3))

    (perPath ++ perOperation ++ duplicates.map(StyleViolation.DuplicateOperationId.apply)).distinct
  }

  /** `PathItem` exposes one `Option[Operation]` per HTTP method rather than a map, so the methods KUI uses
    * are listed here. A method missing from this list would silently skip the style check, which is why it
    * names all of them rather than only the two M0 needs.
    */
  private def methodsOf(
      path: String,
      item: sttp.apispec.openapi.PathItem
  ): List[(String, String, Operation)] =
    List(
      ("GET", item.get),
      ("PUT", item.put),
      ("POST", item.post),
      ("DELETE", item.delete),
      ("OPTIONS", item.options),
      ("HEAD", item.head),
      ("PATCH", item.patch),
      ("TRACE", item.trace)
    ).collect { case (method, Some(operation)) => (path, method, operation) }

  /** What a KUI operation id looks like: the service, a dot, then the operation.
    *
    * The shape is checked and not merely the presence, because Tapir *always* produces an operation id --
    * when an endpoint has no `.name` it synthesises one from the path, such as `getInternalV1Ping`. That
    * synthesised id is stable only as long as the path never changes, so a client generated against it
    * silently loses a method the day someone renames a path segment. Requiring the dotted form is what makes
    * "every operation has an id" mean "somebody chose one".
    */
  private val OperationId = "^[a-z][a-zA-Z0-9]*(\\.[a-z][a-zA-Z0-9]*)+$".r

  private def violations(path: String, method: String, operation: Operation): List[StyleViolation] =
    operation.operationId.filter(OperationId.matches) match {
      case None => List(StyleViolation.MissingOperationId(path, method))
      case Some(id) =>
        List(
          Option.when(operation.summary.forall(_.isBlank))(StyleViolation.MissingSummary(id)),
          Option.when(operation.tags.isEmpty)(StyleViolation.MissingTag(id))
        ).flatten ++ errorShape(id, operation)
    }

  /** Every 4xx and 5xx must answer with the one envelope (ADR-034).
    *
    * The check is on the *reference* rather than on the schema's contents: an inline copy of the same fields
    * would pass a structural comparison and would still give a generated client a second, separate error type
    * to handle.
    */
  private def errorShape(id: String, operation: Operation): List[StyleViolation] = {
    val explicit = operation.responses.responses.toList.collect {
      case (sttp.apispec.openapi.ResponsesCodeKey(code), response) if code >= 400 =>
        (code, mentionsEnvelope(response.toString))
    }

    val default = operation.responses.responses.toList.collectFirst {
      case (sttp.apispec.openapi.ResponsesDefaultKey, response) => mentionsEnvelope(response.toString)
    }

    val wrongExplicit =
      explicit.collect { case (code, false) => StyleViolation.ErrorResponseIsNotTheEnvelope(id, code) }

    // Every KUI endpoint is built from `KuiEndpoint.base`, whose error output is the envelope with no
    // status attached, so it lands under `default`. An operation with neither an envelope `default` nor a
    // single explicit envelope response documents no error shape at all, which leaves a client to guess.
    val missingAny =
      Option.when(default.contains(false) || (default.isEmpty && explicit.isEmpty))(
        StyleViolation.ErrorResponseIsNotTheEnvelope(id, DefaultResponse)
      )

    wrongExplicit ++ missingAny
  }

  /** The status reported for a missing or non-envelope `default` response. Zero, because `default` is not a
    * status: it is what an OpenAPI document says for "any other outcome".
    */
  val DefaultResponse: Int = 0

  private def mentionsEnvelope(rendered: String): Boolean = rendered.contains(EnvelopeSchema)

  private def isKebab(path: String): Boolean =
    path
      .split('/')
      .filter(_.nonEmpty)
      .filterNot(segment => segment.startsWith("{") && segment.endsWith("}"))
      .forall(KebabSegment.matches)

  /** The violations as one message, for a build failure that explains itself. */
  def report(violations: List[StyleViolation]): String =
    violations.map(violation => s"  - ${violation.message}").mkString("\n")
}
