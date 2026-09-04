package kui.observability

import munit.FunSuite

/** That the metric names in the code are the ones the plan promised.
  *
  * This suite is the contract between `PLAN.md` §30 plus `ARCHITECTURE.md` §13 and what KUI
  * actually emits. The expected list below is written out in full rather than derived from
  * `MetricNames.all`, which is the entire point: a name that changes has to be changed in two
  * places, and the second place is a test whose diff a reviewer reads.
  */
final class MetricNamesSuite extends FunSuite {

  /** Copied from PLAN §30, then the `ARCHITECTURE.md` §13 additions, in that order. */
  private val expected = List(
    "kui.http.server.duration",
    "kui.upstream.duration",
    "kui.upstream.circuit.state",
    "kui.kafka.admin.duration",
    "kui.kafka.consume.records",
    "kui.kafka.consume.bytes",
    "kui.cache.hits",
    "kui.cache.misses",
    "kui.capability.state",
    // ARCHITECTURE.md §13 additions
    "kui.stream.events",
    "kui.stream.active",
    "kui.cursor.rejected",
    "kui.principal.rejected",
    "kui.config.version",
    "kui.cluster.profile.fetch",
    "kui.cluster.profile.subscribed"
  )

  test("the list matches PLAN §30 and ARCHITECTURE.md §13, exactly and in order") {
    assertEquals(MetricNames.all, expected)
  }

  test("no name appears twice") {
    assertEquals(MetricNames.all.distinct, MetricNames.all)
  }

  test("every name is in the kui namespace") {
    assert(MetricNames.all.forall(_.startsWith("kui.")), MetricNames.all.toString)
  }

  test("every name is lowercase and dot-separated, so no dashboard has to guess the spelling") {
    MetricNames.all.foreach { name =>
      assertEquals(name, name.toLowerCase, clue = name)
      assert(name.matches("^[a-z0-9.]+$"), name)
    }
  }

  test("no attribute key appears twice, and none is a metric name by accident") {
    assertEquals(MetricNames.Attr.all.distinct, MetricNames.Attr.all)
    assertEquals(MetricNames.Attr.all.toSet.intersect(MetricNames.all.toSet), Set.empty[String])
  }

  test("an upstream outcome has one spelling, and it round-trips") {
    assertEquals(
      UpstreamOutcome.values.toList.map(_.wire),
      List("success", "client_error", "server_error", "timeout", "circuit_open", "unreachable")
    )
    UpstreamOutcome.values.foreach { outcome =>
      assertEquals(UpstreamOutcome.fromWire(outcome.wire), Some(outcome))
    }
  }

  test("a status becomes the outcome an operator would group by") {
    assertEquals(UpstreamOutcome.ofStatus(200), UpstreamOutcome.Success)
    assertEquals(UpstreamOutcome.ofStatus(204), UpstreamOutcome.Success)
    assertEquals(UpstreamOutcome.ofStatus(404), UpstreamOutcome.ClientError)
    assertEquals(UpstreamOutcome.ofStatus(429), UpstreamOutcome.ClientError)
    assertEquals(UpstreamOutcome.ofStatus(500), UpstreamOutcome.ServerError)
    assertEquals(UpstreamOutcome.ofStatus(503), UpstreamOutcome.ServerError)
  }
}
