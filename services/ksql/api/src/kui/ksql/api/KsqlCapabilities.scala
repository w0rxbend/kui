package kui.ksql.api

import cats.MonadThrow
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger

import kui.contracts.capability.{CapabilityState, ClusterCapability, DegradedReason, ReasonCode}
import kui.kernel.ClusterId
import kui.ksql.application.{ClusterKsqlSource, KsqlProfileView}
import kui.ksql.contract.KsqlEndpoints

/** What the ksql service can currently do, per cluster, as the gateway reads it.
  *
  * ==Three states, and the schema service's argument for why they are three==
  *
  * ksqlDB is optional, and it is also a component that is routinely down or still building its metastore.
  * Those are different facts and they must reach the browser as different states, because they call for
  * opposite behaviour:
  *
  *   - **not configured** — no `kui.clusters.<n>.ksql.url` for this cluster. The feature is hidden. No panel,
  *     no error, no retry button, nothing red. There is nothing wrong and nothing to fix, and ADR-032's rule
  *     is that the drawer shows no ksqlDB row at all for such a deployment;
  *   - **degraded** — a ksqlDB is configured and did not answer. The feature is visible, the panel explains
  *     what is unreachable, and retrying is worth doing;
  *   - **available** — the configured ksqlDB answered.
  *
  * ==What a service may say about itself==
  *
  * `available`, `degraded` or `not_configured`, and never `unavailable`: a service answering this request is
  * reachable by definition, and `unavailable` is the *gateway's* verdict when it gets no answer at all
  * (ADR-039 §6).
  */
trait KsqlCapabilities[F[_]] {
  def report: F[Map[ClusterId, ClusterCapability]]
}

object KsqlCapabilities {

  /** The `degraded` discriminator, read off the enum rather than typed out a second time. */
  private val DegradedStatus: String =
    CapabilityState.Degraded(DegradedReason(ReasonCode.Starting, "", None, None)).status

  /** What a row says when this deployment configured no ksqlDB for that cluster.
    *
    * It names the configuration key, because the person most likely to read it is the one wondering where the
    * ksqlDB row went.
    */
  val NotConfiguredMessage: String =
    "no ksqlDB is configured for this cluster (kui.clusters.<n>.ksql.url)"

  def make[F[_]: MonadThrow](
      clusters: ClusterKsqlSource[F],
      logger: StructuredLogger[F]
  ): KsqlCapabilities[F] =
    new KsqlCapabilities[F] {

      def report: F[Map[ClusterId, ClusterCapability]] =
        clusters.all.flatMap(
          _.traverse(profile => stateOf(profile).map(profile.cluster -> _)).map(_.toMap)
        )

      private def stateOf(profile: KsqlProfileView): F[ClusterCapability] =
        if !profile.configured then notConfigured(profile).pure[F] else probe(profile)

      /** The one server, asked the question the feature itself asks.
        *
        * The probe is the object listing and not something cheaper. `GET /info` is one request smaller and
        * answers whether a socket is open, which is not the question: a ksqlDB whose metastore cannot answer
        * `SHOW STREAMS` has a working socket and a ksqlDB screen that cannot draw. The schema service probes
        * with the cheapest call *that the feature depends on*, and this is that call here.
        */
      private def probe(profile: KsqlProfileView): F[ClusterCapability] =
        clusters.client(profile.cluster).flatMap {
          // Configured but with no client built is a wiring failure rather than a deployment choice, so it
          // is degraded and says so: reporting it as not configured would hide a KUI bug behind a screen
          // that looks deliberately switched off.
          case None =>
            degraded(
              profile,
              "a ksqlDB is configured for this cluster and this process could not build a client for it"
            ).pure[F]

          case Some(client) =>
            client.objects
              .map {
                case Right(_) => available(profile)
                case Left(error) => degraded(profile, error.message)
              }
              .handleErrorWith { failure =>
                // The port promises not to throw, so this branch is a defect somewhere below rather than
                // an outage. It is still answered rather than propagated: a capability report that fails
                // takes every other cluster's row down with it, at the exact moment the browser needs the
                // report most.
                logger
                  .error(failure)(
                    s"the ksqlDB probe for cluster ${profile.cluster.value} threw instead of returning " +
                      "a typed failure"
                  )
                  .as(degraded(profile, "the ksqlDB probe failed unexpectedly"))
              }
        }
    }

  /** Which features a cluster's row advertises.
    *
    * A read-only cluster advertises the **read only**, and this is the one place the read-only decision
    * reaches the browser before a request is made. `Action.KsqlExecute` is altering, so the guard refuses it
    * on a read-only cluster; a browser that had been told the feature was there would draw an enabled Run
    * button whose only possible outcome is a refusal, and a control that changes position between users is
    * worse than a disabled one (`SCREENS-V4.md` §3.7).
    *
    * The names are derived from the published contract rather than written out, for `AlertsCapabilities`'
    * reason: a list written out is a list that goes stale, and a fourth endpoint added to `KsqlEndpoints.all`
    * and forgotten here would be a feature the browser never asks for on a cluster that can answer it. The
    * stream is added explicitly because it is not in `all` — it cannot be, since `ContractRouting.derive` may
    * not see it — and a feature the browser is never told about is a screen that never opens a stream.
    */
  def featuresFor(readOnly: Boolean): List[String] =
    if readOnly then Features.filterNot(WriteFeatures.contains) else Features

  /** Everything this service does, each named by its own endpoint, plus the stream. */
  val Features: List[String] =
    KsqlEndpoints.all.flatMap(_.info.name) :+ KsqlEndpoints.StreamOperation

  /** Everything a read-only cluster does not do: both statement phases and the push query. */
  val WriteFeatures: List[String] =
    KsqlEndpoints.writes.flatMap(_.info.name) :+ KsqlEndpoints.StreamOperation

  /** A cluster with no ksqlDB configured. `configured = false` is what the gateway folds into
    * `CapabilityState.NotConfigured`, and it is what makes the browser hide the feature for this cluster
    * rather than render an error.
    */
  def notConfigured(profile: KsqlProfileView): ClusterCapability =
    ClusterCapability(
      configured = false,
      features = Nil,
      status = CapabilityState.NotConfigured.status,
      name = Some(profile.displayName),
      reason = Some(NotConfiguredMessage)
    )

  def available(profile: KsqlProfileView): ClusterCapability =
    ClusterCapability(
      configured = true,
      features = featuresFor(profile.readOnly),
      status = CapabilityState.Available.status,
      name = Some(profile.displayName),
      reason = None
    )

  /** A configured ksqlDB that is not answering. `configured` stays `true`: the feature exists here and is
    * having a bad day, which is a different sentence from "this deployment has no ksqlDB".
    *
    * The features are still advertised, deliberately. A degraded row means the screen should be drawn and
    * should explain itself; a row that also withdrew its features would make the browser hide the tab at the
    * moment the operator most needs to see why it is empty.
    */
  def degraded(profile: KsqlProfileView, reason: String): ClusterCapability =
    ClusterCapability(
      configured = true,
      features = featuresFor(profile.readOnly),
      status = DegradedStatus,
      name = Some(profile.displayName),
      reason = Some(reason)
    )
}
