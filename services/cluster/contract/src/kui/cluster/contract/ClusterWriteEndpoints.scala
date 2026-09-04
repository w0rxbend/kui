package kui.cluster.contract

import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody

import kui.cluster.contract.dto.{ClusterProfileDto, ClusterWriteRequest, ConnectivityDto}
import kui.contracts.KernelSchemas.given
import kui.contracts.rbac.{EndpointAuthorization, ResourceRequirement}
import kui.contracts.{ErrorEnvelope, KuiEndpoint}
import kui.kernel.ClusterId
import kui.security.rbac.{Action, Resource}
import kui.security.SignedPrincipal

/** Registering, changing, removing and testing a cluster, from the administration screen.
  *
  * ==Why this list used to be separate, and why it no longer is==
  *
  * M1 shipped `put` alone and deliberately kept it out of `ClusterEndpoints.all`, because the gateway derives
  * its public routes from that list and there was no screen to call it from. The endpoint existed only so
  * that the metadata store's guarantees could be demonstrated with a writer.
  *
  * That is now a defect rather than a decision. The screen exists, so the list is published like any other:
  * `ServiceContracts` names it beside `ClusterEndpoints.all`, the gateway derives its routes from both, and
  * `ApplicationConfig.Edit` — not the absence of a route — is what stops an unauthorised caller. A permission
  * is a rule the product can state; an unrouted endpoint is only a rule nobody has got round to breaking.
  *
  * ==Everything here is marked==
  *
  * Each of the three writes carries `KuiEndpoint.MutationKey` and the CSRF header, which the M1 `put` did
  * not. A mutation without the marker is invisible to read-only mode's enumeration (ADR-047): the endpoint
  * would keep answering on a cluster an operator had marked read-only, and nothing would report it as an
  * exception. `probe` carries the marker with `destructive = false`, for the reason
  * `ConsumerMutationEndpoints.planReset` does: it changes nothing, and it must still be refused where the
  * write it precedes would be refused, so that the form does not validate a connection the operator is not
  * allowed to save.
  */
object ClusterWriteEndpoints {

  /** The header a caller states the version it is replacing in. */
  val IfMatchHeader: String = "If-Match"

  /** The `If-Match` value that means "create this cluster; fail if it already exists". */
  val CreateTag: String = "\"0\""

  val ProbeSegment: String = "connection-test"

  /** What all three need: the right to edit KUI's own configuration.
    *
    * `ApplicationConfig` and not `ClusterConfig`. `ClusterConfig` is a *Kafka* cluster's broker settings;
    * this is KUI's own list of which clusters exist and how to reach them, which is application configuration
    * whichever Kafka cluster it happens to name. The distinction matters for a real role: an operator who may
    * tune brokers is not thereby an operator who may add a production cluster to the tool with credentials of
    * their choosing.
    */
  private def editsApplicationConfig(operation: String): EndpointAuthorization =
    EndpointAuthorization.one(
      operation,
      ResourceRequirement.unnamed(Resource.ApplicationConfig, Action.ApplicationConfigEdit)
    )

  /** The operation names, which are also the `MutationKind` names in the application layer and the strings in
    * every audit record.
    */
  val WriteOperation: String = "cluster.write"
  val DeleteOperation: String = "cluster.delete"

  private val clustersBase = "internal" / "v1" / ClusterEndpoints.ClustersSegment

  private val clusterIdPath: EndpointInput[ClusterId] =
    path[ClusterId](ClusterEndpoints.ClusterIdParam).description("The cluster's slug id")

  private val ifMatch: EndpointInput[String] =
    header[String](IfMatchHeader)
      .description("The version being replaced, quoted; \"0\" to create")

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
    (String, ClusterId, String, ClusterWriteRequest),
    ErrorEnvelope,
    ClusterProfileDto,
    Any
  ] =
    KuiEndpoint
      .mutation(WriteOperation, destructive = false)
      .put
      .in(clustersBase / clusterIdPath)
      .in(ifMatch)
      .in(jsonBody[ClusterWriteRequest])
      .out(jsonBody[ClusterProfileDto])
      .name("cluster.put")
      .attribute(EndpointAuthorization.Key, editsApplicationConfig("cluster.put"))
      .summary("Register or replace one cluster")
      .description(
        KuiEndpoint.mutationNote(WriteOperation, destructive = false) +
          "Answers only after the write has been read back from the store, so a 200 means every replica " +
          "that has caught up can already see it. A version that does not match answers 409 " +
          "KUI-CONFIG-VERSION-CONFLICT; a deployment with no metadata store answers 501. The response is " +
          "the redacted profile and never an echo of the request."
      )
      .tag("cluster")

  /** `DELETE /internal/v1/clusters/{clusterId}`.
    *
    * `If-Match` is required here for the same reason as on `put`, and one more: an unconditional delete races
    * with somebody else's edit, and the operator who loses that race watches the cluster they were fixing
    * disappear.
    *
    * `destructive = true`: the profile, including its credentials, is gone, and KUI cannot put it back.
    */
  val delete: Endpoint[SignedPrincipal, (String, ClusterId, String), ErrorEnvelope, Unit, Any] =
    KuiEndpoint
      .mutation(DeleteOperation, destructive = true)
      .delete
      .in(clustersBase / clusterIdPath)
      .in(ifMatch)
      .name("cluster.delete")
      .attribute(EndpointAuthorization.Key, editsApplicationConfig("cluster.delete"))
      .summary("Remove one cluster from the metadata store")
      .description(
        KuiEndpoint.mutationNote(DeleteOperation, destructive = true) +
          "Removes the stored profile and its credentials. A cluster this deployment also declares in its " +
          "configuration file answers 409: the store record would go and the file would put it straight " +
          "back, so the refusal names the file instead of pretending to succeed. A deployment with no " +
          "metadata store answers 501."
      )
      .tag("cluster")

  /** `POST /internal/v1/clusters/connection-test`.
    *
    * The body is the same `ClusterWriteRequest` the form is about to save, and there is no cluster id in the
    * path, because the whole point is to answer before anything has been written. Testing a *saved* cluster
    * is the dashboard's existing per-row status and needs no second endpoint.
    *
    * It is bounded by the probe's own five-second timeout rather than the admin client's minute, so a form
    * against a dead address answers while the operator is still looking at it.
    */
  val probe: Endpoint[SignedPrincipal, (String, ClusterWriteRequest), ErrorEnvelope, ConnectivityDto, Any] =
    KuiEndpoint
      .mutation(WriteOperation, destructive = false)
      .post
      .in(clustersBase / ProbeSegment)
      .in(jsonBody[ClusterWriteRequest])
      .out(jsonBody[ConnectivityDto])
      .name("cluster.probe")
      .attribute(EndpointAuthorization.Key, editsApplicationConfig("cluster.probe"))
      .summary("Can KUI reach this cluster with these settings?")
      .description(
        KuiEndpoint.mutationNote(WriteOperation, destructive = false) +
          "Opens one bounded connection and describes the cluster, which exercises DNS, TCP, TLS and SASL " +
          "in one call. It stores nothing. The answer distinguishes 'could not reach it' from 'reached it " +
          "and it refused our credentials', because those send an operator to two different forms."
      )
      .tag("cluster")

  val all: List[AnyEndpoint] = List(put, delete, probe)

  /** The endpoints that change the deployment, read from the marker rather than listed by hand. */
  val mutating: List[AnyEndpoint] =
    all.filter(endpoint => endpoint.attribute(KuiEndpoint.MutationKey).exists(_.destructive))
}
