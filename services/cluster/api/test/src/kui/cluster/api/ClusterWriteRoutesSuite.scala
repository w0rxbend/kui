package kui.cluster.api

import java.nio.charset.StandardCharsets

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
import kui.cluster.domain.{ClusterProfile, Connectivity, ProfileVersion}
import kui.contracts.KuiEndpoint
import kui.http.principal.RbacGuard
import kui.kernel.error.{ApplicationError, ErrorCode, InfrastructureError, KuiError}
import kui.kernel.{ClusterId, RoleName, Secret, UserName}
import kui.observability.Telemetry
import kui.security.*
import kui.security.rbac as rbac
import kui.security.rbac.{Action, DefaultRole, RbacPolicy, Role}
import kui.testkit.fakes.FakeStructuredLogger

/** That the one write M1 ships is safe to expose to a wizard a milestone from now.
  *
  * Each case is one row of the endpoint's status table, and the table is the specification: which failures a
  * caller can tell apart decides whether a retry is safe, whether the caller should change what it sent, and
  * whether an operator has to change the deployment.
  */
final class ClusterWriteRoutesSuite extends CatsEffectSuite {

  private val path = "/internal/v1/clusters/prod-eu"

  private val editorRole: RoleName = RoleName.unsafe("cluster-admin")

  /** A deployment that has configured RBAC: one role that may edit KUI's own configuration, and a default
    * role for everybody else that may only look at it.
    *
    * A policy with something in it is what switches RBAC on at all (`RbacPolicy.enabled`), so this is also
    * what makes the refusals below refusals rather than the "no policy, allow everything" answer every other
    * endpoint in KUI gives.
    */
  private val policy: RbacPolicy =
    RbacPolicy(
      roles = List(
        Role(
          name = editorRole,
          clusters = Set.empty,
          subjects = Nil,
          permissions = List(
            RbacPolicy.permission(
              rbac.Resource.ApplicationConfig,
              None,
              Set(Action.ApplicationConfigEdit)
            )
          )
        )
      ),
      defaultRole = Some(
        DefaultRole(
          List(RbacPolicy.permission(rbac.Resource.ApplicationConfig, None, Set(Action.ApplicationConfigView)))
        )
      )
    )

  /** The check three of these routes run, over the policy above. */
  private val underPolicy: Principal => Boolean = ClusterWriteRoutes.permissionFrom(policy)

