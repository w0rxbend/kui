package kui.ui.consumers.topic

import java.time.Instant

import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite

import kui.contracts.Section
import kui.contracts.capability.ReasonCode
import kui.contracts.consumer.TopicConsumerRowDto
import kui.consumer.contract.ConsumerSamples
import kui.gateway.contract.dto.TopicOverviewDto

/** What the topic page's Consumers tab decides, driven with documents rather than with a DOM.
  *
  * The cases that matter here are the ones that lie quietly when they are wrong: a section this deployment
  * does not have rendered as an error, and a section that could not be read rendered as an empty table. Both
  * of those produce a screen that looks fine and says something false.
  */
class TopicConsumersSuite extends FunSuite {

  private val at: Instant = Instant.parse("2026-09-04T09:00:00Z")

  private def overview(section: Section[List[Json]]): TopicOverviewDto =
    TopicOverviewDto(
      topic = Section.NotConfigured,
      consumerGroups = section,
      connectors = Section.NotConfigured,
      acls = Section.NotConfigured,
      schemas = Section.NotConfigured,
      generatedAt = at
    )

  private val rows: List[Json] = ConsumerSamples.topicConsumers.rows.map(_.asJson)

  test("theRowsAreDecodedWithTheConsumerServicesOwnCodec") {
    // The gateway carries them as `Json` because their shape belongs to this service and the gateway must
    // not declare it. Decoding them against anything but the consumer contract's own type is M1's second
    // integration defect — a document that decoded successfully into zero rows.
    TopicConsumers.of(overview(Section.Ok(rows, at))) match {
      case TopicConsumersView.Rows(decoded, stale) =>
        assertEquals(decoded, ConsumerSamples.topicConsumers.rows)
        assertEquals(stale, false)
      case other => fail(s"expected rows, got $other")
    }
  }

  test("aStaleSectionIsRealDataAndSaysSo") {
    TopicConsumers.of(overview(Section.Stale(rows, at, ReasonCode.UpstreamTimeout))) match {
      case TopicConsumersView.Rows(decoded, stale) =>
        assertEquals(decoded.size, 2)
        assertEquals(stale, true)
      case other => fail(s"expected stale rows, got $other")
    }
  }

  test("noConsumersAtAllIsAnAnswerAndNotAFailure") {
    // A producer-only topic. It is drawn as an empty state, because "nothing reads this topic" is a true and
    // useful thing to be told.
    assertEquals(TopicConsumers.of(overview(Section.Ok(Nil, at))), TopicConsumersView.Rows(Nil, stale = false))
  }

  test("aDeploymentWithoutAConsumerServiceIsNotAnError") {
    // Four of the overview's five sections are `not_configured` in a plain deployment. Rendering one as an
    // error would put a permanent red panel on every topic page, and an operator shown a permanent error
    // stops reading errors — including the one that matters (ADR-032).
    assertEquals(TopicConsumers.of(overview(Section.NotConfigured)), TopicConsumersView.Absent)
  }

  test("aSectionThatCouldNotBeReadIsASentenceAndNeverAnEmptyTable") {
    // The difference this test protects: an empty table on this tab says "nothing consumes this topic",
    // which at the moment KUI cannot tell is the most expensive thing this panel could say.
    TopicConsumers.of(
      overview(Section.Unavailable(ReasonCode.UpstreamUnavailable, "connection refused", Some(at)))
    ) match {
      case TopicConsumersView.Unreadable(message) =>
        assert(message.contains("UPSTREAM_UNAVAILABLE"), message)
        assert(message.contains("connection refused"), message)
      case other => fail(s"expected an explanation, got $other")
    }
  }

  test("forbiddenIsItsOwnSentenceRatherThanAnOutage") {
    TopicConsumers.of(overview(Section.Forbidden)) match {
      case TopicConsumersView.Unreadable(message) => assert(message.contains("permission"), message)
      case other => fail(s"expected an explanation, got $other")
    }
  }

  test("oneRowThatDoesNotDecodeFailsTheTabRatherThanBeingSkipped") {
    // A shorter list of consumer groups than exists, with nothing on screen saying a row was dropped, would
    // let "no group is behind" be read off a list that quietly lost the group that is behind.
    val broken = rows :+ Json.obj("group" -> Json.fromString("not a group summary"))

    TopicConsumers.of(overview(Section.Ok(broken, at))) match {
      case TopicConsumersView.Unreadable(message) => assert(message.contains("none are shown"), message)
      case other => fail(s"expected a refusal, got $other")
    }
  }

  test("everyRowSampleRoundTripsThroughTheSectionsJson") {
    // The seam this walks is the one that has actually caused a defect in this project: two modules each
    // holding their own idea of a payload, both suites green.
    val decoded = rows.map(_.as[TopicConsumerRowDto])
    assert(decoded.forall(_.isRight), decoded.toString)
  }
}
