package kui.cluster.api

import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.kernel.{Ref, Resource}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.typelevel.otel4s.oteljava.testkit.OtelJavaTestkit
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.*
import sttp.model.Uri
import sttp.client4.impl.cats.implicits.*
import sttp.client4.testing.StreamBackendStub
import sttp.tapir.server.stub4.TapirStreamStubInterpreter

import kui.cluster.application.*
import kui.cluster.domain.LogDirError
import kui.contracts.KuiEndpoint
import kui.kernel.error.{ApplicationError, ErrorCode, InfrastructureError}
import kui.kernel.{BrokerId, ClusterId, UserName}
import kui.observability.Telemetry
import kui.security.{PrincipalClaims, PrincipalCodec, PrincipalKind, RequestDigest, SignedPrincipal}
import kui.testkit.fakes.FakeStructuredLogger

/** That the cluster service answers, and answers honestly when a cluster is down.
  *
  * The distinction under test in most of these cases is the one that decides whether M1's headline promise
  * holds: a request naming a cluster that does not exist fails with a 404, and a request naming a cluster
  * that exists but cannot be reached succeeds with a 200 whose section says so. Get that backwards and one
  * broken cluster takes the dashboard down.
  *
  * Everything runs through Tapir's stub interpreter, which is the real interceptor chain, the real principal
  * check and the real mapping without a socket.
  */
final class ClusterRoutesSuite extends CatsEffectSuite {

  private val prod = ClusterFixtures.Prod
  private val profile = ClusterFixtures.profile()
  private val topology = ClusterFixtures.topology(profile)

  private val fresh = TopologyView(profile.ref, Some(topology), SnapshotFreshness.Fresh(ClusterFixtures.At))

  private val down = TopologyView(
    profile.ref,
    None,
    SnapshotFreshness.Unavailable("connection refused", ClusterFixtures.At)
  )

  /** The service, over whatever use cases the case under test wants. */
  private def server(
      registry: ClusterRegistry[IO] = new ClusterFixtures.StubRegistry(List(profile)),
      topology: ClusterTopologyUseCase[IO] = new ClusterFixtures.StubTopology(List(fresh)),
      brokers: BrokerDetailUseCase[IO] = new ClusterFixtures.StubBrokers(),
      codec: PrincipalCodec[IO] = ClusterTestServer.codec
  ): Resource[IO, ClusterTestServer] =
    OtelJavaTestkit.inMemory[IO]().evalMap { testkit =>
      val telemetry = Telemetry.fromProviders(testkit.tracerProvider, testkit.meterProvider)

      for {
        logger <- FakeStructuredLogger[IO]
        rejections <- ClusterTestServer.rejectionCounter(testkit)
        interceptors <- ClusterApi.interceptors[IO](telemetry, rejections, logger)
      } yield {
        val routes = ClusterRoutes[IO](
          registry,
          topology,
          brokers,
          codec,
          rejections,
          logger
        )

        ClusterTestServer(
          TapirStreamStubInterpreter(interceptors, StreamBackendStub[IO, Fs2Streams[IO]](summon))
            .whenServerEndpointsRunLogic(routes)
            .backend(),
          logger,
          codec,
          testkit
        )
      }
    }

  /** `http://cluster<path>`, parsed rather than interpolated: sttp's `uri` interpolator escapes an embedded
    * string as one segment, which turns every path here into a single literal and every request into a 404.
    */
  private def address(path: String): Uri = Uri.unsafeParse(s"http://cluster$path")

  /** A token for one request line, minted with whichever codec the server under test verifies with.
    *
    * The digest covers the method and the *path*: a query string is deliberately outside it (ADR-020), so
    * the token for `/log-dirs?brokerId=1` is minted over `/log-dirs`.
    */
  private def token(server: ClusterTestServer, method: String, path: String): IO[SignedPrincipal] =
    IO.realTimeInstant.flatMap(now =>
      server.principals.sign(
        PrincipalClaims(
          subject = UserName.unsafe("alice"),
          roles = Set.empty,
          kind = PrincipalKind.Session,
          sessionRef = None,
          issuedAt = now,
          expiresAt = now.plusSeconds(60L),
          audience = kui.cluster.application.ClusterService.Id,
          requestDigest = RequestDigest.ofRequestLine(method, path.takeWhile(_ != '?'))
        )
      )
    )

