package kui.http.health

import scala.concurrent.duration.DurationInt

import cats.effect.IO
import cats.effect.testkit.TestControl
import io.circe.parser.decode
import munit.CatsEffectSuite
import sttp.tapir.server.ServerEndpoint

import kui.contracts.capability.{ClusterCapability, ServiceCapabilities}
import kui.contracts.health.{CheckResult, LivenessReport, ReadinessReport}
import kui.http.TestServer
import kui.kernel.{ClusterId, ServiceId}
import kui.testkit.fakes.FakeCapabilities

/** That the three endpoints answer the questions they are for, and that a slow or hanging check
  * cannot turn a degraded service into an unreachable one.
  */
final class HealthEndpointsSuite extends CatsEffectSuite {

  private val service = ServiceId.unsafe("schema")
  private val cluster = ClusterId.unsafe("prod-eu")

  private def endpointsFor(
      checks: List[ReadinessCheck[IO]],
      capabilities: IO[ServiceCapabilities]
  ): List[ServerEndpoint[sttp.capabilities.fs2.Fs2Streams[IO], IO]] =
    HealthEndpoints.make[IO](checks, capabilities).map(widen)

  /** An endpoint that needs no streaming still has to be typed as though it could be served
    * alongside one, because that is what the server takes.
    */
  private def widen(
      endpoint: ServerEndpoint[Any, IO]
  ): ServerEndpoint[sttp.capabilities.fs2.Fs2Streams[IO], IO] = endpoint

  private val healthyCapabilities: IO[ServiceCapabilities] =
    IO.pure(
      ServiceCapabilities(
        service,
        Map(cluster -> ClusterCapability(configured = true, List("SCHEMA_REGISTRY"), "available"))
      )
    )

  private val failing = ReadinessCheck[IO](
    "schema-registry",
    IO.pure(CheckResult.failed("schema-registry", "connection refused"))
  )

  private val passing = ReadinessCheck.always[IO]("config")

  // ---------------------------------------------------------------------------------------------
  // Liveness and readiness answer different questions
  // ---------------------------------------------------------------------------------------------

  test("liveIsTwoHundredEvenWhenEveryReadinessCheckFails") {
    // The distinction that keeps a degraded service from being restart-looped. A service whose
    // registry is down is not broken; restarting it will not fix the registry, and restarting it
    // in a loop turns one outage into two.
    TestServer.resource(endpointsFor(List(failing), healthyCapabilities)).use { server =>
      for {
        live <- server.get("/health/live")
        ready <- server.get("/health/ready")
      } yield {
        assertEquals(live.code.code, 200)
        assertEquals(decode[LivenessReport](live.body).map(_.alive), Right(true))
        assertEquals(ready.code.code, 503)
      }
    }
  }

  test("readyIsFiveOhThreeWhenAnyCheckFails, and the body lists exactly the failing checks") {
    TestServer.resource(endpointsFor(List(passing, failing), healthyCapabilities)).use { server =>
      server.get("/health/ready").map { response =>
        assertEquals(response.code.code, 503)

        val report = decode[ReadinessReport](response.body).fold(f => fail(f.toString), identity)
        assertEquals(report.ready, false)
        assertEquals(report.checks.filterNot(_.healthy).map(_.name), List("schema-registry"))
        // Every check is listed, not only the failures: what was tried matters as much as what
        // failed when an operator is deciding whether this is one broken upstream or all of them.
        assertEquals(report.checks.map(_.name).sorted, List("config", "schema-registry"))
      }
    }
  }

