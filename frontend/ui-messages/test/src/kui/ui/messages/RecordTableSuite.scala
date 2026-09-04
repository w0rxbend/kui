package kui.ui.messages

import com.raquo.laminar.api.L.*
import io.circe.parser.decode
import munit.FunSuite
import org.scalajs.dom

import kui.contracts.message.DecodedPayloadDto
import kui.message.contract.{GoldenDocuments, MessageDto}
import kui.ui.messages.row.{RecordDetail, RecordTable}

/** What the record table puts on screen, asserted against the DOM.
  *
  * The three behaviours here are the ones a unit test of a pure function cannot reach and a screenshot cannot
  * be trusted on: a row that opens *in place*, an absent payload that says what it is rather than being
  * blank, and a record delivered with a serde failure attached still showing its bytes.
  */
final class RecordTableSuite extends FunSuite {

  private val record: MessageDto =
    decode[MessageDto](GoldenDocuments.message).getOrElse(fail("the contract's own record must decode"))

  private val undecodable: MessageDto =
    decode[MessageDto](GoldenDocuments.messageWithDecodeError)
      .getOrElse(fail("the contract's own undecodable record must decode as a record"))

  /** Mounts the table, runs the body against the container, and unmounts. */
  private def withTable(records: List[MessageDto])(body: dom.Element => Unit): Unit = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container): Unit

    val root =
      render(
        container,
        RecordTable(
          records = Val(records),
          zone = Val("UTC"),
          empty = Val(div("nothing")),
          testId = Some("records")
        )
      )

    try body(container)
    finally {
      root.unmount(): Unit
      dom.document.body.removeChild(container): Unit
    }
  }

  private def find(container: dom.Element, testId: String): dom.Element =
    Option(container.querySelector(s"[data-testid='$testId']"))
      .getOrElse(fail(s"no element with testid '$testId'"))

  test("theEmptyStateIsACellSpanningEveryColumnAndNotStrandedInTheFirstOne") {
    // Found by looking at the message browser in a browser: the empty state was attached straight to the
    // `<table>` element, which HTML does not allow, and the browser's recovery left it laid out inside the
    // width of the Offset column -- "No records yet" one word per line. The fix is structural, so the
    // assertion is structural: it must be a `<td>` that spans the five columns the header declares.
    withTable(Nil) { container =>
      val cell = Option(container.querySelector("table td"))
        .getOrElse(fail("the empty state must be in a table cell"))

      assertEquals(cell.getAttribute("colspan"), "5", "the empty state must span every column")
      assertEquals(cell.textContent, "nothing")
      assertEquals(
        cell.parentNode.parentNode.asInstanceOf[dom.Element].tagName.toLowerCase,
        "tbody",
        "a row belongs in a section of the table, not directly under it"
      )
    }
  }

  test("a record's detail is hidden until its row is opened, and then appears in place") {
    withTable(List(record)) { container =>
      val detailRow = find(container, "record-3-41284-detail-row")
      assert(detailRow.hasAttribute("hidden"), "a closed record must not show its payload")

      // The detail is a row of the same table, immediately after the summary. That is what keeps it attached
      // to its record while the table scrolls, and what makes a screen reader read the two together.
      val summary = find(container, "record-3-41284")
      assertEquals(summary.nextElementSibling, detailRow)

      val toggle = find(container, "record-3-41284-toggle").asInstanceOf[dom.html.Element]
      assertEquals(toggle.getAttribute("aria-expanded"), "false")
      toggle.click()

      assert(!detailRow.hasAttribute("hidden"), "an opened record must show its payload")
      assertEquals(toggle.getAttribute("aria-expanded"), "true")
      assert(container.textContent.contains("orderId"), container.textContent)
    }
  }

  test("two records can be open at once") {
    // Comparing two records is the common act on this screen, and a table that closed one to open another
    // would make it impossible.
    withTable(List(record, undecodable)) { container =>
      find(container, "record-3-41284-toggle").asInstanceOf[dom.html.Element].click()
      find(container, "record-0-7-toggle").asInstanceOf[dom.html.Element].click()

      assert(!find(container, "record-3-41284-detail-row").hasAttribute("hidden"))
      assert(!find(container, "record-0-7-detail-row").hasAttribute("hidden"))
    }
  }

  test("a record delivered with a serde failure still shows its bytes, and says what failed") {
    withTable(List(undecodable)) { container =>
      find(container, "record-0-7-toggle").asInstanceOf[dom.html.Element].click()

      val text = container.textContent
      // The failure, in words — and the raw bytes underneath it. A screen that hid either would hide the
      // reason somebody opened the record.
      assert(text.contains("magic byte"), text)
      assert(text.contains("7b 22 6f 72 64"), text)
    }
  }

  test("an absent key and an absent value say what they are rather than being blank") {
    // A record with no key and a tombstone are facts about the record. An empty cell reads as a rendering
    // bug and hides the one thing that distinguishes a deletion from an ordinary record.
    assertEquals(RecordTable.preview(DecodedPayloadDto.absent("String"), Messages.NoKey), Messages.NoKey)
    assertEquals(RecordTable.preview(DecodedPayloadDto.absent("String"), Messages.Tombstone), Messages.Tombstone)
  }

  test("a long payload is clipped to one line in the summary cell") {
    // Rendered in full, one JSON document would make a row taller than the viewport and push every other
    // record off the screen.
    val long = DecodedPayloadDto(text = "x" * 1000, kind = "json", serde = "Json", properties = Map.empty)
    val preview = RecordTable.preview(long, Messages.Tombstone)

    assertEquals(preview.length, RecordTable.PreviewLength + 1)
    assert(!preview.contains('\n'), preview)
  }

  test("a multi-line payload becomes one line in the cell and stays formatted in the detail") {
    val json = DecodedPayloadDto(text = "{\n  \"a\": 1\n}", kind = "json", serde = "Json", properties = Map.empty)

    assertEquals(RecordTable.preview(json, Messages.Tombstone), "{ \"a\": 1 }")
    assert(RecordDetail.format(json).contains("\n"), RecordDetail.format(json))
  }

  test("a payload labelled JSON that will not parse is shown exactly as it arrived") {
    // The disagreement is worth seeing. Showing nothing, or an error, would hide what the bytes actually are.
    val broken = DecodedPayloadDto(text = "{not json", kind = "json", serde = "Json", properties = Map.empty)
    assertEquals(RecordDetail.format(broken), "{not json")
  }

  test("a record's identity is its partition and offset") {
    // The only pair Kafka guarantees unique, and therefore the only safe key for a row that must stay open
    // while a live tail redraws around it.
    assertEquals(RecordTable.keyOf(record), "3-41284")
  }

  test("the timestamp is drawn in the zone the caller passed") {
    withTable(List(record)) { container =>
      assert(container.textContent.contains("2026-09-03"), container.textContent)
    }
  }

  test("an empty table draws the state the caller supplied") {
    withTable(Nil) { container =>
      assert(container.textContent.contains("nothing"), container.textContent)
    }
  }
}
