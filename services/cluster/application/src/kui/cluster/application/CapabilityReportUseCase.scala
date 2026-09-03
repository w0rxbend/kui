package kui.cluster.application

import cats.Applicative
import cats.syntax.all.*

import kui.kernel.ClusterId

/** What this service can do on one cluster.
  *
  * The three fields answer three different questions, and collapsing them into a boolean loses all of it:
  * `configured` says whether this deployment was told about the cluster at all, `features` names the optional
  * things the service found it could do there, and `available` says whether it can do them right now. A
  * registry that is not configured is not broken, and a configured one that cannot be reached is not absent
  * (`ARCHITECTURE.md` §6, ADR-032).
  */
final case class ClusterCapabilityReport(configured: Boolean, features: Set[String], available: Boolean)

object ClusterCapabilityReport {
  given CanEqual[ClusterCapabilityReport, ClusterCapabilityReport] = CanEqual.derived
}

/** Everything this service currently reports about itself, keyed by cluster.
  *
  * This type exists instead of `kui.contracts.capability.ServiceCapabilities`, which is the DTO the gateway
  * actually reads, and the difference is the worked example the rest of the project follows. A use case
  * returns a type its own layer owns; `services/cluster/api` maps that type to the wire DTO with Chimney
  * (ADR-033). If the use case returned the DTO instead, `application` would depend on `libs/contracts-core`,
  * a change to the wire format would be a change to a use case, and `./mill checkArchitecture` would fail the
  * build on rule A3 — which is exactly what it is there for.
  */
final case class CapabilityReport(clusters: Map[ClusterId, ClusterCapabilityReport])

object CapabilityReport {
  given CanEqual[CapabilityReport, CapabilityReport] = CanEqual.derived
}

/** Answering "what can you do right now?", which the gateway asks every service on a schedule. */
trait CapabilityReportUseCase[F[_]] {

  /** In M0 this is a constant: the service is configured, has no cluster-scoped features and reports
    * `available`. M1 replaces the body with real capability probing — an `AdminClient` call per cluster,
    * cached, with the degraded and unavailable states ADR-032 defines.
    */
  def report: F[CapabilityReport]
}

object CapabilityReportUseCase {

  /** The M0 implementation: every configured cluster is reported as configured, featureless and available.
    *
    * It takes the cluster ids rather than inventing them so that the answer is still a function of this
    * deployment's configuration, which is the one thing about it that is true in M0. An empty set is a
    * legitimate answer — a KUI that has been started with no clusters configured yet — and produces an empty
    * report rather than a failure.
    */
  def constant[F[_]: Applicative](clusters: Set[ClusterId]): CapabilityReportUseCase[F] =
    new CapabilityReportUseCase[F] {
      private val answer: CapabilityReport = CapabilityReport(
        clusters
          .map(id => id -> ClusterCapabilityReport(configured = true, Set.empty, available = true))
          .toMap
      )

      def report: F[CapabilityReport] = answer.pure[F]
    }
}
