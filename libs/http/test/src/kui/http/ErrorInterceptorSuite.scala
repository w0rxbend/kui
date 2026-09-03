package kui.http

import java.time.Instant

import cats.effect.IO
import io.circe.parser.decode
import munit.CatsEffectSuite
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.ServerEndpoint

import kui.contracts.ErrorEnvelope
import kui.contracts.ErrorEnvelope.given
import kui.contracts.KernelSchemas.given
import kui.kernel.error.{ApplicationError, DomainError, ErrorCode, FieldError, InfrastructureError, KuiError}
import kui.kernel.{ClusterId, CorrelationId}
import kui.observability.Correlation

/** That every way a request can fail produces the same body, with the right code, and that nothing
  * escapes in it that should not.
  */
final class ErrorInterceptorSuite extends CatsEffectSuite {

  private val at = Instant.parse("2026-09-03T10:11:12Z")
  private val correlationId = CorrelationId.unsafe("abc123")

  // ---------------------------------------------------------------------------------------------
  // The pure mapping
  // ---------------------------------------------------------------------------------------------

  private val everyErrorCase: List[KuiError] = List(
    DomainError.InvariantViolation("a topic must have at least one partition"),
    ApplicationError.NotFound("cluster", "prod-eu", ErrorCode.ClusterNotFound),
    ApplicationError.NotFound("topic", "orders", ErrorCode.TopicNotFound),
    ApplicationError.Conflict("the group is rebalancing"),
    ApplicationError.Forbidden("you may not delete topics on this cluster"),
    ApplicationError.Unauthenticated("no principal"),
    ApplicationError.Unsupported("schema registry"),
    ApplicationError.InvalidState("the connector is paused"),
    ApplicationError.Invalid("bad request", List(FieldError.of("pageSize", "must be at most 500"))),
    InfrastructureError.Unreachable("schema-registry", "connection refused"),
    InfrastructureError.Timeout("listTopics", 10000),
    InfrastructureError.AuthFailed("connect"),
    InfrastructureError.Upstream("ksql", 502),
    InfrastructureError.CircuitOpen("connect", at)
  )

  everyErrorCase.foreach { error =>
    test(s"mapsEveryKuiErrorCaseToItsStatusAndCode: ${error.getClass.getSimpleName} ${error.code.wire}") {
      val (status, envelope) = ErrorInterceptor.render(error, correlationId, at)

      assertEquals(status, error.code.httpStatus)
      assertEquals(envelope.code, error.code.wire)
      assertEquals(envelope.message, error.message)
      assertEquals(envelope.correlationId, correlationId.value)
      assertEquals(envelope.retryable, error.code.retryable)
      assertEquals(envelope.timestamp, at)
    }
  }

  test("the status comes from ErrorEnvelope.statusOf, so there is only one code-to-status table") {
    everyErrorCase.foreach { error =>
      assertEquals(ErrorInterceptor.render(error, correlationId, at)._1, ErrorEnvelope.statusOf(error))
    }
  }

  test("upstreamBodyIsNeverEchoed") {
    // `InfrastructureError.Upstream` carries a status and deliberately not a body: an upstream's
    // response can contain its own internal detail, or its credentials (ADR-034). The type is what
    // makes the rule impossible to break, and this asserts the type has not grown a body field.
    val (_, envelope) = ErrorInterceptor.render(InfrastructureError.Upstream("ksql", 502), correlationId, at)

    assertEquals(envelope.message, "ksql answered with status 502")
    assertEquals(envelope.details, Nil)
  }

  // ---------------------------------------------------------------------------------------------
  // Against a bound port
  // ---------------------------------------------------------------------------------------------

  private val echo: ServerEndpoint[Fs2Streams[IO], IO] =
    endpoint.get
      .in("echo" / path[ClusterId]("clusterId"))
      .in(query[Int]("limit"))
      .out(stringBody)
      .errorOut(jsonBody[ErrorEnvelope])
      .serverLogicSuccess[IO]((cluster, limit) => IO.pure(s"${cluster.value}:$limit"))

  private val boom: ServerEndpoint[Fs2Streams[IO], IO] =
    endpoint.get
      .in("boom")
      .out(stringBody)
      .errorOut(jsonBody[ErrorEnvelope])
      .serverLogicSuccess[IO](_ => IO.raiseError(new RuntimeException("a very secret stack trace")))

  /** The shape a service's `api` layer uses: the status travels with the envelope, so the code and
    * the status can never disagree.
    */
  private val notFound: ServerEndpoint[Fs2Streams[IO], IO] =
    endpoint.get
      .in("missing")
      .out(stringBody)
      .errorOut(statusCode.and(jsonBody[ErrorEnvelope]))
      .serverLogic[IO] { _ =>
        val error = ApplicationError.NotFound("cluster", "nope", ErrorCode.ClusterNotFound)
        val (status, envelope) = ErrorInterceptor.render(error, correlationId, at)
        IO.pure(Left((sttp.model.StatusCode(status), envelope)))
      }

  private val endpoints = List(echo, boom, notFound)

  private def envelopeOf(body: String): ErrorEnvelope =
    decode[ErrorEnvelope](body).fold(failure => fail(s"not an error envelope: $body ($failure)"), identity)

