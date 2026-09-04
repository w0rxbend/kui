package kui.ui.messages.table

import java.time.Instant

import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.scalajs.dom

import kui.contracts.message.DecodedPayloadDto
import kui.kernel.{Offset, PartitionId}
import kui.message.contract.MessageDto

/** What the table view puts on screen.
  *
  * The flattener itself is property-tested next door; what cannot be checked there is the part that made
  * MS-004 worth building — that a record's JSON becomes columns a person can scan, that a record missing a
  * field leaves a *gap* rather than shifting its neighbours along, and that the caps are visible.
  */
final class FlatTableSuite extends FunSuite {

  private def json(text: String): DecodedPayloadDto =
    DecodedPayloadDto(text = text, kind = DecodedPayloadDto.Kind.Json, serde = "Json", properties = Map.empty)

  private def record(offset: Long, value: String, headers: Map[String, String] = Map.empty): MessageDto =
    MessageDto(
      partition = PartitionId.unsafe(0),
      offset = Offset.unsafe(offset),
      timestamp = Instant.parse("2026-09-04T09:00:00Z"),
      timestampType = MessageDto.TimestampType.CreateTime,
      key = DecodedPayloadDto(
        text = s"key-$offset",
        kind = DecodedPayloadDto.Kind.Text,
        serde = "String",
        properties = Map.empty
      ),
      value = json(value),
      headers = headers,
      keySize = 0,
      valueSize = value.length,
      headersSize = 0,
      deserializeErrors = Nil
    )

  private def withGrid(records: List[MessageDto], limits: FlattenLimits = FlattenLimits.Default)(
      body: dom.Element => Unit
  ): Unit = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container): Unit

    val root =
      render(
        container,
        FlatTable(
          records = Val(records),
          zone = Val("UTC"),
          empty = Val(div("nothing")),
          limits = limits,
          testId = Some("grid")
        )
      )

    try body(container)
    finally {
      root.unmount(): Unit
      dom.document.body.removeChild(container): Unit
    }
  }

  private def headers(container: dom.Element): List[String] =
    container.querySelectorAll("thead th").toList.map(_.textContent)

  private def cellsOf(container: dom.Element, testId: String): List[String] =
    Option(container.querySelector(s"[data-testid='$testId']"))
      .map(_.querySelectorAll("td").toList.map(_.textContent))
      .getOrElse(fail(s"no row with testid '$testId'"))

  test("aJsonPayloadBecomesOneColumnPerField") {
    withGrid(List(record(0L, """{"status":"PAID","total":12}"""))) { container =>
      assertEquals(headers(container), List("Partition", "Offset", "Timestamp", "K", "V.status", "V.total"))
      assertEquals(cellsOf(container, "grid-0-0").drop(3), List("key-0", "PAID", "12"))
    }
  }

  test("aRecordWithoutAFieldLeavesTheCellEmptyRatherThanShiftingItsNeighbours") {
    // The defect this prevents is the one that makes a grid actively misleading: a row whose cells slide
    // one column to the left reads as a record whose status is its total.
    val records = List(record(0L, """{"status":"PAID","total":12}"""), record(1L, """{"total":9}"""))

    withGrid(records) { container =>
      assertEquals(cellsOf(container, "grid-0-1").drop(3), List("key-1", "", "9"))
    }
  }

  test("columnsAppearInFirstSeenOrderAndAreNeverReordered") {
    // The property a live tail depends on: a record arriving with a new field adds a column on the right
    // and does not shuffle the ones being read.
    val records = List(record(0L, """{"a":1}"""), record(1L, """{"b":2,"a":3}"""))

    withGrid(records) { container =>
      assertEquals(headers(container).drop(3), List("K", "V.a", "V.b"))
    }
  }

  test("headersAreTheirOwnColumnsUnderH") {
    withGrid(List(record(0L, """{"a":1}""", Map("traceparent" -> "00-abc")))) { container =>
      assertEquals(headers(container).drop(3), List("H.traceparent", "K", "V.a"))
      assertEquals(cellsOf(container, "grid-0-0").drop(3), List("00-abc", "key-0", "1"))
    }
  }

  test("aPayloadThatIsNotJsonStillRendersAsOneColumn") {
    // The documented degraded behaviour. A binary or plain-text topic must still open in the table view;
    // it simply has one column per side instead of many.
    val plain = record(0L, "not json at all").copy(value =
      DecodedPayloadDto(
        text = "not json at all",
        kind = DecodedPayloadDto.Kind.Text,
        serde = "String",
        properties = Map.empty
      )
    )

    withGrid(List(plain)) { container =>
      assertEquals(headers(container).drop(3), List("K", "V"))
      assertEquals(cellsOf(container, "grid-0-0").drop(3), List("key-0", "not json at all"))
    }
  }

  test("theRowCapIsStatedRatherThanSilent") {
    // A table that stopped at its cap without saying so is a table whose user believes that is all the
    // records there are.
    val capped = FlattenLimits.Default.copy(maxRows = 1)
    val records = List(record(0L, """{"a":1}"""), record(1L, """{"a":2}"""))

    withGrid(records, capped) { container =>
      assertEquals(container.querySelectorAll("tbody tr").length, 1)
      assert(
        Option(container.querySelector(".kui-messages__grid-note")).exists(_.textContent.contains("newest 1")),
        "the table stopped at its cap without saying so"
      )
    }
  }

  test("theEmptyStateIsOneCellSpanningTheWholeTable") {
    withGrid(Nil) { container =>
      val cell = Option(container.querySelector("table td")).getOrElse(fail("no cell for the empty state"))
      assertEquals(cell.getAttribute("colspan"), "3")
    }
  }
}
