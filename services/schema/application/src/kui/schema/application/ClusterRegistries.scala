package kui.schema.application

import kui.kernel.ClusterId
import kui.kernel.error.{ApplicationError, ErrorCode, KuiError}
import kui.schema.domain.SchemaRegistryPort

/** One cluster, as this service needs to see it.
  *
  * @param hasRegistry
  *   whether a Schema Registry was configured for this cluster. It is the fact the whole service is shaped
  *   around: `false` is not a failure and must never render as one — the capability report says
  *   `not_configured`, the routes answer `KUI-UNSUPPORTED`, and the browser hides the feature for that
  *   cluster instead of showing a panel that will stay red forever.
  * @param readOnly
  *   whether this deployment is allowed to change anything on this cluster (`kui.clusters.<n>.readOnly`,
  *   ADR-047). It gates the two compatibility writes, and nothing else here.
  */
final case class RegistryProfile(
    cluster: ClusterId,
    displayName: String,
    hasRegistry: Boolean,
    readOnly: Boolean
)

object RegistryProfile {
  given CanEqual[RegistryProfile, RegistryProfile] = CanEqual.derived
}

/** Which clusters exist, and which of them have a registry KUI can talk to.
  *
  * ==Why the two questions are one interface==
  *
  * "I have never heard of this cluster" and "I know this cluster and it has no registry" are different
  * answers with different HTTP statuses, different screens and different meanings to an operator — the first
  * is a bad link, the second is a deployment choice. A single lookup that returned `Option[Port]` would
  * collapse them, and every route would then have to consult a second source to tell them apart. Keeping both
  * behind one interface is what makes [[RegistryAccess.resolve]] able to answer correctly in one call, which
  * is the only place in the service that decides between the two.
  */
trait ClusterRegistries[F[_]] {

  /** Every cluster this service knows about, registry or not. The capability report is built from this, which
    * is why clusters *without* a registry are in it: a cluster missing from the report reads as "the service
    * has never heard of it", and the screen would have nothing to grey out.
    */
  def all: F[List[RegistryProfile]]

  /** One cluster's profile, or `None` when no such cluster is configured. */
  def profile(cluster: ClusterId): F[Option[RegistryProfile]]

  /** The registry client for one cluster, or `None` when that cluster has none configured. */
  def registry(cluster: ClusterId): F[Option[SchemaRegistryPort[F]]]
}

/** Turning "which cluster" into "the registry to ask, or the reason there is none", once.
  *
  * Every use case in this service starts with the same three-way decision, and it is written here rather than
  * six times: an unknown cluster is a 404 naming the cluster, a known cluster with no registry is
  * `KUI-UNSUPPORTED` naming the feature, and everything else is a port to call. Repeating it per use case is
  * how the third case ends up spelled `KUI-CLUSTER-NOT-FOUND` in one route and `KUI-UNSUPPORTED` in the next,
  * which are two different screens for one situation.
  */
object RegistryAccess {

  /** The sentence the browser shows when a cluster has no registry. It says what is missing and what to do,
    * because "unsupported" on its own reads like a KUI limitation rather than a configuration choice.
    */
  def notConfigured(cluster: ClusterId): KuiError =
    ApplicationError.Unsupported(
      s"cluster ${cluster.value} has no Schema Registry configured; set " +
        "kui.clusters.<n>.schemaRegistry.url to use subjects, schemas and compatibility here"
    )

  def unknownCluster(cluster: ClusterId): KuiError =
    ApplicationError.NotFound("cluster", cluster.value, ErrorCode.ClusterNotFound)
}
