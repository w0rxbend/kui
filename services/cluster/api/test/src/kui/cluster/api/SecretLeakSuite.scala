package kui.cluster.api

import cats.effect.IO
import cats.effect.kernel.Resource
import cats.syntax.all.*
import munit.CatsEffectSuite
import org.typelevel.otel4s.oteljava.testkit.OtelJavaTestkit
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.*
import sttp.client4.impl.cats.implicits.*
import sttp.client4.testing.StreamBackendStub
import sttp.model.Uri
import sttp.tapir.AnyEndpoint
import sttp.tapir.server.stub4.TapirStreamStubInterpreter

import kui.cluster.application.{
  BrokerConfigView,
  BrokerList,
  BrokerLogDirs,
  ClusterService,
  SnapshotFreshness,
  TopologyView
}
import kui.cluster.contract.{ClusterEndpoints, ClusterWriteEndpoints, ProfileEndpoints}
import kui.contracts.KuiEndpoint
import kui.http.principal.RbacGuard
import kui.observability.Telemetry
import kui.kernel.BrokerId
import kui.security.{PrincipalClaims, PrincipalKind, RequestDigest}
import kui.testkit.fakes.FakeStructuredLogger

/** One endpoint of this service can emit a credential, and it is the profile.
  *
  * ==Why this suite exists as a walk rather than as a review==
  *
  * ADR-046 puts the cluster's password, its keystore bytes and its property overrides on
  * `GET /internal/v1/clusters/{id}/profile`, because a Kafka-facing service cannot open a connection
  * without them. Every other endpoint of this service must still be unable to emit one — and "must" is a
  * property of *all* the endpoints, including the one somebody adds next year by copying the profile route
  * because it was the nearest example.
  *
  * So this is a walk over every declared endpoint rather than an assertion about the ones anybody thought
  * of. The fixture profile's every credential is one distinctive token; each route is driven; and the token
  * is asserted to appear in exactly one response body. `everyDeclaredEndpointIsExercised` is the half that
  * keeps the walk honest: a new endpoint that this file does not drive fails the suite, so the list cannot
  * silently stop covering the service.
  *
  * The second half is the one M1's own review would have wanted: a secret that never reaches a *body* can
  * still reach a log line or a span attribute, which is a file on a disk in a different system, usually with
  * a longer retention than the request itself.
  */
final class SecretLeakSuite extends CatsEffectSuite {

  private val profile = ClusterFixtures.profile()
  private val canary = ClusterFixtures.Canary

  private val topologyView =
    TopologyView(profile.ref, Some(ClusterFixtures.topology(profile)), SnapshotFreshness.Fresh(ClusterFixtures.At))

  /** Every endpoint this service declares, with a concrete request line for it.
    *
    * The paths are written out rather than derived from `showPathTemplate`, because a template's
    * placeholders have to be filled in with values that exist in the fixture registry anyway — and a
    * hand-written path that has drifted from its endpoint answers 404, which
    * `everyResponseIsAnAnswerAndNotARoutingMiss` catches.
    */
  private val requests: List[(AnyEndpoint, String, String)] = List(
    (ClusterEndpoints.listClusters, "GET", "/internal/v1/clusters"),
    (ClusterEndpoints.getCluster, "GET", "/internal/v1/clusters/prod-eu"),
    (ClusterEndpoints.listBrokers, "GET", "/internal/v1/clusters/prod-eu/brokers"),
    (ClusterEndpoints.brokerConfigs, "GET", "/internal/v1/clusters/prod-eu/brokers/1/configs"),
    (ClusterEndpoints.logDirs, "GET", "/internal/v1/clusters/prod-eu/log-dirs"),
    (ClusterEndpoints.refresh, "POST", "/internal/v1/clusters/prod-eu/refresh"),
    (ProfileEndpoints.profile, "GET", "/internal/v1/clusters/prod-eu/profile")
  )

  /** Answering stubs, not refusing ones: a 404 body carries no secret either, so a walk over routes that
    * all missed would pass the leak assertion while proving nothing.
    */
  private val brokers = new ClusterFixtures.StubBrokers(
    brokerList = Right(BrokerList(profile.ref, Nil, SnapshotFreshness.Fresh(ClusterFixtures.At))),
    dirs = Right(BrokerLogDirs(profile.ref, BrokerId.unsafe(1), Nil, SnapshotFreshness.Fresh(ClusterFixtures.At))),
    configView = Right(BrokerConfigView(profile.ref, BrokerId.unsafe(1), Nil, hasDocumentation = false))
  )

