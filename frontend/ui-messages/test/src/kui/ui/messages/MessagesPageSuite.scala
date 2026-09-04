package kui.ui.messages

import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.scalajs.dom

import kui.kernel.{ClusterId, TopicName}
import kui.ui.messages.browse.{BrowseEvent, BrowseQuery, BrowseSession}
import kui.ui.kernel.sse.SseHandle

/** That the message browser mounts.
  *
  * ## Why "it mounts" is worth a suite of its own
  *
  * Every other suite in this module tests a piece: the wire format, the routes, the table, the flattener.
  * All of them were green while the screen itself rendered nothing but "Something went wrong", because
  * nothing anywhere mounted `MessagesPage`. The failure was a Laminar `controlled` input paired with the
  * `change` event, which Laminar rejects at *run time* — an exception thrown during mounting, invisible to a
  * compiler and to every test of a part.
  *
  * So this suite mounts the whole page and asserts that the controls are there and no error boundary fired.
  * It needs no server: the session is handed a stream opener that is never called, because opening the
  * screen deliberately starts no browse.
  */
final class MessagesPageSuite extends FunSuite {

  private val topic = TopicName.unsafe("orders.v1")

  /** A session whose stream opener would fail the test if the page ever called it on mount. */
  private def session: BrowseSession =
    new BrowseSession(
      apiRoot = "/api/v1",
      cluster = ClusterId.unsafe("quickstart"),
      topic = topic,
      open = (_, _, _, _: BrowseQuery) =>
        fail("opening the screen must not start a browse; the Read button does that")
          .asInstanceOf[SseHandle[BrowseEvent]]
    )

  private def mounted(body: dom.Element => Unit): Unit = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container): Unit
    val root = render(container, MessagesPage(topic, Val("UTC"), session))
    try body(container)
    finally {
      root.unmount(): Unit
      dom.document.body.removeChild(container): Unit
    }
  }

  private def find(container: dom.Element, testId: String): dom.Element =
    Option(container.querySelector(s"[data-testid='$testId']"))
      .getOrElse(fail(s"no element with testid '$testId' in ${container.innerHTML}"))

  test("thePageMountsWithoutRaising") {
    mounted { container =>
      find(container, "page-messages"): Unit
      assert(
        !container.textContent.contains("Something went wrong"),
        s"the page rendered its error state: ${container.textContent}"
      )
    }
  }

  test("theStartControlAndTheTextBoxesAreAllPresent") {
    // The text boxes are the controls whose `controlled(value, onChange)` binding threw on mount. Naming
    // them here means a future edit that reintroduces the pairing fails this test rather than a user's
    // screen. The offset and time boxes are not among them: they are drawn only once the corresponding
    // start is chosen, and they are built by the same function these two are.
    mounted { container =>
      find(container, "messages-start"): Unit
      find(container, "messages-partitions"): Unit
      find(container, "messages-contains"): Unit
    }
  }

  test("theTopicIsTheHeading") {
    mounted(container => assert(container.textContent.contains(topic.value), container.textContent))
  }
}
