package kui.metrics.api

import java.time.Instant

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.kernel.Resource
import io.circe.parser.parse
import io.circe.Json
import munit.CatsEffectSuite
import org.typelevel.otel4s.metrics.MeterProvider
import sttp.client4.*
import sttp.client4.impl.cats.implicits.*
import sttp.client4.testing.BackendStub
import sttp.model.Uri
import sttp.tapir.server.stub4.TapirStubInterpreter

import kui.contracts.KuiEndpoint
import kui.http.principal.PrincipalVerification
import kui.kernel.error.InfrastructureError
import kui.kernel.{ClusterId, Secret, ServiceId, UserName}
import kui.metrics.application.{ClusterSources, SourceProfile, ThroughputUseCase}
import kui.metrics.domain.{MetricsSourcePort, ThroughputRange, ThroughputSample, ThroughputSeries}
import kui.observability.Telemetry
import kui.security.*
import kui.testkit.fakes.FakeStructuredLogger

/** That the one endpoint this service has answers the way the whole service was designed around.
  *
  * The claim is a single sentence and it is the reason `services/metrics` exists at all: a cluster KUI cannot
  * measure gets **200** with a section saying so, never a 404 and never a 500, because the dashboard card
  * beside it has a written "not measured" sentence to show and a 4xx would make a card behaving exactly as
  * designed indistinguishable from a broken one (ADR-032). Until this suite that sentence was asserted at the
  * mapping level and at the use-case level and nowhere at the endpoint — so a `MetricsRoutes` that lost the
  * `Section`, or a range codec that started defaulting instead of refusing, would have gone out green.
  *
  * Everything below the route is real: the range codec the contract publishes, the use case that decides
  * between "no such cluster" and "nothing to measure", and the mapping onto the wire. Only the metrics source
  * is stubbed, because there is no collector in this build to stub anything else with. It runs through
  * Tapir's stub interpreter, which is the real interceptor chain and the real principal check without a
  * socket; a bound port would add seconds per case and prove only that Netty works, which `libs/http` proves
  * once for every service.
  */
final class MetricsRoutesSuite extends CatsEffectSuite {

  private val prod = ClusterId.unsafe("prod-eu")
  private val measured = ClusterId.unsafe("measured")
  private val broken = ClusterId.unsafe("broken")

  private val exporterDown =
    InfrastructureError.Unreachable("metrics-exporter", "connection refused")

  // -----------------------------------------------------------------------------------------------
  // The service, with no socket
  // -----------------------------------------------------------------------------------------------

  /** Thirty-two bytes, which is the shortest key HS256 accepts. */
  private val key: SigningKey =
    SigningKey("test-1", Secret(Array.fill[Byte](32)(7)), Instant.parse("2020-01-01T00:00:00Z"))

  private val codec: PrincipalCodec[IO] =
    JwsPrincipalCodec
      .make[IO](NonEmptyList.of(key), "kui-gateway")
      .getOrElse(throw new IllegalStateException("the test signing key is too short for HS256"))

  /** A source that always fails, and a source that always answers.
    *
    * Two stubs rather than one configurable one: the two are the two sides of the claim under test, and a
    * single stub with a flag would make each case read as a setting rather than as a situation.
    */
  private final class DeadSource extends MetricsSourcePort[IO] {
    def throughput(range: ThroughputRange, endingAt: Instant) =
      IO.pure(Left(exporterDown))
  }

  private final class LiveSource extends MetricsSourcePort[IO] {
    def throughput(range: ThroughputRange, endingAt: Instant) =
      IO.pure(
        Right(
          ThroughputSeries.over(
            range,
            endingAt,
            List(ThroughputSample(endingAt.minusSeconds(60L), 1024.0d, 2048.0d, 12.0d))
          )
        )
      )
  }

  /** The deployment this suite describes: one cluster with no source, one with a source that answers, one
    * with a source that is down, and nothing at all for any other id.
    */
  private val sources: ClusterSources[IO] = new ClusterSources[IO] {
    private val profiles = List(
      SourceProfile(prod, "Production EU", hasSource = false),
      SourceProfile(measured, "Measured", hasSource = true),
      SourceProfile(broken, "Broken", hasSource = true)
    )

    def all: IO[List[SourceProfile]] = IO.pure(profiles)

    def profile(cluster: ClusterId): IO[Option[SourceProfile]] =
      IO.pure(profiles.find(_.cluster == cluster))

    def source(cluster: ClusterId): IO[Option[MetricsSourcePort[IO]]] =
      IO.pure(
        if cluster == measured then Some(new LiveSource)
        else if cluster == broken then Some(new DeadSource)
        else None
      )
  }

