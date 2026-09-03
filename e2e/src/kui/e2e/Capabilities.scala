package kui.e2e

import io.circe.Json

/** One service's line in `GET /api/v1/capabilities`, reduced to what an assertion looks at.
  *
  * A parsed view rather than raw JSON, so that a suite says `capability.status == "unavailable"` instead of
  * navigating a cursor in the middle of an assertion, and so the shape of that document is described in
  * exactly one place in this module.
  *
  * @param reason
  *   the machine-readable code (`UPSTREAM_UNAVAILABLE`), absent while the service is available.
  * @param since
  *   when the state last changed, as the gateway wrote it. The fallback panel renders this value, so the test
  *   compares the two rather than trusting either alone.
  */
final case class Capability(
    service: String,
    status: String,
    reason: Option[String],
    message: Option[String],
    since: Option[String]
)

object Capabilities {

  /** Reads the snapshot and picks out one service, or `None` when the gateway did not answer, the document
    * could not be parsed, or it holds no line for that service. All three are states an assertion should
    * treat as "not what I was waiting for" rather than as a crash, because they all happen legitimately while
    * a stack is still starting.
    */
  def of(baseUrl: String, service: String): Option[Capability] =
    Http.getJson(s"$baseUrl/api/v1/capabilities").flatMap(entryFor(_, service))

  /** The raw document, for the failure report. What the gateway actually said is the first thing anybody
    * wants when a capability assertion fails, and reformatting it would lose the very detail that explains
    * the failure.
    */
  def raw(baseUrl: String): String =
    Http.getJson(s"$baseUrl/api/v1/capabilities").fold("<no answer from /api/v1/capabilities>")(_.spaces2)

  private def entryFor(document: Json, service: String): Option[Capability] = {
    val entries = document.hcursor.downField("entries").values.getOrElse(Nil)

    entries.collectFirst {
      case entry
          if entry.hcursor.downField("key").downField("service").as[String].toOption.contains(service) =>
        val state = entry.hcursor.downField("state")
        Capability(
          service = service,
          status = state.downField("status").as[String].getOrElse("unknown"),
          reason = state.downField("reason").as[String].toOption,
          message = state.downField("message").as[String].toOption,
          since = state.downField("since").as[String].toOption
        )
    }
  }
}
