package kui.cluster.api

import io.scalaland.chimney.Transformer
import io.scalaland.chimney.dsl.*

import kui.cluster.application.{
  CapabilityReport,
  CapabilityState as ApplicationCapabilityState,
  ClusterCapabilityReport,
  ClusterService
}
import kui.contracts.capability.{
  CapabilityState,
  ClusterCapability,
  DegradedReason,
  ReasonCode,
  ServiceCapabilities
}

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
    * a failure and must not be rendered as one. A configured cluster is `available` or `degraded`, and never
    * `unavailable`: a service that is answering this request at all is reachable by definition, and
    * `unavailable` is the gateway's verdict when it gets no answer. A service reporting itself unavailable
    * would be claiming it is not there.
    *
    * `degraded` is what M0 could not produce, because its report had no reason to give. The reason now comes
    * from the use case — an unreachable metadata store, or a first scrape that has not finished — and it
    * reaches the browser, which is the difference between a dimmed menu item and a dimmed menu item that says
    * why.
    */
  def statusOf(report: ClusterCapabilityReport): String =
    if !report.configured then CapabilityState.NotConfigured.status
    else
      report.state match {
        case ApplicationCapabilityState.Available => CapabilityState.Available.status
        case ApplicationCapabilityState.Degraded(_) => DegradedStatus
      }

  /** The reason a cluster is degraded, for the entry that carries one. `None` when it is not degraded. */
  def reasonOf(report: ClusterCapabilityReport): Option[String] =
    report.state match {
      case ApplicationCapabilityState.Available => None
      case ApplicationCapabilityState.Degraded(reason) => Some(reason)
    }

  /** The `degraded` discriminator, read off the enum rather than typed out.
    *
    * `CapabilityState.Degraded` needs a whole `DegradedReason` before it will tell anyone what its `status`
    * string is, and that reason does not reach this wire shape — only the discriminator does. Building one
    * throwaway value to ask it its own name is still better than writing `"degraded"` in a second place,
    * because the enum is where that word is contract.
    */
  private val DegradedStatus: String =
    CapabilityState.Degraded(DegradedReason(ReasonCode.Starting, "", None, None)).status

  /** The per-cluster transformer, as a `given` so the map-valued transformation below can find it. */
  private given Transformer[ClusterCapabilityReport, ClusterCapability] =
    Transformer
      .define[ClusterCapabilityReport, ClusterCapability]
      .withFieldComputed(_.status, statusOf)
      // `name` is a field on both sides and Chimney copies it; `reason` is computed, because on this
      // side it lives inside the state rather than beside it.
      .withFieldComputed(_.reason, reasonOf)
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
