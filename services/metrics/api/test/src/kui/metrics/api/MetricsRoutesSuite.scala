package kui.metrics.api

import java.time.Instant

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.kernel.Resource
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.typelevel.otel4s.metrics.MeterProvider
import sttp.client4.*
import sttp.client4.impl.cats.implicits.*
import sttp.client4.testing.BackendStub
import sttp.model.Uri
import sttp.tapir.server.stub4.TapirStubInterpreter

import kui.contracts.KuiEndpoint
import kui.http.principal.PrincipalVerification
import kui.kernel.error.{InfrastructureError, KuiError}
import kui.kernel.{ClusterId, Secret, ServiceId, UserName}
import kui.metrics.application.{ClusterSources, MetricsUseCases, SourceProfile}
import kui.metrics.contract.MetricsEndpoints
import kui.metrics.domain.*
import kui.observability.Telemetry
import kui.security.*
import kui.testkit.fakes.FakeStructuredLogger

/** That the five endpoints this service has answer the way the whole service was designed around.
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
  * is stubbed, because what is under test here is the route rather than the collector — `MetricsWiringSuite`
  * drives the real one against a real socket. It runs through Tapir's stub interpreter, which is the real
  * interceptor chain and the real principal check without a socket; a bound port would add seconds per case
  * and prove only that Netty works, which `libs/http` proves once for every service.
  */
final class MetricsRoutesSuite extends CatsEffectSuite {

  private val prod = ClusterId.unsafe("prod-eu")
  private val measured = ClusterId.unsafe("measured")
  private val broken = ClusterId.unsafe("broken")
  private val forgotten = ClusterId.unsafe("forgotten")

  /** The scrape cadence the service under test is configured with, and therefore the age at which a
    * point-in-time reading stops being current. `LiveSource` answers with the instant it was asked for, so
    * every case here is fresh unless it uses the `forgotten` cluster.
    */
  private val ScrapeInterval: FiniteDuration = 30.seconds

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
  final private class DeadSource extends MetricsSourcePort[IO] {
    def throughput(range: ThroughputRange, endingAt: Instant) = IO.pure(Left(exporterDown))
    def latency(range: ThroughputRange, endingAt: Instant) = IO.pure(Left(exporterDown))
    def requestHandlers(asOf: Instant): IO[Either[KuiError, Observed[RequestHandlerReading]]] =
      IO.pure(Left(exporterDown))
    def producers(count: Int, asOf: Instant): IO[Either[KuiError, Observed[TopProducers]]] =
      IO.pure(Left(exporterDown))
    def recordSize(asOf: Instant): IO[Either[KuiError, Observed[RecordSizeReading]]] =
      IO.pure(Left(exporterDown))
  }

  final private class LiveSource extends MetricsSourcePort[IO] {

    def throughput(range: ThroughputRange, endingAt: Instant) =
      IO.pure(
        Right(
          ThroughputSeries.over(
            range,
            endingAt,
            List(ThroughputSample(endingAt.minusSeconds(60L), Some(1024.0d), Some(2048.0d), Some(12.0d)))
          )
        )
      )

    def latency(range: ThroughputRange, endingAt: Instant) =
      IO.pure(
        Right(
          LatencySeries.over(
            range,
            endingAt,
            List(LatencySample(endingAt.minusSeconds(60L), Some(9.0d), Some(502.0d)))
          )
        )
      )

    def requestHandlers(asOf: Instant) =
      IO.pure(
        Right(
          Observed(
            RequestHandlerReading(Some(0.8912d), Some(0.7104d), List(PurgatoryQueue("Fetch", 481L))),
            asOf
          )
        )
      )

    /** Ten topics, so that a `?top=` the route ignored would be visible as a list of the wrong length. */
    def producers(count: Int, asOf: Instant) =
      IO.pure(
        Right(
          Observed(
            TopProducers.of(
              List.tabulate(10)(index => TopicProducer(s"topic-$index", (index + 1).toDouble * 100.0d)),
              count
            ),
            asOf
          )
        )
      )

    def recordSize(asOf: Instant) =
      IO.pure(Right(Observed(RecordSizeReading.from(Some(1024.0d), Some(8.0d)), asOf)))
  }

  /** A source whose newest scrape is an hour old: the exporter answered once and then went away.
    *
    * It is a third stub rather than a flag on `LiveSource` for the reason the other two are separate — the
    * situation is what is under test. Everything it holds is true; none of it is current.
    */
  final private class AbandonedSource extends MetricsSourcePort[IO] {
    private def scrapedAnHourBefore(asOf: Instant): Instant = asOf.minusSeconds(3600L)

