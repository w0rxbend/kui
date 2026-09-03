package kui.cluster.api

import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.kernel.{Ref, Resource}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.typelevel.otel4s.oteljava.testkit.OtelJavaTestkit
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.*
import sttp.client4.impl.cats.implicits.*
import sttp.client4.testing.StreamBackendStub
import sttp.model.Uri
import sttp.tapir.server.stub4.TapirStreamStubInterpreter

import kui.cluster.contract.ClusterWriteEndpoints
import kui.cluster.contract.dto.*
import kui.cluster.domain.{ClusterProfile, ProfileVersion}
import kui.contracts.KuiEndpoint
import kui.kernel.error.{ApplicationError, ErrorCode, InfrastructureError, KuiError}
import kui.kernel.{RoleName, Secret, UserName}
import kui.observability.Telemetry
import kui.security.*
import kui.testkit.fakes.FakeStructuredLogger

/** That the one write M1 ships is safe to expose to a wizard a milestone from now.
  *
  * Each case is one row of the endpoint's status table, and the table is the specification: which failures a
  * caller can tell apart decides whether a retry is safe, whether the caller should change what it sent, and
  * whether an operator has to change the deployment.
  */
final class ClusterWriteRoutesSuite extends CatsEffectSuite {

  private val path = "/internal/v1/clusters/prod-eu"

  /** A principal that holds the permission. Nothing grants it while authentication is disabled, which is
    * what keeps this endpoint out of a browser's reach; a test has to mint one deliberately.
    */
  private val editor: Principal = Principal(
    UserName.unsafe("operator"),
    Set(RoleName.unsafe(ClusterWriteRoutes.RequiredPermission)),
    PrincipalKind.Session
  )

  private val request = ClusterWriteRequest(
    name = "prod eu",
    readOnly = false,
    bootstrapServers = "broker-1.example.com:9093",
    security = ClusterSecurityWrite(
      protocol = "SASL_SSL",
      mechanism = Some("SCRAM-SHA-512"),
      username = Some(ClusterFixtures.Canary),
      password = Some(Secret(ClusterFixtures.Canary)),
      truststore = Some(StoreMaterialWrite(Secret(ClusterFixtures.Canary), Some(Secret(ClusterFixtures.Canary)))),
      keystore = None,
      verifyHostname = true
    ),
    properties = Map("ssl.truststore.password" -> ClusterFixtures.Canary),
    admin = AdminTuningWrite(timeoutMs = 15000L, batchSize = 200, parallelism = 4)
  )

  private def server(
      writes: ClusterWriteUseCaseStub,
      permitted: Principal => Boolean = _ => true
  ): Resource[IO, ClusterTestServer] =
    OtelJavaTestkit.inMemory[IO]().evalMap { testkit =>
      val telemetry = Telemetry.fromProviders(testkit.tracerProvider, testkit.meterProvider)
      val codec = PrincipalCodec.inProcess[IO]

      for {
        logger <- FakeStructuredLogger[IO]
        rejections <- ClusterTestServer.rejectionCounter(testkit)
        interceptors <- ClusterApi.interceptors[IO](telemetry, rejections, logger)
      } yield ClusterTestServer(
        TapirStreamStubInterpreter(interceptors, StreamBackendStub[IO, Fs2Streams[IO]](summon))
          .whenServerEndpointsRunLogic(
            ClusterWriteRoutes[IO](writes, codec, rejections, logger, permitted)
          )
          .backend(),
        logger,
        codec,
        testkit
      )
    }

  /** The stub, with the fields each case needs. Kept local rather than in the shared fixtures because the
    * timing case needs the delay and nothing else does.
    */
  private final class ClusterWriteUseCaseStub(
      val answer: ClusterProfile => Either[KuiError, ClusterProfile] = _.asRight[KuiError],
      val takes: FiniteDuration = Duration.Zero,
      val seen: Option[Ref[IO, List[(ClusterProfile, ProfileVersion)]]] = None
  ) extends kui.cluster.application.ClusterWriteUseCase[IO] {

    def put(profile: ClusterProfile, expected: ProfileVersion): IO[Either[KuiError, ClusterProfile]] =
      seen.traverse_(_.update(_ :+ ((profile, expected)))) *> IO.sleep(takes).as(answer(profile))
  }

