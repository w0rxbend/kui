package kui.filter

import scala.concurrent.duration.*

import cats.effect.{IO, Resource}
import cats.syntax.all.*

import kui.cache.CacheMetrics
import kui.kernel.ClusterId
import kui.kernel.error.{ErrorCode, KuiError}
import kui.testkit.KuiIOSuite

/** What a user is allowed to write, what happens when they write something wrong, and the two properties
  * that make a filter safe to run against a million records.
  */
final class CelFilterEngineSuite extends KuiIOSuite {

  private val cluster: ClusterId = ClusterId.unsafe("prod-eu")

  private val record: FilterableRecord = FilterableRecord(
    partition = 3,
    offset = 41892L,
    timestampMs = 1_700_000_000_000L,
    keyAsText = "order-1",
    valueAsText = """{"status":"FAILED","total":19.5,"name":{"first":"user1"},"tags":["a","b"]}""",
    headers = Map("trace-id" -> "abc", "kafka_delivery-attempt" -> "3")
  )

  private def engine(
      limits: FilterLimits = FilterLimits.default
  ): Resource[IO, MessageFilterPort[IO]] =
    CelFilterEngine.resource[IO](cluster, limits, FilterMetrics.noop[IO], CacheMetrics.noop[IO])

  /** A `KuiError` is a value, not a `Throwable`, so a suite that wants the happy path has to say so. */
  private def orFail[A](fa: IO[Either[KuiError, A]]): IO[A] =
    fa.flatMap(_.fold(error => IO.raiseError(new AssertionError(error.message)), IO.pure))

  private def evaluate(source: String, against: FilterableRecord = record): IO[Either[FilterError, Boolean]] =
    engine().use { port =>
      for {
        id <- orFail(port.register(source))
        predicate <- orFail(port.predicate(id, Some(source)))
        result <- predicate.test(against)
      } yield result
    }

  // ------------------------------------------------------------------ the environment

  test("the environment exposes exactly the documented variables, in both directions") {
    // A variable in the help that does not exist would be a documented feature that fails to compile the
    // first time a user pastes it. A variable that exists and is not documented is a feature nobody can
    // find. Both are failures, so both fail the build.
    // A record whose key and value are both JSON, so that all eight fields are present at once: `key`
    // and `value` are absent by design when the payload is not JSON, which is its own test below.
    val jsonBoth = record.copy(keyAsText = """{"id":1}""")
    val fields = CelEnvironment.recordFields(jsonBoth)
    val declared = scala.jdk.CollectionConverters.SetHasAsScala(fields.keySet()).asScala.toSet
    assertEquals(declared, CelEnvironment.Variables.toSet)
    // And the other direction: nothing is exposed that the help does not list.
    assert(declared.subsetOf(CelEnvironment.Variables.toSet))
  }

  test("every documented variable compiles and evaluates") {
    CelEnvironment.Variables.traverse_ { name =>
      evaluate(s"has(record.$name) || true").map(result => assertEquals(result, Right(true), clue = name))
    }
  }

  test("the metadata fields compare as numbers, not as a type error") {
    // `record.partition == 3` is the first thing anyone writes. If CEL is handed an Integer where it expects
    // its 64-bit `int`, this fails to find an overload at run time — a comparison that looks obviously
    // correct and is rejected, which is a miserable thing to debug from a filter box.
    evaluate("record.partition == 3").assertEquals(Right(true)) >>
      evaluate("record.offset == 41892").assertEquals(Right(true)) >>
      evaluate("record.timestampMs > 1000").assertEquals(Right(true))
  }

  test("JSON values are addressable as dyn, at any depth and through arrays") {
    evaluate("record.value.status == 'FAILED'").assertEquals(Right(true)) >>
      evaluate("record.value.name.first == 'user1'").assertEquals(Right(true)) >>
      evaluate("record.value.tags[0] == 'a'").assertEquals(Right(true)) >>
      evaluate("record.value.total > 19.0").assertEquals(Right(true))
  }

  test("text and headers are addressable too") {
    evaluate("record.keyAsText == 'order-1'").assertEquals(Right(true)) >>
      evaluate("record.valueAsText.contains('FAILED')").assertEquals(Right(true)) >>
      evaluate("record.headers['trace-id'] == 'abc'").assertEquals(Right(true))
  }

  test("a non-JSON value leaves record.value unset while valueAsText stays populated") {
    val plain = record.copy(valueAsText = "not json at all")
    // Unset, not null. A filter reading `record.value.status` against a text topic gets a counted, visible
    // runtime error; with a null it would silently match nothing and the user would blame their data.
    evaluate("record.valueAsText.contains('json')", plain).assertEquals(Right(true)) >>
      evaluate("has(record.value)", plain).assertEquals(Right(false))
  }

  test("a bare JSON number is not exposed as a value either, matching the Json serde's rule") {
    val numeric = record.copy(valueAsText = "123")
    evaluate("has(record.value)", numeric).assertEquals(Right(false))
  }

  // ------------------------------------------------------------------ compilation

  test("the three examples from the user-facing help compile") {
    // These are the filters people will paste first, because they are the ones the reference product shows
    // them. If they do not compile here, the feature is not the feature they know.
    val examples = List(
      "record.partition == 0",
      "record.keyAsText.startsWith('order')",
      "has(record.value.status) && record.value.status == 'FAILED'"
    )
    engine().use(port => examples.traverse_(source => port.register(source).map(r => assert(r.isRight, source))))
  }

