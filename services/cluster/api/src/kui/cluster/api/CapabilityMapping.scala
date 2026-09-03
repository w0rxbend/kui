package kui.cluster.api

import java.time.Instant

import io.scalaland.chimney.Transformer
import io.scalaland.chimney.dsl.*

import kui.cluster.application.{CapabilityReport, ClusterCapabilityReport, ClusterService}
import kui.contracts.capability.{CapabilityState, ClusterCapability, ReasonCode, ServiceCapabilities}

/** Turning what the use case said into what the gateway reads.
  *
  * This is the worked example the rest of KUI copies, so it is worth saying plainly what it is an example
  * *of*. `CapabilityReport` is a type `services/cluster/application` owns; `ServiceCapabilities` is a type
  * `libs/contracts-core` publishes to the browser and to the gateway. They look almost the same today and
  * they are still two types, because the moment they become one, a change to the wire format becomes a change
  * to a use case — and `./mill checkArchitecture` fails rule A3 on the import that would make it possible.
  *
  * The `api` module is where the two meet. That placement is ADR-041's, and it is the only layer that is
  * allowed to know both halves.
  *
  * ==What the mapping actually decides==
  *
  * Three fields become three, and only one of them is a rename-free copy:
  *
  *   - `configured` is carried across unchanged;
  *   - `features` is a `Set[String]` in the application layer and a `List[String]` on the wire, because JSON
  *     has no set — Chimney converts the collection;
  *   - `available`, a `Boolean`, becomes `status`, a string. This is the interesting one. ADR-032 says a
  *     capability has four states and not two, so the boolean has to be widened at the boundary rather than
  *     published as-is. The three strings this can produce are `CapabilityState`'s own, taken from that enum
  *     rather than written out here, so a rename there is a compile error rather than a wire change nobody
  *     noticed.
  */
object CapabilityMapping {

  /** How one cluster's report is spelled on the wire.
    *
    * A cluster this deployment was never told about is `not_configured`, which ADR-032 is explicit is **not**
    * a failure and must not be rendered as one. A configured cluster is `available` or `unavailable`
    * depending on whether the service can reach it right now. `degraded` cannot be produced here in M0: it
    * carries a reason and a suggested poll interval, and the M0 report has neither to give.
    */
  def statusOf(report: ClusterCapabilityReport): String =
    if !report.configured then CapabilityState.NotConfigured.status
    else if report.available then CapabilityState.Available.status
    else UnavailableStatus

  /** The `unavailable` discriminator, read off the enum rather than typed out.
    *
    * `CapabilityState.Unavailable` needs a reason, a message and an instant before it will tell anyone what
    * its `status` string is, and none of those three reach the wire from here — only the discriminator does.
    * Building one throwaway value to ask it its own name is still better than writing `"unavailable"` in a
    * second place, because the enum is where that word is contract.
    */
  private val UnavailableStatus: String =
    CapabilityState.Unavailable(ReasonCode.UpstreamUnavailable, "", Instant.EPOCH).status

  /** The per-cluster transformer, as a `given` so the map-valued transformation below can find it. */
  private given Transformer[ClusterCapabilityReport, ClusterCapability] =
    Transformer
      .define[ClusterCapabilityReport, ClusterCapability]
      .withFieldComputed(_.status, statusOf)
      .buildTransformer

  /** The document `GET /capabilities` answers with.
    *
    * `service` has no counterpart in the application type and is supplied here, because "which service am I"
    * is not something a use case should have to state on every answer — it is a fact about this deployment,
    * and `ClusterService.Id` is the one place it is written down.
    */
  def toWire(report: CapabilityReport): ServiceCapabilities =
    report
      .into[ServiceCapabilities]
      .withFieldConst(_.service, ClusterService.Id)
      .transform
}