    def throughput(range: ThroughputRange, endingAt: Instant) =
      IO.pure(Right(ThroughputSeries.absent(range, endingAt)))
    def latency(range: ThroughputRange, endingAt: Instant) =
      IO.pure(Right(LatencySeries.absent(range, endingAt)))
    def requestHandlers(asOf: Instant) =
      IO.pure(
        Right(Observed(RequestHandlerReading(Some(0.5d), Some(0.5d), Nil), scrapedAnHourBefore(asOf)))
      )
    def producers(count: Int, asOf: Instant) =
      IO.pure(
        Right(
          Observed(TopProducers.of(List(TopicProducer("orders.v1", 12.0d)), count), scrapedAnHourBefore(asOf))
        )
      )
    def recordSize(asOf: Instant) =
      IO.pure(
        Right(Observed(RecordSizeReading.from(Some(64.0d), Some(2.0d)), scrapedAnHourBefore(asOf)))
      )
  }

  /** The deployment this suite describes: one cluster with no source, one with a source that answers, one
    * with a source that is down, one whose exporter answered an hour ago and stopped, and nothing at all for
    * any other id.
    */
  private val sources: ClusterSources[IO] = new ClusterSources[IO] {
    private val profiles = List(
      SourceProfile(prod, "Production EU", hasSource = false),
      SourceProfile(measured, "Measured", hasSource = true),
      SourceProfile(broken, "Broken", hasSource = true),
      SourceProfile(forgotten, "Forgotten", hasSource = true)
    )

    def all: IO[List[SourceProfile]] = IO.pure(profiles)

    def profile(cluster: ClusterId): IO[Option[SourceProfile]] =
      IO.pure(profiles.find(_.cluster == cluster))

    def source(cluster: ClusterId): IO[Option[MetricsSourcePort[IO]]] =
      IO.pure(
        if cluster == measured then Some(new LiveSource)
        else if cluster == broken then Some(new DeadSource)
        else if cluster == forgotten then Some(new AbandonedSource)
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
          // The scrape cadence this suite's service is configured with, and therefore the age at which a
          // point-in-time reading becomes `stale`. Both stubs answer with the instant they were asked for,
          // so every case below is a fresh reading unless it says otherwise.
          MetricsUseCases.make[IO](sources, ScrapeInterval),
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

  private def metricsPath(cluster: ClusterId, endpoint: String): String =
    s"/internal/v1/clusters/${cluster.value}/metrics/$endpoint"

  private def throughputPath(cluster: ClusterId): String = metricsPath(cluster, "throughput")

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

  // -----------------------------------------------------------------------------------------------
  // The four endpoints the milestone added
  // -----------------------------------------------------------------------------------------------

  test("everyCardAnswersTwoHundredWithANotConfiguredSectionForAnUnmeasuredCluster") {
    // The claim the whole service exists to demonstrate, asserted on every card rather than on the one it
    // was first written for. A dashboard with four honest cards and one red panel is a dashboard whose
    // operator learns to ignore red panels.
    val cards = List(
      "latency" -> "latency",
      "request-handlers" -> "requestHandlers",
      "producers" -> "producers",
      "record-size" -> "recordSize"
    )

    server
      .use(backend =>
        cards.traverse((path, field) =>
          get(backend, metricsPath(prod, path)).map { response =>
            val section = body(response).hcursor.downField(field)
            assertEquals(response.code.code, 200, s"$path: ${response.body}")
            assertEquals(section.get[String]("status"), Right("not_configured"), s"$path: ${response.body}")
            assertEquals(section.get[Option[Json]]("data"), Right(None), s"$path: ${response.body}")
          }
        )
      )
      .map(_ => ())
  }

  test("aMeasuredClusterAnswersLatencyAsTwoSeriesOnTheRequestedWindow") {
    server.use(get(_, s"${metricsPath(measured, "latency")}?window=7d")).map { response =>
      val section = body(response).hcursor.downField("latency")
      val data = section.downField("data")

      assertEquals(response.code.code, 200, response.body)
      assertEquals(section.get[String]("status"), Right("ok"))
      assertEquals(data.get[String]("window"), Right("7d"))
      assertEquals(data.get[Long]("stepSeconds"), Right(ThroughputRange.Last7Days.step.toSeconds))
      assertEquals(
        data.downField("buckets").as[List[Json]].map(_.size),
        Right(ThroughputRange.Last7Days.bucketCount)
      )
      val buckets = data.downField("buckets").as[List[Json]].getOrElse(fail(response.body))
      // The two lines the legend draws, and a gap where nothing was sampled — never a zero, which would
      // claim the broker answered instantly.
      assertEquals(
        buckets.flatMap(_.hcursor.get[Option[Double]]("produceP99Millis").toOption.flatten),
        List(9.0)
      )
      assertEquals(
        buckets.flatMap(_.hcursor.get[Option[Double]]("fetchP99Millis").toOption.flatten),
        List(502.0)
      )
      assert(buckets.exists(_.hcursor.get[Option[Double]]("produceP99Millis") == Right(None)), response.body)
    }
  }

  test("anUnrecognisedLatencyWindowIsFourHundredWithTheFieldNamed") {
    // The same refusal as `?range=`, under the parameter name this card's control uses. Defaulting would
    // draw seven days of data under a label the caller chose.
    server.use(get(_, s"${metricsPath(prod, "latency")}?window=90d")).map { response =>
      val json = body(response).hcursor

      assertEquals(response.code.code, 400, response.body)
      assertEquals(json.get[String]("code"), Right("KUI-VALIDATION"))
      assertEquals(json.downField("details").downN(0).get[String]("field"), Right("window"))
    }
  }

  test("theIdleRatiosArriveAsRatiosAndPurgatoryArrivesAsACount") {
    // ADR-052's first refusal, asserted where a browser reads it: two fractions of one, and a count of
    // parked requests with no denominator anywhere on the document to turn it into a percentage.
    server.use(get(_, metricsPath(measured, "request-handlers"))).map { response =>
      val data = body(response).hcursor.downField("requestHandlers").downField("data")

      assertEquals(response.code.code, 200, response.body)
      assertEquals(data.get[Option[Double]]("requestHandlerIdleRatio"), Right(Some(0.8912)))
      assertEquals(data.get[Option[Double]]("networkProcessorIdleRatio"), Right(Some(0.7104)))
      assertEquals(data.downField("purgatory").downN(0).get[String]("operation"), Right("Fetch"))
      assertEquals(data.downField("purgatory").downN(0).get[Long]("delayedRequests"), Right(481L))
      assert(!response.body.contains("Percent"), response.body)
      assert(!response.body.contains("%"), response.body)
    }
  }

  test("topProducersAreTopicsAndTheTopParameterReachesThePort") {
    // ADR-052's second refusal on the wire — `measuredBy` is `topic` and no field is called `clientId` —
    // and the query parameter travelling all the way, which is what stops a five-row card answering a
    // request for three.
    server.use(get(_, s"${metricsPath(measured, "producers")}?top=3")).map { response =>
      val data = body(response).hcursor.downField("producers").downField("data")

      assertEquals(response.code.code, 200, response.body)
      assertEquals(data.get[String]("measuredBy"), Right("topic"))
      assertEquals(data.downField("topics").as[List[Json]].map(_.size), Right(3))
      assertEquals(data.downField("topics").downN(0).get[String]("topic"), Right("topic-9"))
      assert(!response.body.contains("clientId"), response.body)
    }
  }

  test("aCallerWhoNamesNoTopGetsTheContractsDefaultAndNotTheWholeList") {
    // The default is part of the contract a browser codes against: the card draws five rows, and the
    // endpoint's own document says five. Nothing asserted it until this case, so `?top=` could have been
    // defaulted to any number at all and every existing case — each of which names a `top` — would have
    // stayed green while the card grew rows nobody asked for.
    server.use(get(_, metricsPath(measured, "producers"))).map { response =>
      val topics = body(response).hcursor
        .downField("producers")
        .downField("data")
        .downField("topics")
        .as[List[Json]]
        .getOrElse(fail(response.body))

      assertEquals(response.code.code, 200, response.body)
      assertEquals(topics.size, MetricsEndpoints.DefaultTop)
      assertEquals(MetricsEndpoints.DefaultTop, 5)
    }
  }

  test("anHourOldGaugeIsAStaleSectionCarryingItsFigureAndTheInstantItWasTaken") {
    // The fourth section state, on the wire, over the whole path a browser reads. An exporter that stopped
    // answering leaves a true reading behind it: refusing would throw the number away and offer a Retry,
    // and answering `ok` would draw an hour-old idle ratio as the broker's current state. `stale` is the
    // one answer that is neither, and `fetchedAt` is the scrape's own instant rather than the request's.
    server.use(get(_, metricsPath(forgotten, "request-handlers"))).map { response =>
      val section = body(response).hcursor.downField("requestHandlers")

      assertEquals(response.code.code, 200, response.body)
      assertEquals(section.get[String]("status"), Right("stale"))
      assertEquals(section.downField("data").get[Option[Double]]("requestHandlerIdleRatio"), Right(Some(0.5)))
      assertEquals(section.get[String]("reason"), Right("UPSTREAM_UNAVAILABLE"))
      // Older than the reply itself by roughly an hour, which is what makes the badge's age true.
      val fetchedAt = section.get[Instant]("fetchedAt").getOrElse(fail(response.body))
      assert(fetchedAt.isBefore(Instant.now().minusSeconds(1800L)), s"$fetchedAt in ${response.body}")
    }
  }

  test("aFreshGaugeIsOkAndNotStale") {
    // The other half, so the rule above cannot be satisfied by answering `stale` to everything. `LiveSource`
    // answers with the instant it was asked for, which is inside one `ScrapeInterval` by construction.
    server.use(get(_, metricsPath(measured, "request-handlers"))).map { response =>
      assertEquals(body(response).hcursor.downField("requestHandlers").get[String]("status"), Right("ok"))
      assert(ScrapeInterval.toSeconds > 0L)
    }
  }

  test("aTopOutsideItsBoundsIsFourHundredRatherThanClamped") {
    // Clamping would answer fifty of the two hundred asked for and look like a cluster with fewer busy
    // topics than it has, which is `SearchEndpoints`' argument and holds here.
    server.use(get(_, s"${metricsPath(measured, "producers")}?top=200")).map { response =>
      assertEquals(response.code.code, 400, response.body)
      assertEquals(body(response).hcursor.get[String]("code"), Right("KUI-VALIDATION"))
    }
  }

  test("aTopThatIsNotANumberIsFourHundredAndNotTheDefault") {
    server.use(get(_, s"${metricsPath(measured, "producers")}?top=lots")).map { response =>
      assertEquals(response.code.code, 400, response.body)
    }
  }

  test("theRecordSizeCardIsAMeanWithItsTwoRatesAndNoPercentile") {
    // ADR-052's third refusal, asserted where a browser reads it. There is no `p50`, no `p99`, no `max`
    // and no bucket array on this document, so a twelve-bucket histogram cannot be drawn from it at all.
    server.use(get(_, metricsPath(measured, "record-size"))).map { response =>
      val data = body(response).hcursor.downField("recordSize").downField("data")

      assertEquals(response.code.code, 200, response.body)
      assertEquals(data.get[Option[Double]]("meanBytes"), Right(Some(128.0)))
      assertEquals(data.get[Option[Double]]("bytesInPerSecond"), Right(Some(1024.0)))
      assertEquals(data.get[Option[Double]]("recordsPerSecond"), Right(Some(8.0)))
      assert(!response.body.contains("p50"), response.body)
      assert(!response.body.contains("p99"), response.body)
      assert(!response.body.contains("histogram"), response.body)
    }
  }

  test("aSourceThatRefusesOneFamilyIsUnavailableOnThatCardAndOkOnTheOthers") {
    // One dead family costs one card. The `broken` cluster's source refuses everything, so what this
    // asserts is that each card carries its own reason rather than one 500 taking the page.
    val cards = List("latency" -> "latency", "producers" -> "producers", "record-size" -> "recordSize")

    server
      .use(backend =>
        cards.traverse((path, field) =>
          get(backend, metricsPath(broken, path)).map { response =>
            val section = body(response).hcursor.downField(field)
            assertEquals(response.code.code, 200, s"$path: ${response.body}")
            assertEquals(section.get[String]("status"), Right("unavailable"), s"$path: ${response.body}")
            assertEquals(section.get[String]("message"), Right(exporterDown.message), s"$path: $response")
          }
        )
      )
      .map(_ => ())
  }

  test("everyPublishedEndpointIsRoutedAndNoneOfThemIsAFourOhFour") {
    // The list `MetricsEndpoints.all` publishes is the list the gateway proxies. An endpoint declared and
    // not bound is a public path that 404s on a deployment whose capability document says it works.
    val paths = MetricsEndpoints.all
      .map(_.showPathTemplate().takeWhile(_ != '?').replace("{clusterId}", measured.value))

    assertEquals(paths.size, 5)
    server
      .use(backend =>
        paths.traverse(path =>
          get(backend, path)
            .map(response => assertEquals(response.code.code, 200, s"$path: ${response.body}"))
        )
      )
      .map(_ => ())
  }
}
