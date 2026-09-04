package kui.ui.messages.filter

import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody

import kui.contracts.KernelSchemas.given
import kui.contracts.{ErrorEnvelope, KuiEndpoint, PublicApi}
import kui.kernel.ClusterId
import kui.message.contract.*

/** The two filter calls, as the *browser* makes them (MS-007).
  *
  * Built the same way `ProduceApi` is and for the same reason: a browser talks to the gateway, whose public
  * path is the service's own with `/internal/v1` rewritten to `/api/v1` and the signed principal replaced by
  * the session cookie, so the endpoint the browser calls cannot be the value the service serves. Every
  * segment and every document here still comes from `FilterEndpoints` and its DTOs, so a rename in the
  * contract stops this file compiling instead of producing a 404 at run time.
  */
object FilterApi {

  private val filtersOf: EndpointInput[ClusterId] =
    PublicApi.prefix / FilterEndpoints.ClustersSegment /
      path[ClusterId](FilterEndpoints.ClusterIdParam) /
      FilterEndpoints.MessagesSegment /
      FilterEndpoints.FiltersSegment

  /** `POST /api/v1/clusters/{clusterId}/messages/filters` — compile an expression and get its id.
    *
    * This is the call the editor makes when somebody presses Apply, and it is what turns a typo into an
    * underlined line rather than into a browse that reads a million records and matches none of them.
    */
  val register: PublicEndpoint[(ClusterId, FilterRegistrationDto), ErrorEnvelope, FilterIdDto, Any] =
    KuiEndpoint.base.post
      .in(filtersOf)
      .in(jsonBody[FilterRegistrationDto])
      .out(jsonBody[FilterIdDto])
      .name("message.filter.register")

  /** `POST /api/v1/clusters/{clusterId}/messages/filters/test` — try an expression against one record.
    *
    * The record is one the browser already has, so this costs no Kafka read and can be run against the row a
    * user is looking at: "would this filter have kept this record?" is the question that turns a guess into
    * an answer before a scan is started.
    */
  val test: PublicEndpoint[(ClusterId, FilterTestDto), ErrorEnvelope, FilterTestResultDto, Any] =
    KuiEndpoint.base.post
      .in(filtersOf / FilterEndpoints.TestSegment)
      .in(jsonBody[FilterTestDto])
      .out(jsonBody[FilterTestResultDto])
      .name("message.filter.test")

  /** Every client this file has. The suite walks it, so a third cannot be added untested. */
  val all: List[AnyEndpoint] = List(register, test)
}
