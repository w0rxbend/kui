package kui.ui.clusters

import munit.FunSuite
import sttp.client4.UriContext
import sttp.tapir.AnyEndpoint
import sttp.tapir.client.sttp4.SttpClientInterpreter

import kui.cluster.contract.ClusterEndpoints
import kui.contracts.PublicApi
import kui.kernel.{BrokerId, ClusterId}

/** What URL each client actually calls, asserted against the contract's own constants.
  *
  * The failure this suite is here to catch is the quiet one: a segment renamed in
  * `services/cluster/contract` while this module keeps calling the old address. That compiles
  * cleanly — a segment is a `String` — and 404s in production.
  */
class ClustersApiSuite extends FunSuite {

  private val base = uri"https://kui.example"
  private val interpreter = SttpClientInterpreter()

  private def pathOf[I](endpoint: sttp.tapir.PublicEndpoint[I, ?, ?, Any], input: I): String =
    interpreter.toRequest(endpoint, Some(base))(input).uri.toString.stripPrefix(base.toString)

  private val cluster = ClusterId.from("local").getOrElse(fail("`local` should be a legal cluster id"))
  private val broker = BrokerId.from(1).getOrElse(fail("1 should be a legal broker id"))

  private val calls: List[(String, String)] = List(
    "clusters" -> pathOf(ClustersApi.clusters, ()),
    "cluster" -> pathOf(ClustersApi.cluster, cluster),
    "brokers" -> pathOf(ClustersApi.brokers, cluster),
    "brokerConfigs" -> pathOf(ClustersApi.brokerConfigs, (cluster, broker, true)),
    "logDirs" -> pathOf(ClustersApi.logDirs, (cluster, Some(broker))),
    "refresh" -> pathOf(ClustersApi.refresh, cluster)
  )

  test("everyClientTargetsThePublicPrefix") {
    // Every endpoint, not a hand-written list of them: adding a seventh without a test is not possible.
    assertEquals(calls.length, ClustersApi.all.length)
    calls.foreach { (name, path) =>
      assert(path.startsWith(PublicApi.Prefix), s"$name calls $path")
      assert(!path.contains("/internal"), s"$name calls $path")
    }
  }

  test("pathsAreBuiltFromTheContractsOwnSegments") {
    val clusters = s"${PublicApi.Prefix}/${ClusterEndpoints.ClustersSegment}"
    val one = s"$clusters/${cluster.value}"
    val expected = Map(
      "clusters" -> clusters,
      "cluster" -> one,
      "brokers" -> s"$one/${ClusterEndpoints.BrokersSegment}",
      "brokerConfigs" ->
        (s"$one/${ClusterEndpoints.BrokersSegment}/${broker.value}/${ClusterEndpoints.ConfigsSegment}" +
          s"?${ClusterEndpoints.DocsParam}=true"),
      "logDirs" ->
        s"$one/${ClusterEndpoints.LogDirsSegment}?${ClusterEndpoints.BrokerIdParam}=${broker.value}",
      "refresh" -> s"$one/${ClusterEndpoints.RefreshSegment}"
    )
    calls.foreach((name, path) => assertEquals(path, expected(name), name))
  }

  test("anOmittedBrokerFilterOmitsTheQueryParameterEntirely") {
    // Not `?brokerId=`, which the server would have to decode as an empty id rather than as absent.
    val path = pathOf(ClustersApi.logDirs, (cluster, None))
    assert(!path.contains("?"), path)
  }

  test("clusterIdsAreEncodedNotConcatenated") {
    // Cluster ids are slugs and cannot contain a space or a slash (ADR-031), which is exactly why this is
    // worth asserting: the invariant is enforced somewhere else, and this proves the client does not
    // silently depend on it.
    val path = interpreter
      .toRequest(ClustersApi.brokers, Some(base))(ClusterId.unsafe("a b/c"))
      .uri
      .toString
    assert(path.contains("a%20b%2Fc"), path)
  }

  test("noClientDeclaresAPrincipalHeader") {
    // The signed principal header is the gateway's to mint (ADR-040). A browser that sent one would be
    // asserting an identity, which is the shape of a privilege-escalation bug.
    ClustersApi.all.foreach { endpoint =>
      assert(!inputsOf(endpoint).toLowerCase.contains("principal"), endpoint.showShort)
    }
  }

  test("refreshIsAPostAndReadsAreGets") {
    assertEquals(ClustersApi.refresh.method.map(_.method), Some("POST"))
    List(ClustersApi.clusters, ClustersApi.cluster, ClustersApi.brokers, ClustersApi.logDirs)
      .foreach(endpoint => assertEquals(endpoint.method.map(_.method), Some("GET"), endpoint.showShort))
  }

  private def inputsOf(endpoint: AnyEndpoint): String =
    s"${endpoint.securityInput.show} ${endpoint.input.show}"
}
