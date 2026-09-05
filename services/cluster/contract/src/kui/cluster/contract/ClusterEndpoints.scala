package kui.cluster.contract

import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody

import kui.cluster.contract.dto.*
import kui.contracts.KernelSchemas.given
import kui.contracts.rbac.{EndpointAuthorization, ResourceRequirement}
import kui.contracts.{ErrorEnvelope, KuiEndpoint}
import kui.kernel.{BrokerId, ClusterId}
import kui.security.SignedPrincipal
import kui.security.rbac.{Action, Resource}

/** Everything `kui-cluster-service` serves, described once.
  *
  * This is the single source ADR-003 asks for: the same values produce the server's routes, the gateway's
  * client, the browser's client and the OpenAPI document. Nothing is served that is not in `all`, and no path
  * is written out by hand anywhere else — a hand-written path is a path that drifts from the handler it was
  * supposed to name.
  *
  * The paths start at `/internal/v1` and not at `/api/v1`. `/api/v1` is the public prefix and it belongs to
  * the gateway (`ARCHITECTURE.md` §5); a service is only ever called by the gateway, over the internal
  * prefix, with a signed principal header.
  *
  * The path and query codecs for `ClusterId` and `BrokerId` come from `KernelSchemas`, which is where every
  * kernel identifier acquires its transport form. A slug the smart constructor refuses therefore fails to
  * decode — a 400 naming `clusterId` — instead of reaching a lookup that cannot match and answering 404. The
  * two mean different things to a caller: "that is not an id" and "no such cluster".
  */
object ClusterEndpoints {

  val ClustersSegment: String = "clusters"
  val BrokersSegment: String = "brokers"
  val ConfigsSegment: String = "configs"
  val LogDirsSegment: String = "log-dirs"
  val RefreshSegment: String = "refresh"

  val ClusterIdParam: String = "clusterId"
  val BrokerIdParam: String = "brokerId"

  /** The query flag that asks a broker for each setting's own documentation.
    *
    * It defaults to false because `DescribeConfigsOptions.includeDocumentation` exists only from Kafka 2.6
    * and roughly doubles the size of the answer. The configs tab asks for it; nothing else does. On a broker
    * too old to supply it the field is simply absent, never an error.
    */
  val DocsParam: String = "docs"

  private val clustersBase = "internal" / "v1" / ClustersSegment

  private val clusterIdPath: EndpointInput[ClusterId] =
    path[ClusterId](ClusterIdParam).description("The configured cluster's slug id")

  private val brokerIdPath: EndpointInput[BrokerId] =
    path[BrokerId](BrokerIdParam).description("The broker's node id, as the cluster reports it")

  /** Every configured cluster, with each one's last known state. The dashboard's single call. */
  val listClusters: Endpoint[SignedPrincipal, Unit, ErrorEnvelope, ClustersResponse, Any] =
    KuiEndpoint.internal.get
      .in(clustersBase)
      .out(jsonBody[ClustersResponse])
      .name("cluster.list")
      .attribute(EndpointAuthorization.Key, EndpointAuthorization.clusterScoped("cluster.list"))
      .summary("Every configured cluster and its last known state")
      .description(
        "The list of clusters comes from configuration overlaid by the metadata store and is always " +
          "available; each row's summary is a section that can be stale or unavailable on its own."
      )
      .tag("cluster")

  /** One cluster. A deep link has to work without fetching the other thirty-nine. */
  val getCluster: Endpoint[SignedPrincipal, ClusterId, ErrorEnvelope, ClusterDetailResponse, Any] =
    KuiEndpoint.internal.get
      .in(clustersBase / clusterIdPath)
      .out(jsonBody[ClusterDetailResponse])
      .name("cluster.get")
      .attribute(EndpointAuthorization.Key, EndpointAuthorization.clusterScoped("cluster.get"))
      .summary("One configured cluster and its last known state")
      .description("Answers 404 when no cluster with that id is configured; 400 when the id is malformed.")
      .tag("cluster")