  private def get(server: ClusterTestServer, path: String): IO[Response[String]] =
    token(server, "GET", path)
      .flatMap(token =>
        basicRequest
          .get(address(path))
          .header(KuiEndpoint.PrincipalHeader, token.value)
          .response(asStringAlways)
          .send(server.backend)
      )

  private def post(server: ClusterTestServer, path: String): IO[Response[String]] =
    token(server, "POST", path)
      .flatMap(token =>
        basicRequest
          .post(address(path))
          .header(KuiEndpoint.PrincipalHeader, token.value)
          .response(asStringAlways)
          .send(server.backend)
      )

  private def body(response: Response[String]): Json =
    parse(response.body).fold(failure => fail(s"not JSON: ${failure.message} in ${response.body}"), identity)

  // -----------------------------------------------------------------------------------------------

  test("theClusterListCarriesOneRowPerConfiguredCluster") {
    server().use(get(_, "/internal/v1/clusters")).map { response =>
      val json = body(response)

      assertEquals(response.code.code, 200, response.body)
      assertEquals(json.hcursor.downField("items").downN(0).get[String]("id"), Right("prod-eu"))
      assertEquals(
        json.hcursor.downField("items").downN(0).downField("summary").get[String]("status"),
        Right("ok")
      )
    }
  }

  test("anUnreachableClusterIsTwoHundredWithAnUnavailableSection") {
    // The milestone's central promise, at the service layer: the row is present, named, and linkable, and
    // only the part that needed a broker is missing.
    server(topology = new ClusterFixtures.StubTopology(List(down)))
      .use(get(_, "/internal/v1/clusters"))
      .map { response =>
        val summary = body(response).hcursor.downField("items").downN(0).downField("summary")

        assertEquals(response.code.code, 200, response.body)
        assertEquals(summary.get[String]("status"), Right("unavailable"))
        assertEquals(summary.get[String]("reason"), Right("UPSTREAM_UNAVAILABLE"))
        assertEquals(summary.get[String]("message"), Right("connection refused"))
        // Identity survives the failure, which is what makes the row clickable.
        assertEquals(
          body(response).hcursor.downField("items").downN(0).get[String]("name"),
          Right("Production EU")
        )
      }
  }

  test("anUnknownClusterIdIsFourOhFourWithClusterNotFound") {
    server().use(get(_, "/internal/v1/clusters/other")).map { response =>
      assertEquals(response.code.code, 404, response.body)
      assertEquals(body(response).hcursor.get[String]("code"), Right("KUI-CLUSTER-NOT-FOUND"))
    }
  }

  test("aMalformedClusterIdIsFourHundredWithTheFieldNamed") {
    // 400 rather than 404: "that is not an id" and "no such cluster" are different answers to a caller.
    server().use(get(_, "/internal/v1/clusters/Not%20A%20Slug")).map { response =>
      assertEquals(response.code.code, 400, response.body)
      assertEquals(body(response).hcursor.get[String]("code"), Right("KUI-VALIDATION"))
    }
  }

  test("theBrokerListRendersFromTheSnapshot") {
    val list = BrokerList(
      profile.ref,
      List(
        BrokerListRow(
          broker = ClusterFixtures.broker(1),
          isController = true,
          replicas = Some(3),
          leaders = None,
          skewPercent = Some(0.0d),
          totalBytes = Some(100L),
          usableBytes = Some(40L),
          offlineDirCount = 0
        )
      ),
      SnapshotFreshness.Fresh(ClusterFixtures.At)
    )

    server(brokers = new ClusterFixtures.StubBrokers(brokerList = Right(list)))
      .use(get(_, "/internal/v1/clusters/prod-eu/brokers"))
      .map { response =>
        val broker = body(response).hcursor.downField("brokers").downField("data").downN(0)

        assertEquals(response.code.code, 200, response.body)
        assertEquals(broker.get[Int]("id"), Right(1))
        assertEquals(broker.get[Option[String]]("rack"), Right(Some("eu-west-1a")))
        assertEquals(broker.get[Boolean]("isController"), Right(true))
        assertEquals(broker.get[Option[Long]]("diskUsageBytes"), Right(Some(60L)))
        // The two counts M1 cannot produce are null on the wire, not zero.
        assertEquals(broker.get[Option[Int]]("partitionCount"), Right(None))
        assertEquals(broker.get[Option[Int]]("leaderCount"), Right(None))
      }
  }

