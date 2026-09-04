package kui.ui.messages.table

import io.circe.Json
import io.circe.parser.parse
import munit.{FunSuite, ScalaCheckSuite}
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll

/** Generators for the two things this module has to survive: arbitrary JSON, and object keys written by
  * somebody who has never heard of a dotted path.
  */
object JsonGenerators {

  /** Keys chosen to break a naive path syntax: the separators themselves, the escape character, quotes,
    * whitespace, an empty name (which is legal JSON) and characters outside the basic multilingual plane.
    */
  val awkwardKey: Gen[String] =
    Gen.oneOf(
      Gen.const(""),
      Gen.const("a.b"),
      Gen.const("a[0]"),
      Gen.const("""back\slash"""),
      Gen.const("""quote"inside"""),
      Gen.const("  spaced  "),
      Gen.const("ключ"),
      Gen.const("🥁drum"),
      Gen.const("]["),
      Gen.alphaNumStr
    )

  val scalar: Gen[Json] =
    Gen.oneOf(
      Gen.const(Json.Null),
      Arbitrary.arbitrary[Boolean].map(Json.fromBoolean),
      Arbitrary.arbitrary[Int].map(Json.fromInt),
      Gen.asciiPrintableStr.map(Json.fromString)
    )

  /** Arbitrary JSON, sized so that ScalaCheck's own size parameter bounds the depth rather than the stack. */
  val json: Gen[Json] = Gen.sized(size => sized(size.min(6)))

  private def sized(size: Int): Gen[Json] =
    if size <= 0 then scalar
    else
      Gen.frequency(
        3 -> scalar,
        2 -> Gen.listOfN(size.min(4), sized(size / 2)).map(Json.fromValues),
        3 -> Gen
          .listOfN(size.min(4), Gen.zip(awkwardKey, sized(size / 2)))
          .map(fields => Json.fromFields(fields))
      )
}

/** Exit criterion 4's second half: the flattener is total, bounded, lossless where it claims to be, and
  * honest about the places where it is not.
  */
class JsonFlattenerPropertySuite extends ScalaCheckSuite {

  import JsonGenerators.*

  private val limits = FlattenLimits.Default

  private def valueRow(json: Json, at: FlattenLimits = limits): FlatRow =
    JsonFlattener.flatten(FlatSource(Vector.empty, Json.Null, json), at)

  property("isTotal") {
    // Nothing about a Kafka record is under KUI's control, so "it threw" is not an acceptable answer for
    // any document. The property is simply that a row comes back.
    forAll(json) { document =>
      val row = valueRow(document)
      row.cells.nonEmpty
    }
  }

  property("depthCapIsRespected") {
    forAll(json, Gen.choose(1, 4)) { (document, depth) =>
      val at = limits.copy(maxDepth = depth)
      valueRow(document, at).cells.keys.forall { path =>
        FlatPath.parse(path).exists(_.length <= depth + 1) // the root step, plus at most `depth` below it
      }
    }
  }

  property("pathEscapingRoundTrips") {
    forAll(Gen.listOf(awkwardKey), Gen.listOf(Gen.choose(0, 40))) { (names, indices) =>
      val steps =
        PathStep.Field(FlatPath.Value) ::
          (names.map(PathStep.Field.apply) ++ indices.map(PathStep.Index.apply) :+ PathStep.Overflow)
      FlatPath.parse(FlatPath.render(steps)).contains(steps)
    }
  }

  property("noValueIsSilentlyDropped") {
    forAll(json) { document =>
      val row = valueRow(document)
      // Every path the flattener would produce with no caps at all must still be *accounted for* under the
      // real caps: either it is a cell of its own, or an ancestor cell holds the subtree it lives in, or it
      // sits in an array tail whose `+N more` marker says so. Anything else is a value that vanished.
      val uncapped = valueRow(document, FlattenLimits(Int.MaxValue, Int.MaxValue, Int.MaxValue, Int.MaxValue))
      uncapped.cells.keys.forall(path => accountedFor(path, row))
    }
  }

  private def accountedFor(path: String, row: FlatRow): Boolean = {
    val steps = FlatPath.parse(path).getOrElse(Nil)
    val truncatedAncestor =
      steps.inits.exists(prefix => prefix.nonEmpty && row.cells.contains(FlatPath.render(prefix)))
    val collapsedArrayTail =
      steps.indices.exists { at =>
        steps(at) match {
          case PathStep.Index(index) if index >= limits.maxArrayElements =>
            row.cells.contains(FlatPath.render(steps.take(at) :+ PathStep.Overflow))
          case _ => false
        }
      }
    truncatedAncestor || collapsedArrayTail
  }

