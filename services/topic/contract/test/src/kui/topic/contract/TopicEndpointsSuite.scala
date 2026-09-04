package kui.topic.contract

import munit.FunSuite
import sttp.model.Method
import sttp.tapir.{AnyEndpoint, DecodeResult, EndpointIO, EndpointInput}

import kui.contracts.KernelSchemas.given
import kui.contracts.KuiEndpoint
import kui.kernel.TopicName

/** That the topic service's addresses are what everything downstream assumes they are.
  *
  * Three of these assertions are preconditions of code in *other* modules: the gateway's `ContractRouting`
  * refuses to build a route for an endpoint outside `/internal/v1`, and its narrowing cast assumes every
  * endpoint's security input is the signed principal header. Asserting them here means a mistake is found in
  * the module that made it, with the endpoint named, instead of as a composition-root failure two services
  * away.
  */
final class TopicEndpointsSuite extends FunSuite {

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
    TopicEndpoints.all.foreach { endpoint =>
      assertEquals(pathSegments(endpoint).take(2), List("internal", "v1"), named(endpoint))
    }
  }

  test("everyEndpointCarriesTheSignedPrincipalSecurityInput") {
    // The gateway narrows every endpoint to one shape and reads this header off it. An endpoint that forgot
    // the security input would be served with no caller identity at all.
    TopicEndpoints.all.foreach { endpoint =>
      val headers = leaves(endpoint.securityInput).collect { case EndpointIO.Header(name, _, _) => name }
      assertEquals(headers, List(KuiEndpoint.PrincipalHeader), named(endpoint))
    }
  }

  test("everyEndpointHasAUniqueNameASummaryAndATag") {
    // The name keys the merged OpenAPI document, labels a metric and names a span, so a duplicate silently
    // merges two endpoints into one row on a dashboard.
    val names = TopicEndpoints.all.map(_.info.name)

    assert(names.forall(_.isDefined), names.toString)
    assertEquals(names.distinct.size, names.size, names.toString)

    TopicEndpoints.all.foreach { endpoint =>
      assert(endpoint.info.summary.isDefined, s"${named(endpoint)} has no summary")
      assert(endpoint.info.description.isDefined, s"${named(endpoint)} has no description")
      assertEquals(endpoint.info.tags.toList, List("topic"), named(endpoint))
    }
  }

  test("theEndpointListIsExactlyTheFiveDeclared") {
    // A sixth must be a deliberate edit to this list rather than something that appears because a value was
    // declared somewhere in the object.
    assertEquals(
      TopicEndpoints.all.flatMap(_.info.name),
      List("topic.list", "topic.get", "topic.config", "topic.partitions", "topic.refresh")
    )
  }

  test("the five endpoints are at exactly the documented addresses") {
    val addresses = TopicEndpoints.all.map(endpoint =>
      s"${endpoint.method.getOrElse(Method.GET).method} ${pathTemplate(endpoint)}"
    )

    assertEquals(
      addresses,
      List(
        "GET /internal/v1/clusters/{clusterId}/topics",
        "GET /internal/v1/clusters/{clusterId}/topics/{topicName}",
        "GET /internal/v1/clusters/{clusterId}/topics/{topicName}/config",
        "GET /internal/v1/clusters/{clusterId}/topics/{topicName}/partitions",
        "POST /internal/v1/clusters/{clusterId}/topics/refresh"
      )
    )
  }

  test("noEndpointMutates") {
    // Risk R-11's enforcer. "No mutations in M2" is stated in the roadmap, in the DEVPLAN and in this
    // task's spec; this is the thing that makes it true. Every endpoint is a GET except `topic.refresh`,
    // which is a POST that asks KUI to re-read a cluster and changes nothing on the cluster itself.
    val byMethod = TopicEndpoints.all.map(endpoint => named(endpoint) -> endpoint.method)

    assertEquals(
      byMethod,
      List(
        "topic.list" -> Some(Method.GET),
        "topic.get" -> Some(Method.GET),
        "topic.config" -> Some(Method.GET),
        "topic.partitions" -> Some(Method.GET),
        "topic.refresh" -> Some(Method.POST)
      )
    )

    // Named separately, because the list above could be edited to admit one of these without the diff
    // looking like it added a mutation.
    List(Method.PUT, Method.PATCH, Method.DELETE).foreach { method =>
      assert(
        !TopicEndpoints.all.exists(_.method.contains(method)),
        s"$method must not be declared before M5's read-only mode and audit trail exist"
      )
    }
  }

  test("no path suggests a mutation either") {
    // A `POST /topics` would pass the method check above by being a POST like `refresh`. The path table is
    // the second half of the same rule.
    val paths = TopicEndpoints.all.map(pathTemplate)

    List("replication-factor", "partitions/increase", "clone", "purge", "recreate").foreach { forbidden =>
      assert(!paths.exists(_.contains(forbidden)), s"$forbidden is M5's, with its safety net")
    }
  }

  test("refresh answers 202, because the snapshot is not new when it returns") {
    assert(TopicEndpoints.refresh.showDetail.contains("202"), TopicEndpoints.refresh.showDetail)
  }

  test("aMalformedTopicNameIsADecodeFailure") {
    // ".." must be a 400 naming the field, never a lookup that cannot match and answers 404: the two tell a
    // caller different things, and only one of them is worth retrying with a different name.
    val codec = summon[sttp.tapir.Codec[String, TopicName, sttp.tapir.CodecFormat.TextPlain]]

    codec.decode("..") match {
      case DecodeResult.Error(original, error) =>
        assertEquals(original, "..")
        assert(error.getMessage.contains("topic"), error.getMessage)
      case other => fail(s"'..' is not a topic name and should not decode: $other")
    }

    assertEquals(codec.decode("orders"), DecodeResult.Value(TopicName.unsafe("orders")))
  }

  test("no endpoint of another milestone has been declared here") {
    // M3's messages and M4's consumer groups are separate services with their own contracts. A sub-resource
    // declared here would be one this service then has to serve.
    val paths = TopicEndpoints.all.map(pathTemplate)

    List("messages", "consumer-groups", "analysis", "producers", "acls", "connectors", "schemas")
      .foreach(forbidden => assert(!paths.exists(_.contains(forbidden)), s"$forbidden belongs to another service"))
  }
}
