package kui.gateway.api.client

import scala.concurrent.duration.DurationInt

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.decode
import munit.CatsEffectSuite
import org.typelevel.otel4s.oteljava.testkit.trace.TracesTestkit

import kui.cluster.contract.ClusterEndpoints
import kui.gateway.api.client.ServiceClientFixture as Fixture
import kui.kernel.error.{ApplicationError, ErrorCode, InfrastructureError}
import kui.kernel.ClusterId
import kui.observability.{Correlation, Telemetry}
import kui.security.{PrincipalClaims, PrincipalCodec, RequestDigests}

/** That a call the gateway makes on another service's behalf carries exactly what ADR-020 and
  * `ARCHITECTURE.md` §5 say it carries, and that every failure comes back as a value.
  *
  * The suite is written against the *real* published endpoint of the cluster service rather than a
  * locally invented one. That is the point of GW-002: a route the gateway calls is a value the owning
  * team wrote, so a test that used its own endpoint definition would be testing a copy and would pass on
  * the day the two drifted apart.
  */
final class SttpServiceClientSuite extends CatsEffectSuite {

  private val ping = ClusterEndpoints.ping

  private val pingBody: Json = Json.obj(
    "message" -> Json.fromString("hello"),
    "at" -> Json.fromString("2026-09-03T10:11:12.000Z"),
    "service" -> Json.fromString("cluster")
  )

  private def envelope(code: ErrorCode, message: String): Json = Json.obj(
    "code" -> Json.fromString(code.wire),
    "message" -> Json.fromString(message),
    "details" -> Json.arr(),
    "correlationId" -> Json.fromString("upstreamcorrelid"),
    "timestamp" -> Json.fromString("2026-09-03T10:11:12.000Z"),
    "retryable" -> Json.fromBoolean(code.retryable)
  )

  /** Reads the claims back out of the in-process token, which renders them as plain JSON. */
  private def claimsOf(token: String): PrincipalClaims =
    decode[PrincipalClaims](token).fold(failure => fail(s"the token is not claims JSON: $failure"), identity)

  test("sendsTheFourStandardHeaders") {
    // A recording tracer, because `traceparent` is only propagated when there is a real span to
    // propagate; with the no-op tracer the header would be legitimately absent and the assertion would
    // be testing nothing.
    TracesTestkit.inMemory[IO]().use { traces =>
      for {
        stub <- Fixture.stub(ServiceBehaviour.Ok(pingBody))
        tracer <- traces.tracerProvider.get("kui.test")
        telemetry = Telemetry.fromProviders[IO](traces.tracerProvider, org.typelevel.otel4s.metrics.MeterProvider.noop[IO])
        _ <- kui.testkit.fakes.FakeStructuredLogger[IO].flatMap { logger =>
          SttpServiceClient
            .resource[IO](
              Fixture.Cluster,
              Fixture.config(),
              PrincipalCodec.inProcess[IO],
              telemetry,
              logger,
              stub.backend
            )
            .use(client =>
              tracer.span("inbound").surround(
                client.call(ping, "hello")(Fixture.context(Some(ClusterId.unsafe("local"))))
              )
            )
        }
        sent <- stub.sent.map(_.head)
      } yield {
        assert(sent.header("X-Kui-Principal").isDefined, "the signed principal is missing")
        assertEquals(sent.header(Correlation.HeaderName), Some("0123456789abcdef"))
        assertEquals(sent.header(SttpServiceClient.ClusterHeader), Some("local"))
        assert(
          sent.header("traceparent").exists(_.startsWith("00-")),
          s"traceparent is missing or malformed: ${sent.header("traceparent")}"
        )
      }
    }
  }

  test("omitsTheClusterHeaderWhenTheCallIsNotAboutOneCluster") {
    for {
      stub <- Fixture.stub(ServiceBehaviour.Ok(pingBody))
      _ <- Fixture.client(Fixture.Cluster, stub).use(_.call(ping, "hello")(Fixture.context()))
      sent <- stub.sent.map(_.head)
    } yield assertEquals(sent.header(SttpServiceClient.ClusterHeader), None)
  }

