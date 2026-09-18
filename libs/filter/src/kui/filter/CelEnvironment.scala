package kui.filter

import scala.jdk.CollectionConverters.*

import dev.cel.common.CelAbstractSyntaxTree
import dev.cel.common.ast.CelExpr
import dev.cel.common.navigation.CelNavigableAst
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
  * | JSON value nodes       | 50 000                      | bounds the work of parsing `record.key`/`record.value`, since the deadline above cannot (see below)     |
  * | compiled-program cache | 10 000 entries, 1 hour      | ADR-017                                                                                                 |
  * | regex engine           | re2j, through CEL's default | linear time, so a regex filter cannot backtrack the service to a halt                                   |
  *
  * All six are `kui.message.filter.*` configuration keys with these defaults.
  *
  * The per-record deadline is enforced by `Sync[F].interruptible` around the whole evaluation, JSON parsing
  * included (`CelFilterEngine`'s `program.test`). `interruptible` only requests `Thread.interrupt()`; it
  * cannot stop code that never checks for it, and neither `io.circe`'s parser nor [[toJava]]'s walk of the
  * parsed tree does. A producer controls the bytes of `record.value`, so without a budget of its own, one
  * pathological record — a very large or very deeply nested JSON value — spends CPU on its thread for as
  * long as the walk takes regardless of the ten-millisecond deadline, and a burst of such records from
  * concurrent browses can occupy the whole interruptible pool while every deadline it reports is a fiction.
  * The JSON value node limit bounds that walk directly, independent of whether the interrupt is honored;
  * [[toJava]] also caps recursion depth, since a narrow, very deep structure can exhaust the JVM stack long
  * before it exhausts the node budget.
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
  private val options: dev.cel.common.CelOptions =
    dev.cel.common.CelOptions.current().enableHeterogeneousNumericComparisons(true).build()

  def compiler: CelCompiler =
    CelCompilerFactory
      .standardCelCompilerBuilder()
      .setOptions(options)
      .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
      .addVar(RecordVariable, RecordType)
      .build()

  def runtime: CelRuntime = CelRuntimeFactory.standardCelRuntimeBuilder().setOptions(options).build()

  /** The activation a program is evaluated against: one entry, `record`, holding [[recordFields]].
    *
    * Separate from `recordFields` because the program's variable is `record` and its *contents* are what
    * every test and every piece of documentation talks about. Collapsing the two is exactly the mistake that
    * makes CEL return an unknown set instead of a boolean, which surfaces as "the filter returned something
    * that is not true or false" and reads like a user error rather than a wiring one.
    */
  def activation(
      record: FilterableRecord,
      maxJsonNodes: Int = DefaultMaxJsonNodes,
      neededDynamicFields: Set[String] = AllDynamicFields
  ): java.util.Map[String, Object] =
    java.util.Collections.singletonMap(RecordVariable, recordFields(record, maxJsonNodes, neededDynamicFields))

  /** One record, as the fields of `record`.
    *
    * Every number is a `java.lang.Long` because CEL's `int` is 64-bit and handing it an `Integer` makes
    * `record.partition == 0` fail to find an overload — a comparison that looks obviously correct and is
    * rejected at run time, which is the worst kind of failure to debug from a filter box.
    *
    * `neededDynamicFields` skips `asDynamic` for `key`/`value` when the caller already knows the compiled
    * filter never reads them (`record.partition == 0` being the obvious one): parsing and converting a
    * record's decoded text to JSON for a filter that cannot possibly look at it is pure cost, and — since
    * that text is producer-controlled — pure attack surface too. Defaulting to [[AllDynamicFields]] keeps
    * every direct caller (tests, the warm-up call) exactly as correct as before this parameter existed.
    */
  def recordFields(
      record: FilterableRecord,
      maxJsonNodes: Int = DefaultMaxJsonNodes,
      neededDynamicFields: Set[String] = AllDynamicFields
  ): java.util.Map[String, Object] = {
    val base = Map[String, Object](
      "partition" -> java.lang.Long.valueOf(record.partition.toLong),
      "offset" -> java.lang.Long.valueOf(record.offset),
      "timestampMs" -> java.lang.Long.valueOf(record.timestampMs),
      "keyAsText" -> record.keyAsText,
      "valueAsText" -> record.valueAsText,
      "headers" -> record.headers.asJava
    )
    val parsed = List(
      Option.when(neededDynamicFields.contains("key"))("key" -> asDynamic(record.keyAsText, maxJsonNodes)),
      Option.when(neededDynamicFields.contains("value"))("value" -> asDynamic(record.valueAsText, maxJsonNodes))
    ).flatten.collect { case (name, Some(value)) => name -> value }

    (base ++ parsed).asJava
  }

  /** `{"key", "value"}` — the subset of `record`'s fields that are ever worth skipping, since every other
    * field is cheap to compute unconditionally.
    */
  val AllDynamicFields: Set[String] = Set("key", "value")

  /** Which of [[AllDynamicFields]] a compiled filter can actually observe, computed once per compiled
    * program rather than once per record.
    *
    * Deliberately over-inclusive rather than exact: it flags a field as needed on any `record.key`/
    * `record.value` **select** (including the one `has()` expands to, and one nested arbitrarily deep, e.g.
    * `record.value.items[0]`) and on any `record["key"]`/`record["value"]` **index** call, without checking
    * that the select's or index's operand chain actually resolves back to the top-level `record` variable.
    * A false positive costs a conversion nobody reads, which [[recordFields]] already paid on every record
    * before this method existed; a false negative would silently make a field a live filter asked for
    * disappear, which is a far worse failure to ship than the perf win is worth. Uses `CelNavigableAst`, the
    * same navigation `CelFilterEngine.compile` already uses to count AST nodes.
    */
  private[filter] def referencedDynamicFields(ast: CelAbstractSyntaxTree): Set[String] = {
    val fieldSelects = CelNavigableAst
      .fromAst(ast)
      .getRoot
      .allNodes()
      .iterator()
      .asScala
      .flatMap { node =>
        node.getKind match {
          case CelExpr.ExprKind.Kind.SELECT =>
            Some(node.expr().select().field())
          case CelExpr.ExprKind.Kind.CALL =>
            val call = node.expr().call()
            if call.function() == "_[_]" || call.function() == "_[?_]" then
              call.args().asScala.collectFirst {
                case arg
                    if arg.getKind == CelExpr.ExprKind.Kind.CONSTANT &&
                      arg.constant().getKind == dev.cel.common.ast.CelConstant.Kind.STRING_VALUE =>
                  arg.constant().stringValue()
              }
            else None
          case _ => None
        }
      }
      .toSet

    fieldSelects.intersect(AllDynamicFields)
  }

  /** The default of the `JSON value nodes` limit above, used wherever a caller does not have a
    * [[kui.filter.FilterLimits]] to hand (tests, and the engine's warm-up call).
    */
  val DefaultMaxJsonNodes: Int = 50000

  /** Recursion beyond this depth is refused regardless of how much of the node budget remains, because a
    * narrow structure nested this deep — `[[[[...]]]]` — costs almost nothing in node count but one JVM
    * stack frame per level, and a producer can make the text of such a payload arbitrarily small.
    */
  private val MaxJsonDepth: Int = 500

  /** Thrown by [[toJava]] when it exceeds its node or depth budget, and caught nowhere but [[asDynamic]] —
    * an oversized or pathologically nested value is treated exactly like text that is not JSON at all,
    * which is already a well-defined, tested outcome (`record.value` absent, not null).
    */
  private object JsonBudgetExceeded extends RuntimeException(null, null, false, false)

  /** A JSON payload's remaining walk budget: a running count of nodes and the current recursion depth,
    * shared across one call to [[toJava]] and every node it recurses into.
    */
  private final class JsonBudget(initialNodes: Int) {
    private var nodesLeft: Int = initialNodes
    private var depth: Int = 0

    def spend(): Unit = {
      if nodesLeft <= 0 then throw JsonBudgetExceeded
      nodesLeft -= 1
    }

    def descend(): Unit = {
      depth += 1
      if depth > MaxJsonDepth then throw JsonBudgetExceeded
    }

    def ascend(): Unit = depth -= 1
  }

  /** JSON text as the Java values CEL understands, or `None` when the text is not JSON, or when converting
    * it would exceed `maxNodes` nodes or [[MaxJsonDepth]] levels of nesting.
    *
    * Only objects and arrays count, matching the `Json` serde's rule: a payload of `123` is a number, and
    * exposing it as `record.value` would let `record.value.status` fail in a way that reads like a missing
    * field rather than like a payload that has no fields at all. A payload that blows the budget gets the
    * same treatment on purpose (see the class doc's `## The limits` section): the alternative is a filter
    * referencing `record.value` succeeding on every record but the rare oversized one, which is a much
    * harder failure to notice than a runtime error that is counted and shown.
    */
  private[filter] def asDynamic(text: String, maxNodes: Int = DefaultMaxJsonNodes): Option[Object] =
    parser.parse(text).toOption.filter(json => json.isObject || json.isArray).flatMap { json =>
      try Some(toJava(json, new JsonBudget(maxNodes)))
      catch { case JsonBudgetExceeded => None }
    }

  private def toJava(json: Json, budget: JsonBudget): Object = {
    budget.spend()
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
      jsonArray = values => {
        budget.descend()
        try values.map(toJava(_, budget)).asJava
        finally budget.ascend()
      },
      jsonObject = obj => {
        budget.descend()
        try obj.toMap.map((key, value) => key -> toJava(value, budget)).asJava
        finally budget.ascend()
      }
    )
  }
}
