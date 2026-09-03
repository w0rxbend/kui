# HTTP-001 — `libs/http`: Netty server, error interceptor, CORS, base path

- **ID:** HTTP-001
- **Title:** `libs/http`: Netty server, error interceptor, CORS, base path
- **Milestone / Feature:** M0 / OT-005, NX-005, NX-006
- **Owner role:** Principal Scala Engineer
- **Context / service:** `libs/http`
- **Size:** L
- **Dependencies / blocked by:** KERN-004, OBS-002

## Goal (user value)

Every KUI process serves HTTP the same way: the same error body for the same failure, the same
CORS posture, the same behaviour behind a reverse proxy on a sub-path. An operator who learns
one service has learned all of them.

## Scope

1. `KuiServer.resource` — a `Resource[F, ServerBinding]` over `tapir-netty-server-cats`,
   built from `ServerConfig` plus a list of `ServerEndpoint`s and the interceptors from
   OBS-002.
2. **Error interceptor**: maps a failed effect, a rejected decode and an uncaught exception to
   `ErrorEnvelope` with the status from `ErrorEnvelope.statusOf`, the current correlation id
   and the current time. Decode failures become `KUI-VALIDATION` with `details` naming the
   field. Uncaught exceptions become `KUI-INTERNAL` with a fixed message and a logged
   stack trace — the trace never reaches the client (ADR-034).
3. **Base path** (NX-005): `server.basePath` prefixes every route, and generated links,
   redirects and the OpenAPI `servers` entry all respect it. `"/"` means no prefix.
4. **CORS** (NX-006): off by default; when enabled, an explicit origin allow-list (no `*`),
   `Vary: Origin`, credentials allowed only for listed origins, and a preflight handler.
5. `KuiEndpoint` — the base Tapir endpoint every contract module builds on: it fixes
   `errorOut` to the `ErrorEnvelope` shape so no endpoint can invent its own error body.

## Non-goals

No health endpoints (HTTP-002). No upstream client (HTTP-003). No SSE (HTTP-004). No auth
(GW-009). No static files (GW-008).

## Design references

ADR-003 (Tapir + Netty), ADR-034 (envelope and statuses), `ARCHITECTURE.md` §15,
feature matrix NX-005, NX-006, ADR-019 ("CORS is off by default; the gateway serves the SPA
from the same origin").

## Files to create

```
libs/http/src/kui/http/KuiServer.scala
libs/http/src/kui/http/KuiEndpoint.scala
libs/http/src/kui/http/ErrorInterceptor.scala
libs/http/src/kui/http/BasePath.scala
libs/http/src/kui/http/Cors.scala
libs/http/test/src/kui/http/ErrorInterceptorSuite.scala
libs/http/test/src/kui/http/BasePathSuite.scala
libs/http/test/src/kui/http/CorsSuite.scala
```

## Public Scala signatures to implement

```scala
package kui.http

object KuiEndpoint:
  /** The base every contract module starts from. Carries the correlation-id header in and
    * the ErrorEnvelope out, so `errorOut` is never re-declared. */
  val base: PublicEndpoint[Unit, ErrorEnvelope, Unit, Any]
  /** Endpoints under /internal/v1 additionally require the signed principal header. */
  val internal: PublicEndpoint[SignedPrincipal, ErrorEnvelope, Unit, Any]

object KuiServer:
  final case class ServerBinding(host: String, port: Int, basePath: String)
  def resource[F[_]: Async](
      config: ServerConfig,
      endpoints: List[ServerEndpoint[Fs2Streams[F] & WebSockets, F]],
      interceptors: List[Interceptor[F]],
      logger: StructuredLogger[F]
  ): Resource[F, ServerBinding]

object ErrorInterceptor:
  def apply[F[_]: Sync](logger: StructuredLogger[F]): Interceptor[F]
  /** Pure, so it is testable without a server. */
  def render(error: KuiError, correlationId: CorrelationId, at: Instant): (Int, ErrorEnvelope)

object BasePath:
  def normalize(raw: String): String                 // "" | "/kui" — never a trailing slash
  def prefix[A](basePath: String, e: ServerEndpoint[A, ?]): ServerEndpoint[A, ?]

object Cors:
  def interceptor[F[_]: Applicative](config: CorsConfig): Option[Interceptor[F]]
```

## Library coordinates

```
com.softwaremill.sttp.tapir::tapir-netty-server-cats::1.13.31
com.softwaremill.sttp.tapir::tapir-cats-effect::1.13.31
org.typelevel::cats-effect::3.7.1
co.fs2::fs2-core::3.13.0
co.fs2::fs2-io::3.13.0
```

## Acceptance criteria

```
$ ./mill libs.http.test
```

Behavioural, asserted in tests against a real bound port:

```
$ curl -i localhost:8080/does-not-exist
HTTP/1.1 404
{"code":"KUI-ROUTE-NOT-FOUND","message":"No route for GET /does-not-exist",...}
```

The exact expected bodies are golden files:

| Situation | Status | `code` |
| --- | --- | --- |
| unknown route | 404 | `KUI-ROUTE-NOT-FOUND` — `ErrorCode.RouteNotFound` (KERN-002, ADR-034 amendment 1). Neither `KUI-INTERNAL` nor `KUI-VALIDATION` is used for an unmatched route: `KUI-INTERNAL` would tell the caller a server fault occurred, and `KUI-VALIDATION` would imply the request body was wrong |
| body fails to decode | 400 | `KUI-VALIDATION` with `details[0].field` |
| path parameter fails `ClusterId.from` | 400 | `KUI-VALIDATION` |
| server logic returns `ApplicationError.NotFound` | 404 | that error's code |
| server logic throws | 500 | `KUI-INTERNAL`, message `"Internal error"`, stack trace only in the log |

## Tests required

- `ErrorInterceptorSuite` (unit + integration on a bound port):
  - `mapsEveryKuiErrorCaseToItsStatusAndCode` — exhaustive table.
  - `decodeFailureBecomesValidationWithFieldDetails`.
  - `uncaughtExceptionBecomesInternalAndTheStackTraceIsOnlyLogged` — asserts against
    `FakeStructuredLogger` that the trace was logged and against the body that it was not sent.
  - `everyErrorResponseCarriesTheCorrelationIdHeaderAndBodyField` (they must match).
  - `upstreamBodyIsNeverEchoed` — the ADR-034 rule.
- `BasePathSuite` (unit + integration): with `basePath = /kui`, `GET /kui/health/live` works
  and `GET /health/live` 404s; `normalize` table test over `"" , "/", "/kui", "/kui/"`.
- `CorsSuite` (unit + integration): disabled by default (no CORS headers at all); enabled with
  one origin allows it and rejects another; preflight returns the allowed methods; `*` in the
  config is rejected at load time (CFG-001's validation, asserted here end to end).

## Observability

The interceptor logs one entry per error at WARN (4xx) or ERROR (5xx) with
`error.code`, `correlation.id`, `operation` and the route; it does not log successful requests
(OBS-002's metrics cover those).

## Degraded behavior

If the port is in use the process fails at startup with a clear message naming the port and
exits non-zero — it never retries silently on another port.

## Docs to update

`ARCHITECTURE.md` §15: note the interceptor is the single mapping point.