  test("signsWithTheTargetServiceAsAudience") {
    for {
      stub <- Fixture.stub(ServiceBehaviour.Ok(pingBody))
      _ <- Fixture
        .client(Fixture.Topic, stub, Fixture.config("http://topic:8082"))
        .use(_.call(ping, "hello")(Fixture.context()))
      sent <- stub.sent.map(_.head)
      claims = claimsOf(sent.header("X-Kui-Principal").getOrElse(fail("no principal header")))
    } yield {
      assertEquals(claims.audience.value, "topic")
      assertEquals(claims.subject.value, "ada")
    }
  }

  test("requestDigestCoversMethodPathAndBody") {
    for {
      stub <- Fixture.stub(ServiceBehaviour.Ok(pingBody))
      _ <- Fixture.client(Fixture.Cluster, stub).use(_.call(ping, "hello")(Fixture.context()))
      sent <- stub.sent.map(_.head)
      claims = claimsOf(sent.header("X-Kui-Principal").getOrElse(fail("no principal header")))
    } yield {
      // The three fields KERN-006's `RequestDigest` is made of, taken from the request that actually
      // went out rather than from anything the gateway happened to have lying around. The receiving
      // service recomputes them from the request line it read, so a token minted for `GET
      // /internal/v1/ping` cannot be replayed against `DELETE /internal/v1/topics/orders`.
      assertEquals(claims.requestDigest.method, "GET")
      assertEquals(claims.requestDigest.path, "/internal/v1/ping")
      assertEquals(claims.requestDigest.bodySha256, RequestDigests.sha256Hex(Array.emptyByteArray))
      // A different body is a different digest — asserted on the digest function itself, because no
      // endpoint the cluster service publishes in M0 has a request body to vary.
      assertNotEquals(
        RequestDigests.of("POST", "/internal/v1/topics", "a".getBytes("UTF-8")),
        RequestDigests.of("POST", "/internal/v1/topics", "b".getBytes("UTF-8"))
      )
    }
  }

  test("theQueryStringIsDeliberatelyOutsideTheDigest") {
    // Recording a real constraint rather than a preference. `RequestDigest` (KERN-006, ADR-020) is
    // method, path and body — the service on the other side recomputes exactly those three — so two
    // calls that differ only in the query string share a digest. What stops a token from being reused
    // across them is its audience and its 30-second lifetime, not the digest. Widening the digest to
    // cover the query would have to be a change on both sides at once, which is why it is written down
    // here rather than fixed locally.
    for {
      stub <- Fixture.stub(ServiceBehaviour.Ok(pingBody))
      _ <- Fixture.client(Fixture.Cluster, stub).use { client =>
        client.call(ping, "hello")(Fixture.context()) >>
          client.call(ping, "goodbye")(Fixture.context())
      }
      sent <- stub.sent
      first = claimsOf(sent.head.header("X-Kui-Principal").getOrElse(fail("no principal header")))
      second = claimsOf(sent(1).header("X-Kui-Principal").getOrElse(fail("no principal header")))
    } yield {
      assertEquals(first.requestDigest, second.requestDigest)
      assert(sent.head.uri.contains("message=hello"))
      assert(sent(1).uri.contains("message=goodbye"))
    }
  }

  test("mapsAnErrorEnvelopeResponseBackToTheOriginalKuiError") {
    for {
      stub <- Fixture.stub(
        ServiceBehaviour.Failure(404, envelope(ErrorCode.TopicNotFound, "topic 'orders' does not exist"))
      )
      result <- Fixture.client(Fixture.Cluster, stub).use(_.call(ping, "hello")(Fixture.context()))
    } yield result match {
      case Left(error: ApplicationError.Remote) =>
        assertEquals(error.code, ErrorCode.TopicNotFound)
        // The message the *service* wrote, not one the gateway re-derived: the user needs to read
        // which topic was missing.
        assertEquals(error.message, "topic 'orders' does not exist")
      case other => fail(s"expected an application error carrying the upstream code, got $other")
    }
  }

