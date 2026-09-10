package kui.connect.api

import cats.MonadThrow
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger

import kui.connect.application.{ClusterConnectSource, ConnectProfileView}
import kui.connect.contract.ConnectEndpoints
import kui.contracts.capability.{CapabilityState, ClusterCapability, DegradedReason, ReasonCode}
import kui.kernel.error.{ErrorCode, KuiError}
import kui.kernel.{ClusterId, ConnectName}

/** What the connect service can currently do, per cluster, as the gateway reads it.
  *
  * ==Three states, and the schema service's argument for why they are three==
  *
  * Kafka Connect is optional, and it is also a component that is routinely down or mid-rebalance. Those are
  * different facts and they must reach the browser as different states, because they call for opposite
  * behaviour:
  *
  *   - **not configured** — no `kui.clusters.<n>.connect[]` for this cluster. The feature is hidden. No
  *     panel, no error, no retry button, nothing red. There is nothing wrong and nothing to fix, and M9's own
  *     exit criterion is that the drawer shows no Kafka Connect row for such a deployment.
  *   - **degraded** — a Connect cluster is configured and did not answer. The feature is visible, the panel
  *     explains what is unreachable, and retrying is worth doing.
  *   - **available** — every configured Connect cluster answered, **or was rebalancing**. See [[probe]].
  *
  * ==What a service may say about itself==
  *
  * `available`, `degraded` or `not_configured`, and never `unavailable`: a service answering this request is
  * reachable by definition, and `unavailable` is the *gateway's* verdict when it gets no answer at all
  * (ADR-039 §6).
  */
trait ConnectCapabilities[F[_]] {
  def report: F[Map[ClusterId, ClusterCapability]]
}

object ConnectCapabilities {

  /** The `degraded` discriminator, read off the enum rather than typed out a second time. */
  private val DegradedStatus: String =
    CapabilityState.Degraded(DegradedReason(ReasonCode.Starting, "", None, None)).status

  /** What a row says when this deployment configured no Kafka Connect for that cluster.
    *
    * It names the configuration key, because the person most likely to read it is the one wondering where the
    * Kafka Connect row went.
    */
  val NotConfiguredMessage: String =
    "no Kafka Connect cluster is configured for this cluster (kui.clusters.<n>.connect[].url)"

  def make[F[_]: MonadThrow](
      clusters: ClusterConnectSource[F],
      logger: StructuredLogger[F]
  ): ConnectCapabilities[F] =
    new ConnectCapabilities[F] {

      def report: F[Map[ClusterId, ClusterCapability]] =
        clusters.all.flatMap(
          _.traverse(profile => stateOf(profile).map(profile.cluster -> _)).map(_.toMap)
        )

      private def stateOf(profile: ConnectProfileView): F[ClusterCapability] =
        if !profile.configured then notConfigured(profile).pure[F]
        else profile.connects.traverse(connect => probe(profile, connect)).map(worst(profile, _))

      /** One Connect cluster, asked the question the feature itself asks.
        *
        * The probe is the connector list and not something cheaper. `GET /` on a worker is one request
        * smaller and answers whether a socket is open, which is not the question: a Connect cluster whose
        * herder cannot answer `GET /connectors` has a working socket and a Connect screen that cannot draw.
        * The schema service probes with the cheapest call *that the feature depends on*, and this is that
        * call here.
        *
        * **A rebalancing Connect cluster is `available`, and this arm is the rule this packet owns.**
        * `ErrorCode.ConnectRebalancing` is a transient state that clears itself in seconds; a capability
        * dimmed by one is a red badge in the sidebar that an operator cannot clear and learns to ignore,
        * which is exactly what ADR-039 §6 warns about, and the next poll would clear it anyway. The other two
        * halves of the same rule are `ConnectHttp.errorFrom`, which classifies the 409 as an
        * `ApplicationError` so that no failure of this kind reaches the capability registry at all, and
        * `ConnectMapping.section`, which reports it to the screen as `STARTING` rather than as an outage.
        */
      private def probe(profile: ConnectProfileView, connect: ConnectName): F[Option[String]] =
        clusters.worker(profile.cluster, connect).flatMap {
          // Configured but with no client built is a wiring failure rather than a deployment choice, so it
          // is degraded and says so: reporting it as not configured would hide a KUI bug behind a screen
          // that looks deliberately switched off.
          case None =>
            Some(
              s"the Kafka Connect cluster '${connect.value}' is configured and this process could not " +
                "build a client for it"
            ).pure[F]

          case Some(port) =>
            port.connectors
              .map {
                case Right(_) => None
                case Left(error) if rebalancing(error) => None
                case Left(error) => Some(s"'${connect.value}': ${error.message}")
              }
              .handleErrorWith { failure =>
                // The port promises not to throw, so this branch is a defect somewhere below rather than
                // an outage. It is still answered rather than propagated: a capability report that fails
                // takes every other cluster's row down with it, at the exact moment the browser needs the
                // report most.
                logger
                  .error(failure)(
                    s"the Kafka Connect probe for '${connect.value}' on cluster " +
                      s"${profile.cluster.value} threw instead of returning a typed failure"
                  )
                  .as(Some(s"'${connect.value}': the Kafka Connect probe failed unexpectedly"))
              }
        }

      /** Every configured Connect cluster's verdict folded into one row.
        *
        * One failing Connect cluster degrades the cluster's row even when the other answered, because the
        * screen it feeds draws them together and half a screen that says nothing about its missing half is
        * the honesty failure this product is built against. Which one failed is in the message, and the
        * per-worker section in the read is where the detail lives.
        */
      private def worst(profile: ConnectProfileView, probes: List[Option[String]]): ClusterCapability =
        probes.flatten match {
          case Nil => available(profile)
          case reasons => degraded(profile, reasons.mkString("; "))
        }
    }

