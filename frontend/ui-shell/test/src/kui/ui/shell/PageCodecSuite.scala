package kui.ui.shell

import munit.{FunSuite, ScalaCheckSuite}
import org.scalacheck.Prop.forAll

import kui.ui.kernel.feature.Page

/** `history.state`, and the deploy that happens while a tab is open.
  *
  * The failure this suite exists to prevent is specific and unpleasant: a user has KUI open, KUI is deployed,
  * the user presses Back, and the browser hands the new build a string the old build wrote. If reading that
  * string can throw, the application dies on a keystroke the user is entitled to press.
  */
class PageCodecSuite extends FunSuite {

  private val everyShellPage: List[Page] = List(
    ShellPage.Home,
    ShellPage.Settings,
    ShellPage.Gallery,
    ShellPage.NotFound("/ui/nope"),
    ShellPage.Forbidden("the schema registry")
  )

  test("everyShellPageRoundTrips") {
    everyShellPage.foreach { page =>
      assertEquals(PageCodec.decode(PageCodec.encode(page)), page, s"$page did not survive the round trip")
    }
  }

  test("anUnknownSerializedPageDecodesToNotFoundRatherThanThrowing") {
    assertEquals(PageCodec.decode("""{"page":"invented-in-2027"}"""), ShellPage.NotFound(""))
  }

  test("garbageDecodesToNotFoundRatherThanThrowing") {
    List("", "not json at all", "{", "null", "[]", """{"unrelated":true}""").foreach { raw =>
      PageCodec.decode(raw) match {
        case ShellPage.NotFound(_) => ()
        case other => fail(s"'$raw' should have decoded to NotFound, got $other")
      }
    }
  }

  test("aPageThisBuildCannotEncodeBecomesARecognisablePlaceholder") {
    // A feature page, which M0 has no codec for. It is written as `unknown` rather than as nothing,
    // so that somebody looking at `history.state` in devtools can see why Back behaved oddly.
    object SomeFeaturePage extends Page
    assertEquals(PageCodec.encode(SomeFeaturePage), """{"page":"unknown"}""")
    assertEquals(PageCodec.decode(PageCodec.encode(SomeFeaturePage)), ShellPage.NotFound(""))
  }

  test("theEncodingIsStableSoAnOlderTabsStateIsStillReadable") {
    // These strings are what previous builds wrote. Changing them is a compatibility break, and this
    // assertion is where somebody is told so before their users find out.
    assertEquals(PageCodec.encode(ShellPage.Home), """{"page":"home"}""")
    assertEquals(PageCodec.encode(ShellPage.Settings), """{"page":"settings"}""")
    assertEquals(PageCodec.encode(ShellPage.Gallery), """{"page":"gallery"}""")
  }
}

class PageCodecPropertySuite extends ScalaCheckSuite {

  property("everyNotFoundUrlRoundTrips") {
    forAll { (url: String) =>
      PageCodec.decode(PageCodec.encode(ShellPage.NotFound(url))) == ShellPage.NotFound(url)
    }
  }

  property("everyForbiddenSubjectRoundTrips") {
    forAll { (what: String) =>
      PageCodec.decode(PageCodec.encode(ShellPage.Forbidden(what))) == ShellPage.Forbidden(what)
    }
  }

  property("decodingAnyStringNeverThrows") {
    forAll { (raw: String) =>
      // The assertion is that this line returns rather than throws; what it returns does not matter.
      PageCodec.decode(raw) != null
    }
  }
}