  test("doesNotDimACapabilityForABusinessError") {
    // The classification half of ADR-039 §6, asserted at the point where it is decided.
    for {
      stub <- Fixture.stub(
        ServiceBehaviour.Failure(403, envelope(ErrorCode.Forbidden, "not your cluster"))
      )
      result <- Fixture.client(Fixture.Cluster, stub).use(_.call(ping, "hello")(Fixture.context()))
    } yield assert(
      result.left.exists(_.isInstanceOf[ApplicationError]),
      s"a 403 must stay an application error, got $result"
    )
  }

  test("mapsATransportFailureToInfrastructureError") {
    val cases = List(
      "connection refused" -> ServiceBehaviour.Refused,
      "a 500 with no envelope" -> ServiceBehaviour.Failure(500, Json.obj("oops" -> Json.True)),
      "a timeout" -> ServiceBehaviour.Slow(30.seconds, ServiceBehaviour.Ok(pingBody))
    )

    cases.traverse_ { (name, behaviour) =>
      for {
        stub <- Fixture.stub(behaviour)
        result <- Fixture
          .client(Fixture.Cluster, stub, Fixture.config(timeout = 200.millis))
          .use(_.call(ping, "hello")(Fixture.context()))
      } yield assert(
        result.left.exists(_.isInstanceOf[InfrastructureError]),
        s"$name should be an infrastructure error, got $result"
      )
    }
  }

  test("doesNotForwardTheBrowsersHeaders") {
    // Nothing of the inbound request is available to the client by construction — it is handed an
    // endpoint value and a `CallContext`, and neither can carry a cookie — so this asserts the
    // construction held: no `Cookie`, no `Authorization`, and no second principal header.
    for {
      stub <- Fixture.stub(ServiceBehaviour.Ok(pingBody))
      _ <- Fixture.client(Fixture.Cluster, stub).use(_.call(ping, "hello")(Fixture.context()))
      sent <- stub.sent.map(_.head)
    } yield {
      assertEquals(sent.header("Cookie"), None)
      assertEquals(sent.header("Authorization"), None)
      assertEquals(sent.headers.count(_.is("X-Kui-Principal")), 1)
    }
  }

  test("oneSlowServiceDoesNotDelayAnother") {
    // Independent bulkheads, per PLAN §16.4: the topic service saturating its own concurrency limit
    // must not queue a call to the cluster service behind it.
    for {
      slow <- Fixture.stub(ServiceBehaviour.Slow(2.seconds, ServiceBehaviour.Ok(pingBody)))
      quick <- Fixture.stub(ServiceBehaviour.Ok(pingBody))
      outcome <- (
        Fixture.client(Fixture.Topic, slow, Fixture.config("http://topic:8082", maxConcurrent = 1)),
        Fixture.client(Fixture.Cluster, quick)
      ).tupled.use { (slowClient, quickClient) =>
        for {
          blocked <- slowClient.call(ping, "hello")(Fixture.context()).start
          fast <- quickClient.call(ping, "hello")(Fixture.context()).timeout(500.millis)
          _ <- blocked.cancel
        } yield fast
      }
    } yield assert(outcome.isRight, s"the fast call should have succeeded, got $outcome")
  }

  test("returnsTheDecodedOutputOnSuccess") {
    for {
      stub <- Fixture.stub(ServiceBehaviour.Ok(pingBody))
      result <- Fixture.client(Fixture.Cluster, stub).use(_.call(ping, "hello")(Fixture.context()))
    } yield assertEquals(result.map(_.message), Right("hello"))
  }
}