  /** Whether this failure is a Connect cluster mid-rebalance rather than a Connect cluster with a problem.
    *
    * Named rather than inlined so that the three places that must agree about it can be read together, and so
    * that a case can assert this predicate rather than a copy of it.
    */
  def rebalancing(error: KuiError): Boolean = error.code == ErrorCode.ConnectRebalancing

  /** Which features a cluster's row advertises.
    *
    * A read-only cluster does **not** advertise the three writes, and this is the one place the read-only
    * decision reaches the browser before a request is made. `Action.ConnectOperate` is altering, so the guard
    * refuses it on a read-only cluster; a browser that had been told the feature was there would draw an
    * enabled Restart button whose only possible outcome is a refusal, and a control that changes position
    * between users is worse than a disabled one (`SCREENS-V4.md` §3.7).
    *
    * The names are derived from the published contract rather than written out, for `AlertsCapabilities`'
    * reason: a list written out is a list that goes stale, and a fifth endpoint added to
    * `ConnectEndpoints.all` and forgotten here would be a feature the browser never asks for on a cluster
    * that can answer it.
    */
  def featuresFor(readOnly: Boolean): List[String] =
    if readOnly then Features.filterNot(WriteFeatures.contains) else Features

  /** Everything this service does, each named by its own endpoint. */
  val Features: List[String] = ConnectEndpoints.all.flatMap(_.info.name)

  /** The three writes, named by their own endpoints for the same reason. */
  val WriteFeatures: List[String] = ConnectEndpoints.writes.flatMap(_.info.name)

  /** A cluster with no Kafka Connect configured. `configured = false` is what the gateway folds into
    * `CapabilityState.NotConfigured`, and it is what makes the browser hide the feature for this cluster
    * rather than render an error.
    */
  def notConfigured(profile: ConnectProfileView): ClusterCapability =
    ClusterCapability(
      configured = false,
      features = Nil,
      status = CapabilityState.NotConfigured.status,
      name = Some(profile.displayName),
      reason = Some(NotConfiguredMessage)
    )

  def available(profile: ConnectProfileView): ClusterCapability =
    ClusterCapability(
      configured = true,
      features = featuresFor(profile.readOnly),
      status = CapabilityState.Available.status,
      name = Some(profile.displayName),
      reason = None
    )

  /** A configured Connect cluster that is not answering. `configured` stays `true`: the feature exists here
    * and is having a bad day, which is a different sentence from "this deployment has no Kafka Connect".
    *
    * The features are still advertised, deliberately. A degraded row means the screen should be drawn and
    * should explain itself; a row that also withdrew its features would make the browser hide the tab at the
    * moment the operator most needs to see why it is empty.
    */
  def degraded(profile: ConnectProfileView, reason: String): ClusterCapability =
    ClusterCapability(
      configured = true,
      features = featuresFor(profile.readOnly),
      status = DegradedStatus,
      name = Some(profile.displayName),
      reason = Some(reason)
    )
}
