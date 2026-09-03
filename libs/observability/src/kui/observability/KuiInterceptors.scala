package kui.observability

import cats.effect.kernel.Async
import cats.syntax.all.*
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.{Histogram, Meter}
import org.typelevel.otel4s.trace.Tracer
import sttp.model.Header
import sttp.tapir.AnyEndpoint
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.interceptor.metrics.MetricsRequestInterceptor
import sttp.tapir.server.interceptor.{Interceptor, RequestInterceptor}
import sttp.tapir.server.metrics.{EndpointMetric, Metric}
import sttp.tapir.server.tracing.otel4s.{Otel4sTracing, Otel4sTracingConfig}

/** The interceptors every KUI server installs, so no endpoint has to be instrumented by hand.
  *
  * The value of doing it here is that it cannot be forgotten. An endpoint added in M5 is traced and measured
  * because it is served, not because whoever wrote it remembered to wrap it — which means no feature can ship
  * unobservable, and there is no per-endpoint boilerplate to review.
  *
  * ==Order==
  *
  * Correlation, then tracing, then metrics. Each reason is specific:
  *
  *   - **correlation first**, because the id has to exist before anything else can record it. It is added to
  *     the request, so the tracer, the metrics and the error interceptor all read the same value rather than
  *     each inventing one;
  *   - **tracing outside metrics**, so that a request which fails inside the metrics layer is still inside a
  *     span, and so that a failed request still records its duration. If metrics wrapped tracing, the slowest
  *     and most interesting requests would be the ones that recorded nothing.
  *
  * ==What is not here==
  *
  * The error handling. `ErrorInterceptor` lives in `libs/http`, which depends on this module, so this module
  * cannot reach back for it. A composition root appends `ErrorInterceptor.interceptors(logger)` to this list;
  * that is also where request-error logging happens, in one place rather than two components logging the same
  * failure twice.
  */
object KuiInterceptors {

  /** Routes that are measured by their absence: a liveness probe every second would dominate the duration
    * histogram and tell nobody anything (HTTP-002).
    */
  val UnmeasuredRoutes: Set[String] = Set("/health/live", "/health/ready")

  /** The instrumentation interceptors, outermost first.
    *
    * @param serviceName
    *   the process's name, e.g. `kui-topic`. It becomes the `service` label on every metric and the context
    *   of every span name.
    */
  def serverInterceptors[F[_]: Async](
      telemetry: Telemetry[F],
      serviceName: String
  ): F[List[Interceptor[F]]] = {
    val instrumentation = s"kui.${contextOf(serviceName)}"

    for {
      tracer <- telemetry.tracer(instrumentation)
      meter <- telemetry.meter(instrumentation)
      duration <- httpServerDuration[F](meter, serviceName)
    } yield List(
      correlationInterceptor[F],
      Otel4sTracing(tracingConfig[F](tracer, serviceName)),
      new MetricsRequestInterceptor[F](List(duration), Seq.empty)
    )
  }

  /** `kui-topic` is the topic context. The prefix is stripped so a span reads `kui.topic.list` rather than
    * `kui.kui-topic.list`.
    */
  def contextOf(serviceName: String): String =
    serviceName.stripPrefix("kui-").stripPrefix("kui.")

  // ---------------------------------------------------------------------------------------------
  // Correlation
  // ---------------------------------------------------------------------------------------------

  /** Makes sure every request carries a correlation id before anything else looks at it.
    *
    * The id is added to the *request*, not stored beside it, so that everything downstream — including
    * `libs/http`'s error interceptor, which knows nothing about this module — reads the same value from the
    * same header. A caller's own id is kept when it is safe to echo; otherwise a fresh one is generated,
    * because a header goes back out in a response and into a log file.
    */
  def correlationInterceptor[F[_]: Async]: RequestInterceptor[F] =
    RequestInterceptor.transformServerRequest[F] { request =>
      request.header(Correlation.HeaderName).flatMap(Correlation.accept) match {
        case Some(_) => Async[F].pure(request)
        case None =>
          Correlation.newRandom[F].map { id =>
            val headers = request.headers.filterNot(_.is(Correlation.HeaderName)) :+
              Header(Correlation.HeaderName, id.value)
            request.withOverride(
              methodOverride = None,
              uriOverride = None,
              protocolOverride = None,
              connectionInfoOverride = None,
              pathSegmentsOverride = None,
              queryParametersOverride = None,
              headersOverride = Some(headers)
            )
          }
      }
    }

  // ---------------------------------------------------------------------------------------------
  // Tracing
  // ---------------------------------------------------------------------------------------------

