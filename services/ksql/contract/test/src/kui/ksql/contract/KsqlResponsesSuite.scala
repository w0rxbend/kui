package kui.ksql.contract

import scala.io.Source
import scala.util.Using

import io.circe.parser.parse
import io.circe.syntax.*
import munit.FunSuite

import kui.contracts.Section
import kui.ksql.contract.dto.*

/** That each response document is exactly the file committed beside it, and that the file decodes back into
  * the value it was rendered from.
  *
  * ==Why the round trip is over the encoder's own output==
  *
  * House rule 12, and it is wave 5's most expensive lesson written as a test. `producers.data.topics` and
  * `producers.data.entries` were two hand-written shapes on two sides of one wire, each with a suite that
  * asserted its own literal, both green, and the card drew "the metrics source named no producers" over a
  * source that named five. A decode assertion against a hand-written literal proves that the decoder matches
  * the literal. A decode assertion against `value.asJson` proves that the decoder matches the **encoder**,
  * which is the only thing anybody wanted to know.
  *
  * The golden files are the third leg: they are what a reviewer reads and what a browser fixture is cut from,
  * so a field rename is a diff in a file rather than an expectation quietly edited in passing. A file that is
  * missing **fails loudly** rather than being skipped, which is the difference between a gate and a habit.
  */
final class KsqlResponsesSuite extends FunSuite {

  import KsqlDocuments.*

  private def golden(name: String): String =
    Using
      .resource(Option(getClass.getResourceAsStream(s"/golden/$name")).getOrElse {
        fail(s"golden/$name is missing from the test resources")
      })(stream => Source.fromInputStream(stream, "UTF-8").mkString)
      .stripLineEnd

  private def assertGolden(name: String, encoded: io.circe.Json): Unit =
    assertNoDiff(
      encoded.spaces2,
      parse(golden(name)).fold(failure => fail(s"$name is not JSON: ${failure.message}"), _.spaces2)
    )

  all.foreach { (name, document) =>
    test(s"$name is exactly what the encoder rendered") {
      assertGolden(name, document)
    }
  }

  test("a listing is its golden document and decodes back into the value it came from") {
    assertGolden("objects-response.json", listing.asJson)
    assertEquals(listing.asJson.as[KsqlObjectsResponse], Right(listing))
  }

  test("a server that names two streams and a table reaches the wire with each object's kind") {
    // The required case, at the wire's own boundary. §3.16 draws one pane whose rows differ by a glyph and
    // says "the glyph is the only thing that says which", so the kind has to be a field a browser reads —
    // not something it infers from which other fields happen to be present.
    val items = listing.objects.toOption.map(_.items).getOrElse(Nil)

    assertEquals(
      items.map(item => s"${item.kind}:${item.name}"),
      List("stream:ORDERS", "stream:PAYMENTS", "table:USERS", "query:CSAS_ENRICHED_ORDERS_5", "topic:orders")
    )
    assertEquals(items.count(_.kind == "stream"), 2)
    assertEquals(items.count(_.kind == "table"), 1)
    // Each stream carries its own topic, so two streams over two topics cannot be drawn as one.
    assertEquals(items.filter(_.kind == "stream").flatMap(_.topic), List("orders", "payments"))
  }

  test("each kind carries the facts that kind has, and none that it does not") {
    // `KsqlObjectDto`'s header states which fields belong to which kind. This is the case that holds the
    // sentence: without it, a mapping that put a topic on a query or dropped a table's `windowed` would be
    // a silent change to what a screen can draw.
    assertEquals(
      (orders.topic.isDefined, orders.windowed, orders.statement, orders.partitions),
      (true, None, None, None)
    )
    assertEquals((users.topic.isDefined, users.windowed), (true, Some(false)))
    assertEquals((query.topic, query.statement.isDefined, query.sinks), (None, true, List("ENRICHED_ORDERS")))
    assertEquals((topic.topic, topic.partitions, topic.replication), (None, Some(3), Some(3)))
  }

  test("a format the server did not report is null, and never a default") {
    // The absence is the fact. A defaulted `JSON` here would tell an operator their Avro stream was JSON,
    // which is the class of invented measurement this product exists to refuse.
    assertEquals(payments.format, None)
    assertEquals(payments.asJson.hcursor.get[Option[String]]("format"), Right(None))
    assert(payments.asJson.asObject.exists(_.contains("format")), clue = payments.asJson.noSpaces)
  }

