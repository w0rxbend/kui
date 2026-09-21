package kui.metrics.infrastructure.prometheus

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.TimeoutException

import scala.concurrent.duration.FiniteDuration

import cats.effect.kernel.{Async, Clock, Resource}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import sttp.client4.*
import sttp.model.{StatusCode, Uri}

import kui.config.MetricsSourceSettings
import kui.http.upstream.{UpstreamClient, UpstreamCredentials, UpstreamFailure}
import kui.kernel.ClusterId
import kui.kernel.error.{ApplicationError, DomainError, ErrorCode, InfrastructureError, KuiError}

/** The bounded provider boundary used by the server-owned Kafka metrics catalog.
  *
  * Callers can select only an already-compiled query. Raw PromQL, response bodies, labels, URLs and upstream
  * error strings stay on this side of the boundary and never enter a browser-visible [[KuiError]].
  */
trait PrometheusQueryClient[F[_]] {
  def probe(at: Instant): F[Either[KuiError, PrometheusProbe]]

  def instant(
      query: CompiledPromQuery,
      at: Instant
  ): F[Either[KuiError, QueryAnswer[InstantQueryResult]]]

  def range(
      query: CompiledPromQuery,
      from: Instant,
      to: Instant,
      step: FiniteDuration
  ): F[Either[KuiError, QueryAnswer[RangeQueryResult]]]
}

/** A successful probe proves that the endpoint spoke the instant-query protocol. */
final case class PrometheusProbe(
    sampleCount: Int,
    retrievedAt: Instant,
    diagnostics: QueryDiagnostics
)

object PrometheusQueryClient {

  val UpstreamName: String = "prometheus"
  private[prometheus] val CacheEntriesPerKind: Int = PrometheusQueryCache.MaxEntries / 2

  /** The sole production constructor. Both source-local caches are resources so shutdown cannot leave a
    * shared load running or retain decoded responses after the source is gone.
    */
  def resource[F[_]: Async](
      upstream: UpstreamClient[F],
      settings: MetricsSourceSettings,
      credentials: UpstreamCredentials[F],
      source: ClusterId,
      metrics: PrometheusQueryMetrics[F]
  ): Resource[F, PrometheusQueryClient[F]] = {
    val totalBytes = settings.maxCacheBytes.toLong
    val instantBytes = totalBytes / 2L
    val rangeBytes = totalBytes - instantBytes
    val physical = new LivePrometheusQueryClient[F](upstream.backend, settings, credentials, source, metrics)

    for {
      instantCache <- PrometheusQueryCache.resource[
        F,
        InstantCacheKey,
        QueryAnswer[InstantQueryResult]
      ](
        settings.cacheTtl,
        settings.staleTtl,
        instantBytes,
        PrometheusQueryWeight.instant,
        CacheEntriesPerKind
      )
      rangeCache <- PrometheusQueryCache.resource[F, RangeCacheKey, QueryAnswer[RangeQueryResult]](
        settings.cacheTtl,
        settings.staleTtl,
        rangeBytes,
        PrometheusQueryWeight.range,
        CacheEntriesPerKind
      )
    } yield new CachedPrometheusQueryClient[F](
      physical,
      instantCache,
      rangeCache,
      settings,
      source,
      metrics
    )
  }

  /** Test seam for the physical transport. Production composition uses [[resource]] so cache cleanup and
    * exactly-once logical telemetry cannot be omitted. Its backend must still be the configured resilient
    * backend because request paths are deliberately built without the reverse-proxy prefix.
    */
  private[prometheus] def physical[F[_]: Async](
      backend: Backend[F],
      settings: MetricsSourceSettings,
      credentials: UpstreamCredentials[F],
      source: ClusterId,
      metrics: PrometheusQueryMetrics[F]
  ): PrometheusQueryClient[F] =
    new LivePrometheusQueryClient[F](backend, settings, credentials, source, metrics)

  final private case class InstantCacheKey(
      source: ClusterId,
      query: QueryId,
      expressionDigest: String,
      at: PrometheusTimestamp
  )

