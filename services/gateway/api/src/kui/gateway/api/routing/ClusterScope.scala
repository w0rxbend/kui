package kui.gateway.api.routing

import kui.cluster.contract.ClusterEndpoints
import kui.contracts.PublicApi
import kui.kernel.{ClusterId, ValidationError}

/** Which cluster a request is about, decided from its path and nothing else.
  *
  * A pure function over the path segments, so every case is testable without HTTP and without a stub upstream
  * — the same shape `CsrfCheck.verdict` uses, and for the same reason: the table of cases *is* the
  * specification, and a table is reviewable in a way a chain of `if`s inside a request handler is not.
  *
  * ==Why the path and not the decoded input==
  *
  * `ContractRouting` works over `AnyEndpoint`s whose input types Tapir has erased, so recovering a typed path
  * capture would need a third cast on top of the two the routing already justifies. The path is on the
  * request, the rule is one line, and it is the same rule for every cluster-scoped endpoint that the topic,
  * message and schema services will add in M2 to M8.
  *
  * ==Why syntax and never membership==
  *
  * A well-formed id the deployment has never heard of is forwarded, and the cluster service answers
  * `404 KUI-CLUSTER-NOT-FOUND`. The gateway holding a list of clusters would be domain state in the module
  * ADR-004 defines as holding none; it would be stale exactly when it matters — a cluster added a second ago
  * — and it would give two different 404s for one question depending on which copy was fresher.
  */
object ClusterScope {

  /** The path segment that introduces a cluster id, read off the contract rather than typed again. */
  val Segment: String = ClusterEndpoints.ClustersSegment

  /** The public prefix's segments, so `/api/v1/topics/clusters/x` is not mistaken for a cluster path. */
  private val PublicPrefix: List[String] = ContractRouting.pathSegments(PublicApi.prefix)

  enum Scope {

    /** Not about one cluster. `/api/v1/capabilities`, and also `/api/v1/clusters` itself, which is about all
      * of them and must not send a cluster header: an arbitrary label there would put a meaningless value on
      * every metric the dashboard aggregation produces.
      */
    case None

    case Cluster(id: ClusterId)

    /** The path names a cluster and the name is not one. A 400 before the upstream is called. */
    case Malformed(raw: String, error: ValidationError)
  }

  /** The scope of a request, from its decoded path segments with the base path already removed.
    *
    * The id is the segment immediately after the *first* `clusters` segment, and only when the path starts
    * with the public prefix — so `/api/v1/topics/clusters/x`, where `clusters` is a topic name, is not
    * cluster-scoped.
    */
  def of(segments: List[String]): Scope =
    // `indexOfSlice` rather than `startsWith`, because a deployment served under a base path - `/kui` in the
    // Compose stack - carries that base path in the request's own segments while the endpoint definitions
    // know nothing about it. Looking for the public prefix wherever it is keeps one rule for both shapes.
    segments.indexOfSlice(PublicPrefix) match {
      case -1 => Scope.None
      case at =>
        segments.drop(at + PublicPrefix.size) match {
          case Segment :: raw :: _ =>
            ClusterId.from(raw) match {
              case Right(id) => Scope.Cluster(id)
              case Left(error) => Scope.Malformed(raw, error)
            }
          case _ => Scope.None
        }
    }

  /** The cluster to label a metric, a span and a log line with. `None` for everything else. */
  def clusterOf(scope: Scope): Option[ClusterId] = scope match {
    case Scope.Cluster(id) => Some(id)
    case Scope.None | Scope.Malformed(_, _) => scala.None
  }

  given CanEqual[Scope, Scope] = CanEqual.derived
}
