package kui.ui.kernel.component

import com.raquo.laminar.api.L.*
import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import org.scalajs.dom

import kui.kernel.{Sort, SortOrder}

private final case class Broker(id: String, host: String, bytesIn: Option[Long])

final class DataTableSuite extends ScalaCheckSuite with Mounted {

  private val columns = List(
    Column[Broker]("id", "ID", broker => broker.id, sortable = true),
    Column[Broker]("host", "Host", broker => broker.host),
    Column[Broker]("bytesIn", "Bytes in", broker => broker.bytesIn.fold(DataTable.missing)(_.toString))
  )

  private val brokers = List(
    Broker("1", "kafka-1", Some(100L)),
    Broker("2", "kafka-2", None),
    Broker("3", "kafka-3", Some(300L))
  )

  private def bodyRows(root: dom.Element): List[dom.Element] =
    root.querySelectorAll(".kui-table__row").toList.collect { case element: dom.Element => element }

  test("renders one row per item") {
    mounted(DataTable(columns, Val(brokers), _.id)) { root =>
      assertEquals(bodyRows(root).size, 3)
      assert(root.textContent.contains("kafka-2"), root.textContent)
    }
  }

  test("a missing value renders an em dash rather than a gap") {
    // An empty cell is ambiguous: zero, unmeasured, or the request failed. The dash says which.
    mounted(DataTable(columns, Val(brokers), _.id)) { root =>
      val secondRow = bodyRows(root)(1)

      assert(secondRow.textContent.contains(DataTable.missing), secondRow.textContent)
    }
  }

  test("headers are column-scoped and only sortable ones are buttons") {
    mounted(DataTable(columns, Val(brokers), _.id)) { root =>
      val headers = root.querySelectorAll("th").toList.collect { case element: dom.Element => element }

      assertEquals(headers.map(attributeOf(_, "scope")), List(Some("col"), Some("col"), Some("col")))
      // A clickable `<th>` would be invisible to the keyboard; the control has to be a real button.
      assertEquals(headers.map(_.querySelectorAll("button").length), List(1, 0, 0))
    }
  }

  test("sortableHeaderTogglesAscDescAndSetsAriaSort") {
    val sort = Var(Option.empty[Sort[String]])

    mounted(DataTable(columns, Val(brokers), _.id, sort = sort)) { root =>
      val header = root.querySelector("th")
      val toggle = header.querySelector("button")

      assertEquals(attributeOf(header, "aria-sort"), Some("none"))

      click(toggle)
      assertEquals(sort.now(), Some(Sort("id", SortOrder.Asc)))
      assertEquals(attributeOf(header, "aria-sort"), Some("ascending"))

      click(toggle)
      assertEquals(sort.now(), Some(Sort("id", SortOrder.Desc)))
      assertEquals(attributeOf(header, "aria-sort"), Some("descending"))

      // The third click returns to the server's natural order. Without it there is no way back.
      click(toggle)
      assertEquals(sort.now(), None)
      assertEquals(attributeOf(header, "aria-sort"), Some("none"))
    }
  }

  test("emptyStateReplacesTheBodyButKeepsTheHeader") {
    mounted(DataTable(columns, Val(List.empty[Broker]), _.id)) { root =>
      assertEquals(bodyRows(root), Nil)
      // The header stays, so the columns still say what the table would have contained.
      assertEquals(root.querySelectorAll("th").length, 3)
      assert(root.textContent.contains("Nothing to show"), root.textContent)
    }
  }

  test("loadingShowsBusyAndKeepsThePreviousRowCount") {
    // Replacing the rows with a spinner would collapse the table, jump the page, and jump it back.
    val loading = Var(false)

    mounted(DataTable(columns, Val(brokers), _.id, loading = loading.signal)) { root =>
      loading.set(true)

      assertEquals(attributeOf(root, "aria-busy"), Some("true"))
      assertEquals(bodyRows(root).size, 3)
    }
  }

  property("rendersOneRowPerItemAndKeysThemStably") {
    // Laminar's `split` matches items to existing elements by key, so a reordered list must move the
    // elements rather than rebuild them. A rebuilt row loses focus, loses a text selection, and
    // closes whatever the user had expanded — none of which a row-count assertion would catch, so
    // the identity of the DOM nodes is what is checked here.
    forAll { (seed: Int) =>
      val shuffled = scala.util.Random(seed).shuffle(brokers)
      val rows     = Var(brokers)

      mounted(DataTable(columns, rows.signal, _.id)) { root =>
        val before = bodyRows(root).map(row => (row.textContent, row))
        rows.set(shuffled)
        val after = bodyRows(root)

        val sameNodes = shuffled.forall { broker =>
          before.find(_._1.contains(broker.host)).exists(entry => after.contains(entry._2))
        }

        sameNodes && after.size == brokers.size
      }
    }
  }
}