  /** The routes, the interceptors and the principal check, over the stub backend.
    *
    * The telemetry is `Telemetry.noop` and not a recording testkit: nothing here asserts a span or a counter,
    * and the rejection counter exists because `SecuredRoutes` takes one, not because a case reads it back.
    */
  private def server: Resource[IO, Backend[IO]] =
    Resource.eval(
      for {
        logger <- FakeStructuredLogger[IO]
        meter <- MeterProvider.noop[IO].get("kui.metrics")
        rejections <- PrincipalVerification.rejectionCounter[IO](meter)
        interceptors <- MetricsApi.interceptors[IO](Telemetry.noop[IO], rejections, logger)
      } yield {
        val routes = MetricsRoutes[IO](
          ThroughputUseCase.make[IO](sources),
          MetricsApi.Securing[IO](codec, rejections, logger)
        )

        TapirStubInterpreter(interceptors, BackendStub[IO](summon))
          .whenServerEndpointsRunLogic(routes)
          .backend()
      }
    )

  /** `http://metrics<path>`, parsed rather than interpolated: sttp's `uri` interpolator escapes an embedded
    * string as one segment, which would turn every path here into a single literal and every request into a
    * 404 that had nothing to do with the case.
    */
  private def address(path: String): Uri = Uri.unsafeParse(s"http://metrics$path")

  /** A token for one request line. The digest covers the method and the *path*; a query string is outside it
    * (ADR-020), which is why `?range=` can vary between cases while the token does not.
    */
  private def token(path: String, validFor: FiniteDuration = 60.seconds): IO[SignedPrincipal] =
    IO.realTimeInstant.flatMap(now =>
      codec.sign(
        PrincipalClaims(
          subject = UserName.unsafe("alice"),
          roles = Set.empty,
          kind = PrincipalKind.Session,
          sessionRef = None,
          issuedAt = now,
          expiresAt = now.plusSeconds(validFor.toSeconds),
          audience = MetricsApi.Id,
          requestDigest = RequestDigest.ofRequestLine("GET", path.takeWhile(_ != '?'))
        )
      )
    )

  private def get(backend: Backend[IO], path: String): IO[Response[String]] =
    token(path).flatMap(signed =>
      basicRequest
        .get(address(path))
        .header(KuiEndpoint.PrincipalHeader, signed.value)
        .response(asStringAlways)
        .send(backend)
    )

  private def body(response: Response[String]): Json =
    parse(response.body).fold(failure => fail(s"not JSON: ${failure.message} in ${response.body}"), identity)

  private def throughputPath(cluster: ClusterId): String =
    s"/internal/v1/clusters/${cluster.value}/metrics/throughput"

  // -----------------------------------------------------------------------------------------------

  test("aClusterWithNoSourceIsTwoHundredWithANotConfiguredSection") {
    // The claim the whole service exists to demonstrate. A 404 here would be indistinguishable from a
    // mistyped URL and a 500 would put a red panel in front of every operator who never asked for metrics,
    // which is how people learn to ignore red panels.
    server.use(get(_, throughputPath(prod))).map { response =>
      val section = body(response).hcursor.downField("throughput")

      assertEquals(response.code.code, 200, response.body)
      assertEquals(section.get[String]("status"), Right("not_configured"))
      // No series at all rather than an empty one: an empty axis is a chart claiming it looked and found
      // nothing, which is a different and much stronger statement than "nothing was measured here".
      assertEquals(section.get[Option[Json]]("data"), Right(None))
    }
  }

  test("anUnrecognisedRangeIsFourHundredWithTheFieldNamed") {
    // Refused rather than defaulted. Answering `?range=90d` with twenty-four hours of samples would draw a
    // chart under a label the caller chose, and nothing on the screen could contradict it.
    server.use(get(_, s"${throughputPath(prod)}?range=90d")).map { response =>
      val json = body(response).hcursor

      assertEquals(response.code.code, 400, response.body)
      assertEquals(json.get[String]("code"), Right("KUI-VALIDATION"))
      assertEquals(json.downField("details").downN(0).get[String]("field"), Right("range"))
      // The three spellings that would have worked, so the message is a fix rather than a refusal.
      assert(
        json
          .downField("details")
          .downN(0)
          .downField("restrictions")
          .as[List[String]]
          .exists(_.exists(_.contains("24h"))),
        response.body
      )
    }
  }

  test("anUnknownClusterIsFourOhFourWithClusterNotFound") {
    // The one case that is a real error: a caller followed a link to something that does not exist, and
    // that is a different answer from "this cluster exists and cannot be measured".
    server.use(get(_, throughputPath(ClusterId.unsafe("never-heard-of-it")))).map { response =>
      assertEquals(response.code.code, 404, response.body)
      assertEquals(body(response).hcursor.get[String]("code"), Right("KUI-CLUSTER-NOT-FOUND"))
    }
  }