  test("with no checks at all, a service is ready") {
    TestServer.resource(endpointsFor(Nil, healthyCapabilities)).use { server =>
      server.get("/health/ready").map { response =>
        assertEquals(response.code.code, 200)
        assertEquals(decode[ReadinessReport](response.body).map(_.ready), Right(true))
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Timing, without waiting for it
  // ---------------------------------------------------------------------------------------------

  test("checksRunInParallelAndRespectTheTotalBudget") {
    // Three checks of a second each finish in about a second, not three. Sequentially they would
    // exceed a two-second probe deadline and the orchestrator would call the service dead.
    val slow = (1 to 3).toList.map { n =>
      ReadinessCheck[IO](s"check-$n", IO.sleep(1.second).as(CheckResult.healthy(s"check-$n")))
    }

    TestControl.executeEmbed(HealthEndpoints.report[IO](slow).timed).map { (elapsed, report) =>
      assertEquals(report.ready, true)
      assertEquals(elapsed, 1.second)
    }
  }

  test("aHangingCheckIsReportedAsTimeoutNotAsAFailedRequest") {
    val hanging = ReadinessCheck[IO]("wedged", IO.never).withTimeout(2.seconds)

    TestControl.executeEmbed(HealthEndpoints.report[IO](List(passing, hanging))).map { report =>
      assertEquals(report.ready, false)
      assertEquals(report.checks.find(_.name == "wedged").flatMap(_.detail), Some("timeout"))
      // The other check still answered. A hanging dependency must not erase what is known.
      assertEquals(report.checks.find(_.name == "config").map(_.healthy), Some(true))
    }
  }

  test("a check that raises is reported as failed, not propagated") {
    val exploding = ReadinessCheck[IO]("angry", IO.raiseError(new RuntimeException("no")))

    HealthEndpoints.report[IO](List(exploding)).map { report =>
      assertEquals(report.ready, false)
      assertEquals(report.checks.map(_.detail), List(Some("no")))
    }
  }

  test("the total budget is a backstop over the per-check timeouts") {
    // A check configured with an absurd timeout still cannot hold the endpoint open: readiness
    // answers within the total budget or reports every check as timed out.
    val absurd = ReadinessCheck[IO]("absurd", IO.never).withTimeout(1.hour)

    TestControl.executeEmbed(HealthEndpoints.report[IO](List(absurd)).timed).map { (elapsed, report) =>
      assertEquals(elapsed, ReadinessCheck.TotalBudget)
      assertEquals(report.checks.map(_.detail), List(Some("timeout")))
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Capabilities
  // ---------------------------------------------------------------------------------------------

  test("capabilitiesMatchesTheGoldenDocument") {
    // The bytes of `libs/contracts-core/test/resources/golden/service-capabilities.json`, which
    // `CapabilityDtosSuite` asserts the encoder against on both platforms. Repeated here rather
    // than shared because that constant lives in another module's test sources; what this test
    // adds is that a real endpoint serves exactly it.
    val goldenText =
      """{"service":"schema","clusters":{
        |"prod-eu":{"configured":true,"features":["SCHEMA_REGISTRY"],"status":"available"},
        |"staging":{"configured":false,"features":[],"status":"not_configured"}}}""".stripMargin

    val golden = decode[ServiceCapabilities](goldenText).fold(f => fail(f.toString), identity)

    TestServer.resource(endpointsFor(Nil, IO.pure(golden))).use { server =>
      server.get("/capabilities").map { response =>
        assertEquals(response.code.code, 200)
        assertEquals(decode[ServiceCapabilities](response.body), Right(golden))
      }
    }
  }

  test("the answer is recomputed per request, because the gateway polls it to learn about changes") {
    val program = for {
      fake <- FakeCapabilities.available[IO](service, List(cluster), List("SCHEMA_REGISTRY"))
      result <- TestServer.resource(endpointsFor(Nil, fake.current)).use { server =>
        for {
          first <- server.get("/capabilities")
          _ <- fake.setCluster(cluster, ClusterCapability(configured = false, Nil, "not_configured"))
          second <- server.get("/capabilities")
          calls <- fake.calls
        } yield (first.body, second.body, calls)
      }
    } yield result

    program.map { (first, second, calls) =>
      assert(first.contains("available"), first)
      assert(second.contains("not_configured"), second)
      assertEquals(calls, 2, "the endpoint cached the answer instead of asking again")
    }
  }

  // ---------------------------------------------------------------------------------------------
  // No principal required
  // ---------------------------------------------------------------------------------------------

  test("healthEndpointsRequireNoPrincipalHeader") {
    // A probe has no credentials and cannot be given any. None of the three declares a security
    // input, so no amount of later authentication wiring can start demanding one.
    val endpoints = HealthEndpoints.make[IO](List(passing), healthyCapabilities)

    endpoints.foreach { endpoint =>
      assertEquals(
        endpoint.endpoint.securityInput.show,
        "-",
        clue = s"${endpoint.showShort} declares a security input"
      )
    }

    TestServer.resource(endpointsFor(List(passing), healthyCapabilities)).use { server =>
      for {
        live <- server.get("/health/live")
        ready <- server.get("/health/ready")
        capabilities <- server.get("/capabilities")
      } yield assertEquals(
        List(live.code.code, ready.code.code, capabilities.code.code),
        List(200, 200, 200)
      )
    }
  }

  test("every health endpoint declares an operation id, so its span is named") {
    val missing = kui.observability.KuiInterceptors.missingOperationIds(
      HealthEndpoints.make[IO](Nil, healthyCapabilities).map(_.endpoint)
    )
    assertEquals(missing, Nil)
  }

  test("the paths a server excludes from metrics and logging are the three of them") {
    assertEquals(
      HealthEndpoints.paths,
      Set(HealthEndpoints.LivePath, HealthEndpoints.ReadyPath, HealthEndpoints.CapabilitiesPath)
    )
  }
}
