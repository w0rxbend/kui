package kui.ui.kernel.component

import com.raquo.laminar.api.L.*
import com.raquo.laminar.codecs.StringAsIsCodec

/** How big a control is. One scale shared by every primitive, so a small button next to a small input line up
  * without either of them knowing about the other.
  */
enum Size(val modifier: String) {
  case Sm extends Size("sm")
  case Md extends Size("md")
  case Lg extends Size("lg")
}

/** The five ways KUI colours a piece of status: a tag, a toast, a banner, a dot.
  *
  * `Neutral` is not "no tone": it is the deliberate choice to say nothing about health, which is different
  * from saying everything is fine.
  */
enum Tone(val modifier: String) {
  case Neutral extends Tone("neutral")
  case Info extends Tone("info")
  case Success extends Tone("success")
  case Warning extends Tone("warning")
  case Danger extends Tone("danger")
}

/** Small pieces of plumbing every kernel primitive needs.
  *
  * Kept in one place because each is two lines and repeating them across eight components is how the eight
  * quietly stop agreeing with each other.
  */
object Components {

  /** The attribute end-to-end tests select on.
    *
    * Every primitive takes an optional `testId` and renders it here. E2E selectors use it and never a CSS
    * class or a piece of visible text, so that restyling a button or rewording its label never breaks a test
    * — and so that a test failing means the behaviour broke, which is the only reason a test should ever
    * fail.
    */
  def testIdAttr(testId: Option[String]): Modifier[HtmlElement] =
    testId.map(value => dataAttr("testid") := value).getOrElse(emptyMod)

  /** The same, for the SVG elements `Icon` produces. */
  def svgTestIdAttr(testId: Option[String]): Modifier[SvgElement] =
    testId.map(value => Components.SvgTestId := value).getOrElse(emptyMod)

  /** A number that has not been used before in this page.
    *
    * Used to give a label and the input it describes a matching `for`/`id` pair. Those ids must be unique
    * within the document, so they cannot be derived from anything the caller passes — two "Topic name" inputs
    * on one screen are perfectly normal — and they must be generated per instance rather than per call site.
    *
    * This is the one piece of mutable state the kernel keeps outside a `Var`, and it is safe to: JavaScript
    * in a browser tab is single-threaded, and nothing ever reads the counter back.
    */
  def nextId(prefix: String): String = {
    counter += 1
    s"$prefix-$counter"
  }

  /** `data-testid` for SVG. Laminar defines `dataAttr` only for HTML, so the SVG namespace needs the
    * attribute spelled out.
    */
  private val SvgTestId = svg.svgAttr("data-testid", StringAsIsCodec, None)

  private var counter: Int = 0
}
