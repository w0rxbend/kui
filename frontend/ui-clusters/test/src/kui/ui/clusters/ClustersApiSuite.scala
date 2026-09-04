package kui.ui.clusters

import munit.FunSuite
import sttp.client4.UriContext
import sttp.tapir.AnyEndpoint
import sttp.tapir.client.sttp4.SttpClientInterpreter

import io.circe.parser.decode

import kui.cluster.contract.ClusterEndpoints
import kui.cluster.contract.dto.{BrokersResponse, ClusterDetailResponse, LogDirsResponse}
import kui.gateway.contract.dto.ClusterOverviewDto
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

  // -----------------------------------------------------------------------------------------------
  // Recorded gateway responses
  // -----------------------------------------------------------------------------------------------
  //
  // Captured verbatim from a running KUI — the quickstart, against a real single-broker Kafka — with
  // `curl http://localhost:8080/api/v1/...`. They are here because of a bug that cost the whole of
  // M1's dashboard and that nothing else could have caught.
  //
  // `GET /api/v1/clusters` is the one endpoint the gateway *aggregates* rather than proxies, so it
  // answers with the gateway's `ClusterOverviewDto` (`{"clusters": {...}}`). This module declared it
  // as the cluster service's `ClustersResponse` (`{"items": [...]}`), whose decoder defaults a missing
  // `items` to the empty list. Every response therefore decoded successfully into zero rows: the
  // dashboard drew "No clusters yet" under a "last updated just now" timestamp, against a healthy
  // broker, with no error anywhere. Both modules' own suites were green, because each tested itself
  // against its own idea of the payload.
  //
  // A recorded document is the cheapest thing that could have caught it, and it catches the same
  // class of mistake for the four read endpoints below.

  private val recordedClusters: String =
    """{"clusters":{"status":"ok","data":[{"cluster":{"id":"quickstart","name":"Quickstart (local)",""" +
      """"readOnly":false,"bootstrapServers":"kafka:9092","security":{"protocol":"PLAINTEXT",""" +
      """"mechanism":null,"truststoreConfigured":false,"keystoreConfigured":false},"summary":""" +
      """{"status":"ok","data":{"kafkaClusterId":"kui-quickstart-0000000001","version":null,""" +
      """"controllerId":1,"controllerKind":"kraft","brokerCount":1,"onlinePartitionCount":null,""" +
      """"offlinePartitionCount":null,"underReplicatedPartitionCount":null,""" +
      """"totalDiskUsageBytes":503316811776,"features":["kraft-quorum","log-dirs"],""" +
      """"scrapedAt":"2026-09-04T01:40:22.443Z"},"fetchedAt":"2026-09-04T01:40:22.443Z"}},""" +
      """"capability":{"status":"available"}}],"fetchedAt":"2026-09-04T01:40:28.904Z"},""" +
      """"generatedAt":"2026-09-04T01:40:28.904Z"}"""

  private val recordedCluster: String =
    """{"cluster":{"id":"quickstart","name":"Quickstart (local)","readOnly":false,""" +
      """"bootstrapServers":"kafka:9092","security":{"protocol":"PLAINTEXT","mechanism":null,""" +
      """"truststoreConfigured":false,"keystoreConfigured":false},"summary":{"status":"ok","data":""" +
      """{"kafkaClusterId":"kui-quickstart-0000000001","version":null,"controllerId":1,""" +
      """"controllerKind":"kraft","brokerCount":1,"onlinePartitionCount":null,""" +
      """"offlinePartitionCount":null,"underReplicatedPartitionCount":null,""" +
      """"totalDiskUsageBytes":503316811776,"features":["kraft-quorum","log-dirs"],""" +
      """"scrapedAt":"2026-09-04T01:40:22.443Z"},"fetchedAt":"2026-09-04T01:40:22.443Z"}}}"""

  private val recordedBrokers: String =
    """{"brokers":{"status":"ok","data":[{"id":1,"host":"kafka","port":9092,"rack":null,""" +
      """"isController":true,"partitionCount":null,"leaderCount":null,"replicaCount":83,""" +
      """"replicaSkewPercent":0.0,"leaderSkewPercent":null,"diskUsageBytes":183246045184,""" +
      """"segmentCount":null}],"fetchedAt":"2026-09-04T01:40:22.443Z"}}"""

  private val recordedLogDirs: String =
    """{"logDirs":{"status":"ok","data":[{"brokerId":1,"path":"/tmp/kafka-logs","error":null,""" +
      """"totalBytes":503316811776,"usableBytes":320070766592,"topicCount":9,""" +
      """"partitionCount":83}],"fetchedAt":"2026-09-04T01:40:22.443Z"}}"""

  test("theDashboardResponseDecodesToTheClustersItContains") {
    val decoded = decode[ClusterOverviewDto](recordedClusters)
      .fold(error => fail(s"the recorded gateway response did not decode: $error"), identity)

    val rows = decoded.clusters.toOption.toList.flatten
    assertEquals(rows.length, 1, "one configured cluster was recorded, so one row must come out")
    assertEquals(rows.head.cluster.id.value, "quickstart")
    assertEquals(rows.head.cluster.summary.toOption.map(_.brokerCount), Some(1))
  }

  test("theClusterDetailResponseDecodes") {
    val decoded = decode[ClusterDetailResponse](recordedCluster)
      .fold(error => fail(s"the recorded gateway response did not decode: $error"), identity)
    assertEquals(decoded.cluster.id.value, "quickstart")
  }

  test("theBrokersResponseDecodesToTheBrokersItContains") {
    val decoded = decode[BrokersResponse](recordedBrokers)
      .fold(error => fail(s"the recorded gateway response did not decode: $error"), identity)
    val brokers = decoded.brokers.toOption.toList.flatten
    assertEquals(brokers.map(_.id.value), List(1))
    assertEquals(brokers.map(_.host), List("kafka"))
  }

  test("theLogDirsResponseDecodesToTheDirectoriesItContains") {
    val decoded = decode[LogDirsResponse](recordedLogDirs)
      .fold(error => fail(s"the recorded gateway response did not decode: $error"), identity)
    val dirs = decoded.logDirs.toOption.toList.flatten
    assertEquals(dirs.map(_.path), List("/tmp/kafka-logs"))
  }

  private def inputsOf(endpoint: AnyEndpoint): String =
    s"${endpoint.securityInput.show} ${endpoint.input.show}"
}
