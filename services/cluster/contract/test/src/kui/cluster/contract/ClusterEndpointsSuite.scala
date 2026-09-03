package kui.cluster.contract

import munit.FunSuite
import sttp.model.Method
import sttp.tapir.{AnyEndpoint, DecodeResult, EndpointIO, EndpointInput}

import kui.contracts.KernelSchemas.given
import kui.contracts.KuiEndpoint
import kui.kernel.ClusterId

/** That the cluster service's addresses are what everything downstream assumes they are.
  *
  * Three of these assertions are preconditions of code in *other* modules: the gateway's `ContractRouting`
  * refuses to build a route for an endpoint outside `/internal/v1`, and its narrowing cast assumes every
  * endpoint's security input is the signed principal header. Asserting them here means a mistake is found in
  * the module that made it, with the endpoint named, instead of as a composition-root failure two services
  * away.
  */
final class ClusterEndpointsSuite extends FunSuite {

  private def leaves(input: EndpointInput[?]): List[EndpointInput[?]] =
    input match {
      case EndpointInput.Pair(left, right, _, _) => leaves(left) ++ leaves(right)
      case EndpointIO.Pair(left, right, _, _) => leaves(left) ++ leaves(right)
      case EndpointInput.MappedPair(wrapped, _) => leaves(wrapped)
      case EndpointIO.MappedPair(wrapped, _) => leaves(wrapped)
      case leaf => List(leaf)
    }

  private def pathSegments(endpoint: AnyEndpoint): List[String] =
    leaves(endpoint.input).collect { case EndpointInput.FixedPath(segment, _, _) => segment }

  private def named(endpoint: AnyEndpoint): String = endpoint.info.name.getOrElse(endpoint.showShort)

  /** The path alone. Tapir's template appends the query parameters, which are not part of the address. */
  private def pathTemplate(endpoint: AnyEndpoint): String = endpoint.showPathTemplate().takeWhile(_ != '?')

  test("everyEndpointIsUnderInternalV1") {
    // The precondition `ContractRouting.derive` fails construction on. `/api/v1` belongs to the gateway.
    ClusterEndpoints.all.foreach { endpoint =>
      assertEquals(pathSegments(endpoint).take(2), List("internal", "v1"), named(endpoint))
    }
  }

  test("everyEndpointCarriesTheSignedPrincipalSecurityInput") {
    // The gateway narrows every endpoint to one shape and reads this header off it. An endpoint that
    // forgot the security input would be served with no caller identity at all.
    ClusterEndpoints.all.foreach { endpoint =>
      val headers = leaves(endpoint.securityInput).collect { case EndpointIO.Header(name, _, _) => name }
      assertEquals(headers, List(KuiEndpoint.PrincipalHeader), named(endpoint))
    }
  }

  test("everyEndpointHasAUniqueName") {
    // The name keys the merged OpenAPI document and labels a metric, so a duplicate silently merges two
    // endpoints into one row on a dashboard.
    val names = ClusterEndpoints.all.map(_.info.name)

    assert(names.forall(_.isDefined), names.toString)
    assertEquals(names.distinct.size, names.size, names.toString)
  }

  test("everyEndpointHasASummaryAndATag") {
    ClusterEndpoints.all.foreach { endpoint =>
      assert(endpoint.info.summary.isDefined, s"${named(endpoint)} has no summary")
      assertEquals(endpoint.info.tags.toList, List("cluster"), named(endpoint))
    }
  }

  test("theEndpointListIsExactlyTheSevenDeclared") {
    // A seventh read endpoint, or an eighth, must be a deliberate edit to this list rather than something
    // that appears because a value was declared. `cluster.ping` is the M0 sample and CLAPI-004 removes it.
    assertEquals(
      ClusterEndpoints.all.flatMap(_.info.name),
      List(
        "cluster.ping",
        "cluster.list",
        "cluster.get",
        "cluster.brokers",
        "cluster.broker.configs",
        "cluster.logDirs",
        "cluster.refresh"
      )
    )
  }

  test("the six read endpoints are at exactly the documented addresses") {
    val addresses = ClusterEndpoints.all.tail.map(endpoint =>
      s"${endpoint.method.getOrElse(Method.GET).method} ${pathTemplate(endpoint)}"
    )

    assertEquals(
      addresses,
      List(
        "GET /internal/v1/clusters",
        "GET /internal/v1/clusters/{clusterId}",
        "GET /internal/v1/clusters/{clusterId}/brokers",
        "GET /internal/v1/clusters/{clusterId}/brokers/{brokerId}/configs",
        "GET /internal/v1/clusters/{clusterId}/log-dirs",
        "POST /internal/v1/clusters/{clusterId}/refresh"
      )
    )
  }

  test("aMalformedClusterIdIsADecodeFailure") {
    // "Not A Slug" must be a 400 naming the field, never a lookup that cannot match and answers 404: the
    // two tell a caller different things, and only one of them is worth retrying with a different id.
    val codec = summon[sttp.tapir.Codec[String, ClusterId, sttp.tapir.CodecFormat.TextPlain]]

    codec.decode("Not A Slug") match {
      case DecodeResult.Error(original, error) =>
        assertEquals(original, "Not A Slug")
        assert(error.getMessage.contains("clusterId"), error.getMessage)
      case other => fail(s"a malformed slug should not decode: $other")
    }

    assertEquals(codec.decode("prod-eu"), DecodeResult.Value(ClusterId.unsafe("prod-eu")))
  }

  test("the log-dirs path is kebab-case, because every other KUI sub-resource is") {
    assertEquals(pathTemplate(ClusterEndpoints.logDirs), "/internal/v1/clusters/{clusterId}/log-dirs")
  }

  test("refresh answers 202, because the snapshot is not new when it returns") {
    assert(ClusterEndpoints.refresh.showDetail.contains("202"), ClusterEndpoints.refresh.showDetail)
  }

  test("no broker-config edit endpoint is declared") {
    // BR-002 is read-only in M1. An endpoint declared before its safety net - read-only mode and audit,
    // both M5 - is one somebody implements (DEVPLAN §3).
    assert(
      !ClusterEndpoints.all.exists(endpoint => endpoint.method.contains(Method.PUT)),
      ClusterEndpoints.all.flatMap(_.info.name).toString
    )
  }
}