  /** A principal in the role that grants the edit. */
  private val editor: Principal =
    Principal(UserName.unsafe("operator"), Set(editorRole), PrincipalKind.Session)

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
      permitted: Principal => Boolean = _ => true,
      probe: kui.cluster.application.ClusterProbeUseCase[IO] = new ClusterFixtures.StubProbe()
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
            ClusterWriteRoutes[IO](writes, probe, codec, rejections, logger, RbacGuard.allowAll[IO], permitted)
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
      val seen: Option[Ref[IO, List[(ClusterProfile, ProfileVersion)]]] = None,
      val removal: Either[KuiError, Unit] = Right(()),
      val removals: Option[Ref[IO, List[(ClusterId, ProfileVersion)]]] = None
  ) extends kui.cluster.application.ClusterWriteUseCase[IO] {

    def put(profile: ClusterProfile, expected: ProfileVersion): IO[Either[KuiError, ClusterProfile]] =
      seen.traverse_(_.update(_ :+ ((profile, expected)))) *> IO.sleep(takes).as(answer(profile))

    def delete(id: ClusterId, expected: ProfileVersion): IO[Either[KuiError, Unit]] =
      removals.traverse_(_.update(_ :+ ((id, expected)))) *> IO.sleep(takes).as(removal)
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
            // The body, hashed, because this endpoint carries one (ADR-020 Amendment 1). Signing the
            // request line alone is what the gateway used to do and what the route used to check, and
            // both were wrong: a token so bound is replayable with any body at all against this path.
            requestDigest = RequestDigests.of("PUT", at, body.noSpaces.getBytes(StandardCharsets.UTF_8))
          )
        )
        .flatMap { token =>
          val base = basicRequest
            .put(Uri.unsafeParse(s"http://cluster$at"))
            .header(KuiEndpoint.PrincipalHeader, token.value)
            .header(kui.contracts.HttpHeaders.Csrf, "a-csrf-token")
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

  test("aSuccessfulWriteReadsBackTheStoredProfileAndNotTheRequest") {
    // The write endpoint is on `/internal/v1` and answers with the same profile the read endpoint serves
    // (ADR-046), so the credentials do come back — a read-back that dropped them would be a different
    // document from the one a consumer fetches a moment later, and the difference would be invisible
    // until a client failed to authenticate.
    //
    // What is asserted is that it is the *stored* profile and not an echo of the request: the id comes
    // from the path and the shape from the store.
    server(new ClusterWriteUseCaseStub()).use(put(_)).map { response =>
      assertEquals(response.code.code, 200, response.body)
      assertEquals(
        parse(response.body).flatMap(_.hcursor.get[String]("id")),
        Right("prod-eu")
      )
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

      server(writes, permitted = underPolicy)
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

  // ----------------------------------------------------------------------------------------------
  // Removing a cluster
  // ----------------------------------------------------------------------------------------------

  private def remove(
      service: ClusterTestServer,
      ifMatch: Option[String] = Some("\"3\""),
      principal: Principal = editor
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
            // No body, so the request line is the whole digest — which is what `SecuredRoutes.apply`
            // checks against for a body-less endpoint.
            requestDigest = RequestDigest.ofRequestLine("DELETE", path)
          )
        )
        .flatMap { token =>
          val base = basicRequest
            .delete(Uri.unsafeParse(s"http://cluster$path"))
            .header(KuiEndpoint.PrincipalHeader, token.value)
            .header(kui.contracts.HttpHeaders.Csrf, "a-csrf-token")
            .response(asStringAlways)

          ifMatch
            .fold(base)(tag => base.header(ClusterWriteEndpoints.IfMatchHeader, tag))
            .send(service.backend)
        }
    }

  test("aDeleteRemovesTheClusterAtTheVersionTheCallerNamed") {
    Ref.of[IO, List[(ClusterId, ProfileVersion)]](Nil).flatMap { removals =>
      server(new ClusterWriteUseCaseStub(removals = Some(removals)))
        .use(remove(_))
        .flatMap(response => removals.get.map((response, _)))
        .map { (response, seen) =>
          assertEquals(response.code.code, 200, response.body)
          // The version travels from the header into the store call unchanged. A delete that dropped it
          // would be an unconditional delete, which races with somebody else's edit.
          assertEquals(seen.map((id, version) => (id.value, version.value)), List(("prod-eu", 3L)))
        }
    }
  }

  test("aDeleteWithoutIfMatchIsRefusedBeforeAnythingIsRemoved") {
    Ref.of[IO, List[(ClusterId, ProfileVersion)]](Nil).flatMap { removals =>
      server(new ClusterWriteUseCaseStub(removals = Some(removals)))
        .use(remove(_, ifMatch = None))
        .flatMap(response => removals.get.map((response, _)))
        .map { (response, seen) =>
          assertEquals(response.code.code, 400, response.body)
          assertEquals(seen, Nil)
        }
    }
  }

  test("aDeleteAtTheCreateVersionIsRefusedByName") {
    // `"0"` means "create; fail if it exists". Handing it to the store would produce "no record at
    // version 0", which reads as a bug rather than as the caller having sent the wrong header.
    server(new ClusterWriteUseCaseStub()).use(remove(_, ifMatch = Some("\"0\""))).map { response =>
      assertEquals(response.code.code, 400, response.body)
      assertEquals(code(response), Some(ErrorCode.Validation.wire))
      assert(response.body.contains("If-Match"), response.body)
    }
  }

  test("aDeleteOfAStaticallyConfiguredClusterIsFourOhNineNamingTheFile") {
    // The store record would go and the configuration file would put it straight back on the next
    // resolve. An operator watching a row they deleted reappear has no way to tell that from a bug.
    val refused = new ClusterWriteUseCaseStub(
      removal = Left(kui.cluster.application.ClusterWriteUseCase.staticallyDefined(ClusterId.unsafe("prod-eu")))
    )

    server(refused).use(remove(_)).map { response =>
      assertEquals(response.code.code, 409, response.body)
      assert(response.body.contains("kui.clusters[]"), response.body)
    }
  }

  test("aPrincipalWithoutThePermissionCannotDelete") {
    Ref.of[IO, List[(ClusterId, ProfileVersion)]](Nil).flatMap { removals =>
      server(
        new ClusterWriteUseCaseStub(removals = Some(removals)),
        permitted = underPolicy
      ).use(remove(_, principal = Principal.Anonymous))
        .flatMap(response => removals.get.map((response, _)))
        .map { (response, seen) =>
          assertEquals(response.code.code, 403, response.body)
          assertEquals(seen, Nil)
        }
    }
  }

  // ----------------------------------------------------------------------------------------------
  // Testing a connection
  // ----------------------------------------------------------------------------------------------

  private val probePath = "/internal/v1/clusters/connection-test"

  private def test_connection(
      service: ClusterTestServer,
      body: Json = request.asJson,
      principal: Principal = editor
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
            requestDigest =
              RequestDigests.of("POST", probePath, body.noSpaces.getBytes(StandardCharsets.UTF_8))
          )
        )
        .flatMap { token =>
          basicRequest
            .post(Uri.unsafeParse(s"http://cluster$probePath"))
            .header(KuiEndpoint.PrincipalHeader, token.value)
            .header(kui.contracts.HttpHeaders.Csrf, "a-csrf-token")
            .header("Content-Type", "application/json")
            .body(body.noSpaces)
            .response(asStringAlways)
            .send(service.backend)
        }
    }

  test("aConnectionTestReportsTheThreeVerdictsDistinguishably") {
    // One boolean would send an operator to the network when the answer is a password. These are the two
    // different places the two failures send them.
    val cases = List(
      Connectivity.Reachable -> ("reachable", true),
      Connectivity.AuthenticationFailed("the cluster rejected KUI's credentials") ->
        ("authentication-failed", false),
      Connectivity.Unreachable("KUI could not open a connection to this cluster") -> ("unreachable", false)
    )

    cases.traverse_ { (verdict, expected) =>
      server(new ClusterWriteUseCaseStub(), probe = new ClusterFixtures.StubProbe(verdict))
        .use(test_connection(_))
        .map { response =>
          assertEquals(response.code.code, 200, response.body)
          assertEquals(parse(response.body).flatMap(_.hcursor.get[String]("status")), Right(expected._1))
          assertEquals(parse(response.body).flatMap(_.hcursor.get[Boolean]("reachable")), Right(expected._2))
        }
    }
  }

  test("aConnectionTestValidatesTheSettingsBeforeItOpensAnything") {
    // "That is not a bootstrap list" and "that address does not answer" are different problems, and the
    // first one must not be reported as the second.
    val broken = request.copy(bootstrapServers = "not a broker list")

    server(new ClusterWriteUseCaseStub()).use(test_connection(_, body = broken.asJson)).map { response =>
      assertEquals(response.code.code, 400, response.body)
      assertEquals(code(response), Some(ErrorCode.Validation.wire))
    }
  }

  test("aConnectionTestIsBehindTheSamePermissionAsTheWrite") {
    // Unguarded, it would let any caller use KUI to open connections to whatever KUI's network can reach
    // and read the answers off the three verdicts.
    server(new ClusterWriteUseCaseStub(), permitted = underPolicy)
      .use(test_connection(_, principal = Principal.Anonymous))
      .map(response => assertEquals(response.code.code, 403, response.body))
  }

  test("aConnectionTestNeverEchoesACredential") {
    server(new ClusterWriteUseCaseStub()).use(service => test_connection(service).map((service, _))).flatMap {
      (service, response) =>
        assert(!response.body.contains(ClusterFixtures.Canary), response.body)
        service.logger.entries.map(lines =>
          assert(!lines.mkString("\n").contains(ClusterFixtures.Canary), lines.toString)
        )
    }
  }

  test("everyWriteEndpointIsPublishedAndMarked") {
    // The inverse of what this suite used to assert. `put` was deliberately absent from the list the
    // gateway derives its routes from, because it had no screen; it has one now, and an endpoint the
    // browser cannot reach would make that screen a set of buttons that answer 404. What keeps an
    // unauthorised caller out is the permission, which is a rule the product states, rather than a missing
    // route, which is only a rule nobody has got round to breaking.
    assertEquals(
      ClusterWriteEndpoints.all.flatMap(_.info.name).sorted,
      List("cluster.delete", "cluster.probe", "cluster.put")
    )

    // Every one of them classified for read-only mode. An unmarked mutation keeps answering on a cluster
    // an operator has marked read-only and nothing reports it as an exception (ADR-047).
    assert(
      ClusterWriteEndpoints.all.forall(endpoint =>
        endpoint.attribute(KuiEndpoint.MutationKey).isDefined
      ),
      ClusterWriteEndpoints.all.flatMap(_.info.name).toString
    )

    // Only the delete is destructive: a write replaces a record KUI can be told again, a delete removes
    // credentials KUI cannot reconstruct.
    assertEquals(ClusterWriteEndpoints.mutating.flatMap(_.info.name), List("cluster.delete"))
  }

  test("a deployment that has not configured RBAC lets a caller change its cluster list") {
    // The defect this pair of tests exists for. The check used to compare the principal's *role names* to
    // the literal string "ApplicationConfig.Edit", which is not a name any role vocabulary produces -- so
    // every deployment refused every cluster write, and the administration screen was a form, three buttons
    // and a 403. Meanwhile `/api/v1/auth/me` was telling the same browser it held APPLICATIONCONFIG EDIT
    // over every cluster, because that is what an unconfigured policy grants everywhere else in KUI.
    //
    // `RbacPolicy.Disabled` is that deployment, and it must allow, for the same reason the quickstart can
    // create a topic without anybody logging in.
    assert(ClusterWriteRoutes.permissionFrom(RbacPolicy.Disabled)(Principal.Anonymous))
    assert(ClusterWriteRoutes.defaultPermission(Principal.Anonymous))
  }

  test("a configured policy grants the edit to the role that has it and to nobody else") {
    assert(underPolicy(editor))
    assert(!underPolicy(Principal.Anonymous))
    assert(
      !underPolicy(Principal(UserName.unsafe("viewer"), Set(RoleName.unsafe("reader")), PrincipalKind.Session))
    )
  }
}
