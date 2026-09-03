package kui.gateway.api

import java.time.Instant

import cats.effect.IO
import io.circe.parser.parse
import munit.CatsEffectSuite

import kui.contracts.Section
import kui.contracts.capability.{CapabilityState, ReasonCode}
import kui.contracts.cluster.{ClusterRowDto, ClusterSecurityDto}
import kui.gateway.api.routing.{ContractRouting, ServiceContracts}
import kui.gateway.application.cluster.ClusterOverviewUseCase
import kui.gateway.contract.dto.{ClusterOverviewDto, ClusterOverviewRow}
import kui.kernel.{ClusterId, CorrelationId, ServiceId}
import kui.security.Principal

/** That the dashboard is served at `/api/v1/clusters`, by the gateway, and by nothing else.
  *
  * The last part is the one worth a test rather than a comment: the cluster service publishes a list endpoint
  * at the same public path, and if it were still derived as a proxy route then which of the two answered
  * would depend on the order two lists were concatenated in a composition root.
  */
final class ClusterOverviewRoutesSuite extends CatsEffectSuite {

  private val at = Instant.parse("2026-09-03T10:11:12Z")

  private val answer = ClusterOverviewDto(
    Section.Ok(
      List(
        ClusterOverviewRow(
          ClusterRowDto(
            id = ClusterId.unsafe("prod-eu"),
            name = "Production EU",
            readOnly = false,
            bootstrapServers = "broker-1.example.com:9093",
            security = ClusterSecurityDto("PLAINTEXT", None, false, false),
            summary = Section.Unavailable(ReasonCode.UpstreamUnavailable, "connection refused", Some(at))
          ),
          CapabilityState.Available
        )
      ),
      at
    ),
    at
  )

  private val overview = new ClusterOverviewUseCase[IO] {
    def overview(principal: Principal, correlationId: CorrelationId): IO[ClusterOverviewDto] =
      IO.pure(answer)
  }

  test("the dashboard is served at /api/v1/clusters") {
    GatewayTestServer.resource(extraRoutes = ClusterOverviewRoutes[IO](overview)).use { server =>
      server.get("/api/v1/clusters").map { response =>
        assertEquals(response.code.code, 200, response.body)
        assertEquals(
          parse(response.body).flatMap(_.as[ClusterOverviewDto]),
          Right(answer)
        )
      }
    }
  }

  test("exactly one route claims the path") {
    // The proxy routes derived from the cluster service's contract must not include its own list endpoint,
    // or two routes would answer one address.
    val service = ServiceId.unsafe("cluster")
    val proxied = ServiceContracts
      .proxied(service)
      .flatMap(endpoint => ContractRouting.publicPathOf(endpoint).toOption.map(_ -> endpoint))

    val claiming = proxied.count((path, endpoint) =>
      path == "/api/v1/clusters" && endpoint.showPathTemplate().count(_ == '{') == 0
    )

    assertEquals(claiming, 0)
  }

  test("the endpoint is published for the merged OpenAPI document") {
    // Otherwise the one path a browser starts from would be missing from the document an integrator reads.
    assertEquals(ClusterOverviewRoutes.endpoints.flatMap(_.info.name), List("gateway.clusters.overview"))
  }

}