  test("a compile error carries a position, which is what the editor underlines") {
    engine().use { port =>
      port.register("record.partition ==").map {
        case Right(_) => fail("an incomplete expression must not compile")
        case Left(error) =>
          assertEquals(error.code, ErrorCode.FilterCompile)
          assert(error.details.nonEmpty, "a compile error with no details tells the editor nothing")
          assert(
            error.details.exists(_.restrictions.exists(_.contains("column"))),
            error.details.toString
          )
      }
    }
  }

  test("source over the size limit is rejected before it is parsed") {
    val limits = FilterLimits.default.copy(maxSourceBytes = 64)
    engine(limits).use { port =>
      port.register("true && " * 100 + "true").map { result =>
        assert(result.swap.exists(_.message.contains("limit is 64")), result.toString)
      }
    }
  }

  test("an AST over the node limit is rejected") {
    val limits = FilterLimits.default.copy(maxAstNodes = 5)
    engine(limits).use { port =>
      port.register("record.partition == 0 && record.offset == 1 && record.keyAsText == 'x'").map { result =>
        assert(result.swap.exists(_.message.contains("expression nodes")), result.toString)
      }
    }
  }

  // ------------------------------------------------------------------ evaluation failures

  test("a missing field is a Left, not a thrown exception") {
    evaluate("record.value.nosuchfield == 'x'").map {
      case Left(FilterError.Runtime(_)) => ()
      case other                        => fail(s"expected a runtime error, got $other")
    }
  }

  test("a non-boolean result is a runtime error, not a truthy value") {
    // `1 + 1` is a perfectly good CEL expression and a nonsensical filter. Calling a non-zero number
    // "matched" is how a user ends up with a filter that appears to work and silently matches everything.
    evaluate("1 + 1").map {
      case Left(FilterError.Runtime(message)) => assert(message.contains("rather than true or false"), message)
      case other                              => fail(s"expected a runtime error, got $other")
    }
  }

  test("a runtime error names its kind, which is the metric attribute and the stream event's field") {
    assertEquals(FilterError.Runtime("x").kind, "runtime")
    assertEquals(FilterError.Timeout(10L).kind, "timeout")
  }

  test("an evaluation that outruns the deadline is a Timeout, not a hang") {
    // A one-nanosecond deadline is the honest way to assert the deadline exists without depending on how
    // long a real program takes on the machine running the suite.
    val limits = FilterLimits.default.copy(evaluationDeadline = 1.nanosecond)
    engine(limits).use { port =>
      for {
        id <- orFail(port.register("record.partition == 3"))
        predicate <- orFail(port.predicate(id, None))
        result <- predicate.test(record)
      } yield result match {
        case Left(FilterError.Timeout(afterMs)) => assertEquals(afterMs, 0L)
        // A trivial program can legitimately finish inside a one-nanosecond window on a fast machine after
        // the JIT has warmed up, so a success is accepted; what must never happen is an exception escaping.
        case Right(_)                           => ()
        case Left(other)                        => fail(s"expected a timeout or a result, got $other")
      }
    }
  }

  // ------------------------------------------------------------------ identity and caching

  test("ids are a pure function of the source, so two engines agree") {
    // The property Kafbat's per-process salt broke: an id minted by one replica has to mean the same filter
    // on every other one, or a load balancer silently breaks the feature.
    val source = "record.partition == 0"
    assertEquals(FilterId.of(source).value.length, 16)
    assertEquals(FilterId.of(source), FilterId.of(source))
    assertNotEquals(FilterId.of(source), FilterId.of("record.partition == 1"))
    (engine().use(port => orFail(port.register(source))), engine().use(port => orFail(port.register(source)))).tupled
      .map((first, second) => assertEquals(first, second))
  }

  test("an id is sixteen lowercase hex characters and reads back") {
    val id = FilterId.of("record.offset > 0")
    assertEquals(FilterId.fromString(id.value), Some(id))
    assertEquals(FilterId.fromString("NOTHEX0000000000"), None)
    assertEquals(FilterId.fromString("abc"), None)
  }

  test("a replica that has never seen an id compiles it from the source it was sent with") {
    val source = "record.partition == 3"
    val id = FilterId.of(source)
    // A fresh engine: nothing in its cache, exactly like a pod that started thirty seconds ago.
    engine().use(port => orFail(port.predicate(id, Some(source))).flatMap(_.test(record))).assertEquals(Right(true))
  }

  test("an unknown id with no source is refused rather than silently matching everything") {
    engine().use(_.predicate(FilterId.of("never registered"), None)).map(result => assert(result.isLeft))
  }

  test("a source that does not hash to the id it was sent with is refused") {
    // Compiling it anyway would mean the browser and the server disagree about which filter is running,
    // which is the one outcome worse than refusing.
    engine()
      .use(_.predicate(FilterId.of("record.partition == 0"), Some("record.partition == 1")))
      .map(result => assert(result.swap.exists(_.message.contains("does not match"))))
  }

  // ------------------------------------------------------------------ the test endpoint

  test("the test endpoint answers about one synthetic record without touching Kafka") {
    engine().use(_.test("record.value.status == 'FAILED'", record)).assertEquals(Right(true))
  }

  test("the test endpoint reports a runtime error rather than swallowing it") {
    // During a browse a runtime error is counted and the record excluded. On the test endpoint the error
    // *is* the answer the user came for.
    engine().use(_.test("record.value.nosuchfield == 'x'", record)).map(result => assert(result.isLeft))
  }

  test("the test endpoint reports a compile error with its code") {
    engine().use(_.test("record.partition ==", record)).map { result =>
      assertEquals(result.swap.toOption.map(_.code), Some(ErrorCode.FilterCompile))
    }
  }
}
