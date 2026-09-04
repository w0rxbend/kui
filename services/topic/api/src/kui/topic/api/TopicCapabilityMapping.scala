package kui.topic.api

import kui.contracts.capability.{
  CapabilityState,
  ClusterCapability,
  DegradedReason,
  ReasonCode,
  ServiceCapabilities
}
import kui.kernel.ClusterId
import kui.topic.application.TopicCapability

/** What the topic service can currently do, as the gateway reads it.
  *
  * `TopicCapability` is a type `services/topic/application` owns; `ServiceCapabilities` is a type
  * `libs/contracts-core` publishes to the gateway and the browser. They stay two types because the moment
  * they become one, a change to the wire format becomes a change to a use case — and `checkArchitecture`
  * fails rule A3 on the import that would make it possible.
  *
  * ==What a topic service may and may not say about itself==
  *
  * `available` or `degraded`, never `unavailable`. A service that is answering this request at all is
  * reachable by definition; `unavailable` is the *gateway's* verdict when it gets no answer, and a service
  * reporting it about itself would be claiming it is not there. The application layer's `Unavailable` case —
  * "this cluster has never been scraped, or its scrape failed with nothing in hand" — is a statement about
  * one Kafka cluster, and it is reported here as `degraded` with the reason.
  *
  * That distinction is DEVPLAN §10 D11 and ADR-039 §6: a Kafka cluster the topic service cannot reach must
  * not dim the Topics entry in the sidebar. It is one cluster's row that goes grey, not the feature.
  */
object TopicCapabilityMapping {

  /** The `degraded` discriminator, read off the enum rather than typed out.
    *
    * `CapabilityState.Degraded` needs a whole `DegradedReason` before it will say what its own `status`
    * string is, and only the discriminator reaches this wire shape. Building one throwaway value to ask it
    * its name is still better than writing "degraded" in a second place, because the enum is where that word
    * is contract.
    */
  private val DegradedStatus: String =
    CapabilityState.Degraded(DegradedReason(ReasonCode.Starting, "", None, None)).status

  def statusOf(capability: TopicCapability): String = capability match {
    case TopicCapability.Available(_) => CapabilityState.Available.status
    case TopicCapability.Degraded(_, _, _) => DegradedStatus
    case TopicCapability.Unavailable(_, _) => DegradedStatus
  }

  /** The sentence a dimmed row carries. `None` when there is nothing wrong to say. */
  def reasonOf(capability: TopicCapability): Option[String] = capability match {
    case TopicCapability.Available(_) => None
    case TopicCapability.Degraded(reason, _, _) => Some(reason)
    case TopicCapability.Unavailable(reason, _) => Some(reason)
  }

  def cluster(capability: TopicCapability): ClusterCapability =
    ClusterCapability(
      // Every cluster in this report came from the profiles this service holds, so all of them are
      // configured. A cluster that is not configured is absent from the map, which is what
      // `CapabilityState.NotConfigured` means to the reader.
      configured = true,
      // No feature flags in M2. The topic service's abilities are the same on every cluster it can read;
      // `describeLogDirs` availability shows up as an absent size on a row, not as a missing feature.
      features = Nil,
      status = statusOf(capability),
      name = None,
      reason = reasonOf(capability)
    )

  /** The document `GET /capabilities` answers with.
    *
    * `service` has no counterpart in the application type and is supplied here: "which service am I" is not
    * something a use case should have to state on every answer, and `TopicApi.Id` is the one place it is
    * written down.
    */
  def toWire(report: List[(ClusterId, TopicCapability)]): ServiceCapabilities =
    ServiceCapabilities(
      service = TopicApi.Id,
      clusters = report.map((id, capability) => id -> cluster(capability)).toMap
    )
}
