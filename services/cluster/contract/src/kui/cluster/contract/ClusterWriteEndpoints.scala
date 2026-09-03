package kui.cluster.contract

import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody

import kui.cluster.contract.dto.{ClusterProfileDto, ClusterWriteRequest}
import kui.contracts.KernelSchemas.given
import kui.contracts.{ErrorEnvelope, KuiEndpoint}
import kui.kernel.ClusterId
import kui.security.SignedPrincipal

/** The one write endpoint of M1, in a list of its own.
  *
  * Separate from `ClusterEndpoints.all` deliberately, and the separation is what keeps it off the public API.
  * The gateway derives its public routes from that list; this one is concatenated only into the service's own
  * OpenAPI document, so the endpoint is reachable by an internal caller and by tests and by no browser. M8
  * adds the public route together with the wizard that calls it and the permission that guards it.
  *
  * It exists now, a milestone before its user interface, because the metadata store's guarantees cannot be
  * demonstrated without a writer: two replicas racing on one key, a write that is visible before it is
  * acknowledged, and a deployment with no store saying so plainly.
  */
object ClusterWriteEndpoints {

  /** The header a caller states the version it is replacing in. */
  val IfMatchHeader: String = "If-Match"

  /** The `If-Match` value that means "create this cluster; fail if it already exists". */
  val CreateTag: String = "\"0\""

  /** `PUT /internal/v1/clusters/{clusterId}`.
    *
    * `If-Match` is **required**, not optional. An unconditional write to a versioned record is a lost update
    * waiting for a second replica, and making the header optional would make the safe path the one a caller
    * has to remember. `"0"` means create, which keeps one code path instead of two endpoints.
    *
    * The version travels in the header rather than in the body because it is metadata *about* the record and
    * not part of it: in the body, a caller could send a version that disagrees with the record it is
    * replacing, and "the same body applied twice" would stop having one meaning.
    */
  val put: Endpoint[
    SignedPrincipal,
    (ClusterId, String, ClusterWriteRequest),
    ErrorEnvelope,
    ClusterProfileDto,
    Any
  ] =
    KuiEndpoint.internal.put
      .in(
        "internal" / "v1" / ClusterEndpoints.ClustersSegment /
          path[ClusterId](ClusterEndpoints.ClusterIdParam).description("The cluster's slug id")
      )
      .in(
        header[String](IfMatchHeader)
          .description("The version being replaced, quoted; \"0\" to create")
      )
      .in(jsonBody[ClusterWriteRequest])
      .out(jsonBody[ClusterProfileDto])
      .name("cluster.put")
      .summary("Register or replace one cluster")
      .description(
        "Answers only after the write has been read back from the store, so a 200 means every replica " +
          "that has caught up can already see it. A version that does not match answers 409 " +
          "KUI-CONFIG-VERSION-CONFLICT; a deployment with no metadata store answers 501. The response is " +
          "the redacted profile and never an echo of the request."
      )
      .tag("cluster")

  val all: List[AnyEndpoint] = List(put)
}
