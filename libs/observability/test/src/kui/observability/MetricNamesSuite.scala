package kui.observability

import java.nio.file.{Files, Path}

import munit.FunSuite

/** That the metric names in the code are the ones the documentation promises.
  *
  * This suite is the contract between `ARCHITECTURE.md` §13 and what KUI actually emits. The expected list
  * below is written out in full rather than derived from `MetricNames.all`, which is the entire point: a name
  * that changes has to be changed in two places, and the second place is a test whose diff a reviewer reads.
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
    "kui.cluster.profile.subscribed",
    // M3: the serde layer, the smart filters and the masking engine
    "kui.serde.deserialize.failures",
    "kui.serde.autodetected",
    "kui.serde.serialize.failures",
    "kui.serde.registry.built",
    "kui.serde.registry.requests",
    "kui.serde.registry.up",
    "kui.filter.compile",
    "kui.filter.evaluate.duration",
    "kui.filter.errors",
    "kui.masking.applied"
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

  test("the operator's metric table names exactly the metrics this build declares") {
    // W10-04/F7, closed by W10-A2. `docs/operations/*.md` is what an operator reads to build a dashboard,
    // and not one line of it was read by anything: the verification pass deleted `masking.md` outright and
    // `./scripts/run-tests.sh` stayed green at 4,328 cases. §Metrics of `observability.md` is the half of
    // that prose which is checkable without inventing a language — it is a list of names, and this build
    // holds the same list — so it is the half that gets a gate.
    //
    // Both directions, because they fail differently and both quietly. A name in the table that this build
    // does not emit is a panel that is empty for ever and a query nobody can debug; a name this build emits
    // that the table omits is a number no operator will ever look at. Neither shows up anywhere else: the
    // suite above compares the code with a list inside the same suite, and the document was outside both.
    //
    // Precedent for a test reading a shipped document: `TopicsConfigSuite` asserts its defaults against
    // `docs/operations/configuration.md`'s table for the same reason.
    val table = documentedMetricNames

    assertEquals(
      table.diff(MetricNames.all).filterNot(_ == EmittedButUnpinned),
      Nil,
      "docs/operations/observability.md documents a metric this build does not declare in MetricNames"
    )
    assertEquals(
      MetricNames.all.diff(table),
      Nil,
      "this build declares a metric docs/operations/observability.md's table does not name"
    )
    // The one documented exception, asserted rather than silently tolerated: the row says in its own words
    // that this series is emitted and is not in `MetricNames.all`. If it is ever added there, the filter
    // above stops being needed and this line is what says so.
    assert(
      table.contains(EmittedButUnpinned) && !MetricNames.all.contains(EmittedButUnpinned),
      s"observability.md's exception for $EmittedButUnpinned no longer matches MetricNames.all"
    )
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

  /** The series `observability.md` documents as emitted and deliberately absent from `MetricNames.all`. */
  private val EmittedButUnpinned: String = "kui.gateway.aggregation.section"

  /** Every metric named in the first column of `observability.md`'s metric table, in the order printed.
    *
    * Anchored on a row whose first cell is a backticked `kui.` name, which is the shape of that table and of
    * no other table in the file: the attribute table's first column is an attribute key, and the
    * configuration table two sections down puts its backticked setting in the *second* column.
    */
  private def documentedMetricNames: List[String] = {
    val document = Files.readString(resolve("docs/operations/observability.md"))
    val row = "^\\| `(kui\\.[a-z0-9.]+)` \\|".r

    val names = document.linesIterator.flatMap(line => row.findFirstMatchIn(line).map(_.group(1))).toList

    // A reader that quietly matched nothing would make every assertion above vacuously true, which is the
    // failure mode of every document check ever written.
    assert(names.sizeIs > 20, s"observability.md's metric table read as ${names.size} rows: $names")
    names
  }

  private def resolve(relative: String): Path = {
    val start = Path.of("").toAbsolutePath
    val root = Iterator
      .iterate(Option(start))(_.flatMap(path => Option(path.getParent)))
      .takeWhile(_.isDefined)
      .flatten
      .find(candidate => Files.exists(candidate.resolve("build.mill")))
      .getOrElse(fail(s"no build.mill above $start, so the repository root could not be found"))

    val file = root.resolve(relative)
    if Files.isRegularFile(file) then file
    else fail(s"$relative is read by this suite and does not exist")
  }
}
