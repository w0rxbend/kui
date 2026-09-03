package kui.gateway.api

import cats.effect.IO
import io.circe.parser.decode
import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint

import kui.contracts.ErrorEnvelope
import kui.contracts.ErrorEnvelope.given
import kui.gateway.contract.GatewayEndpoints
import kui.kernel.error.ErrorCode
import kui.observability.Correlation
import kui.testkit.KuiIOSuite

/** That the gateway process answers, and answers the way every other KUI process does.
  *
  * Every assertion here goes through a real server on a real port. That is the point: what is being tested is
  * the assembly — the route list, the interceptor order, the header policy — and every one of those is a
  * property of how the pieces were put together rather than of any piece on its own.
  */
final class GatewayApiSuite extends KuiIOSuite {

  private val correlationHeader: String = Correlation.HeaderName

  /** The route a test uses to look at the request a handler actually received.
    *
    * Proving that a forged header was stripped needs somewhere that reports what survived, and the honest
    * place is a handler: asserting on the interceptor's return value would test the function rather than the
    * chain it was installed in.
    */
  private val echoHeaders: ServerEndpoint[Any, IO] =
    GatewayEndpoints.base.get
      .in("test" / "echo-headers")
      .in(headers)
      .out(stringBody)
      .name("gateway.test.echoHeaders")
      .serverLogicSuccess[IO](received =>
        IO.pure(received.map(header => s"${header.name}: ${header.value}").mkString("\n"))
      )

  test("healthEndpointsAnswer") {
    GatewayTestServer.resource().use { server =>
      for {
        live <- server.get(s"${GatewayEndpoints.ApiPrefix}/health/live")
        ready <- server.get(s"${GatewayEndpoints.ApiPrefix}/health/ready")
      } yield {
        assertEquals(live.code.code, 200, live.body)
        assertEquals(ready.code.code, 200, ready.body)
        // The gateway has no mandatory upstream, so readiness with nothing configured is still ready.
        assert(ready.body.contains("\"ready\":true"), ready.body)
      }
    }
  }

  test("unknownPathReturnsTheEnvelopeWithKuiRouteNotFound") {
    GatewayTestServer.resource().use { server =>
      server.get(s"${GatewayEndpoints.ApiPrefix}/nope").map { response =>
        assertEquals(response.code.code, 404)
        assertEquals(envelopeOf(response.body).code, ErrorCode.RouteNotFound.wire)
      }
    }
  }

  test("everyResponseCarriesTheCorrelationIdHeader") {
    GatewayTestServer.resource().use { server =>
      for {
        ok <- server.get(s"${GatewayEndpoints.ApiPrefix}/health/live")
        missing <- server.get(s"${GatewayEndpoints.ApiPrefix}/nope")
      } yield {
        // Both, and the failure is the one that matters: a 404 is exactly the response a user is looking at
        // when they ring up, so it is the response whose id has to be findable.
        assert(ok.header(correlationHeader).isDefined, s"no $correlationHeader on a 200")
        assert(missing.header(correlationHeader).isDefined, s"no $correlationHeader on a 404")
      }
    }
  }

  test("theCorrelationIdInTheHeaderMatchesTheOneInTheErrorBody") {
    GatewayTestServer.resource().use { server =>
      server.get(s"${GatewayEndpoints.ApiPrefix}/nope").map { response =>
        assertEquals(response.header(correlationHeader), Some(envelopeOf(response.body).correlationId))
      }
    }
  }

  test("generatesACorrelationIdWhenNoneExists") {
    GatewayTestServer.resource().use { server =>
      server.get(s"${GatewayEndpoints.ApiPrefix}/health/live").map { response =>
        assert(response.header(correlationHeader).exists(_.nonEmpty))
      }
    }
  }

  test("neverReusesAnInboundOne") {
    // The security rule of ADR-040 §2: a client must not be able to choose the id that ends up in the logs,
    // because an id it chose can be a duplicate of another request's, and the investigation that needed the
    // id is then the investigation that fails.
    val forged = "aaaaaaaaaaaaaaaa"

    GatewayTestServer.resource().use { server =>
      server
        .get(s"${GatewayEndpoints.ApiPrefix}/nope", Map(correlationHeader -> forged))
        .map { response =>
          assertNotEquals(response.header(correlationHeader), Some(forged))
          assertNotEquals(envelopeOf(response.body).correlationId, forged)
        }
    }
  }

  test("aForgedPrincipalHeaderNeverReachesAHandler") {
    GatewayTestServer.resource(extraRoutes = List(echoHeaders)).use { server =>
      server
        .get(
          s"${GatewayEndpoints.ApiPrefix}/test/echo-headers",
          Map("X-Kui-Principal" -> "forged", "Accept-Language" -> "en")
        )
        .map { response =>
          assertEquals(response.code.code, 200, response.body)
          // The body is the list of headers the handler was given. The forged principal must not be in it,
          // and the innocent header beside it must be — proving the rule removed one thing and not everything.
          assert(!response.body.toLowerCase.contains("x-kui-principal"), response.body)
          assert(response.body.contains("Accept-Language"), response.body)
          // The only `X-Kui-*` header the handler sees is the correlation id the gateway itself minted.
          assert(response.body.contains(Correlation.HeaderName), response.body)
        }
    }
  }

  test("healthEndpointsMoveWithTheDeploymentsBasePath") {
    GatewayTestServer.resource(basePath = "/kui").use { server =>
      for {
        prefixed <- server.get(s"/kui${GatewayEndpoints.ApiPrefix}/health/live")
        bare <- server.get(s"${GatewayEndpoints.ApiPrefix}/health/live")
      } yield {
        assertEquals(prefixed.code.code, 200, prefixed.body)
        // Mounted under `/kui`, the bare path is not this deployment's; answering it anyway would make a
        // reverse-proxy misconfiguration invisible until something else broke.
        assertEquals(bare.code.code, 404)
      }
    }
  }

  private def envelopeOf(body: String): ErrorEnvelope =
    decode[ErrorEnvelope](body).fold(error => fail(s"not an error envelope: $body ($error)"), identity)
}
