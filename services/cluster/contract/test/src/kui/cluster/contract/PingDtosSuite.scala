package kui.cluster.contract

import java.time.Instant

import io.circe.parser.decode
import io.circe.syntax.*
import munit.FunSuite
import sttp.tapir.{AnyEndpoint, EndpointIO, EndpointInput}

import kui.cluster.contract.dto.PingResponse
import kui.contracts.KuiEndpoint

/** The cluster service's published wire shape.
  *
  * Two kinds of assertion live here, and the second kind is the important one. The first pins the JSON of a
  * DTO against a committed document, so that changing the format means changing a file a reviewer can read.
  * The second walks `ClusterEndpoints.all` and asserts a property of *every* endpoint — an operation id, a
  * summary, the principal header, the `/internal/v1` prefix. Those tests are what stop the endpoint someone
  * adds in eight months from being served unauthenticated or unnamed: they fail for the new endpoint without
  * anyone having to remember to write a test for it.
  */
final class PingDtosSuite extends FunSuite {

  private val at: Instant = Instant.parse("2026-09-03T10:11:12Z")

  private val sample: PingResponse = PingResponse("hello", at, "cluster")

  /** Every leaf of an endpoint input tree.
    *
    * Tapir builds an endpoint's inputs as a tree of pairs — `a.and(b).and(c)` — so a test that wants to ask
    * "does this endpoint read the principal header" has to walk it rather than look at a list. The four cases
    * below are the tree's only branches; everything else is a leaf, and a leaf is what carries a name.
    */
  private def leaves(input: EndpointInput[?]): List[EndpointInput[?]] = input match {
    case EndpointInput.Pair(left, right, _, _) => leaves(left) ++ leaves(right)
    case EndpointIO.Pair(left, right, _, _) => leaves(left) ++ leaves(right)
    case EndpointInput.MappedPair(wrapped, _) => leaves(wrapped)
    case EndpointIO.MappedPair(wrapped, _) => leaves(wrapped)
    case leaf => List(leaf)
  }

  /** The security input and the ordinary input together: what the endpoint reads from a request in total. */
  private def allInputs(endpoint: AnyEndpoint): List[EndpointInput[?]] =
    leaves(endpoint.securityInput) ++ leaves(endpoint.input)

  private def fixedPathSegments(endpoint: AnyEndpoint): List[String] =
    leaves(endpoint.input).collect { case EndpointInput.FixedPath(segment, _, _) => segment }

  private def headerNames(endpoint: AnyEndpoint): List[String] =
    allInputs(endpoint).collect { case header: EndpointIO.Header[?] => header.name }

  private def queryNames(endpoint: AnyEndpoint): List[String] =
    leaves(endpoint.input).collect { case query: EndpointInput.Query[?] => query.name }

  test("pingResponseMatchesTheGoldenDocument") {
    assertNoDiff(io.circe.Printer.spaces2.print(sample.asJson), GoldenDocuments.pingResponse)
    assertEquals(decode[PingResponse](GoldenDocuments.pingResponse), Right(sample))
  }

  test("the instant is RFC 3339 in UTC with exactly three fractional digits") {
    assertEquals(
      sample.copy(at = Instant.parse("2026-09-03T10:11:12.5Z")).asJson.noSpaces,
      """{"message":"hello","at":"2026-09-03T10:11:12.500Z","service":"cluster"}"""
    )
  }

  test("everyEndpointHasAnOperationIdAndASummary") {
    ClusterEndpoints.all.foreach { endpoint =>
      assert(endpoint.info.name.exists(_.nonEmpty), s"$endpoint has no .name to use as an operation id")
      assert(endpoint.info.summary.exists(_.nonEmpty), s"${endpoint.info.name} has no .summary")
    }
  }

  test("everyInternalEndpointRequiresThePrincipalHeader") {
    ClusterEndpoints.all.foreach { endpoint =>
      assert(
        headerNames(endpoint).contains(KuiEndpoint.PrincipalHeader),
        s"${endpoint.info.name} does not require ${KuiEndpoint.PrincipalHeader}; " +
          "build it from KuiEndpoint.internal"
      )
    }
  }

  test("endpointPathsStartWithInternalV1") {
    ClusterEndpoints.all.foreach { endpoint =>
      assertEquals(
        fixedPathSegments(endpoint).take(2),
        List("internal", "v1"),
        clue = s"${endpoint.info.name} is not under /internal/v1; the public prefix belongs to the gateway"
      )
    }
  }

  test("the ping endpoint is a GET that reads its message from the query string") {
    assertEquals(fixedPathSegments(ClusterEndpoints.ping), List("internal", "v1", "ping"))
    assertEquals(ClusterEndpoints.ping.method.map(_.method), Some("GET"))

    assertEquals(queryNames(ClusterEndpoints.ping), List("message"))
  }
}