  final private case class RangeCacheKey(
      source: ClusterId,
      query: QueryId,
      expressionDigest: String,
      from: PrometheusTimestamp,
      to: PrometheusTimestamp,
      stepSeconds: String
  )

  final private case class AcceptedResponse(
      series: Long,
      samples: Long,
      diagnostics: QueryDiagnostics
  )

  final private class LivePrometheusQueryClient[F[_]: Async](
      backend: Backend[F],
      settings: MetricsSourceSettings,
      credentials: UpstreamCredentials[F],
      source: ClusterId,
      metrics: PrometheusQueryMetrics[F]
  ) extends PrometheusQueryClient[F] {

    /** The resilient backend prefixes the configured base path while rebasing each call. Starting at a
      * pathless root therefore preserves `/prometheus` exactly once instead of producing
      * `/prometheus/prometheus/api/v1/query`.
      */
    private val root: Uri =
      Uri.parse(settings.url.value).getOrElse(uri"http://prometheus.invalid").withWholePath("")

    private val instantEndpoint: Uri = root.addPath("api").addPath("v1").addPath("query")
    private val rangeEndpoint: Uri = root.addPath("api").addPath("v1").addPath("query_range")
    private val limits: PrometheusDecodeLimits = PrometheusDecodeLimits.fromSettings(settings)

    def probe(at: Instant): F[Either[KuiError, PrometheusProbe]] = {
      val timestamp = PrometheusTimestamp.fromInstant(at)
      val probeLimits = limits.copy(maxSeries = 1, maxTotalSamples = 1)

      send(
        instantEndpoint,
        Vector(
          "query" -> "vector(1)",
          "time" -> timestamp.wireValue,
          "timeout" -> timeoutParameter,
          "limit" -> "1"
        ),
        None
      ) { (body, retrievedAt) =>
        PrometheusResponseDecoder.decodeInstant(body, retrievedAt, probeLimits).map { answer =>
          PrometheusProbe(answer.result.series.size, retrievedAt, answer.diagnostics)
        }
      }(_ => AcceptedResponse(0L, 0L, QueryDiagnostics.Empty))
    }

    def instant(
        query: CompiledPromQuery,
        at: Instant
    ): F[Either[KuiError, QueryAnswer[InstantQueryResult]]] = {
      val timestamp = PrometheusTimestamp.fromInstant(at)

      send(
        instantEndpoint,
        Vector(
          "query" -> query.expression,
          "time" -> timestamp.wireValue,
          "timeout" -> timeoutParameter,
          "limit" -> settings.maxSeriesPerQuery.toString
        ),
        Some(context(query, QueryOperation.Instant))
      )(PrometheusResponseDecoder.decodeInstant(_, _, limits))(answer =>
        AcceptedResponse(
          answer.result.series.size.toLong,
          answer.result.series.size.toLong,
          answer.diagnostics
        )
      )
    }

    def range(
        query: CompiledPromQuery,
        from: Instant,
        to: Instant,
        step: FiniteDuration
    ): F[Either[KuiError, QueryAnswer[RangeQueryResult]]] =
      RangeQuery.create(query, from, to, step, settings.maxPointsPerSeries) match {
        case Left(problem) => rangeProtocolFailure(problem).asLeft[QueryAnswer[RangeQueryResult]].pure[F]
        case Right(validated) =>
          send(
            rangeEndpoint,
            Vector(
              "query" -> query.expression,
              "start" -> validated.from.wireValue,
              "end" -> validated.to.wireValue,
              "step" -> validated.stepSeconds,
              "timeout" -> timeoutParameter,
              "limit" -> settings.maxSeriesPerQuery.toString
            ),
            Some(context(query, QueryOperation.Range))
          )((body, retrievedAt) =>
            PrometheusResponseDecoder.decodeRange(body, retrievedAt, validated, limits)
          )(answer =>
            AcceptedResponse(
              answer.result.series.size.toLong,
              answer.result.series.iterator.map(_.samples.size.toLong).sum,
              answer.diagnostics
            )
          )
      }

    /** Authenticate, execute exactly one physical POST and decode only after the body ceiling has held. */
    private def send[A](
        endpoint: Uri,
        form: Vector[(String, String)],
        context: Option[PrometheusQueryContext]
    )(
        decode: (String, Instant) => Either[PrometheusDecodeProblem, A]
    )(accepted: A => AcceptedResponse): F[Either[KuiError, A]] = {
      val request = basicRequest
        .post(endpoint)
        .body(form.toSeq, StandardCharsets.UTF_8.name)
        .header("Accept", "application/json")
        .followRedirects(false)
        .readTimeout(settings.callTimeout)
        // sttp's JVM backends enforce this while receiving the body, before `asStringAlways` materialises
        // it. It applies even when Content-Length is absent or understated.
        .maxResponseBodyLength(settings.maxResponseBytes.toLong)
        .response(asStringAlways)

      credentials
        .authenticate(request)
        .flatMap {
          case Left(failure) => credentialFailure(failure).asLeft[A].pure[F]
          case Right(authenticated) =>
            authenticated.send(backend).flatMap { response =>
              if response.code.isSuccess then
                Clock[F].realTimeInstant.flatMap { retrievedAt =>
                  decode(response.body, retrievedAt) match {
                    case Left(problem) =>
                      recordLimit(context, problem).as(Left(decodeFailure(problem)))
                    case Right(value) =>
                      recordAccepted(context, response.body, accepted(value)).as(Right(value))
                  }
                }
              else statusFailure(response.code).asLeft[A].pure[F]
            }
        }
        .timeoutTo(
          settings.callTimeout,
          InfrastructureError
            .Timeout("Prometheus query", settings.callTimeout.toMillis)
            .asLeft[A]
            .pure[F]
        )
        .handleErrorWith {
          case failure: Exception =>
            val error = thrown(failure)
            val record = failure match {
              case failure: UpstreamFailure if UpstreamClient.isResponseLimitFailure(failure) =>
                context.fold(Async[F].unit)(metrics.limitRejected(_, QueryLimit.ResponseBytes))
              case _ => Async[F].unit
            }
            record.as(Left(error))
          case fatal => Async[F].raiseError(fatal)
        }
    }

    private def recordAccepted(
        context: Option[PrometheusQueryContext],
        body: String,
        accepted: AcceptedResponse
    ): F[Unit] =
      context.fold(Async[F].unit)(value =>
        metrics.response(
          value,
          body.getBytes(StandardCharsets.UTF_8).length.toLong,
          accepted.series,
          accepted.samples
        ) *> metrics.diagnostics(value, accepted.diagnostics)
      )

    private def recordLimit(
        context: Option[PrometheusQueryContext],
        problem: PrometheusDecodeProblem
    ): F[Unit] =
      (context, limitOf(problem)) match {
        case (Some(value), Some(limit)) => metrics.limitRejected(value, limit)
        case _ => Async[F].unit
      }

    private def limitOf(problem: PrometheusDecodeProblem): Option[QueryLimit] =
      problem match {
        case PrometheusDecodeProblem.LimitExceeded(PrometheusDecodeLimit.Series) => Some(QueryLimit.Series)
        case PrometheusDecodeProblem.LimitExceeded(PrometheusDecodeLimit.PointsPerSeries) =>
          Some(QueryLimit.Points)
        case PrometheusDecodeProblem.LimitExceeded(PrometheusDecodeLimit.TotalSamples) =>
          Some(QueryLimit.Samples)
        case PrometheusDecodeProblem.LimitExceeded(PrometheusDecodeLimit.LabelsPerSeries) =>
          Some(QueryLimit.Labels)
        case PrometheusDecodeProblem.LimitExceeded(PrometheusDecodeLimit.LabelBytesPerSeries) =>
          Some(QueryLimit.LabelBytes)
        case _ => None
      }

    private def context(query: CompiledPromQuery, operation: QueryOperation): PrometheusQueryContext =
      PrometheusQueryContext(source, query.id, operation)

    private def timeoutParameter: String = {
      val seconds = BigDecimal(settings.queryTimeout.toNanos) / BigDecimal(1000000000)
      s"${seconds.bigDecimal.stripTrailingZeros.toPlainString}s"
    }

    private def decodeFailure(problem: PrometheusDecodeProblem): KuiError =
      problem match {
        case PrometheusDecodeProblem.UpstreamError => queryDefect
        case PrometheusDecodeProblem.UnsupportedResultType | PrometheusDecodeProblem.MismatchedResultType |
            PrometheusDecodeProblem.NativeHistogramUnsupported =>
          ApplicationError.Unsupported("the Prometheus query result")
        case _ => upstreamContractFailure
      }

    private def statusFailure(status: StatusCode): KuiError =
      status.code match {
        case 400 => queryDefect
        case 401 | 403 => InfrastructureError.AuthFailed(UpstreamName)
        case 404 =>
          InfrastructureError.Remote(
            ErrorCode.UpstreamUnavailable,
            "the configured Prometheus address does not expose the query API",
            Nil
          )
        case 422 =>
          ApplicationError.InvalidState("Prometheus could not execute the configured query")
        case 429 => InfrastructureError.Upstream(UpstreamName, 429)
        case 503 => InfrastructureError.Timeout("Prometheus query", settings.queryTimeout.toMillis)
        case other => InfrastructureError.Upstream(UpstreamName, other)
      }

    private def credentialFailure(failure: UpstreamCredentials.Failure): KuiError =
      failure match {
        case UpstreamCredentials.Failure.Transport =>
          InfrastructureError.Unreachable(UpstreamName, "credential transport failure")
        case UpstreamCredentials.Failure.TimedOut(after) =>
          InfrastructureError.Timeout("Prometheus authentication", after.toMillis)
        case UpstreamCredentials.Failure.Rejected(status) if status == 401 || status == 403 =>
          InfrastructureError.AuthFailed(UpstreamName)
        case UpstreamCredentials.Failure.Rejected(status) if status == 429 || status >= 500 =>
          InfrastructureError.Upstream(UpstreamName, status)
        case _: UpstreamCredentials.Failure.Rejected | _: UpstreamCredentials.Failure.Malformed |
            _: UpstreamCredentials.Failure.ResponseTooLarge =>
          InfrastructureError.Remote(
            ErrorCode.UpstreamUnavailable,
            "Prometheus authentication returned a response KUI could not safely use",
            Nil
          )
      }

    private def thrown(failure: Throwable): KuiError =
      failure match {
        case UpstreamFailure(error) => sanitize(error)
        case _: TimeoutException | _: java.net.http.HttpTimeoutException =>
          InfrastructureError.Timeout("Prometheus query", settings.callTimeout.toMillis)
        case other => InfrastructureError.Unreachable(UpstreamName, other.getClass.getSimpleName)
      }

    /** `Unreachable.cause` is log-only, but the resilient transport may populate it with a JVM message that
      * contains the URL. Replace it before the value crosses this adapter so even accidental rendering is
      * harmless.
      */
    private def sanitize(error: KuiError): KuiError =
      error match {
        case _: InfrastructureError.Unreachable =>
          InfrastructureError.Unreachable(UpstreamName, "transport failure")
        case other => other
      }

    private val queryDefect: KuiError =
      ApplicationError.InvalidState("the configured Prometheus query was rejected")

    private val upstreamContractFailure: KuiError =
      InfrastructureError.Remote(
        ErrorCode.UpstreamUnavailable,
        "Prometheus answered with a query response KUI could not safely use",
        Nil
      )
  }