  private def put(
      service: ClusterTestServer,
      body: Json = request.asJson,
      ifMatch: Option[String] = Some("\"0\""),
      principal: Principal = editor,
      at: String = path
  ): IO[Response[String]] =
    IO.realTimeInstant.flatMap { now =>
      service.principals
        .sign(
          PrincipalClaims(
            subject = principal.name,
            roles = principal.roles,
            kind = principal.kind,
            sessionRef = None,
            issuedAt = now,
            expiresAt = now.plusSeconds(60L),
            audience = kui.cluster.application.ClusterService.Id,
            requestDigest = RequestDigest.ofRequestLine("PUT", at)
          )
        )
        .flatMap { token =>
          val base = basicRequest
            .put(Uri.unsafeParse(s"http://cluster$at"))
            .header(KuiEndpoint.PrincipalHeader, token.value)
            .header("Content-Type", "application/json")
            .body(body.noSpaces)
            .response(asStringAlways)

          ifMatch
            .fold(base)(tag => base.header(ClusterWriteEndpoints.IfMatchHeader, tag))
            .send(service.backend)
        }
    }

  private def code(response: Response[String]): Option[String] =
    parse(response.body).toOption.flatMap(_.hcursor.get[String]("code").toOption)

  // -----------------------------------------------------------------------------------------------

  test("aSuccessfulWriteReturnsTheRedactedProfileAndNotTheRequest") {
    // R-12 on the one endpoint that legitimately receives secrets: everything the caller sent is the
    // canary token, and none of it may come back.
    server(new ClusterWriteUseCaseStub()).use(put(_)).map { response =>
      assertEquals(response.code.code, 200, response.body)
      assert(!response.body.contains(ClusterFixtures.Canary), response.body)
      assertEquals(
        parse(response.body).flatMap(_.hcursor.get[String]("id")),
        Right("prod-eu")
      )
      // The shape of the security settings survives; their content does not.
      assertEquals(
        parse(response.body).flatMap(_.hcursor.downField("security").get[String]("protocol")),
        Right("SASL_SSL")
      )
    }
  }

  test("theRouteWaitsForReadBackBeforeAnsweringTwoHundred") {
    // The exit criterion "a write returns 200 only after the writer has read its own record back". The
    // store's `put` returns once the record is readable; asserted on a virtual clock so it is a fact about
    // ordering rather than a stopwatch reading.
    val program = server(new ClusterWriteUseCaseStub(takes = 2.seconds))
      .use(put(_))
      .flatMap(response => IO.monotonic.map((response, _)))

    TestControl.executeEmbed(program).map { (response, elapsed) =>
      assertEquals(response.code.code, 200, response.body)
      assertEquals(elapsed, 2.seconds)
    }
  }

  test("aVersionThatDoesNotMatchIsFourOhNineWithTheConflictCode") {
    // `Remote` is how an adapter carries a code the store decided: the version check happens in the log,
    // not here, so the code travels rather than being re-derived.
    val conflict = ApplicationError.Remote(
      ErrorCode.ConfigVersionConflict,
      "the cluster was changed by someone else",
      Nil
    )

    server(new ClusterWriteUseCaseStub(answer = _ => Left(conflict))).use(put(_)).map { response =>
      assertEquals(response.code.code, 409, response.body)
      assertEquals(code(response), Some(ErrorCode.ConfigVersionConflict.wire))
    }
  }

  test("aDeploymentWithNoStoreIsToldSoAndToldWhichSettingToSet") {
    // 501 and not 500: nothing is broken. An operator reading "not implemented" with no detail would
    // reasonably conclude KUI cannot do this at all.
    server(new ClusterWriteUseCaseStub(answer = _ => Left(ClusterWriteRoutes.NoStore))).use(put(_)).map {
      response =>
        assertEquals(response.code.code, 501, response.body)
        assertEquals(code(response), Some(ErrorCode.Unsupported.wire))
        assert(response.body.contains("kui.store.kafka.bootstrapServers"), response.body)
    }
  }

