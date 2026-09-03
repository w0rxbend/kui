package kui.gateway.contract

import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody

import kui.contracts.ErrorEnvelope
import kui.contracts.capability.{CapabilityEntry, CapabilitySnapshot}

/** What the browser asks to find out what works.
  *
  * Three endpoints, and the shape of the set is the decision (ADR-032): one call for everything known right
  * now, one long-lived stream for everything that changes afterwards, and one button that says "ask again,
  * now".
  *
  * ==No cursors on the capability stream==
  *
  * ADR-026 gives KUI a cursor for paged and resumable streams, and this stream deliberately has none. A
  * client that reconnects — with or without a `Last-Event-ID` — is simply sent a fresh full snapshot as its
  * first event. That is correct here and would be wrong for a message browser: capability state is small,
  * current and idempotent, so resending all of it costs nothing and cannot leave a client holding a
  * half-applied history. Resuming from a cursor would mean keeping a per-client backlog of transitions in
  * order to replay something the client can be told outright in one frame.
  *
  * ==Where the stream endpoint is==
  *
  * The snapshot and probe endpoints are here, in the cross-compiled contract, because the browser decodes
  * exactly what the gateway encodes from this source. The stream endpoint is not: describing it needs `fs2`
  * and a server-side event-stream body, neither of which belongs in a module that has to link for the
  * browser, and the browser does not consume it through a generated client anyway — it opens an
  * `EventSource`. `CapabilityRoutes.streamEndpoint` in the `api` module holds it, and `GW-007`'s OpenAPI
  * merge reads all three from `CapabilityRoutes.endpoints`.
  */
object CapabilityEndpoints {

  val StreamPath: String = s"${GatewayEndpoints.ApiPrefix}/capabilities/stream"

  val snapshot: PublicEndpoint[Unit, ErrorEnvelope, CapabilitySnapshot, Any] =
    GatewayEndpoints.base.get
      .in("capabilities")
      .out(jsonBody[CapabilitySnapshot])
      .name("gateway.capabilities.snapshot")
      .summary("Everything KUI can and cannot do right now")
      .description(
        "One entry per configured service. A service that is configured but has not been checked yet " +
          "appears as degraded with reason STARTING; it is never missing, because 'not asked yet' and " +
          "'not deployed' mean different things to whoever is looking."
      )
      .tag("capabilities")

  val probe: PublicEndpoint[String, ErrorEnvelope, CapabilityEntry, Any] =
    GatewayEndpoints.base.post
      .in("capabilities" / path[String]("service").description("The service id to re-check"))
      .in("probe")
      .out(statusCode(sttp.model.StatusCode.Accepted).and(jsonBody[CapabilityEntry]))
      .name("gateway.capabilities.probe")
      .summary("Re-check one service now and return its recomputed state")
      .description(
        "What the UI's 'Retry now' button calls. It waits for the check to finish, so the entry it " +
          "returns is the fresh one rather than the one that was on screen."
      )
      .tag("capabilities")

  val all: List[AnyEndpoint] = List(snapshot, probe)
}