  test("an unknown route is KUI-ROUTE-NOT-FOUND, not KUI-INTERNAL and not KUI-VALIDATION") {
    TestServer.resource(endpoints).use { server =>
      server.get("/does-not-exist").map { response =>
        assertEquals(response.code.code, 404)
        val envelope = envelopeOf(response.body)
        assertEquals(envelope.code, "KUI-ROUTE-NOT-FOUND")
        assertEquals(envelope.message, "No route for GET /does-not-exist")
      }
    }
  }

  test("decodeFailureBecomesValidationWithFieldDetails: a path parameter that fails its kernel type") {
    TestServer.resource(endpoints).use { server =>
      // `NOT A CLUSTER` is not a lowercase slug, so `ClusterId.from` rejects it.
      server.get("/echo/NOT_A_CLUSTER?limit=5").map { response =>
        assertEquals(response.code.code, 400)
        val envelope = envelopeOf(response.body)
        assertEquals(envelope.code, "KUI-VALIDATION")
        assertEquals(envelope.details.flatMap(_.field), List("clusterId"))
        assert(envelope.details.head.restrictions.nonEmpty, envelope.details.toString)
      }
    }
  }

  test("a query parameter that will not decode names the query parameter") {
    TestServer.resource(endpoints).use { server =>
      server.get("/echo/prod-eu?limit=lots").map { response =>
        assertEquals(response.code.code, 400)
        assertEquals(envelopeOf(response.body).details.flatMap(_.field), List("limit"))
      }
    }
  }

  test("a missing required query parameter names it too") {
    TestServer.resource(endpoints).use { server =>
      server.get("/echo/prod-eu").map { response =>
        assertEquals(response.code.code, 400)
        val envelope = envelopeOf(response.body)
        assertEquals(envelope.code, "KUI-VALIDATION")
        assertEquals(envelope.details.flatMap(_.field), List("limit"))
      }
    }
  }

  test("uncaughtExceptionBecomesInternalAndTheStackTraceIsOnlyLogged") {
    TestServer.resource(endpoints).use { server =>
      for {
        response <- server.get("/boom")
        entries <- server.logger.entries
      } yield {
        assertEquals(response.code.code, 500)

        val envelope = envelopeOf(response.body)
        assertEquals(envelope.code, "KUI-INTERNAL")
        assertEquals(envelope.message, "Internal error")
        assert(!response.body.contains("a very secret stack trace"), response.body)
        assert(!response.body.contains("RuntimeException"), response.body)

        val logged = entries.filter(_.throwable.isDefined)
        assertEquals(logged.map(_.level), List("error"))
        assertEquals(logged.head.throwable.map(_.getMessage), Some("a very secret stack trace"))
        assertEquals(logged.head.context.get("error.code"), Some("KUI-INTERNAL"))
      }
    }
  }

  test("everyErrorResponseCarriesTheCorrelationIdHeaderAndBodyField, and they match") {
    TestServer.resource(endpoints).use { server =>
      server.get("/does-not-exist").map { response =>
        val header = response.header(Correlation.HeaderName)
        val envelope = envelopeOf(response.body)

        assertEquals(header, Some(envelope.correlationId))
        assert(envelope.correlationId.nonEmpty)
      }
    }
  }

  test("a correlation id supplied by the caller is echoed rather than replaced") {
    TestServer.resource(endpoints).use { server =>
      server.get("/does-not-exist", Map(Correlation.HeaderName -> "caller-supplied-1")).map { response =>
        assertEquals(response.header(Correlation.HeaderName), Some("caller-supplied-1"))
        assertEquals(envelopeOf(response.body).correlationId, "caller-supplied-1")
      }
    }
  }

  test("a correlation id that is not safe to echo is replaced with a fresh one") {
    TestServer.resource(endpoints).use { server =>
      server.get("/does-not-exist", Map(Correlation.HeaderName -> "not a valid id")).map { response =>
        assertNotEquals(response.header(Correlation.HeaderName), Some("not a valid id"))
        assertEquals(response.header(Correlation.HeaderName).map(_.length), Some(16))
      }
    }
  }

  test("an error the endpoint's own logic returned keeps its own code and status") {
    TestServer.resource(endpoints).use { server =>
      server.get("/missing").map { response =>
        assertEquals(response.code.code, 404)
        assertEquals(envelopeOf(response.body).code, "KUI-CLUSTER-NOT-FOUND")
      }
    }
  }

  test("a successful request is not logged, because the metrics already count it") {
    TestServer.resource(endpoints).use { server =>
      for {
        response <- server.get("/echo/prod-eu?limit=5")
        entries <- server.logger.entries
      } yield {
        assertEquals(response.body, "prod-eu:5")
        assertEquals(entries.count(_.context.contains("error.code")), 0)
      }
    }
  }

  test("a 4xx is logged at warn and a 5xx at error") {
    TestServer.resource(endpoints).use { server =>
      for {
        _ <- server.get("/does-not-exist")
        _ <- server.get("/boom")
        entries <- server.logger.entries
      } yield {
        val byCode = entries.filter(_.context.contains("error.code")).map { entry =>
          entry.context("error.code") -> entry.level
        }
        assertEquals(byCode.toSet, Set("KUI-ROUTE-NOT-FOUND" -> "warn", "KUI-INTERNAL" -> "error"))
      }
    }
  }
}
