package kui.schema.api

import cats.effect.kernel.Sync
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger

import kui.contracts.capability.{CapabilityState, ClusterCapability, DegradedReason, ReasonCode}
import kui.kernel.ClusterId
import kui.schema.application.{ClusterRegistries, RegistryProfile}
import kui.schema.domain.SchemaRegistryPort

/** What the schema service can currently do, per cluster, as the gateway reads it.
  *
  * ==This is the feature, not the paperwork==
  *
  * The schema registry is optional, and it is also the component of a Kafka deployment most likely to be
  * down. Those are two different facts and they must reach the browser as two different states, because they
  * call for opposite behaviour:
  *
  *   - **not configured** — no `kui.clusters.<n>.schemaRegistry.url` for this cluster. The feature is hidden.
  *     No panel, no error, no retry button, nothing red. There is nothing wrong and nothing to fix.
  *   - **degraded** — a registry is configured and did not answer. The feature is visible, the panel explains
  *     what is unreachable, and retrying is worth doing.
  *
  * Collapsing the two is the failure this service exists to avoid. A deployment that never intended to have a
  * registry would otherwise carry a permanently red panel that no action can clear, which trains operators to
  * ignore red panels — including the ones that mean something.
  *
  * ==What a service may say about itself==
  *
  * `available`, `degraded` or `not_configured`, and never `unavailable`: a service answering this request is
  * reachable by definition, and `unavailable` is the *gateway's* verdict when it gets no answer at all
  * (ADR-039 §6).
  *
  * ==Why the probe is one call and not a full check==
  *
  * Reachability is decided by asking each configured registry for its global compatibility level — the
  * cheapest call in the API that every implementation answers, with no subject and no schema involved. It is
  * made once per capability poll and not once per request, and a failure of it degrades one row rather than
  * the service, which is why a registry that is down never makes the Schemas entry vanish from the sidebar.
  */
trait SchemaCapabilities[F[_]] {
  def report: F[Map[ClusterId, ClusterCapability]]
}

object SchemaCapabilities {

  /** The `degraded` discriminator, read off the enum rather than typed out a second time. */
  private val DegradedStatus: String =
    CapabilityState.Degraded(DegradedReason(ReasonCode.Starting, "", None, None)).status

  /** What a row says when this deployment configured no registry for that cluster.
    *
    * It names the configuration key, because the person most likely to read it is the one wondering where the
    * Schemas tab went.
    */
  val NotConfiguredMessage: String =
    "no Schema Registry is configured for this cluster (kui.clusters.<n>.schemaRegistry.url)"

  def make[F[_]: Sync](
      registries: ClusterRegistries[F],
      logger: StructuredLogger[F]
  ): SchemaCapabilities[F] =
    new SchemaCapabilities[F] {

      def report: F[Map[ClusterId, ClusterCapability]] =
        registries.all.flatMap(
          _.traverse(profile => stateOf(profile).map(profile.cluster -> _)).map(_.toMap)
        )

      private def stateOf(profile: RegistryProfile): F[ClusterCapability] =
        if !profile.hasRegistry then notConfigured(profile).pure[F]
        else
          registries.registry(profile.cluster).flatMap {
            // Configured but with no port built is a wiring failure rather than a deployment choice, so
            // it is degraded and says so: reporting it as not configured would hide a KUI bug behind a
            // screen that looks deliberately switched off.
            case None =>
              degraded(profile, "the registry is configured but this process could not build a client for it")
                .pure[F]
            case Some(port) => probe(profile, port)
          }

      /** One cheap call. Anything that is not an answer is a degraded row carrying the reason. */
      private def probe(profile: RegistryProfile, port: SchemaRegistryPort[F]): F[ClusterCapability] =
        port.globalCompatibility
          .map {
            case Right(_) =>
              ClusterCapability(
                configured = true,
                features = Nil,
                status = CapabilityState.Available.status,
                name = Some(profile.displayName),
                reason = None
              )
            case Left(error) => degraded(profile, error.message)
          }
          .handleErrorWith { failure =>
            // The port promises not to throw, so this branch is a defect somewhere below rather than an
            // outage. It is still answered rather than propagated: a capability report that fails takes
            // every other cluster's row down with it, at the exact moment the browser needs the report
            // most.
            logger
              .error(failure)(
                s"the schema registry probe for cluster ${profile.cluster.value} threw instead of " +
                  "returning a typed failure"
              )
              .as(degraded(profile, "the registry probe failed unexpectedly"))
          }
    }

  /** A cluster with no registry configured. `configured = false` is what the gateway folds into
    * `CapabilityState.NotConfigured`, and it is what makes the browser hide the feature for this cluster
    * rather than render an error.
    */
  def notConfigured(profile: RegistryProfile): ClusterCapability =
    ClusterCapability(
      configured = false,
      features = Nil,
      status = CapabilityState.NotConfigured.status,
      name = Some(profile.displayName),
      reason = Some(NotConfiguredMessage)
    )

  /** A configured registry that is not answering. Configured stays `true`: the feature exists here and is
    * having a bad day, which is a different sentence from "this deployment has no registry".
    */
  def degraded(profile: RegistryProfile, reason: String): ClusterCapability =
    ClusterCapability(
      configured = true,
      features = Nil,
      status = DegradedStatus,
      name = Some(profile.displayName),
      reason = Some(reason)
    )
}
