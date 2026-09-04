package kui.ui.messages

import java.time.Instant

import munit.FunSuite

import kui.contracts.message.DecodedPayloadDto
import kui.kernel.{Offset, PartitionId, TopicName}
import kui.message.contract.MessageDto
import kui.ui.messages.produce.{ProduceDraft, ResendDrawer, ResendTarget}

/** The publish form's rules, tested without a DOM.
  *
  * Every case here is one where the obvious implementation is wrong in a way a screenshot would not show.
  * Two of them — the tombstone and the keyless record — are the difference between publishing a record and
  * deleting somebody's data from a compacted topic, and neither is visible in the text of a form field.
  */
final class ProduceDraftSuite extends FunSuite {

  private val topic = TopicName.unsafe("orders.v1")
  private val other = TopicName.unsafe("orders.v1.replay")

  private def payload(text: String, kind: String = DecodedPayloadDto.Kind.Text): DecodedPayloadDto =
    DecodedPayloadDto(text = text, kind = kind, serde = "String", properties = Map.empty)

  private val absent: DecodedPayloadDto = DecodedPayloadDto.absent("String")

  private def record(
      key: DecodedPayloadDto = payload("k1"),
      value: DecodedPayloadDto = payload("""{"id":1}""", DecodedPayloadDto.Kind.Json),
      headers: Map[String, String] = Map("trace" -> "abc")
  ): MessageDto =
    MessageDto(
      partition = PartitionId.unsafe(2),
      offset = Offset.unsafe(41284L),
      timestamp = Instant.parse("2026-09-04T09:00:00Z"),
      timestampType = MessageDto.TimestampType.CreateTime,
      key = key,
      value = value,
      headers = headers,
      keySize = 2,
      valueSize = 8,
      headersSize = 8,
      deserializeErrors = Nil
    )

  // --- The two absences -------------------------------------------------------------------------

  test("aTombstoneBecomesAnAbsentValueAndNotAnEmptyString") {
    val draft = ProduceDraft.empty(topic).copy(isTombstone = true, value = "left over text")

    assertEquals(draft.request.map(_._2.value), Right(None))
  }

  test("clearingTheValueBoxIsNotATombstone") {
    // The whole reason the absence is a checkbox. An empty box means "publish an empty value", which on a
    // compacted topic keeps the key; a tombstone deletes it.
    val draft = ProduceDraft.empty(topic).copy(isTombstone = false, value = "")

    assertEquals(draft.request.map(_._2.value), Right(Some("")))
  }

  test("noKeyIsNotAnEmptyKey") {
    assertEquals(ProduceDraft.empty(topic).copy(hasKey = false, key = "k").request.map(_._2.key), Right(None))
    assertEquals(
      ProduceDraft.empty(topic).copy(hasKey = true, key = "").request.map(_._2.key),
      Right(Some(""))
    )
  }

  // --- The fields -------------------------------------------------------------------------------

  test("anEmptyPartitionMeansLetKafkaChooseAndNotPartitionZero") {
    // Reading a blank box as 0 would pin every published record to one partition, which on a keyed topic
    // breaks the ordering the keys were chosen for.
    assertEquals(ProduceDraft.partitionOf("  "), Right(None))
    assertEquals(ProduceDraft.partitionOf("3"), Right(Some(PartitionId.unsafe(3))))
  }

  test("aPartitionThatIsNotANumberIsNamedRatherThanIgnored") {
    assertEquals(ProduceDraft.partitionOf("partition-0"), Left(ProduceDraft.PartitionNotANumber))
    assertEquals(ProduceDraft.partitionOf("-1"), Left(ProduceDraft.PartitionNotANumber))
  }

  test("anEmptyCountIsOneAndZeroIsRefused") {
    assertEquals(ProduceDraft.countOf(""), Right(1))
    assertEquals(ProduceDraft.countOf("0"), Left(ProduceDraft.CountNotANumber))
    assertEquals(ProduceDraft.countOf("250"), Right(250))
  }

  test("aBlankHeaderRowIsDroppedAndAValueWithoutANameIsRefused") {
    // Somebody who pressed "add header" and changed their mind leaves an empty row, and dropping it is
    // right. A value with no name would be silently discarded on the way to the broker, and the record
    // would look as though it had been published intact.
    assertEquals(ProduceDraft.namedHeaders(List("" -> "")), Right(Nil))
    assertEquals(ProduceDraft.namedHeaders(List("" -> "abc")), Left(ProduceDraft.HeaderNeedsAName))
    assertEquals(ProduceDraft.namedHeaders(List(" trace " -> "abc")), Right(List("trace" -> "abc")))
  }

  test("anEmptyTopicIsRefusedBeforeAnythingIsSent") {
    assertEquals(ProduceDraft.empty(topic).copy(topic = "  ").request.map(_._1), Left(ProduceDraft.TopicRequired))
  }

  // --- Republishing -----------------------------------------------------------------------------

  test("republishingCarriesTheRecordsKeyValueAndHeaders") {
    val draft = ProduceDraft.of(topic, record())

    assertEquals(draft.key, "k1")
    assert(draft.hasKey)
    assertEquals(draft.value, """{"id":1}""")
    assert(!draft.isTombstone)
    assertEquals(draft.headers, List("trace" -> "abc"))
    assertEquals(draft.topic, topic.value)
  }

  test("republishingATombstoneOffersATombstoneAndNotAnEmptyValue") {
    // Collapsing the two here would turn "send this tombstone again" into "publish an empty value" with
    // nothing on the screen saying so, and the key would stop being deleted.
    val draft = ProduceDraft.of(topic, record(value = absent))

    assert(draft.isTombstone)
    assertEquals(draft.request.map(_._2.value), Right(None))
  }

  test("republishingAKeylessRecordOffersAKeylessRecord") {
    val draft = ProduceDraft.of(topic, record(key = absent))

    assert(!draft.hasKey)
    assertEquals(draft.request.map(_._2.key), Right(None))
  }

  test("republishingLeavesThePartitionToKafka") {
    // The record came from partition 2, and pinning the copy there would be a decision nobody made. The
    // field is empty and editable, which is the honest default.
    assertEquals(ProduceDraft.of(topic, record()).partition, "")
  }

  // --- Resending --------------------------------------------------------------------------------

  test("aResendTargetIsTheHalfOpenWindowHoldingExactlyThatRecord") {
    val target = ResendTarget.of(topic, record())

    assertEquals(target.partition, 2)
    assertEquals(target.from, 41284L)
    assertEquals(target.until, 41285L)
  }

  test("aResendIntoTheSourceTopicIsRefusedBeforeItIsSent") {
    // The server refuses it too — it has to, since a browser is not a security boundary — and this
    // refusal exists so the answer appears next to the field rather than after a round trip.
    val target = ResendTarget.of(topic, record()).copy(destination = topic.value)

    assertEquals(ResendDrawer.request(target), Left(Messages.ResendSameTopic))
  }

  test("aResendNeedsADestination") {
    assertEquals(
      ResendDrawer.request(ResendTarget.of(topic, record())),
      Left(Messages.ResendDestinationRequired)
    )
  }

  test("aResendSendsOneRangeCoveringOneRecord") {
    val target = ResendTarget.of(topic, record()).copy(destination = other.value)

    val document = ResendDrawer.request(target).getOrElse(fail("the request should be ready"))

    assertEquals(document.toTopic, other)
    assertEquals(document.ranges.length, 1)
    assertEquals(document.ranges.head.from.value, 41284L)
    assertEquals(document.ranges.head.until.value, 41285L)
  }
}
