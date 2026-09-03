package kui.cluster.application

import java.time.Instant

import cats.effect.kernel.Temporal
import cats.syntax.all.*

import kui.cluster.domain.{ClusterFeatures, StoreHealth}
import kui.kernel.ClusterId

/** The state this service reports for one cluster.
  *
  * Deliberately a *subset* of the states the gateway can hold: this service can say `Available` or `Degraded`
  * and never `Unavailable`, because a service that is answering the capability request at all is by
  * definition reachable. `Unavailable` is the gateway's verdict when it gets no answer, and a self-reported
  * `Unavailable` would be a service claiming it is not there.
  */
enum CapabilityState {
  case Available
  case Degraded(reason: String)
}

object CapabilityState {
  given CanEqual[CapabilityState, CapabilityState] = CanEqual.derived
}

/** What this service reports about one cluster. */
final case class ClusterCapabilityReport(
    /** Always `true` for an entry that is present at all — an absent entry *is* "not configured". It is kept
      * as an explicit field because the wire DTO has it and the gateway's fold reads it.
      */
    configured: Boolean,
    /** The health of *this service's* ability to serve this cluster. */
    state: CapabilityState,
    /** Whether the last topology refresh against the cluster's brokers succeeded. `false` does not make
      * `state` anything other than `Available`: an unreachable managed cluster is a section of a healthy
      * response, not a broken capability.
      */
    reachable: Boolean,
    /** The probed capability tokens. Empty for a cluster that has never been reached, which reads correctly
      * as "KUI knows of nothing it can do here yet".
      */
    features: Set[String],
    /** When the topology snapshot was last successfully refreshed. `None` before the first success. */
    scrapedAt: Option[Instant]
) {

  /** The M0 shape's single boolean, kept as a derived member.
    *
    * **Temporary.** `services/cluster/api` still maps this field onto the wire's four-state discriminator,
    * and that mapping is another area's file. It is derived rather than stored so the two cannot disagree,
    * and the task that rewires the service deletes it together with the M0 mapping.
    */
  def available: Boolean = state match {
    case CapabilityState.Available => true
    case CapabilityState.Degraded(_) => false
  }
}

object ClusterCapabilityReport {

  /** The M0 three-field shape. **Temporary**, for the callers that have not been rewired yet; it reports a
    * cluster with no probe behind it, which is exactly what those callers were already asserting.
    */
  def apply(configured: Boolean, features: Set[String], available: Boolean): ClusterCapabilityReport =
    ClusterCapabilityReport(
      configured = configured,
      state =
        if available then CapabilityState.Available
        else CapabilityState.Degraded(CapabilityReportUseCase.StartingReason),
      reachable = available,
      features = features,
      scrapedAt = None
    )

  given CanEqual[ClusterCapabilityReport, ClusterCapabilityReport] = CanEqual.derived
}

/** Everything this service currently reports about itself, keyed by cluster.
  *
  * It is this type and not the wire DTO the gateway reads, and the difference is the worked example the rest
  * of the project follows: a use case returns a type its own layer owns, and `services/cluster/api` maps it.
  * Returning the DTO instead would make a change to the wire format a change to a use case, and the layering
  * check would fail the build for it.
  */
final case class CapabilityReport(
    clusters: Map[ClusterId, ClusterCapabilityReport],
    /** The metadata store's state, reported once rather than per cluster, because it is one fact about the
      * deployment. Each cluster's `state` already carries its consequence.
      *
      * The default exists only until the callers that predate it are rewired: a deployment that has not been
      * told about a store has none, which is what `NotConfigured` says.
      */
    storeHealth: StoreHealth = StoreHealth.NotConfigured
)

object CapabilityReport {
  given CanEqual[CapabilityReport, CapabilityReport] = CanEqual.derived
}

/** Answering "what can you do right now?", which the gateway asks every service on a schedule. */
trait CapabilityReportUseCase[F[_]] {
  def report: F[CapabilityReport]
}

object CapabilityReportUseCase {

  val Operation: String = "kui.cluster.capabilities"

