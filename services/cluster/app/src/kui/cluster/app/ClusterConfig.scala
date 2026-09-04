package kui.cluster.app

import kui.config.{ClusterConfig, KuiConfig, PrincipalKeyConfig, ServerConfig, StoreConfig, TelemetryConfig}
import kui.security.rbac.RbacPolicy

/** Everything the cluster process reads out of the configuration, and nothing else.
  *
  * `libs/config` loads one `KuiConfig` for every KUI process, because the precedence chain — defaults, then
  * files, then environment, then flags — is a product decision that must not be implemented eleven times.
  * This is the cluster service's slice of the result.
  *
  * Narrowing is worth a type of its own for two reasons. A composition root that takes exactly what it uses
  * cannot quietly grow a dependency on a section belonging to another service, and a suite can build one of
  * these in a line instead of assembling a whole `KuiConfig` to exercise one field.
  *
  * @param server
  *   the interface, the port and the base path this process serves under
  * @param telemetry
  *   where traces and metrics go, and how log lines are rendered
  * @param principalKeys
  *   the keys this service will accept a signed `X-Kui-Principal` from (ADR-020)
  * @param clusters
  *   the clusters this deployment was told about in its configuration file. They are the *static base* of the
  *   registry: a record in the metadata store overlays the entry with the same id, which is how a cluster
  *   registered at runtime beats one written into the file it was copied from
  * @param store
  *   where KUI keeps its own state. With `kui.store.kafka.*` set this is a Kafka-backed store that is
  *   replayed at startup; with only `kui.store.dir` set it is a read-only directory; with neither, there is
  *   no store and the clusters above are all there are - which is a supported deployment, not a mistake
  */
final case class ClusterServiceConfig(
    server: ServerConfig,
    telemetry: TelemetryConfig,
    principalKeys: List[PrincipalKeyConfig],
    clusters: List[ClusterConfig],
    store: StoreConfig,
    // This deployment's roles, so that the service can re-run the gateway's permission decision itself
    // (ADR-021) rather than trusting the edge. Without it the cluster-write routes had no policy to consult
    // and refused every caller.
    rbac: RbacPolicy
)

object ClusterServiceConfig {

  /** The cluster service's slice of a loaded configuration.
    *
    * ==Why the keys come from `kui.gateway.principalKeys`==
    *
    * They look like a gateway setting and they are not: they are the *shared* key set of one deployment. The
    * gateway signs with the newest key whose `notBefore` has passed and every service accepts any key in the
    * set, which is what makes a rotation a rolling change rather than an outage. One list, configured once,
    * read by both sides — a service with its own separate list would be a service that could be rotated out
    * of step with the gateway that signs for it.
    *
    * The key is spelled `kui.gateway.principalKeys` because ADR-020 put it there and CFG-001 implemented it
    * there. Renaming it to something neutral is a configuration-compatibility change and belongs in its own
    * commit, not in this one.
    */
  def from(config: KuiConfig): ClusterServiceConfig =
    ClusterServiceConfig(
      config.server,
      config.telemetry,
      config.gateway.principalKeys,
      config.clusters,
      config.store,
      config.rbac
    )

  /** What the process runs on when nothing at all is configured: every interface, port 8080, no telemetry
    * exporter and — deliberately — no signing keys, which is a configuration that refuses to start in a
    * distributed deployment. See [[kui.http.principal.ProcessPrincipalCodec.make]] for why that refusal is
    * the right default.
    */
  val Default: ClusterServiceConfig = from(KuiConfig.Default)

  given CanEqual[ClusterServiceConfig, ClusterServiceConfig] = CanEqual.derived
}