  test("a row the server would not describe is named rather than dropped, and a cut answer says how much") {
    assertEquals(partial.asJson.as[KsqlObjectsResponse], Right(partial))

    val objects = partial.objects.toOption.getOrElse(fail("the fixture has no data"))

    assertEquals(objects.unreadable, List("(a table the ksqlDB cluster did not name)"))
    assertEquals(objects.truncated, 1811)
    // A truncated answer is a *measured* shortfall: the browser can say "showing 1 of 1,812" rather than
    // "there may be more", which is not a figure anybody can act on.
    assertEquals(objects.items.size + objects.truncated, 1812)
  }

  test("a deployment with no ksqlDB is not_configured, and carries no data at all") {
    assertGolden("objects-not-configured.json", notConfigured.asJson)
    assertEquals(notConfigured.objects.status, "not_configured")
    assertEquals(notConfigured.objects.toOption, None)
    assertEquals(notConfigured.asJson.as[KsqlObjectsResponse], Right(notConfigured))
  }

  test("a ksqlDB that did not answer is unavailable with a reason, not an empty list") {
    assertEquals(unavailable.objects.status, "unavailable")
    assertEquals(unavailable.objects.toOption, None)
    assertEquals(unavailable.asJson.as[KsqlObjectsResponse], Right(unavailable))
  }

  test("a statement that returns rows and one that returns a status are different documents") {
    // ADR-055 §4. The discriminator is what makes "no rows" and "this statement returns no rows" two
    // different answers: `rows` with an empty list is a query that ran and matched nothing, and a screen
    // must be able to say so.
    assertEquals(created.outcome, "status")
    assertEquals((created.columns, created.rows), (Nil, Nil))
    assertEquals(created.message, Some("Stream created and running"))

    assertEquals(rows.outcome, "rows")
    assertEquals(rows.message, None)
    assertEquals(rows.columns, List("ID", "TOTAL", "NOTE"))

    assertEquals(created.asJson.as[StatementResultDto], Right(created))
    assertEquals(rows.asJson.as[StatementResultDto], Right(rows))
  }

  test("a SQL NULL is null on the wire, and not the four characters null") {
    val second = rows.rows(1)

    assertEquals(second.last, None)
    // On the wire, and read back: a browser that decoded `null` into the string "null" would draw a cell
    // that says something about a value nobody has.
    assertEquals(
      rows.asJson.hcursor.downField("rows").downN(1).downN(2).focus,
      Some(io.circe.Json.Null)
    )
    assertNotEquals(second.last, Some("null"))
  }

  test(
    "a destructive plan names the topic it would delete and carries a token; a harmless one carries none"
  ) {
    assertEquals((destructivePlan.destructive, destructivePlan.deletesTopic), (true, true))
    assert(destructivePlan.token.isDefined)
    assert(destructivePlan.expiresAt.isDefined)
    assert(clue(destructivePlan.warnings).exists(_.contains("'orders'")))

    assertEquals((harmlessPlan.destructive, harmlessPlan.deletesTopic), (false, false))
    assertEquals(harmlessPlan.token, None)
    assertEquals(harmlessPlan.expiresAt, None)
    assertEquals(harmlessPlan.warnings, Nil)

    assertEquals(destructivePlan.asJson.as[StatementPlanDto], Right(destructivePlan))
    assertEquals(harmlessPlan.asJson.as[StatementPlanDto], Right(harmlessPlan))
  }

  test("an absent field decodes to the same value an explicit null does") {
    // A document written by an older build that omitted `format`, `sinks`, `truncated` or `token` must land
    // on the "nothing here" rendering rather than on a decode failure.
    assertEquals(
      parse("""{"kind":"topic","name":"orders"}""").flatMap(_.as[KsqlObjectDto]),
      Right(KsqlObjectDto("topic", "orders", None, None, None, Nil, None, None, None))
    )
    assertEquals(
      parse("""{"items":[]}""").flatMap(_.as[KsqlObjectsDto]),
      Right(KsqlObjectsDto(Nil, Nil, 0))
    )
    assertEquals(
      parse("""{"statement":"SHOW STREAMS;"}""").flatMap(_.as[StatementRequestDto]),
      Right(StatementRequestDto("SHOW STREAMS;", None))
    )
  }

  test("a status the browser does not know is a decode failure and not a silent ok") {
    assert(parse("""{"objects":{"status":"maybe"}}""").flatMap(_.as[KsqlObjectsResponse]).isLeft)
  }

  test("the section vocabulary is the shared one, so a screen renders it with what it already has") {
    assertEquals(
      List(listing, partial, notConfigured, unavailable).map(_.objects.status),
      List("ok", "ok", "not_configured", "unavailable")
    )
    assertEquals(Section.NotConfigured.status, notConfigured.objects.status)
  }
}
