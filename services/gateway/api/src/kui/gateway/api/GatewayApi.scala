package kui.gateway.api

import cats.Parallel
import cats.effect.kernel.Async
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.server.ServerEndpoint

import kui.config.{GatewayConfig, ServerConfig}
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
    * @param config
    *   the sections the routes read: where this deployment is mounted, and which services it names
    * @param capabilities
    *   what the gateway can currently do, recomputed per request. GW-003 replaces the M0 placeholder with the
    *   fold over every upstream's readiness.
    */
  def routes[F[_]: {Async, Parallel}](
      config: GatewayServiceConfigView,
      readiness: List[ReadinessCheck[F]],
      capabilities: F[ServiceCapabilities]
  ): List[ServerEndpoint[Fs2Streams[F], F]] =
    // Two kinds of route, prefixed differently, and the difference is worth stating rather than
    // discovering. The gateway's own endpoints are built from `GatewayEndpoints.base`, which already
    // carries `/api/v1` — that is deliberate, because a contract module is what the OpenAPI document and
    // the browser's client are generated from, and both need the real public path. The health endpoints
    // come from `libs/http` and are shared with all eleven services, which serve them at the root of their
    // own `/internal/v1`-shaped world, so the gateway adds the prefix to those.
    //
    // `libs/http` types them as `ServerEndpoint[Any, F]` — "needs no capability from the server" — while
    // the gateway's list is typed on `Fs2Streams`, because some of its routes do need streaming: the
    // capability stream (GW-005) and the re-streamed message browser. `ServerEndpoint` is contravariant in
    // that parameter, so an endpoint requiring nothing fits wherever one that may require streaming does.
    BasePath.prefixAll(GatewayEndpoints.ApiPrefix, HealthEndpoints.make[F](readiness, capabilities)) ++
      InfoRoutes[F](config.server, config.gateway)
}

/** The two configuration sections the route list needs, and no more.
  *
  * The composition root's `GatewayServiceConfig` lives in the `app` module, which depends on this one, so the
  * dependency cannot point the other way. Naming exactly the two sections the routes read is better than
  * inventing a way around that anyway: a reader can see from the signature that assembling the routes
  * involves the server settings and the upstream list and nothing else — not the telemetry configuration, not
  * the signing keys.
  */
final case class GatewayServiceConfigView(server: ServerConfig, gateway: GatewayConfig)
