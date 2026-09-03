package kui.cluster.api

import scala.io.Source
import scala.util.Using

import cats.effect.IO
import io.circe.parser.parse
import munit.CatsEffectSuite
import sttp.client4.*

import kui.cluster.contract.ClusterEndpoints
import kui.contracts.KuiEndpoint
import kui.observability.KuiInterceptors

/** That the routes this service serves are the routes its contract publishes, and that they behave.
  *
  * The suite talks to the assembled service through Tapir's stub interpreter, so what it exercises is the
  * real interceptor chain, the real principal check and the real mapping — everything except the socket.
  */
final class ClusterApiSuite extends CatsEffectSuite {

  test("pingReturnsTheEchoedMessage") {
    ClusterTestServer.resource().use { server =>
      for {
        token <- ClusterTestServer.token()
        response <- basicRequest
          .get(uri"${ClusterTestServer.PingUri}?message=hello")
          .header(KuiEndpoint.PrincipalHeader, token.value)
          .response(asStringAlways)
          .send(server.backend)
      } yield {
        assertEquals(response.code.code, 200, response.body)
        // The instant comes from the fixed clock and the service name from `ClusterService.Id`,
        // which is the whole of what `PingMapping` adds to the domain value.
        assertEquals(
          parse(response.body),
          parse("""{"message":"hello","at":"2026-09-03T10:11:12.000Z","service":"cluster"}""")
        )
      }
    }
  }

  test("pingWithAnOverLongMessageReturnsValidation") {
    ClusterTestServer.resource().use { server =>
      val tooLong = "x" * 129

      for {
        token <- ClusterTestServer.token()
        response <- basicRequest
          .get(uri"${ClusterTestServer.PingUri}?message=$tooLong")
          .header(KuiEndpoint.PrincipalHeader, token.value)
          .response(asStringAlways)
          .send(server.backend)
        body = parse(response.body).getOrElse(fail(s"not JSON: ${response.body}")).hcursor
      } yield {
        // 400 and not 500: the domain refused the request, and `ErrorEnvelope.statusOf` is what
        // turned that refusal into a status. Naming the field is the whole value of the answer to
        // whoever has to fix the call.
        assertEquals(response.code.code, 400, response.body)
        assertEquals(body.get[String]("code"), Right("KUI-VALIDATION"))
        assertEquals(
          body.downField("details").downN(0).get[String]("field"),
          Right("message")
        )
        assertEquals(body.get[Boolean]("retryable"), Right(false))
      }
    }
  }

  test("healthAndCapabilitiesAreServedWithoutAPrincipal") {
    ClusterTestServer.resource().use { server =>
      // A probe has no credentials and cannot be given any. If these three needed a signed
      // principal, an orchestrator would call the service dead and restart it forever.
      def get(path: String) =
        basicRequest
          .get(uri"http://cluster".withWholePath(path))
          .response(asStringAlways)
          .send(server.backend)

      for {
        live <- get("/health/live")
        ready <- get("/health/ready")
        capabilities <- get("/capabilities")
      } yield {
        assertEquals(live.code.code, 200, live.body)
        assertEquals(ready.code.code, 200, ready.body)
        assertEquals(capabilities.code.code, 200, capabilities.body)
      }
    }
  }

  test("capabilitiesBodyMatchesTheGoldenDocument") {
    ClusterTestServer.resource().use { server =>
      basicRequest
        .get(uri"http://cluster/capabilities")
        .response(asStringAlways)
        .send(server.backend)
        .map(response => assertEquals(parse(response.body), parse(golden("cluster-capabilities.json"))))
    }
  }

  test("capabilitiesReportAnUnreachableClusterRatherThanFailing") {
    // The degraded case the endpoint exists for: the gateway's registry needs this document most
    // exactly when things are wrong, so "cannot reach it" is an answer and never a 503.
    ClusterTestServer.resource(available = false).use { server =>
      basicRequest
        .get(uri"http://cluster/capabilities")
        .response(asStringAlways)
        .send(server.backend)
        .map { response =>
          assertEquals(response.code.code, 200, response.body)
          assertEquals(
            parse(response.body)
              .getOrElse(fail(response.body))
              .hcursor
              .downField("clusters")
              .downField("prod-eu")
              .get[String]("status"),
            Right("unavailable")
          )
        }
    }
  }

  test("openApiContainsEveryEndpointInClusterEndpointsAll") {
    val document = ClusterApi.openApi
    val paths = document.paths.pathItems.keySet

    // The drift guard. An endpoint added to the contract and not to the served list would still
    // appear here — which is why the wiring suite counts the routes as well — but a *path* that is
    // published and undocumented is caught here, before the gateway merges a document that is
    // missing it.
    ClusterEndpoints.all.foreach { endpoint =>
      // `showQueryParam = None` because an OpenAPI path key is the path alone: query parameters are
      // described separately, and the default rendering would append `?message={message}` and never
      // match.
      val template = endpoint.showPathTemplate(showQueryParam = None)
      assert(paths.contains(template), s"$paths does not describe $template")
    }

    // The health endpoints are not in the contract — they are identical in all eleven services and
    // come from `libs/http` — but they are part of what this service serves, so they are part of
    // what it documents.
    assert(paths.contains("/health/live"), paths.toString)
    assert(paths.contains("/health/ready"), paths.toString)
    assert(paths.contains("/capabilities"), paths.toString)
  }

  test("everyEndpointHasAnOperationIdSoNoSpanIsNamedAfterAUrl") {
    // OBS-002's guard, applied to this service's endpoints. The fallback span name exists so a
    // missing id cannot break a request, not so it can be relied on.
    assertEquals(KuiInterceptors.missingOperationIds(ClusterApi.documented), Nil)
  }

  private def golden(name: String): String =
    Using
      .resource(Option(getClass.getResourceAsStream(s"/golden/$name")).getOrElse {
        fail(s"golden/$name is missing from the test resources")
      })(stream => Source.fromInputStream(stream, "UTF-8").mkString)
}