  test("aClusterThatRefusesDescribeConfigsIsAnUnavailableSectionAndNotAnEmptyList") {
    // An empty configuration table and "this cluster does not expose its configuration" look identical on
    // a screen and mean opposite things. The refusal must reach the user.
    val refused = ApplicationError.Unsupported("this cluster does not expose broker configuration")

    server(brokers = new ClusterFixtures.StubBrokers(configView = Left(refused)))
      .use(get(_, "/internal/v1/clusters/prod-eu/brokers/1/configs"))
      .map { response =>
        val configs = body(response).hcursor.downField("configs")

        assertEquals(response.code.code, 200, response.body)
        assertEquals(configs.get[String]("status"), Right("not_configured"))
      }
  }

  test("aFailingLogDirCallProducesAnUnavailableSectionAndTheBrokerListStillAnswers") {
    val brokers = new ClusterFixtures.StubBrokers(
      brokerList = Right(BrokerList(profile.ref, Nil, SnapshotFreshness.Fresh(ClusterFixtures.At))),
      dirs = Left(InfrastructureError.Unreachable("prod-eu", "the broker did not answer"))
    )

    server(brokers = brokers).use { service =>
      for {
        dirs <- get(service, "/internal/v1/clusters/prod-eu/log-dirs?brokerId=1")
        list <- get(service, "/internal/v1/clusters/prod-eu/brokers")
      } yield {
        assertEquals(dirs.code.code, 200, dirs.body)
        assertEquals(
          body(dirs).hcursor.downField("logDirs").get[String]("status"),
          Right("unavailable")
        )
        assertEquals(list.code.code, 200, list.body)
        assertEquals(body(list).hcursor.downField("brokers").get[String]("status"), Right("ok"))
      }
    }
  }

  test("anOfflineDirectoryIsCarriedWithTheHealthyOnesRatherThanFailingTheAnswer") {
    val dirs = BrokerLogDirs(
      profile.ref,
      BrokerId.unsafe(1),
      List(ClusterFixtures.logDir(), ClusterFixtures.logDir("/mnt/broken", Some(LogDirError.Offline))),
      SnapshotFreshness.Fresh(ClusterFixtures.At)
    )

    server(brokers = new ClusterFixtures.StubBrokers(dirs = Right(dirs)))
      .use(get(_, "/internal/v1/clusters/prod-eu/log-dirs?brokerId=1"))
      .map { response =>
        val entries = body(response).hcursor.downField("logDirs").downField("data")

        assertEquals(response.code.code, 200, response.body)
        assertEquals(entries.downN(0).get[Option[String]]("error"), Right(None))
        assertEquals(
          entries.downN(1).get[Option[String]]("error"),
          Right(Some(LogDirError.Offline.describe))
        )
      }
  }

  test("theWholeClusterLogDirsComeFromTheSnapshotWithoutOneCallPerBroker") {
    // No `brokerId`: the answer is assembled from the topology snapshot, so the stubbed live call is
    // never made. A list page must not cost one admin call per broker to fill a column.
    server().use(get(_, "/internal/v1/clusters/prod-eu/log-dirs")).map { response =>
      val entries = body(response).hcursor.downField("logDirs").downField("data")

      assertEquals(response.code.code, 200, response.body)
      assertEquals(entries.downN(0).get[Int]("brokerId"), Right(1))
      assertEquals(entries.downN(0).get[String]("path"), Right("/var/lib/kafka/data"))
    }
  }

