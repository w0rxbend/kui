package kui.gateway.api.routing

import munit.FunSuite
import sttp.tapir.AnyEndpoint

import kui.alerts.contract.AlertsEndpoints
import kui.cluster.contract.{ClusterEndpoints, ClusterWriteEndpoints}
import kui.connect.contract.ConnectEndpoints
import kui.consumer.contract.{ConsumerEndpoints, ConsumerMutationEndpoints}
import kui.gateway.api.KsqlStreamRoutes
import kui.kernel.ServiceId
import kui.ksql.contract.KsqlEndpoints
import kui.message.contract.{FilterEndpoints, MessageMutationEndpoints, TrackEndpoints}
import kui.metrics.contract.MetricsEndpoints
import kui.schema.contract.{SchemaEndpoints, SchemaMutationEndpoints}
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
  private val schema = ServiceId.unsafe("schema")
  private val metrics = ServiceId.unsafe("metrics")
  private val alerts = ServiceId.unsafe("alerts")
  private val connect = ServiceId.unsafe("connect")
  private val ksql = ServiceId.unsafe("ksql")

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
    assertEquals(
      ServiceContracts.byService.keySet,
      Set(cluster, topic, consumer, message, schema, metrics, alerts, connect, ksql)
    )
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
    assertEquals(
      ServiceContracts.of(message),
      MessageMutationEndpoints.all ++ FilterEndpoints.all ++ TrackEndpoints.all
    )
    // Both of the schema service's lists. The second one holds the three writes -- the two compatibility
    // settings and the registration -- *and* the compatibility check, which is not a mutation at all: it
    // is grouped by request shape, not by effect, so leaving it out would drop the one endpoint a
    // registration flow needs most.
    assertEquals(
      ServiceContracts.of(schema),
      SchemaEndpoints.all ++ SchemaMutationEndpoints.all
    )
    // The metrics service's one list. It publishes reads and no mutation, so unlike every service above
    // it there is no second object to forget. The count is deliberately not asserted: M7 adds four reads
    // to that list and this entry does not move.
    assertEquals(ServiceContracts.of(metrics), MetricsEndpoints.all)
    // The alerts service's one list, and the ninth entry. `AlertsEndpoints.all` is the feed read and the
    // acknowledgement write. Its third endpoint is the change stream, which this derivation cannot proxy
    // for the message browse stream's reason -- a stream is relayed rather than called and re-encoded --
    // so `AlertsStreamEndpoint` is deliberately not in the map and this assertion is what says so.
    assertEquals(ServiceContracts.of(alerts), AlertsEndpoints.all)
    // The connect service's one list, and the tenth entry. Unlike topic, consumer and schema it has no
    // second object to forget: its three operations are not destructive -- a paused connector is resumed
    // and a restarted one re-reads its own committed offsets -- so there is no ADR-045 marker to group
    // them by and they are published alongside the read. Which endpoint list is in the map is asserted
    // here, and the count of the writes inside it is asserted in `MergedDocumentShapeSuite`.
    assertEquals(ServiceContracts.of(connect), ConnectEndpoints.all)
    // The ksql service's one list: the eleventh service and the ninth entry in this map. Its plan and
    // its apply both carry ADR-045 markers
    // and are still published from the same object as the read, because ksqlDB has no *known* destructive
    // operation to group into a second one: the statement is whatever somebody typed, so the plan is a
    // classification of that text rather than a separate family of endpoints. Its fourth endpoint is the
    // push query, which this derivation cannot proxy for the alerts and message streams' reason — a stream
    // is relayed rather than called and re-encoded — so `KsqlStreamEndpoint` is deliberately not in the map
    // and this assertion is what says so.
    assertEquals(ServiceContracts.of(ksql), KsqlEndpoints.all)
  }

  test("theKsqlStreamIsRelayedAndThereforeNotInTheMap") {
    // The failure this stops is the one `ContractRouting`'s own comments describe: a stream added to the map
    // derives a proxy route that waits for the whole response value, decodes it and re-encodes it, so a push
    // query would answer nothing until it ended and it never ends. `assertEquals` above pins the list; this
    // pins the *reason*, by name, so that a push query arriving in `KsqlEndpoints.all` one day fails here
    // with the sentence rather than in a browser that hangs.
    val relayed = KsqlStreamRoutes.publicEndpoint[cats.effect.IO].info.name

    assert(relayed.isDefined, "the relayed push query has no endpoint name to check the map against")
    assert(
      !ServiceContracts.of(ksql).flatMap(_.info.name).exists(relayed.contains),
      s"$relayed is in ServiceContracts, where ContractRouting would decode and re-encode an event stream"
    )
  }

  test("theSchemaServicesTwoListsAreTheSizeTheMapSaysTheyAre") {
    // `ServiceContracts` explains its schema entry with two counts, and both of them were wrong for a
    // whole wave: the second list gained the registration in wave 4 and the sentence beside it still said
    // "two compatibility writes". A number in a comment that nothing reads is a number that drifts, so the
    // two the map states are read here.
    assertEquals(SchemaEndpoints.all.size, 5, SchemaEndpoints.all.flatMap(_.info.name).toString)
    assertEquals(
      SchemaMutationEndpoints.all.size,
      4,
      SchemaMutationEndpoints.all.flatMap(_.info.name).toString
    )
  }

  test("a service the gateway has no contract for is not an error") {
    // A service deployed before the gateway build that routes it is configured, polled and reported in the
    // capability snapshot; it simply has no proxied routes yet. No service is in that position today, so
    // the case is made with an id nothing serves rather than left untested until one is.
    //
    // It used to be made with `connect`, then with `ksql`, and each stopped being an id nothing serves the
    // moment that service was routed -- the case would have kept passing only because `getOrElse` answers
    // `Nil` for a key that is absent, and it is absent from nothing now. `security` is the next service this
    // plan names and has no `services/security` at all; whoever routes it moves this line, and a stale one
    // fails on the second assertion rather than silently asserting something true of every string.
    assertEquals(ServiceContracts.of(ServiceId.unsafe("security")), Nil)
    assert(
      !ServiceContracts.byService.keySet.contains(ServiceId.unsafe("security")),
      "security is routed now; this case needs an id the gateway really has no contract for"
    )
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