  /** The cluster's brokers, from the snapshot the refresh loop keeps. */
  val listBrokers: Endpoint[SignedPrincipal, ClusterId, ErrorEnvelope, BrokersResponse, Any] =
    KuiEndpoint.internal.get
      .in(clustersBase / clusterIdPath / BrokersSegment)
      .out(jsonBody[BrokersResponse])
      .name("cluster.brokers")
      .attribute(EndpointAuthorization.Key, EndpointAuthorization.clusterScoped("cluster.brokers"))
      .summary("The cluster's brokers, with rack, controller flag and replica counts")
      .description(
        "Read from the cluster snapshot, not from a fresh admin call: the response carries the time it " +
          "was scraped, and a section status saying whether that time is current."
      )
      .tag("cluster")

  /** One broker's settings. `docs` asks the broker for each setting's own description. */
  val brokerConfigs
      : Endpoint[SignedPrincipal, (ClusterId, BrokerId, Boolean), ErrorEnvelope, BrokerConfigsResponse, Any] =
    KuiEndpoint.internal.get
      .in(clustersBase / clusterIdPath / BrokersSegment / brokerIdPath / ConfigsSegment)
      .in(
        query[Boolean](DocsParam)
          .description("Ask the broker for each setting's documentation (Kafka 2.6+)")
          .default(false)
      )
      .out(jsonBody[BrokerConfigsResponse])
      .name("cluster.broker.configs")
      .attribute(
        EndpointAuthorization.Key,
        EndpointAuthorization.one(
          "cluster.broker.configs",
          ResourceRequirement.unnamed(Resource.ClusterConfig, Action.ClusterConfigView)
        )
      )
      .summary("One broker's settings, read-only")
      .description(
        "Read-only in M1: BR-002 ships without edits, which arrive in M5 with read-only mode and audit. " +
          "A setting the broker marks sensitive has no value and says so."
      )
      .tag("cluster")

  /** Log directories for the whole cluster, or narrowed to one broker.
    *
    * One endpoint rather than a sub-resource of a broker, because the brokers *list* page wants every
    * broker's totals in one call while the broker-detail tab wants one broker's. Two endpoints would make the
    * list page issue one request per broker to fill a column.
    */
  val logDirs: Endpoint[SignedPrincipal, (ClusterId, Option[BrokerId]), ErrorEnvelope, LogDirsResponse, Any] =
    KuiEndpoint.internal.get
      .in(clustersBase / clusterIdPath / LogDirsSegment)
      .in(
        query[Option[BrokerId]](BrokerIdParam)
          .description("Only this broker's directories; omit for every broker in the cluster")
      )
      .out(jsonBody[LogDirsResponse])
      .name("cluster.logDirs")
      .attribute(EndpointAuthorization.Key, EndpointAuthorization.clusterScoped("cluster.logDirs"))
      .summary("Log directories and their sizes, for one broker or for all of them")
      .description(
        "A directory that is offline carries its own error while the rest of the answer is good, which " +
          "is how Kafka reports a failed disk."
      )
      .tag("cluster")

  /** Asks the snapshot loop to read this cluster now.
    *
    * 202, not 200: the answer means the request was taken, not that the snapshot is new. The refresh is
    * asynchronous by design (DEVPLAN D10) — the browser does not poll, it reads a snapshot and shows when it
    * was taken, and this is the user's control over that.
    */
  val refresh: Endpoint[SignedPrincipal, ClusterId, ErrorEnvelope, RefreshAcceptedDto, Any] =
    KuiEndpoint.internal.post
      .in(clustersBase / clusterIdPath / RefreshSegment)
      .out(jsonBody[RefreshAcceptedDto])
      .out(statusCode(sttp.model.StatusCode.Accepted))
      .name("cluster.refresh")
      .attribute(EndpointAuthorization.Key, EndpointAuthorization.clusterScoped("cluster.refresh"))
      .summary("Ask for this cluster to be scraped now")
      .description(
        "Answers 202 with the time the request was taken. The snapshot is not new when this returns; " +
          "the response exists so a button has something truthful to say."
      )
      .tag("cluster")

  /** Every endpoint this service serves. The gateway and the OpenAPI generator read this.
    *
    * The health and capability endpoints are deliberately absent: they are identical in all eleven services
    * and come from `libs/http` (HTTP-002) rather than being redeclared once per service, which is how eleven
    * copies of the same path end up disagreeing.
    */
  val all: List[AnyEndpoint] =
    List(listClusters, getCluster, listBrokers, brokerConfigs, logDirs, refresh)
}