  property("columnsAreStableUnderAppend") {
    forAll(Gen.listOf(json), Gen.listOf(json)) { (first, second) =>
      val before = JsonFlattener.columns(first.toVector.map(document => valueRow(document)), limits)
      val after =
        JsonFlattener.columns((first ++ second).toVector.map(document => valueRow(document)), limits)
      after.startsWith(before)
    }
  }
}

/** The worked examples: the exact assertions MSG-037's acceptance criteria name, plus the two degraded
  * shapes. Examples rather than properties, because the point of each is a specific string a reader can
  * check against the spec.
  */
class JsonFlattenerSuite extends FunSuite {

  private val limits = FlattenLimits.Default

  private def cellsOf(document: String, at: FlattenLimits = limits): Map[String, String] =
    JsonFlattener.flatten(FlatSource(Vector.empty, Json.Null, parse(document).toOption.get), at).cells

  test("theDepthCapTruncatesTheSubtreeRatherThanDroppingIt") {
    assertEquals(cellsOf("""{"a":{"b":{"c":{"d":1}}}}""").get("V.a.b.c"), Some("""{"d":1}"""))
  }

  test("aKeyContainingASeparatorIsEscaped") {
    assertEquals(cellsOf("""{"x.y":1}""") - "K", Map("""V.x\.y""" -> "1"))
  }

  test("arrayCollapseKeepsTheFirstNAndCountsTheRest") {
    val cells = cellsOf(s"""{"items":[${(0 until 25).mkString(",")}]}""")
    assertEquals(cells.get("V.items[0]"), Some("0"))
    assertEquals(cells.get("V.items[9]"), Some("9"))
    assertEquals(cells.get("V.items[10]"), None)
    assertEquals(cells.get("V.items[+]"), Some("+15 more"))
  }

  test("nonJsonPayloadsProduceASingleValueColumn") {
    // A payload the serde could not decode reaches the browser as text; the screen hands it over as a JSON
    // string, and one column with the text in it is the right answer with no special case anywhere.
    val row = JsonFlattener.flatten(
      FlatSource(Vector.empty, Json.Null, Json.fromString(" not json at all")),
      limits
    )
    // The key cell is there too: a record with no key still gets a `K` column reading `null`, because
    // "this record had no key" is information a table must show rather than leave blank.
    assertEquals(row.cells, Map("K" -> "null", "V" -> " not json at all"))
  }

  test("headersKeyAndValueEachGetTheirOwnRoot") {
    val row = JsonFlattener.flatten(
      FlatSource(
        Vector("trace-id" -> "abc"),
        parse("""{"id":7}""").toOption.get,
        parse("""{"total":12}""").toOption.get
      ),
      limits
    )
    assertEquals(row.cells, Map("H.trace-id" -> "abc", "K.id" -> "7", "V.total" -> "12"))
    // Headers first, then key, then value: the order Kouncil's grid uses, and the order the first record
    // therefore seeds the table's columns in.
    assertEquals(row.order, Vector("H.trace-id", "K.id", "V.total"))
  }

  test("rowCapIsRespected") {
    val sources = Vector.fill(1500)(FlatSource(Vector.empty, Json.Null, Json.fromInt(1)))
    assertEquals(JsonFlattener.flattenAll(sources, limits).size, limits.maxRows)
  }

  test("theColumnCapIsTheNumberOfColumnsTheTableWillShow") {
    // 300 distinct paths across one row; the table shows the first 120 and the picker reaches the rest.
    val wide = Json.fromFields((0 until 300).map(index => s"field$index" -> Json.fromInt(index)))
    val row = JsonFlattener.flatten(FlatSource(Vector.empty, Json.Null, wide), limits)
    assertEquals(JsonFlattener.columns(Vector(row), limits).size, limits.maxColumns)
  }

  test("anEmptyContainerIsACellRatherThanNothing") {
    // Otherwise a record whose value is `{}` renders as a completely blank row, indistinguishable from one
    // the flattener failed on.
    assertEquals(cellsOf("""{"tags":[],"meta":{}}""") - "K", Map("V.tags" -> "[]", "V.meta" -> "{}"))
  }
}
