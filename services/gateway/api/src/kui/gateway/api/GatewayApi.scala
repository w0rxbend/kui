package kui.gateway.api

import cats.Parallel
import cats.effect.kernel.Async
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.server.ServerEndpoint

import kui.contracts.capability.ServiceCapabilities
import kui.gateway.application.Gateway
import kui.gateway.contract.GatewayEndpoints
import kui.http.BasePath
import kui.http.health.{HealthEndpoints, ReadinessCheck}

/** Every route the gateway serves, assembled in one place.
  *
  * The list is short in M0 and it grows by task: build info (GW-010), the session and CSRF endpoints
  * (GW-009), the capability registry (GW-003), the static assets and the SPA fallback (GW-008), the proxied
  * service routes (GW-006) and the OpenAPI document (GW-007). Keeping the assembly in one function rather
  * than spreading it through a composition root means the answer to "what does this process actually serve"
  * is one file, and the order routes are matched in is visible rather than emergent.
  */
object GatewayApi {

  /** The gateway's own service id, and the `service.name` it reports itself under. Both are re-exported from
    * the application layer, which owns them, so that a route can name the gateway without every caller having
    * to know which layer the identity lives in.
    */
  export Gateway.{Id, ServiceName}

  /** The routes, in the order the router tries them.
    *
    * @param readiness
    *   what this process checks before saying it can serve. The gateway has **no mandatory upstream**: it is
    *   Core tier (PLAN §15), so if it will not start, the browser gets the full-screen "cannot reach gateway"
    *   page and nothing else works at all. A gateway that refused to start because the schema service was
    *   down would turn one service's outage into a total one, and would do it at exactly the moment an
    *   operator most needs a working UI to diagnose it.
    * @param capabilities
    *   what the gateway can currently do, recomputed per request. GW-003 replaces the M0 placeholder with the
    *   fold over every upstream's readiness.
    */
  def routes[F[_]: {Async, Parallel}](
      readiness: List[ReadinessCheck[F]],
      capabilities: F[ServiceCapabilities]
  ): List[ServerEndpoint[Fs2Streams[F], F]] =
    BasePath.prefixAll(
      GatewayEndpoints.ApiPrefix,
      // `libs/http` builds the health endpoints as `ServerEndpoint[Any, F]` — "needs no capability from the
      // server". The gateway's list is typed on `Fs2Streams`, because some of its routes do need streaming:
      // the capability stream (GW-005) and the re-streamed message browser. `ServerEndpoint` is
      // contravariant in its capability parameter, so an endpoint that requires nothing is usable wherever
      // one that may require streaming is expected, and no conversion is needed.
      HealthEndpoints.make[F](readiness, capabilities)
    )
}