  test("aMalformedClusterIdIsFourHundredRatherThanFourOhFour") {
    // "That is not an id" and "no such cluster" are different answers to a caller, and the status is the
    // only place the difference is visible to a client that does not read the body.
    server.use(get(_, "/internal/v1/clusters/Not%20A%20Slug/metrics/throughput")).map { response =>
      assertEquals(response.code.code, 400, response.body)
      assertEquals(body(response).hcursor.get[String]("code"), Right("KUI-VALIDATION"))
    }
  }

  test("aSourceThatRefusesIsTwoHundredWithAnUnavailableSectionAndItsOwnReason") {
    // The other half of the design: an exporter that stopped answering is worth retrying and must not look
    // the same as a deployment that configured none. Both are 200, and the status is what separates them.
    server.use(get(_, throughputPath(broken))).map { response =>
      val section = body(response).hcursor.downField("throughput")

      assertEquals(response.code.code, 200, response.body)
      assertEquals(section.get[String]("status"), Right("unavailable"))
      assertEquals(section.get[String]("reason"), Right("UPSTREAM_UNAVAILABLE"))
      assertEquals(section.get[String]("message"), Right(exporterDown.message))
    }
  }

  test("aMeasuredClusterAnswersTheRangeItWasAskedFor") {
    // The range travels the whole way: query string, contract codec, domain range, and back onto the wire
    // as the same spelling. The two vocabularies are kept equal by `MetricsMappingSuite`; this is the case
    // that proves the request reaches them.
    server.use(get(_, s"${throughputPath(measured)}?range=7d")).map { response =>
      val data = body(response).hcursor.downField("throughput").downField("data")

      assertEquals(response.code.code, 200, response.body)
      assertEquals(body(response).hcursor.downField("throughput").get[String]("status"), Right("ok"))
      assertEquals(data.get[String]("range"), Right("7d"))
      assertEquals(data.get[Long]("stepSeconds"), Right(ThroughputRange.Last7Days.step.toSeconds))
      assertEquals(
        data.downField("buckets").as[List[Json]].map(_.size),
        Right(ThroughputRange.Last7Days.bucketCount)
      )
    }
  }

  test("aBucketNothingWasSampledInIsNullOnTheWireAndNotZero") {
    // The rule the whole DTO is shaped around, asserted where a browser would read it. A zero would claim
    // the cluster was idle; a gap says KUI did not look. The screens draw the two differently and cannot
    // invent the difference from a number.
    server.use(get(_, throughputPath(measured))).map { response =>
      val buckets = body(response).hcursor
        .downField("throughput")
        .downField("data")
        .downField("buckets")
        .as[List[Json]]
        .getOrElse(fail(s"no buckets in ${response.body}"))

      assertEquals(response.code.code, 200, response.body)
      assert(
        buckets.exists(_.hcursor.get[Option[Double]]("bytesInPerSecond") == Right(None)),
        s"every bucket carried a rate, so the gap rendering is unreachable: ${response.body}"
      )
    }
  }

  test("anUnsignedRequestIsRefusedBeforeAnyUseCaseRuns") {
    // The service does not trust its caller. A request that reaches this port without the gateway's
    // signature is refused here, on the same rule the gateway would have applied, because anything that can
    // reach the port would otherwise be able to read every cluster's metrics.
    server
      .use(backend =>
        basicRequest
          .get(address(throughputPath(prod)))
          .response(asStringAlways)
          .send(backend)
      )
      .map(response => assertEquals(response.code.code, 401, response.body))
  }

  test("aTokenMintedForAnotherServiceIsRefused") {
    // `aud` is checked, so a token the gateway minted for the topic service cannot be replayed here.
    val other = ServiceId.unsafe("topic")

    server
      .use(backend =>
        IO.realTimeInstant
          .flatMap(now =>
            codec.sign(
              PrincipalClaims(
                subject = UserName.unsafe("alice"),
                roles = Set.empty,
                kind = PrincipalKind.Session,
                sessionRef = None,
                issuedAt = now,
                expiresAt = now.plusSeconds(60L),
                audience = other,
                requestDigest = RequestDigest.ofRequestLine("GET", throughputPath(prod))
              )
            )
          )
          .flatMap(signed =>
            basicRequest
              .get(address(throughputPath(prod)))
              .header(KuiEndpoint.PrincipalHeader, signed.value)
              .response(asStringAlways)
              .send(backend)
          )
      )
      .map(response => assertEquals(response.code.code, 401, response.body))
  }
}