  private def server: Resource[IO, ClusterTestServer] =
    OtelJavaTestkit.inMemory[IO]().evalMap { testkit =>
      val telemetry = Telemetry.fromProviders(testkit.tracerProvider, testkit.meterProvider)
      val codec = ClusterTestServer.codec
      val registry = new ClusterFixtures.StubRegistry(List(profile))

      for {
        logger <- FakeStructuredLogger[IO]
        rejections <- ClusterTestServer.rejectionCounter(testkit)
        interceptors <- ClusterApi.interceptors[IO](telemetry, rejections, logger)
      } yield ClusterTestServer(
        TapirStreamStubInterpreter(interceptors, StreamBackendStub[IO, Fs2Streams[IO]](summon))
          .whenServerEndpointsRunLogic(
            ClusterRoutes[IO](
              registry,
              new ClusterFixtures.StubTopology(List(topologyView)),
              brokers,
              codec,
              rejections,
              logger,
              RbacGuard.allowAll[IO]
            ) ++ ProfileRoutes[IO](registry, codec, rejections, telemetry, logger, RbacGuard.allowAll[IO])
          )
          .backend(),
        logger,
        codec,
        testkit
      )
    }

  private def call(service: ClusterTestServer, method: String, path: String): IO[Response[String]] =
    IO.realTimeInstant
      .flatMap(now =>
        service.principals.sign(
          PrincipalClaims(
            subject = kui.kernel.UserName.unsafe("alice"),
            roles = Set.empty,
            kind = PrincipalKind.Session,
            sessionRef = None,
            issuedAt = now,
            expiresAt = now.plusSeconds(60L),
            audience = ClusterService.Id,
            requestDigest = RequestDigest.ofRequestLine(method, path.takeWhile(_ != '?'))
          )
        )
      )
      .flatMap { token =>
        val uri = Uri.unsafeParse(s"http://cluster$path")
        val request = if method == "POST" then basicRequest.post(uri) else basicRequest.get(uri)
        request
          .header(KuiEndpoint.PrincipalHeader, token.value)
          .response(asStringAlways)
          .send(service.backend)
      }

  private def responses: IO[(ClusterTestServer, List[(String, Response[String])])] =
    server.use { service =>
      requests
        .traverse((_, method, path) => call(service, method, path).map(path -> _))
        .map(service -> _)
    }

  test("everyDeclaredEndpointIsExercised") {
    // Without this the walk is only as good as whoever last remembered to extend the list. A new
    // endpoint added to `ClusterEndpoints.all` fails here before it can fail silently below.
    val exercised = requests.map(_._1.showPathTemplate().takeWhile(_ != '?')).toSet
    val declared = (ClusterEndpoints.all ++ ProfileEndpoints.all)
      .map(_.showPathTemplate().takeWhile(_ != '?'))
      .toSet

    assertEquals(declared.diff(exercised), Set.empty[String], "an endpoint this suite does not drive")
  }

  test("everyEndpointOfThisServiceIsOnTheInternalChannel") {
    // The premise of ADR-046: a credential travels on `/internal/v1` and there is no `/api/v1` route in
    // this service to travel on instead. The day one is added, this fails, and the person adding it has
    // to say what redaction the new public route applies.
    val public = (ClusterEndpoints.all ++ ProfileEndpoints.all ++ ClusterWriteEndpoints.all)
      .map(_.showPathTemplate())
      .filterNot(_.startsWith("/internal/v1/"))

    assertEquals(public, Nil)
  }

  test("onlyTheProfileEndpointEmitsASecret") {
    responses.map { (_, answers) =>
      val leaking = answers.collect { case (path, response) if response.body.contains(canary) => path }
      assertEquals(leaking, List("/internal/v1/clusters/prod-eu/profile"), answers.map(_._2.body).mkString("\n"))
    }
  }

  test("everyResponseIsAnAnswerAndNotARoutingMiss") {
    // A 404 body contains no secret either, so without this the assertion above would pass just as well
    // against a suite whose paths had all drifted.
    responses.map { (_, answers) =>
      val missed = answers.collect { case (path, response) if response.code.code == 404 => path }
      assertEquals(missed, Nil)
    }
  }

  test("noSecretReachesALogLineOrASpan") {
    // Including on the profile call itself: the route logs the cluster id, the version and whether it
    // answered 200 or 304, and never the profile.
    server
      .use { service =>
        for {
          _ <- requests.traverse_((_, method, path) => call(service, method, path))
          entries <- service.logger.entries
          spans <- service.telemetry.finishedSpans
        } yield {
          val logged = entries.map(entry => entry.message + entry.context.mkString(" ")).mkString("\n")
          assert(!logged.contains(canary), logged)

          val attributes = spans
            .flatMap(span => span.getAttributes.asMap().values().toArray.toList.map(_.toString))
            .mkString("\n")
          assert(!attributes.contains(canary), attributes)
          assert(spans.nonEmpty, "no span was recorded, so this assertion proved nothing")
        }
      }
  }

  test("theRedactedClusterDtoStillRedacts") {
    // M1's assertion, re-run here so that widening the profile cannot weaken it by accident.
    responses.map { (_, answers) =>
      val rows = answers.collectFirst { case (path, response) if path == "/internal/v1/clusters" => response.body }
      assert(rows.exists(body => !body.contains(canary)), rows.toString)
      assert(rows.exists(_.contains("SASL_SSL")), rows.toString)
    }
  }
}