  test("refreshAnswersTwoHundredAndTwoWithoutWaiting") {
    // Asserted on the clock, not on a stopwatch: the fake refresh takes ten seconds of virtual time and
    // the response must land at virtual time zero. A route that awaited the scrape would make the
    // button's latency the cluster's latency, which is what the snapshot design removed from the page.
    val program = for {
      seen <- Ref.of[IO, List[ClusterId]](Nil)
      // The in-process codec, because the signing key of the JWS codec has a `notBefore` in 2020 and
      // `TestControl` starts its virtual clock at the epoch: a real signature would be refused for being
      // minted before any key was active, which is a fact about the fixture and not about the route.
      response <- server(
        topology =
          new ClusterFixtures.StubTopology(List(fresh), refreshTakes = 10.seconds, refreshes = Some(seen)),
        codec = PrincipalCodec.inProcess[IO]
      ).use(post(_, "/internal/v1/clusters/prod-eu/refresh"))
      asked <- seen.get
      now <- IO.monotonic
    } yield (response, asked, now)

    TestControl.executeEmbed(program).map { (response, asked, now) =>
      assertEquals(response.code.code, 202, response.body)
      assertEquals(asked, List(prod))
      assertEquals(now, Duration.Zero)
      assertEquals(body(response).hcursor.get[String]("clusterId"), Right("prod-eu"))
    }
  }

  test("refreshingAnUnknownClusterIsFourOhFour") {
    server(topology = new ClusterFixtures.StubTopology(Nil))
      .use(post(_, "/internal/v1/clusters/prod-eu/refresh"))
      .map { response =>
        assertEquals(response.code.code, 404, response.body)
        assertEquals(body(response).hcursor.get[String]("code"), Right("KUI-CLUSTER-NOT-FOUND"))
      }
  }

  test("noSecretAppearsInAnyResponseBody") {
    // R-12's contract-test assertion, and the milestone's "secret fields are unreadable" exit criterion at
    // this layer. It drives *every* route from the endpoint list, so a seventh endpoint added later is
    // covered without anyone remembering to add it here.
    val list = BrokerList(
      profile.ref,
      List(
        BrokerListRow(ClusterFixtures.broker(1), true, Some(3), None, Some(0.0d), Some(1L), Some(0L), 0)
      ),
      SnapshotFreshness.Fresh(ClusterFixtures.At)
    )
    val dirs = BrokerLogDirs(
      profile.ref,
      BrokerId.unsafe(1),
      List(ClusterFixtures.logDir()),
      SnapshotFreshness.Fresh(ClusterFixtures.At)
    )
    val configs = BrokerConfigView(
      profile.ref,
      BrokerId.unsafe(1),
      Nil,
      hasDocumentation = false
    )

    val paths = List(
      "/internal/v1/clusters",
      "/internal/v1/clusters/prod-eu",
      "/internal/v1/clusters/prod-eu/brokers",
      "/internal/v1/clusters/prod-eu/brokers/1/configs",
      "/internal/v1/clusters/prod-eu/log-dirs",
      "/internal/v1/clusters/prod-eu/log-dirs?brokerId=1"
    )

    server(brokers = new ClusterFixtures.StubBrokers(Right(list), Right(dirs), Right(configs))).use {
      service =>
        paths.traverse_(path =>
          get(service, path).map(response =>
            assert(
              !response.body.contains(ClusterFixtures.Canary),
              s"$path leaked a secret: ${response.body}"
            )
          )
        ) *> post(service, "/internal/v1/clusters/prod-eu/refresh").map(response =>
          assert(!response.body.contains(ClusterFixtures.Canary), response.body)
        )
    }
  }

  test("everyErrorResponseIsAnErrorEnvelopeWithTheStatusFromStatusOf") {
    // The expected status is read from `ErrorEnvelope.statusOf` rather than written here as a number, so
    // what this asserts is that the route consults the one mapping in the system - not that 404 happens to
    // be right today.
    val notFound = ApplicationError.NotFound("cluster", "prod-eu", ErrorCode.ClusterNotFound)

    server(
      registry = new ClusterFixtures.StubRegistry(Nil),
      topology = new ClusterFixtures.StubTopology(Nil)
    ).use(get(_, "/internal/v1/clusters/prod-eu")).map { response =>
      val json = body(response).hcursor

      assertEquals(response.code.code, kui.contracts.ErrorEnvelope.statusOf(notFound))
      assertEquals(json.get[String]("code"), Right(ErrorCode.ClusterNotFound.wire))
      assert(json.get[String]("correlationId").isRight, response.body)
      assert(json.get[String]("timestamp").isRight, response.body)
      assertEquals(json.get[Boolean]("retryable"), Right(false))
    }
  }
}
