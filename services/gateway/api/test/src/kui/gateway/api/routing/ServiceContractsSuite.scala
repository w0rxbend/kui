package kui.gateway.api.routing

import munit.FunSuite
import sttp.tapir.AnyEndpoint

import kui.cluster.contract.{ClusterEndpoints, ClusterWriteEndpoints}
import kui.consumer.contract.{ConsumerEndpoints, ConsumerMutationEndpoints}
import kui.kernel.ServiceId
import kui.message.contract.{FilterEndpoints, MessageMutationEndpoints}
import kui.topic.contract.{TopicAdminEndpoints, TopicEndpoints}

/** That the one map naming the gateway's downstream services says what the rest of the gateway assumes.
  *
  * Adding a service to the gateway is meant to be one entry here and one configuration key. This suite is
  * what makes "meant to be" checkable: it asserts the entries, and — the part a reviewer cannot see by
  * reading the map — that no two services claim the same public address.
  */
final class ServiceContractsSuite extends FunSuite {

  private val cluster = ServiceId.unsafe("cluster")
  private val topic = ServiceId.unsafe("topic")
  private val consumer = ServiceId.unsafe("consumer")
  private val message = ServiceId.unsafe("message")

  /** The public address of one endpoint, including its path parameters.
    *
    * `ContractRouting.publicPathOf` reports fixed segments only, so it cannot tell `/api/v1/clusters` from
    * `/api/v1/clusters/{clusterId}` — and a collision between those two is exactly what a caller would see
    * as one route shadowing another. The template plus the method is the address.
    */
  private def address(endpoint: AnyEndpoint): String = {
    val method = endpoint.method.map(_.method).getOrElse("GET")
    s"$method ${endpoint.showPathTemplate().takeWhile(_ != '?').replace("/internal/v1", "/api/v1")}"
  }

  test("everyConfiguredServiceHasItsContract") {
    assertEquals(ServiceContracts.byService.keySet, Set(cluster, topic, consumer, message))
    // Both of the cluster service's lists. `ClusterWriteEndpoints` used to be deliberately absent, so
    // that the one write M1 shipped had no public route while it had no screen; the administration screen
    // exists now, and an endpoint the browser cannot reach would make it a set of buttons that answer 404.
    // What keeps an unauthorised caller out is `ApplicationConfig.Edit`.
    assertEquals(ServiceContracts.of(cluster), ClusterEndpoints.all ++ ClusterWriteEndpoints.all)
    // Both of the topic service's lists. Its administration endpoints are published from a second
    // object because they carry a marker, a CSRF header and — for the two that cannot be undone — a plan
    // phase the reads do not; forgetting the second list here would leave create, configure, grow and
    // delete unroutable while every one of their own tests stayed green.
    assertEquals(ServiceContracts.of(topic), TopicEndpoints.all ++ TopicAdminEndpoints.all)
    // Both of the consumer service's lists. Its mutations are published from a second object because
    // they carry a marker and a CSRF header the reads do not; forgetting the second list here would
    // leave the offset reset unroutable while every one of its own tests stayed green.
    assertEquals(
      ServiceContracts.of(consumer),
      ConsumerEndpoints.all ++ ConsumerMutationEndpoints.all
    )
    // The message service's mutations and its two filter endpoints. The filter endpoints change nothing
    // on a cluster — one compiles an expression, the other runs it against a record the caller sent — but
    // they are ordinary request/response calls a browser has to reach, and leaving them out is what makes
    // a filter engine nothing can use.
    //
    // Its remaining endpoint is the browse stream, which this derivation cannot proxy at all: a stream has
    // to be relayed with its own cancellation and heartbeat handling rather than called and re-encoded,
    // which `MessageStreamRoutes` does.
    assertEquals(ServiceContracts.of(message), MessageMutationEndpoints.all ++ FilterEndpoints.all)
  }

  test("a service the gateway has no contract for is not an error") {
    // A service deployed before the gateway build that routes it is configured, polled and reported in the
    // capability snapshot; it simply has no proxied routes yet. No service is in that position today, so
    // the case is made with an id nothing serves rather than left untested until one is.
    assertEquals(ServiceContracts.of(ServiceId.unsafe("schema")), Nil)
  }

  test("theTopicEndpointsAreProxiedAndNoneIsAggregated") {
    // The topic list is proxied rather than aggregated, because the gateway has nothing to add to a topic
    // row. The dashboard's cluster list is aggregated because the gateway decorates each row with
    // capability state it alone holds; an aggregation with nothing to add is a second copy of a response
    // shape to keep in step, which is the shape of M1's second integration defect.
    assertEquals(ServiceContracts.proxied(topic), TopicEndpoints.all ++ TopicAdminEndpoints.all)
    assert(
      !TopicEndpoints.all.flatMap(_.info.name).exists(ServiceContracts.aggregated.contains),
      ServiceContracts.aggregated.toString
    )
  }

  test("noTwoServicesDeclareTheSamePath") {
    // The collision this map can create, asserted rather than discovered. Two routes for one address is
    // invisible in a route list: whichever was added first answers, and which that is depends on the order
    // of a `Map`.
    val addresses = ServiceContracts.byService.values.flatten.toList.map(address)

    assertEquals(addresses.distinct.size, addresses.size, addresses.groupBy(identity).filter(_._2.sizeIs > 1).keys.toString)
  }

  test("no proxied endpoint would be shadowed by an aggregation the gateway serves itself") {
    // The other half of the same rule: an endpoint the gateway answers itself must not also have a derived
    // proxy route, or the two claim one address.
    val proxiedAddresses =
      ServiceContracts.byService.keys.toList.flatMap(ServiceContracts.proxied).map(address)
    val aggregatedAddresses = ServiceContracts.byService.values.flatten.toList
      .filter(_.info.name.exists(ServiceContracts.aggregated.contains))
      .map(address)

    assertEquals(proxiedAddresses.intersect(aggregatedAddresses), Nil)
  }
}
