package kui.gateway.api.routing

import munit.FunSuite

import kui.kernel.ClusterId

/** The table of cases is the specification.
  *
  * Every row is a path a browser can actually produce, and the answer decides three things at once: which
  * cluster header the upstream is sent, which cluster the RBAC check is asked about, and whether the request
  * is refused before it costs the cluster service a connection.
  */
final class ClusterScopeSuite extends FunSuite {

  private def scopeOf(path: String): ClusterScope.Scope =
    ClusterScope.of(path.split('/').toList.filter(_.nonEmpty))

  private def cluster(id: String): ClusterScope.Scope = ClusterScope.Scope.Cluster(ClusterId.unsafe(id))

  test("the cluster list itself is not cluster-scoped") {
    // It is about all of them. An arbitrary cluster label here would put a meaningless value on every
    // metric the dashboard aggregation produces.
    assertEquals(scopeOf("/api/v1/clusters"), ClusterScope.Scope.None)
  }

  test("a cluster path is scoped to the segment after clusters") {
    assertEquals(scopeOf("/api/v1/clusters/prod-eu"), cluster("prod-eu"))
    assertEquals(scopeOf("/api/v1/clusters/prod-eu/brokers"), cluster("prod-eu"))
    assertEquals(scopeOf("/api/v1/clusters/prod-eu/brokers/1/configs"), cluster("prod-eu"))
    assertEquals(scopeOf("/api/v1/clusters/prod-eu/log-dirs"), cluster("prod-eu"))
    assertEquals(scopeOf("/api/v1/clusters/prod-eu/refresh"), cluster("prod-eu"))
  }

  test("a malformed id is malformed, with the value that was wrong") {
    scopeOf("/api/v1/clusters/NOT A SLUG/brokers") match {
      case ClusterScope.Scope.Malformed(raw, error) =>
        assertEquals(raw, "NOT A SLUG")
        assert(error.message.nonEmpty, error.message)
      case other => fail(s"'NOT A SLUG' is not a cluster id: $other")
    }
  }

  test("a path that is not about clusters at all is not scoped") {
    assertEquals(scopeOf("/api/v1/capabilities"), ClusterScope.Scope.None)
    assertEquals(scopeOf("/api/v1/info"), ClusterScope.Scope.None)
    assertEquals(scopeOf("/health/ready"), ClusterScope.Scope.None)
  }

  test("a topic that happens to be called clusters is not a cluster scope") {
    // The rule is "the segment after the public prefix", not "anywhere a `clusters` segment appears". A
    // topic named `clusters` must not make every request about it look cluster-scoped.
    assertEquals(scopeOf("/api/v1/topics/clusters/x"), ClusterScope.Scope.None)
  }

  test("a trailing slash leaves no id, and is therefore not scoped rather than malformed") {
    // `/api/v1/clusters/` is the list endpoint with a stray slash: the empty segment is dropped by every
    // path parser between here and the browser, so treating it as a malformed id would 400 a valid request.
    assertEquals(scopeOf("/api/v1/clusters/"), ClusterScope.Scope.None)
  }

  test("a deployment under a base path is scoped the same way") {
    // The Compose stack serves KUI under `/kui`. The base path is in the request's segments and in no
    // endpoint definition, so the prefix is looked for wherever it is rather than only at the start.
    assertEquals(scopeOf("/kui/api/v1/clusters/prod-eu/brokers"), cluster("prod-eu"))
  }

  test("clusterOf answers only for a real cluster") {
    assertEquals(ClusterScope.clusterOf(cluster("prod-eu")).map(_.value), Some("prod-eu"))
    assertEquals(ClusterScope.clusterOf(ClusterScope.Scope.None), None)
    assertEquals(
      ClusterScope.clusterOf(scopeOf("/api/v1/clusters/NOT A SLUG")),
      None
    )
  }

  test("the segment is read off the contract rather than typed again") {
    assertEquals(ClusterScope.Segment, kui.cluster.contract.ClusterEndpoints.ClustersSegment)
  }
}