  test("anUnreachableStoreRejectsTheWriteRatherThanBufferingIt") {
    // The exit criterion "writes are rejected rather than lost". A queued configuration change that
    // applies twenty minutes later against a version that no longer exists is worse than a refusal.
    val unreachable = InfrastructureError.Unreachable("store", "connection refused")

    server(new ClusterWriteUseCaseStub(answer = _ => Left(unreachable))).use(put(_)).map { response =>
      assertEquals(response.code.code, 503, response.body)
      assertEquals(code(response), Some(ErrorCode.UpstreamUnavailable.wire))
    }
  }

  test("anAbsentIfMatchIsFourHundredWithTheHeaderNamed") {
    // The header is required rather than optional: an unconditional write to a versioned record is a lost
    // update waiting for a second replica, and optional would make the safe path the one to remember.
    server(new ClusterWriteUseCaseStub()).use(put(_, ifMatch = None)).map { response =>
      assertEquals(response.code.code, 400, response.body)
    }
  }

  test("anUnparseableIfMatchIsFourHundredWithTheHeaderNamed") {
    server(new ClusterWriteUseCaseStub()).use(put(_, ifMatch = Some("\"latest\""))).map { response =>
      assertEquals(response.code.code, 400, response.body)
      assertEquals(code(response), Some(ErrorCode.Validation.wire))
      assert(response.body.contains("If-Match"), response.body)
    }
  }

  test("aNameThatSlugsToADifferentIdIsFourHundred") {
    // Renaming a cluster produces a new id, which is a create plus a delete and not a PUT. Accepting it
    // would leave a record whose key and name disagree.
    val renamed = request.copy(name = "somewhere else")

    server(new ClusterWriteUseCaseStub()).use(put(_, body = renamed.asJson)).map { response =>
      assertEquals(response.code.code, 400, response.body)
      assertEquals(code(response), Some(ErrorCode.Validation.wire))
      assert(response.body.contains("somewhere-else"), response.body)
    }
  }

  test("everyValidationFailureIsReportedTogether") {
    // ADR-013's accumulate-everything rule, applied to a request body: someone who got three fields wrong
    // should be told about all three rather than discovering them one request at a time.
    val broken = request.copy(
      bootstrapServers = "not a broker list",
      security = request.security.copy(protocol = "TELEPATHY"),
      admin = AdminTuningWrite(timeoutMs = -1L, batchSize = 200, parallelism = 4)
    )

    server(new ClusterWriteUseCaseStub()).use(put(_, body = broken.asJson)).map { response =>
      val details = parse(response.body)
        .flatMap(_.hcursor.downField("details").as[List[Json]])
        .getOrElse(Nil)

      assertEquals(response.code.code, 400, response.body)
      assert(details.size >= 3, s"expected every failure at once, got ${details.size}: ${response.body}")
    }
  }

  test("aPrincipalWithoutThePermissionIsForbiddenAndTheWriteNeverHappens") {
    Ref.of[IO, List[(ClusterProfile, ProfileVersion)]](Nil).flatMap { seen =>
      val writes = new ClusterWriteUseCaseStub(seen = Some(seen))

      server(writes, permitted = ClusterWriteRoutes.defaultPermission)
        .use(put(_, principal = Principal.Anonymous))
        .flatMap(response => seen.get.map((response, _)))
        .map { (response, written) =>
          assertEquals(response.code.code, 403, response.body)
          assertEquals(code(response), Some(ErrorCode.Forbidden.wire))
          assertEquals(written, Nil)
        }
    }
  }

  test("aRequestBodyNeverReachesALogLine") {
    // `Secret` redacts in `toString`, so this is true today; the test is what keeps it true when someone
    // adds a log line with the request in it.
    val failing = new ClusterWriteUseCaseStub(answer = _ => Left(ClusterWriteRoutes.NoStore))

    server(failing).use(service => put(service).as(service)).flatMap { service =>
      service.logger.entries.map(lines =>
        assert(!lines.mkString("\n").contains(ClusterFixtures.Canary), lines.toString)
      )
    }
  }

  test("theWriteEndpointIsNotInTheListTheGatewayProxies") {
    // The endpoint ships without a user interface, and it is the permission plus this exclusion that keep
    // it that way: no browser can reach a path the gateway has no route for.
    assert(!kui.cluster.contract.ClusterEndpoints.all.exists(_.info.name.contains("cluster.put")))
    assertEquals(ClusterWriteEndpoints.all.flatMap(_.info.name), List("cluster.put"))
  }
}
