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

  test("theAudienceTheContractPublishesIsTheOneThisServiceVerifies") {
    // The two spellings of "cluster". `ClusterService.Id` is what this service checks a token's `aud`
    // against; `ProfileEndpoints.Audience` is what `services/cluster/client` signs one for, and rule A11
    // keeps that module out of the `application` layer where the first constant lives. This module is the
    // only one in the build that can see both, so this is the only place the two can be held together —
    // and if they ever drift, every internal call from every Kafka-facing service becomes a 401.
    assertEquals(
      kui.cluster.contract.ProfileEndpoints.Audience,
      kui.cluster.application.ClusterService.Id
    )
  }

  test("theClusterListIsServedToAVerifiedCaller") {
    // An empty registry answers with an empty list rather than a failure: a KUI nobody has configured a
    // cluster in genuinely has none, and that is not an error state.
    ClusterTestServer.resource().use { server =>
      for {
        token <- ClusterTestServer.token()
        response <- basicRequest
          .get(uri"${ClusterTestServer.ClustersUri}")
          .header(KuiEndpoint.PrincipalHeader, token.value)
          .response(asStringAlways)
          .send(server.backend)
        body = parse(response.body).getOrElse(fail(s"not JSON: ${response.body}")).hcursor
      } yield {
        assertEquals(response.code.code, 200, response.body)
        assertEquals(body.get[List[io.circe.Json]]("items"), Right(Nil))
      }
    }
  }

  test("aMalformedClusterIdIsFourHundredWithTheFieldNamed") {
    // 400 and not 404, and not 500: "that is not an id" and "no such cluster" are different answers, and
    // only one of them is worth retrying with a different id. `ErrorEnvelope.statusOf` decides the status.
    ClusterTestServer.resource().use { server =>
      for {
        token <- ClusterTestServer.token(
          digest = kui.security.RequestDigest.ofRequestLine("GET", "/internal/v1/clusters/Not%20A%20Slug")
        )
        response <- basicRequest
          .get(uri"http://cluster/internal/v1/clusters/Not%20A%20Slug")
          .header(KuiEndpoint.PrincipalHeader, token.value)
          .response(asStringAlways)
          .send(server.backend)
        body = parse(response.body).getOrElse(fail(s"not JSON: ${response.body}")).hcursor
      } yield {
        assertEquals(response.code.code, 400, response.body)
        assertEquals(body.get[String]("code"), Right("KUI-VALIDATION"))
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

  test("capabilitiesReportADegradedClusterRatherThanFailing") {
    // The degraded case the endpoint exists for: the gateway's registry needs this document most
    // exactly when things are wrong, so "cannot serve it fully" is an answer and never a 503.
    //
    // `degraded` and not `unavailable`: a service answering this request is reachable by definition, so
    // reporting itself unavailable would be a service claiming it is not there. `unavailable` is the
    // gateway's verdict when it gets no answer at all.
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
            Right("degraded")
          )
        }
    }
  }

  test("theServiceOwnDocumentDescribesTheWriteEndpointTheGatewayDoesNotPublish") {
    // The other half of the pair in the gateway's `MergedDocumentShapeSuite`. The public document
    // deliberately omits the write endpoint - no browser can reach it - and an operator debugging this
    // service directly still needs a complete description of what it serves.
    val document = ClusterApi.openApi[cats.effect.IO]

    assert(
      document.paths.pathItems.get("/internal/v1/clusters/{clusterId}").flatMap(_.put).isDefined,
      document.paths.pathItems.keySet.toString
    )
  }

  test("openApiContainsEveryEndpointInClusterEndpointsAll") {
    val document = ClusterApi.openApi[cats.effect.IO]
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
