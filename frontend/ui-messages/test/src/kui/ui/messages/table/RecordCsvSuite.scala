package kui.ui.messages.table

import java.time.Instant

import munit.FunSuite

import kui.contracts.message.DecodedPayloadDto
import kui.kernel.{Offset, PartitionId}
import kui.message.contract.MessageDto

/** What the export contains, which is the whole of what MS-011 promises: the records on screen, as a file.
  */
final class RecordCsvSuite extends FunSuite {

  private def payload(text: String, kind: String = DecodedPayloadDto.Kind.Json): DecodedPayloadDto =
    DecodedPayloadDto(text = text, kind = kind, serde = "Json", properties = Map.empty)

  private def record(
      offset: Long,
      value: String,
      headers: Map[String, String] = Map.empty
  ): MessageDto =
    MessageDto(
      partition = PartitionId.unsafe(2),
      offset = Offset.unsafe(offset),
      timestamp = Instant.parse("2026-09-04T09:00:00Z"),
      timestampType = MessageDto.TimestampType.CreateTime,
      key = payload(s"order-$offset", DecodedPayloadDto.Kind.Text),
      value = payload(value),
      headers = headers,
      keySize = 7,
      valueSize = value.length,
      headersSize = 0,
      deserializeErrors = Nil
    )

  private def lines(csv: String): List[String] = csv.split("\r\n").toList

  test("theListViewExportsOneRowPerRecordUnderTheColumnsItShows") {
    val csv = RecordCsv.ofRecords(List(record(41L, """{"status":"PAID"}""")))

    assertEquals(lines(csv).head, RecordCsv.ListHeader.mkString(","))
    assertEquals(lines(csv).size, 2)
    assert(lines(csv)(1).startsWith("2,41,2026-09-04T09:00:00Z"), lines(csv)(1))
  }

  test("aJsonValueIsQuotedRatherThanShiftingEveryColumnAfterIt") {
    // The failure this whole export exists to avoid: a value full of commas and quotation marks that a
    // naive join turns into six columns, so the file opens with the data in the wrong places.
    val csv = RecordCsv.ofRecords(List(record(1L, """{"a":1,"b":"x,y"}""")))

    assertEquals(lines(csv).size, 2)
    assert(lines(csv)(1).contains(""""{""a"":1,""b"":""x,y""}""""), lines(csv)(1))
  }

  test("headersAreOneJsonCellRatherThanAColumnPerName") {
    // Header names differ per record, so a column per name would make the file's width depend on which
    // records happened to be on screen.
    val csv = RecordCsv.ofRecords(List(record(1L, "{}", Map("b" -> "2", "a" -> "1"))))

    // Sorted, so that two records with the same headers written in different orders export identically.
    assert(lines(csv)(1).contains("""{""a"":""1"",""b"":""2""}"""), lines(csv)(1))
  }

  test("theTableViewExportsTheGridItIsShowing") {
    val records = List(record(1L, """{"status":"PAID","total":12}"""), record(2L, """{"total":9}"""))
    val csv = RecordCsv.ofGrid(records, List("V.status", "V.total"), FlattenLimits.Default)

    assertEquals(lines(csv).head, "partition,offset,timestamp,V.status,V.total")
    // The record with no `status` exports an empty cell rather than shifting its `total` left, which in a
    // file would read as a record whose status is 9.
    assert(lines(csv)(2).endsWith(",,9"), lines(csv)(2))
  }

  test("theFileIsNamedForTheTopicAndTheMoment") {
    // Two exports of one topic in an afternoon end up in the same folder, and a name that did not say
    // when would leave the person guessing which is which.
    val name = RecordCsv.fileName("orders.v1", Instant.parse("2026-09-04T09:30:15Z"))

    assert(name.startsWith("orders.v1-"), name)
    assert(name.endsWith(".csv"), name)
    // No colons: they are not legal in a filename on Windows, and a download that silently fails to save
    // is worse than one that saves under an ugly name.
    assert(!name.contains(":"), name)
  }

  test("aTopicNameWithAPathSeparatorInItCannotEscapeTheFilename") {
    // Kafka permits a narrower set than a filesystem does, and the name still comes off a URL.
    assert(!RecordCsv.fileName("../../etc/passwd", Instant.EPOCH).contains("/"))
  }
}