  /** The mapping table as a pure, total function of the two observations.
    *
    * The invariant it encodes, stated once so that a later edit which "helpfully" reports an unreachable
    * cluster as degraded fails a test: **no state of any managed Kafka cluster can move this service's
    * reported capability below `Available`.** Only two things can — the metadata store being degraded, and
    * the process not having finished starting. Everything else about a managed cluster is data on a page.
    *
    * The store row comes first because it takes precedence: a degraded store degrades every entry whatever
    * the clusters themselves are doing, since configuration changes cannot be accepted and the profiles being
    * served are last-known.
    */
  def stateOf(
      freshness: SnapshotFreshness,
      storeHealth: StoreHealth
  ): (CapabilityState, Boolean) = {
    val reachable = freshness.isCurrent

    storeHealth match {
      case StoreHealth.Degraded(reason, _) =>
        (CapabilityState.Degraded(s"configuration store: $reason"), reachable)

      case StoreHealth.Online | StoreHealth.NotConfigured =>
        freshness match {
          // The browser matches on this exact word to render "starting" rather than an outage, so
          // it is a constant and not a sentence.
          case SnapshotFreshness.Loading => (CapabilityState.Degraded(StartingReason), false)
          case SnapshotFreshness.Fresh(_) => (CapabilityState.Available, true)
          case SnapshotFreshness.Stale(_, _, _) => (CapabilityState.Available, false)
          case SnapshotFreshness.Unavailable(_, _) => (CapabilityState.Available, false)
        }
    }
  }

  /** The reason string a starting service reports. Matched on by the browser; not display prose. */
  val StartingReason: String = "starting"

  /** Builds the report from the registry and the snapshot cells.
    *
    * `report` never fails and never blocks: it reads the registry's `Ref` and one cell per cluster, all in
    * memory. That is required rather than incidental — the gateway polls this endpoint every ten seconds per
    * replica, and a capability endpoint that can hang is a capability registry that reports the wrong thing
    * about a service that is fine.
    */
  def make[F[_]: Temporal](
      registry: ClusterRegistry[F],
      snapshots: ClusterSnapshots[F]
  ): CapabilityReportUseCase[F] =
    new CapabilityReportUseCase[F] {

      def report: F[CapabilityReport] =
        registry.snapshot.flatMap { resolved =>
          resolved.profiles.keys.toList
            .traverse(id => entryOf(id, resolved.storeHealth).map(id -> _))
            .map(entries => CapabilityReport(entries.toMap, resolved.storeHealth))
        }

      private def entryOf(
          id: ClusterId,
          storeHealth: StoreHealth
      ): F[ClusterCapabilityReport] =
        for {
          topology <- snapshots.topologyOf(id)
          capabilities <- snapshots.capabilitiesOf(id)
          snapshot <- topology.traverse(_.get)
          probed <- capabilities.traverse(_.get)
        } yield {
          val freshness =
            snapshot.map(ClusterTopologyUseCase.freshnessOf).getOrElse(SnapshotFreshness.Loading)

          val (state, reachable) = stateOf(freshness, storeHealth)

          ClusterCapabilityReport(
            configured = true,
            state = state,
            reachable = reachable,
            features = probed.flatMap(_.value).fold(Set.empty[String])(tokensOf),
            scrapedAt = snapshot.flatMap(_.scrapedAt)
          )
        }

      /** Only the features the probe established are *present*.
        *
        * A feature the probe could not determine stays out of the set rather than being reported as
        * supported, and — the half that matters — it is not reported as *absent* either: the three-set
        * `ClusterFeatures` keeps that distinction, and collapsing it here would cache a lie for an hour.
        */
      private def tokensOf(features: ClusterFeatures): Set[String] = features.tokens
    }

  /** Every configured cluster reported as configured, featureless and available.
    *
    * **Temporary.** It is what the service answered before it could probe anything, and it survives one
    * commit only because the wiring that would replace it lives in another area's module — a red `main`
    * shared by seven parallel lanes is worse than one extra constructor for a day.
    */
  def constant[F[_]: cats.Applicative](clusters: Set[ClusterId]): CapabilityReportUseCase[F] =
    new CapabilityReportUseCase[F] {
      private val answer: CapabilityReport = CapabilityReport(
        clusters.map(id => id -> ClusterCapabilityReport(true, Set.empty[String], true)).toMap,
        StoreHealth.NotConfigured
      )

      def report: F[CapabilityReport] = cats.Applicative[F].pure(answer)
    }
}
