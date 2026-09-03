package kui.ui.shell.feature

import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.scalajs.dom

import kui.ui.kernel.sse.SseConnection
import kui.ui.shell.Messages

/** The banner, and the rule it exists to make honest: a dropped capability stream means the picture is
  * stale, not that every feature is broken.
  */
class CapabilityBannerSuite extends FunSuite {

  private def mounted[A](element: HtmlElement)(check: dom.Element => A): A = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container): Unit
    val rendered = render(container, element)
    try check(element.ref)
    finally {
      rendered.unmount(): Unit
      dom.document.body.removeChild(container): Unit
    }
  }

  test("anOpenStreamWithNothingDegradedShowsNoBanner") {
    mounted(CapabilityBanner(Val(SseConnection.Open), Val(Nil))) { root =>
      assertEquals(root.getAttribute("hidden"), "")
      assertEquals(root.textContent, "")
    }
  }

  test("aClosedStreamSaysThePictureMayBeOutOfDate") {
    val connection = Var[SseConnection](SseConnection.Open)

    mounted(CapabilityBanner(connection.signal, Val(Nil))) { root =>
      connection.set(SseConnection.Closed("the server ended the stream"))
      assertEquals(Option(root.getAttribute("hidden")), None)
      assert(root.textContent.contains("lost its live connection"), root.textContent)
      // Announced, not shouted: `status` tells the user without interrupting whatever they are doing.
      assertEquals(root.getAttribute("role"), "status")
    }
  }

  test("reconnectingIsNotWorthABanner") {
    // A stream between attempts is ordinary — a proxy recycling a connection, a laptop's wifi
    // blinking — and a banner that flickers every few minutes is one people learn to ignore.
    mounted(CapabilityBanner(Val(SseConnection.Reconnecting(2)), Val(Nil))) { root =>
      assertEquals(root.getAttribute("hidden"), "")
    }
  }

  test("degradedFeaturesAreNamed") {
    mounted(CapabilityBanner(Val(SseConnection.Open), Val(List("Clusters")))) { root =>
      assertEquals(root.textContent.contains(Messages.degradedBanner(List("Clusters"))), true)
    }
  }

  test("aStaleStreamOutranksADegradedFeature") {
    // If the picture is stale, saying "Clusters is degraded" would be asserting something KUI is no
    // longer being told. The staleness is the more important and the more truthful message.
    mounted(CapabilityBanner(Val(SseConnection.Closed("gone")), Val(List("Clusters")))) { root =>
      assert(root.textContent.contains("lost its live connection"), root.textContent)
    }
  }
}