  final private class CachedPrometheusQueryClient[F[_]: Async](
      physical: PrometheusQueryClient[F],
      instantCache: PrometheusQueryCache[F, InstantCacheKey, QueryAnswer[InstantQueryResult]],
      rangeCache: PrometheusQueryCache[F, RangeCacheKey, QueryAnswer[RangeQueryResult]],
      settings: MetricsSourceSettings,
      source: ClusterId,
      metrics: PrometheusQueryMetrics[F]
  ) extends PrometheusQueryClient[F] {

    def probe(at: Instant): F[Either[KuiError, PrometheusProbe]] = physical.probe(at)

    def instant(
        query: CompiledPromQuery,
        at: Instant
    ): F[Either[KuiError, QueryAnswer[InstantQueryResult]]] = {
      val context = PrometheusQueryContext(source, query.id, QueryOperation.Instant)
      val key = InstantCacheKey(source, query.id, query.expressionDigest, PrometheusTimestamp.fromInstant(at))
      logical(context) {
        instantCache
          .lookup(key)(physical.instant(query, at))(staleEligible)
          .flatMap(complete(context, _))
      }
    }

    def range(
        query: CompiledPromQuery,
        from: Instant,
        to: Instant,
        step: FiniteDuration
    ): F[Either[KuiError, QueryAnswer[RangeQueryResult]]] = {
      val context = PrometheusQueryContext(source, query.id, QueryOperation.Range)
      logical(context) {
        RangeQuery.create(query, from, to, step, settings.maxPointsPerSeries) match {
          case Left(problem) =>
            metrics.cache(context, QueryCacheAccess.Loaded, QueryCacheFreshness.Fresh) *>
              rangeProtocolFailure(problem).asLeft[QueryAnswer[RangeQueryResult]].pure[F]
          case Right(validated) =>
            val key = RangeCacheKey(
              source,
              query.id,
              query.expressionDigest,
              validated.from,
              validated.to,
              validated.stepSeconds
            )
            rangeCache
              .lookup(key)(physical.range(query, from, to, step))(staleEligible)
              .flatMap(complete(context, _))
        }
      }
    }

    private def logical[A](
        context: PrometheusQueryContext
    )(call: F[Either[KuiError, A]]): F[Either[KuiError, A]] =
      metrics.observe(context)(call)(queryOutcome)

    private def complete[A](
        context: PrometheusQueryContext,
        lookup: QueryCacheLookup[QueryAnswer[A]]
    ): F[Either[KuiError, QueryAnswer[A]]] =
      metrics.cache(context, lookup.access, lookup.freshness) *>
        lookup.result.traverse(cached =>
          cached.freshness match {
            case QueryCacheFreshness.Fresh => cached.value.pure[F]
            case QueryCacheFreshness.Stale => stale(cached.value)
          }
        )

    private def stale[A](answer: QueryAnswer[A]): F[QueryAnswer[A]] =
      Clock[F].realTimeInstant.map { servedAt =>
        val retrievedAt = answer.freshness match {
          case QueryFreshness.Fresh(value) => value
          case QueryFreshness.Stale(value, _) => value
        }
        answer.copy(freshness = QueryFreshness.Stale(retrievedAt, servedAt))
      }

    private def queryOutcome[A](result: Either[KuiError, A]): QueryOutcome =
      result match {
        case Right(_) => QueryOutcome.Success
        case Left(_: InfrastructureError.Timeout) => QueryOutcome.Timeout
        case Left(_: InfrastructureError.CircuitOpen) => QueryOutcome.CircuitOpen
        case Left(_) => QueryOutcome.Failure
      }

    private def staleEligible(error: KuiError): Boolean =
      error match {
        case _: InfrastructureError.Unreachable => true
        case _: InfrastructureError.Timeout => true
        case _: InfrastructureError.CircuitOpen => true
        case InfrastructureError.Upstream(_, status) => status == 429 || status >= 500
        case _ => false
      }
  }

  private def rangeProtocolFailure(problem: PrometheusProtocolProblem): KuiError =
    DomainError.InvariantViolation(s"the Prometheus range query is invalid: ${problemName(problem)}")

  private def problemName(problem: PrometheusProtocolProblem): String =
    problem match {
      case PrometheusProtocolProblem.InvalidPointLimit => "the point limit is invalid"
      case PrometheusProtocolProblem.NonPositiveStep => "the step must be positive"
      case PrometheusProtocolProblem.ReversedRange => "the start must not follow the end"
      case PrometheusProtocolProblem.UnalignedRange(_) => "the range must be aligned to its step"
      case PrometheusProtocolProblem.TooManyPoints(_, maximum) =>
        s"it may contain at most $maximum points"
      case _ => "its parameters are invalid"
    }
}
