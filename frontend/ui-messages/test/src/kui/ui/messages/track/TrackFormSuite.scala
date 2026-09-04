package kui.ui.messages.track

import java.time.Instant

import munit.FunSuite

import kui.message.contract.TrackMatchDto

/** What the track form accepts, and what it refuses with a sentence.
  *
  * Every refusal here is checked because each one has an obvious-looking repair that would be wrong. The
  * worst is the header search with no header name: repaired by searching the value instead — which is the
  * reference product's behaviour — it produces results that look right and are not.
  */
final class TrackFormSuite extends FunSuite {

  private val now = Instant.parse("2026-09-04T10:00:00Z")

  private def filled(
      topics: String = "orders.v1, shipments.v1",
      source: String = TrackMatchDto.Source.Value,
      header: String = "",
      operator: String = TrackMatchDto.Operator.Contains,
      value: String = "order-4711",
      from: String = "2026-09-04T09:00",
      to: String = "2026-09-04T10:00"
  ): TrackForm = TrackForm(topics, source, header, operator, value, from, to)

  test("theWindowStartsAnHourBack") {
    // The overwhelmingly common track is about something that has just gone wrong. A default of
    // "everything" would scan the whole retention the first time anybody pressed the button.
    val form = TrackForm.initial(now)

    assertEquals(form.from, "2026-09-04T09:00")
    assertEquals(form.to, "2026-09-04T10:00")
  }

  test("topicsAreSeparatedByCommasOrSpacesOrBoth") {
    // A person pasting a list out of a terminal has spaces and a person typing has commas, and refusing
    // either would be a difference with no meaning behind it.
    assertEquals(TrackForm.topicsOf("a, b  c,,d"), List("a", "b", "c", "d"))
    assertEquals(TrackForm.topicsOf("   "), Nil)
  }

  test("aTopicNamedTwiceIsReadOnce") {
    // Otherwise the same topic is scanned twice and its hits appear twice, which reads as duplicate
    // records in the log rather than as a duplicate in the form.
    assertEquals(TrackForm.topicsOf("orders.v1, orders.v1"), List("orders.v1"))
  }

  test("aFilledFormBecomesTheRequestTheServiceExpects") {
    TrackForm.query(filled()) match {
      case Right(query) =>
        assertEquals(query.topics.map(_.value), List("orders.v1", "shipments.v1"))
        assertEquals(query.`match`.value, "order-4711")
        assertEquals(query.from, Instant.parse("2026-09-04T09:00:00Z"))
        assertEquals(query.to, Instant.parse("2026-09-04T10:00:00Z"))
        // No header for a value search. The service refuses a request that sends one, because it is a
        // request whose author believed something untrue about it.
        assertEquals(query.`match`.header, None)
      case Left(complaint) => fail(s"a filled form was refused: $complaint")
    }
  }

  test("aHeaderSearchCarriesTheHeaderName") {
    TrackForm
      .query(filled(source = TrackMatchDto.Source.Header, header = "order-id"))
      .fold(complaint => fail(complaint), query => assertEquals(query.`match`.header, Some("order-id")))
  }

  test("aHeaderSearchWithNoHeaderNameIsRefusedRatherThanTurnedIntoAValueSearch") {
    assertEquals(
      TrackForm.query(filled(source = TrackMatchDto.Source.Header)),
      Left(TrackMessages.NoHeader)
    )
  }

  test("aFormWithNoTopicsIsRefused") {
    assertEquals(TrackForm.query(filled(topics = "")), Left(TrackMessages.NoTopics))
  }

  test("aTopicNameKafkaWouldNotAcceptIsNamedInTheRefusal") {
    // Named, because "one of your topics is wrong" over a list of six sends the user back to check all
    // six by hand. `#` is the illegal character here: Kafka allows letters, digits, dots, underscores
    // and hyphens, and a space would simply have separated two legal names.
    TrackForm.query(filled(topics = "orders.v1, has#hash")) match {
      case Left(complaint) => assert(complaint.contains("has#hash"), complaint)
      case Right(_) => fail("an illegal topic name was accepted")
    }
  }

  test("aFormWithNoValueIsRefused") {
    assertEquals(TrackForm.query(filled(value = "")), Left(TrackMessages.NoValue))
  }

  test("aBackwardsWindowIsRefused") {
    // The service refuses it too. This one exists so the user is told before a request is made, and told
    // about the control they got wrong rather than about a field name in an envelope.
    assertEquals(
      TrackForm.query(filled(from = "2026-09-04T10:00", to = "2026-09-04T09:00")),
      Left(TrackMessages.BackwardsWindow)
    )
  }

  test("aWindowOfZeroWidthIsRefused") {
    assertEquals(
      TrackForm.query(filled(from = "2026-09-04T09:00", to = "2026-09-04T09:00")),
      Left(TrackMessages.BackwardsWindow)
    )
  }

  test("aTimeKuiCannotReadIsRefusedWithTheEndItCameFrom") {
    assertEquals(TrackForm.query(filled(from = "yesterday")), Left(TrackMessages.BadFrom))
    assertEquals(TrackForm.query(filled(to = "soon")), Left(TrackMessages.BadTo))
  }

  test("epochMillisecondsAreAcceptedAsWellAsATypedTime") {
    // An operator correlating with a log line has milliseconds; one using a picker has the other form.
    TrackForm
      .query(filled(from = "1757062800000", to = "1757066400000"))
      .fold(complaint => fail(complaint), query => assert(query.to.isAfter(query.from)))
  }
}