  private def tracingConfig[F[_]](tracer: Tracer[F], serviceName: String): Otel4sTracingConfig[F] =
    Otel4sTracingConfig(
      tracer = tracer,
      spanNameFromEndpointAndAttributes = (request, endpoint) =>
        (spanName(serviceName, endpoint), Otel4sTracingConfig.Defaults.requestAttributes(request))
    )

  /** `kui.<context>.<operation>` (PLAN §30).
    *
    * The operation comes from the endpoint's declared name — its OpenAPI operation id — because that is a
    * name a person chose for the thing the endpoint does, and it is already the name the generated
    * documentation uses. An endpoint with no name falls back to its method and path template, which is
    * unambiguous but reads like a URL rather than like an operation. The fallback is a safety net and not a
    * supported state: [[missingOperationIds]] is the check that keeps it unused.
    */
  def spanName(serviceName: String, endpoint: AnyEndpoint): String =
    endpoint.info.name match {
      case Some(name) if name.startsWith("kui.") => name
      case Some(name) => s"kui.${contextOf(serviceName)}.$name"
      case None => routeOf(endpoint)
    }

  /** The endpoints that would use the fallback span name, so a service's own suite can fail on them.
    *
    * This is the guard OBS-002 asks for, and it is a function rather than a test here because the endpoints
    * it has to check belong to the services, not to this module. Each service asserts
    * `KuiInterceptors.missingOperationIds(myEndpoints) == Nil`.
    */
  def missingOperationIds(endpoints: List[AnyEndpoint]): List[String] =
    endpoints.filter(_.info.name.isEmpty).map(endpoint => routeOf(endpoint))

  // ---------------------------------------------------------------------------------------------
  // Metrics
  // ---------------------------------------------------------------------------------------------

  /** `kui.http.server.duration {service, route, status}`, in seconds.
    *
    * Seconds rather than milliseconds because that is the OpenTelemetry convention for a duration, and a
    * dashboard that mixes the two units produces numbers that are wrong by a thousand without looking wrong.
    *
    * The route label is the *path template* (`/clusters/{clusterId}/topics`) and never the actual path. A
    * label whose value is user data — a cluster id, a topic name — turns one metric into thousands of time
    * series, which is the classic way to take a monitoring system down.
    */
  def httpServerDuration[F[_]: Async](
      meter: Meter[F],
      serviceName: String
  ): F[Metric[F, Histogram[F, Double]]] =
    meter
      .histogram[Double](MetricNames.HttpServerDuration)
      .withUnit("s")
      .withDescription("How long KUI took to answer an HTTP request")
      .create
      .map { histogram =>
        Metric[F, Histogram[F, Double]](
          histogram,
          (_, instrument, _) =>
            Async[F].realTime.map { startedAt =>
              EndpointMetric[F]()
                .onResponseHeaders((endpoint, response) =>
                  record(instrument, serviceName, endpoint, response.code.code, startedAt)
                )
                // An exception that escaped the endpoint still took time, and it is the case an
                // operator most wants in the histogram. Without this the p99 would improve every
                // time the service broke.
                .onException((endpoint, _) => record(instrument, serviceName, endpoint, 500, startedAt))
            }
        )
      }

  private def record[F[_]: Async](
      histogram: Histogram[F, Double],
      serviceName: String,
      endpoint: AnyEndpoint,
      status: Int,
      startedAt: scala.concurrent.duration.FiniteDuration
  ): F[Unit] = {
    val route = routeLabel(endpoint)

    if UnmeasuredRoutes.contains(route) then Async[F].unit
    else
      Async[F].realTime.flatMap { endedAt =>
        histogram.record(
          (endedAt - startedAt).toNanos.toDouble / 1e9,
          Attribute(MetricNames.Attr.Service, serviceName),
          Attribute(MetricNames.Attr.Route, route),
          Attribute(MetricNames.Attr.Status, status.toLong)
        )
      }
  }

  /** The path template, with query parameters left out.
    *
    * Tapir renders query parameters into the template by default. In a metric label that is wrong twice: the
    * label stops being the path, and it stops matching [[UnmeasuredRoutes]], so an excluded endpoint that
    * grew a query parameter would quietly start being measured again.
    */
  def routeLabel(endpoint: AnyEndpoint): String =
    endpoint.showPathTemplate(showQueryParam = None)

  private def routeOf(endpoint: AnyEndpoint): String =
    s"${endpoint.method.map(_.method).getOrElse("*")} ${routeLabel(endpoint)}"

  /** The request's correlation id, after [[correlationInterceptor]] has run. */
  def correlationIdOf(request: ServerRequest): Option[String] =
    request.header(Correlation.HeaderName)
}
