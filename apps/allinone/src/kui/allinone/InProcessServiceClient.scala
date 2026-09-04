package kui.allinone

import scala.concurrent.duration.FiniteDuration

import cats.effect.kernel.Async
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.impl.cats.implicits.*
import sttp.client4.testing.StreamBackendStub
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.Interceptor
import sttp.tapir.server.stub4.TapirStreamStubInterpreter

import kui.config.UpstreamServiceConfig
import kui.gateway.api.client.SttpServiceClient
import kui.gateway.application.client.ServiceClient
import kui.kernel.ServiceId
import kui.security.PrincipalCodec

/** The second `ServiceClient[F]`: one service, called without a socket.
  *
  * ==What "the same code path" actually means here==
  *
  * ADR-005 exists to stop the gateway growing two ways of calling a service, because two ways drift and the
  * bug only ever shows up in the shape nobody tests. The strongest available defence against that is not to
  * write a second implementation at all, and that is what this file does. It builds no requests, decodes no
  * responses and maps no errors. It assembles a *transport* — Tapir's stub interpreter, running the service's
  * own routes behind the service's own interceptors, in memory — and hands it to [[SttpServiceClient.over]],
  * which is the identical client code the distributed deployment runs.
  *
  * So the chain a call travels is, line for line:
  *
  *   1. the gateway's contract-derived route (`ContractRouting`, shared);
  *   1. `SttpServiceClient`'s signing, header decoration and request building (shared);
  *   1. **here**: instead of a TCP connection, Tapir's stub hands the request straight to the service's
  *      interceptor chain and server logic;
  *   1. `SttpServiceClient`'s response decoding and error mapping (shared).
  *
  * Only step 3 differs between the two deployment shapes. A `KuiError` a use case returns therefore reaches
  * the browser as the same envelope with the same status either way — including the failures, which is the
  * half that would otherwise go untested.
  *
  * ==Why the services still bind nothing==
  *
  * The routes assembled here are never added to the process's Netty listener. They are reachable only through
  * the gateway, which is the same rule the distributed deployment enforces with a network policy
  * (`ARCHITECTURE.md` §14: services must not be exposed outside the cluster network). It also means the
  * eleven services' identical `/health/live`, `/health/ready` and `/capabilities` paths never have to be
  * disambiguated, because they never share a router. See the deviation note in
  * `docs/plans/M0/tasks/AIO-001.md`.
  *
  * ==What is honestly missing==
  *
  * There is no circuit breaker, because there is no circuit: `circuitStates` is an empty stream and the
  * capability registry folds that as "no breaker signal", not as "the breaker is closed". Fault isolation in
  * this shape is at the code level and not at the process level — a use case that fails is still reported as
  * `Unavailable` and the UI still degrades, but a JVM that dies takes everything with it. ADR-005 says so and
  * so does `README.md`; nobody should read this file and conclude otherwise.
  *
  * There *is* a call timeout, and there has to be. See `SttpServiceClient.over`: the service on the far side
  * of this call is in this JVM, but the Kafka broker it talks to may not exist any more, and a `AdminClient`
  * takes thirty seconds to admit that. Without a bound the HTTP server in front gives up first, at twenty,
  * with a bare `503` carrying no error code and no correlation id — a failure shape KUI otherwise never
  * produces, and one the browser can only report as its own inability to read the answer.
  */
object InProcessServiceClient {

  /** The address in-process requests are built against.
    *
    * Nothing dials it. It exists because a signed principal's request digest covers the method and the path
    * (ADR-020), so a request has to be a real request before it can be signed, and a real request needs a
    * base URL to hang a path off. The service id is used as the host so that anything which does end up
    * printing a URL — a log line, a span attribute, a failed-assertion message — names the service it was
    * meant for rather than something like `http://localhost`, which would suggest a hop that never happened.
    */
  def baseUrlFor(service: ServiceId): String = s"http://${service.value}.in-process"

  /** A client for one in-process service.
    *
    * @param service
    *   the id the gateway configures, routes, signs for and keys the capability registry on
    * @param serverEndpoints
    *   the service's routes, exactly as its own `<Name>Wiring.make` assembled them for its own listener
    * @param interceptors
    *   the service's cross-cutting chain, outermost first, exactly as its own wiring assembled it. It is a
    *   parameter and not something skipped for convenience: the 401 for a missing principal and the shared
    *   error envelope are produced by interceptors, so a client that ran the routes without them would answer
    *   differently from the same service behind a socket — the one thing ADR-005 forbids.
    * @param principals
    *   the unsigned, claim-carrying codec of ADR-005. Both sides of the call are handed the same instance, so
    *   the audience, expiry and request-binding checks still run and are still exercised by every request
    *   this process serves.
    */
  def make[F[_]: Async](
      service: ServiceId,
      serverEndpoints: List[ServerEndpoint[Fs2Streams[F], F]],
      interceptors: List[Interceptor[F]],
      principals: PrincipalCodec[F],
      callTimeout: FiniteDuration = UpstreamServiceConfig.DefaultTimeout
  ): ServiceClient[F] =
    SttpServiceClient.over[F](
      service,
      baseUrlFor(service),
      principals,
      TapirStreamStubInterpreter[F, Fs2Streams[F]](
        interceptors,
        StreamBackendStub[F, Fs2Streams[F]](summon)
      ).whenServerEndpointsRunLogic(serverEndpoints).backend(),
      callTimeout
    )
}
