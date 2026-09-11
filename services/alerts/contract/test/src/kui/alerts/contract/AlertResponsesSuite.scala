package kui.alerts.contract

import scala.io.Source
import scala.util.Using

import io.circe.parser.parse
import io.circe.syntax.*
import munit.FunSuite

import kui.alerts.contract.dto.*

/** That each response envelope is exactly the document committed beside it, and that the document decodes
  * back into the value it was rendered from.
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
  * so a field rename is a diff in a file rather than an expectation quietly edited in passing.
  */
final class AlertResponsesSuite extends FunSuite {

  import AlertDocuments.*

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

  test("a feed is exactly its golden document, resolved row and per-rule sections and all") {
    assertGolden("alerts-feed-response.json", feed.asJson)
    assertEquals(feed.asJson.as[AlertFeedResponse], Right(feed))
  }

  test("a rule that could not run is an unavailable section carrying its reason, not a zero") {
    assertGolden("alerts-feed-blind-rule.json", blindRule.asJson)
    assertEquals(blindRule.asJson.as[AlertFeedResponse], Right(blindRule))

    val rules = blindRule.events.toOption.map(_.rules).getOrElse(Nil)
    assertEquals(rules.head.evaluation.status, "unavailable")
    assertEquals(rules.head.evaluation.toOption, None)
  }

  test("a cluster the rules have never run for says so rather than answering all clear") {
    assertGolden("alerts-feed-unevaluated.json", unevaluated.asJson)
    assertEquals(unevaluated.asJson.as[AlertFeedResponse], Right(unevaluated))
    assertEquals(unevaluated.events.toOption.flatMap(_.evaluatedAt), None)
  }

  test("an acknowledgement is exactly its golden document and carries the new open count") {
    assertGolden("alerts-acknowledgement.json", acknowledgement.asJson)
    assertEquals(acknowledgement.asJson.as[AcknowledgementDto], Right(acknowledgement))
  }

  test("a change frame is exactly its golden document") {
    assertGolden("alerts-change.json", change.asJson)
    assertEquals(change.asJson.as[AlertChangeDto], Right(change))
  }

  test("an SSE frame is exactly its golden document, event name included") {
    // The name is the half of this wire that is not a DTO. `AlertsRoutes` writes frames under
    // `AlertChangeDto.EventName` and the browser listens under a constant of its own; committing the pair
    // in one document is what turns a hand-copied mirror into a compared one.
    assertGolden("alerts-stream-frame.json", streamFrame)
    assertEquals(streamFrame.hcursor.get[String]("event"), Right(AlertChangeDto.EventName))
    assertEquals(streamFrame.hcursor.downField("data").as[AlertChangeDto], Right(change))
  }

  test("a row and its resolution decode on their own, out of the document the feed was rendered to") {
    // `AlertEventDto` and `AlertResolutionDto` have no file of their own because they have no envelope of
    // their own: they are how a row is written *inside* the feed and the acknowledgement. Cutting them out
    // of the committed feed document and decoding them alone is what says the browser may read a row
    // wherever one appears, rather than only where a whole-document decoder happens to look.
    val row = parse(golden("alerts-feed-response.json"))
      .flatMap(_.hcursor.downField("events").downField("data").downField("items").downN(1).as[AlertEventDto])

    assertEquals(row, Right(resolved))
    assertEquals(
      row.map(_.resolution),
      Right(Some(AlertResolutionDto(fetchedAt, "acknowledged", Some("ada"))))
    )

    val resolution = parse(golden("alerts-acknowledgement.json"))
      .flatMap(_.hcursor.downField("event").downField("resolution").as[AlertResolutionDto])

    assertEquals(resolution.map(_.kind), Right("acknowledged"))
    assertEquals(resolution.map(_.by), Right(Some("ada")))
  }

  test("a resolved row keeps its severity and changes only its tone") {
    // The pair SCREENS-V4 §3.8's fourth dot needs. A browser that derived the tone from the severity could
    // not draw the capture, and a browser that derived the severity from the tone would report a resolved
    // critical as a success.
    assertEquals(resolved.severity, "warning")
    assertEquals(resolved.tone, "success")
    assertEquals(offline.severity, "critical")
    assertEquals(offline.tone, "danger")
  }

  test("severity and category are independent, which is what §3.9 corrects") {
    assertNotEquals(offline.glyph, resolved.glyph)
    assertNotEquals(offline.category, resolved.category)
  }

  test("an absent field decodes to the same value an explicit null does") {
    // A document written by an older build that omitted `resolution`, `lastReadAt` or `rules` must land on
    // the "nothing here" rendering rather than on a decode failure.
    val sparse = parse("""{"events":{"status":"ok","data":{},"fetchedAt":"2026-09-03T10:11:12Z"}}""")

    assertEquals(
      sparse.flatMap(_.as[AlertFeedResponse]).map(_.events.toOption.map(_.rules)),
      Right(Some(Nil))
    )
    assertEquals(
      sparse.flatMap(_.as[AlertFeedResponse]).map(_.events.toOption.flatMap(_.lastReadAt)),
      Right(None)
    )
  }

  test("a status the browser does not know is a decode failure and not a silent ok") {
    val unknown = parse("""{"events":{"status":"maybe"}}""")

    assert(unknown.flatMap(_.as[AlertFeedResponse]).isLeft)
  }
}
