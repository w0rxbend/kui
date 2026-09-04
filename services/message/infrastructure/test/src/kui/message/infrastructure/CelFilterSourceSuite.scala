package kui.message.infrastructure

import java.time.Instant

import scala.concurrent.duration.DurationInt

import cats.effect.{IO, Resource}

import kui.cache.CacheMetrics
import kui.filter.{CelFilterEngine, FilterLimits, FilterMetrics, MessageFilterPort}
import kui.kernel.error.ErrorCode
import kui.kernel.serde.{PayloadKind, SerdeName}
import kui.kernel.{ClusterId, Offset, PartitionId}
import kui.message.domain.ports.{FilterSample, FilterVerdict}
import kui.message.domain.{Decoded, DecodedRecord, FilterRef, RenderedHeader, TimestampType}
import kui.testkit.KuiIOSuite

/** The seam between the message service and the CEL engine, over a real engine.
  *
  * This is the file MS-007 was missing: `libs/filter` was built, tested and green, and no module depended on
  * it. Everything here would have passed vacuously as long as that remained true, which is why the assertions
  * are about the *join* — that a browse's `DecodedRecord` reaches an expression as the fields the expression
  * names, and that each of the three verdicts comes out the far side as itself.
  */
final class CelFilterSourceSuite extends KuiIOSuite {

  private val cluster: ClusterId = ClusterId.unsafe("local")
  private val elsewhere: ClusterId = ClusterId.unsafe("not-configured")

  /** The production deadline is ten milliseconds, which is right for a record in a browse and wrong for the
    * first CEL evaluation in a JVM running the whole build in parallel: that one pays for class loading and
    * would intermittently answer `Timeout` instead of the verdict under test.
    */
  private val generous: FilterLimits = FilterLimits.default.copy(evaluationDeadline = 30.seconds)

  private def engine: Resource[IO, MessageFilterPort[IO]] =
    CelFilterEngine.resource[IO](cluster, generous, FilterMetrics.noop[IO], CacheMetrics.noop[IO])

  private def source: Resource[IO, CelFilterSource[IO]] =
    engine.map(port => new CelFilterSource[IO](Map(cluster -> port)))

  private def record(value: String, key: String = "order-1"): DecodedRecord =
    DecodedRecord(
      partition = PartitionId.unsafe(3),
      offset = Offset.unsafe(41892L),
      timestamp = Instant.ofEpochMilli(1_700_000_000_000L),
      timestampType = TimestampType.CreateTime,
      key = Decoded(key, PayloadKind.Text, SerdeName.unsafe("String"), Map.empty),
      value = Decoded(value, PayloadKind.Json, SerdeName.unsafe("Json"), Map.empty),
      headers = List(RenderedHeader("trace-id", "abc")),
      keySize = 7,
      valueSize = value.length,
      headersSize = 3,
      decodeErrors = Nil
    )

  private val paid: DecodedRecord = record("""{"status":"PAID","total":19.5}""")
  private val failed: DecodedRecord = record("""{"status":"FAILED","total":3.0}""")

  /** Registers an expression and evaluates it against a record, the way a browse does. */
  private def verdict(expression: String, against: DecodedRecord): IO[FilterVerdict] =
    source.use { filters =>
      for {
        id <- filters.register(cluster, expression).map(_.getOrElse(fail("the expression must compile")))
        reference = FilterRef.of(id, Some(expression)).getOrElse(fail("the id must be well formed"))
        compiled <- filters.compile(cluster, reference).map(_.getOrElse(fail("the filter must compile")))
        answer <- compiled.test(against)
      } yield answer
    }

  test("an expression written against the decoded value decides which records a browse delivers") {
    // The join under test: `record.value` is the *decoded* document, so a person writing
    // `record.value.status` means the JSON they can see on screen rather than the bytes underneath it.
    for {
      matched <- verdict("record.value.status == 'PAID'", paid)
      rejected <- verdict("record.value.status == 'PAID'", failed)
    } yield {
      assertEquals(matched, FilterVerdict.Matched)
      assertEquals(rejected, FilterVerdict.DidNotMatch)
    }
  }

  test("the record's identity and its headers reach the expression too") {
    for {
      byPartition <- verdict("record.partition == 3 && record.offset == 41892", paid)
      byHeader <- verdict("record.headers['trace-id'] == 'abc'", paid)
      byKey <- verdict("record.keyAsText == 'order-1'", paid)
    } yield {
      assertEquals(byPartition, FilterVerdict.Matched)
      assertEquals(byHeader, FilterVerdict.Matched)
      assertEquals(byKey, FilterVerdict.Matched)
    }
  }

  test("an expression that throws on a record is neither a match nor a non-match") {
    // ADR-017's three-way verdict, checked through the adapter. Counting this as "did not match" is how a
    // broken filter passes for an empty topic, and the user concludes their data is missing.
    verdict("record.value.nosuchfield == 'x'", paid).map {
      case FilterVerdict.Failed(reason) => assert(reason.nonEmpty, "the failure explains nothing")
      case other => fail(s"expected a failure verdict, got $other")
    }
  }

  test("an expression that does not compile is refused with the position in it") {
    source
      .use(_.register(cluster, "record.value.status =="))
      .map {
        case Left(error) =>
          assertEquals(error.code, ErrorCode.FilterCompile)
          assert(error.details.nonEmpty, "a compile failure that names no position underlines nothing")
        case Right(id) => fail(s"a broken expression registered as $id")
      }
  }

  test("registering the same expression twice gives the same id") {
    // The id is a content hash with no per-process salt, which is what lets a browse be re-run, a link be
    // shared, and a second replica serve a filter the first one registered.
    source
      .use(filters =>
        for {
          first <- filters.register(cluster, "record.value.total > 10.0")
          second <- filters.register(cluster, "record.value.total > 10.0")
        } yield assertEquals(first, second)
      )
  }

  test("a filter id no KUI could have minted is refused rather than compiled") {
    source
      .use(filters =>
        filters.compile(cluster, FilterRef.of("0123456789abcdef", None).getOrElse(fail("well formed")))
      )
      .map(result => assert(result.isLeft, "an id nothing registered was accepted"))
  }

  test("a cluster with no engine refuses a filter rather than matching everything") {
    // The one behaviour that must never be a silent success. A filter that is quietly ignored shows the
    // user a million records and gives them no way to tell that their narrowing did nothing.
    source
      .use(_.register(elsewhere, "record.partition == 0"))
      .map {
        case Left(error) => assertEquals(error.code, ErrorCode.Unsupported)
        case Right(id) => fail(s"a cluster with no engine registered $id")
      }
  }

  test("the test path answers about a record the caller supplied, without a compiled filter") {
    val sample =
      FilterSample(
        partition = 0,
        offset = 7L,
        timestampMs = 1L,
        keyAsText = "k",
        valueAsText = """{"status":"PAID"}""",
        headers = Map.empty
      )

    source
      .use(_.check(cluster, "record.value.status == 'PAID'", sample))
      .map(result => assertEquals(result, Right(FilterVerdict.Matched)))
  }
}
