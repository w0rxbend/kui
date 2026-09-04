package kui.filter

import scala.jdk.CollectionConverters.*

import dev.cel.common.types.{MapType, SimpleType}
import dev.cel.compiler.{CelCompiler, CelCompilerFactory}
import dev.cel.parser.CelStandardMacro
import dev.cel.runtime.{CelRuntime, CelRuntimeFactory}
import io.circe.{parser, Json}

/** The variables a smart filter can talk about, and how one record becomes them.
  *
  * The list is **Kafbat's, unchanged** (ADR-017). A user migrating from Kafbat has filters written down — in
  * a runbook, in a chat message, in their head — and a KUI that renamed `keyAsText` to `keyText` would make
  * every one of them a compile error for no benefit. `CelEnvironmentSuite` asserts the list in both
  * directions: a variable documented but not declared, or declared but not documented, fails the build.
  *
  * ## The variables
  *
  * | Name                 | Type                | What it is                                                |
  * |:---------------------|:--------------------|:----------------------------------------------------------|
  * | `record.partition`   | int                 | the partition the record was read from                    |
  * | `record.offset`      | int                 | its offset within that partition                          |
  * | `record.timestampMs` | int                 | its timestamp, in milliseconds since the epoch            |
  * | `record.keyAsText`   | string              | the key, decoded by the key serde                         |
  * | `record.valueAsText` | string              | the value, decoded by the value serde                     |
  * | `record.headers`     | map(string, string) | header names to their rendered values                     |
  * | `record.key`         | dyn                 | the key parsed as JSON — **absent** when it is not JSON   |
  * | `record.value`       | dyn                 | the value parsed as JSON — **absent** when it is not JSON |
  *
  * `key` and `value` are absent rather than null when the payload is not JSON, and that is a decision worth
  * defending. A filter reading `record.value.status` against a topic of plain text gets a runtime error,
  * which is counted and shown; if the field were null, the same filter would silently match nothing, and the
  * user would conclude their data was wrong rather than their filter.
  *
  * ## The limits
  *
  * | Limit                  | Default                     | Why                                                                                                     |
  * |:-----------------------|:----------------------------|:--------------------------------------------------------------------------------------------------------|
  * | source length          | 8 KiB                       | a CEL predicate longer than that is a program, not a filter                                             |
  * | AST nodes              | 1 000                       | the cheap half of ADR-017's complexity limit, checked after parsing and before caching                  |
  * | per-record deadline    | 10 ms                       | at 20 000 records a page it bounds the worst case to 200 s, which the browse deadline then cuts to 60 s |
  * | compiled-program cache | 10 000 entries, 1 hour      | ADR-017                                                                                                 |
  * | regex engine           | re2j, through CEL's default | linear time, so a regex filter cannot backtrack the service to a halt                                   |
  *
  * All five are `kui.message.filter.*` configuration keys with these defaults.
  */
object CelEnvironment {

  /** Every field of `record`, in the order the help modal lists them. */
  val Variables: List[String] = List(
    "partition",
    "offset",
    "timestampMs",
    "keyAsText",
    "valueAsText",
    "headers",
    "key",
    "value"
  )

  /** The one top-level variable. Everything else hangs off it, which is what makes a filter read like a
    * sentence about a record rather than like a function call.
    */
  val RecordVariable: String = "record"

  /** `record` is a `map(string, dyn)` rather than a declared message type.
    *
    * A message type would give better compile-time checking and would require a protobuf descriptor for a
    * shape that is half dynamic anyway: `record.value` is whatever JSON the user's producer writes, and no
    * descriptor can describe that. The trade is deliberate — `record.nosuchfield` is a runtime error rather
    * than a compile error, and a runtime error on a filter is already a first-class, counted, non-fatal
    * outcome.
    */
  private val RecordType = MapType.create(SimpleType.STRING, SimpleType.DYN)

  /** A compiler with the standard macros (`has`, `all`, `exists`, `map`, `filter`) and one variable.
    *
    * The standard macros are the ones the help modal shows examples of, so leaving them out would make the
    * documented examples fail to compile.
    */
  def compiler: CelCompiler =
    CelCompilerFactory
      .standardCelCompilerBuilder()
      .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
      .addVar(RecordVariable, RecordType)
      .build()

  def runtime: CelRuntime = CelRuntimeFactory.standardCelRuntimeBuilder().build()

  /** The activation a program is evaluated against: one entry, `record`, holding [[recordFields]].
    *
    * Separate from `recordFields` because the program's variable is `record` and its *contents* are what
    * every test and every piece of documentation talks about. Collapsing the two is exactly the mistake that
    * makes CEL return an unknown set instead of a boolean, which surfaces as "the filter returned something
    * that is not true or false" and reads like a user error rather than a wiring one.
    */
  def activation(record: FilterableRecord): java.util.Map[String, Object] =
    java.util.Collections.singletonMap(RecordVariable, recordFields(record))

  /** One record, as the fields of `record`.
    *
    * Every number is a `java.lang.Long` because CEL's `int` is 64-bit and handing it an `Integer` makes
    * `record.partition == 0` fail to find an overload — a comparison that looks obviously correct and is
    * rejected at run time, which is the worst kind of failure to debug from a filter box.
    */
  def recordFields(record: FilterableRecord): java.util.Map[String, Object] = {
    val base = Map[String, Object](
      "partition" -> java.lang.Long.valueOf(record.partition.toLong),
      "offset" -> java.lang.Long.valueOf(record.offset),
      "timestampMs" -> java.lang.Long.valueOf(record.timestampMs),
      "keyAsText" -> record.keyAsText,
      "valueAsText" -> record.valueAsText,
      "headers" -> record.headers.asJava
    )
    val parsed = List(
      "key" -> asDynamic(record.keyAsText),
      "value" -> asDynamic(record.valueAsText)
    ).collect { case (name, Some(value)) => name -> value }

    (base ++ parsed).asJava
  }

  /** JSON text as the Java values CEL understands, or `None` when the text is not JSON.
    *
    * Only objects and arrays count, matching the `Json` serde's rule: a payload of `123` is a number, and
    * exposing it as `record.value` would let `record.value.status` fail in a way that reads like a missing
    * field rather than like a payload that has no fields at all.
    */
  private[filter] def asDynamic(text: String): Option[Object] =
    parser.parse(text).toOption.filter(json => json.isObject || json.isArray).map(toJava)

  private def toJava(json: Json): Object =
    json.fold(
      // CEL's own representation of null. A Scala `null` would work by accident and would also be the one
      // value in this file that `-Wunused`'s stricter sibling, the pure-module scalafix rule set, forbids.
      jsonNull = com.google.protobuf.NullValue.NULL_VALUE,
      jsonBoolean = java.lang.Boolean.valueOf(_),
      // CEL has `int`, `uint` and `double` and no arbitrary-precision number. A JSON number that is a
      // whole number becomes an `int` so that `record.value.count == 3` works the way anyone would expect;
      // everything else becomes a `double`.
      jsonNumber = number =>
        number.toLong.fold[Object](java.lang.Double.valueOf(number.toDouble))(java.lang.Long.valueOf),
      jsonString = identity,
      jsonArray = values => values.map(toJava).asJava,
      jsonObject = obj => obj.toMap.map((key, value) => key -> toJava(value)).asJava
    )
}
