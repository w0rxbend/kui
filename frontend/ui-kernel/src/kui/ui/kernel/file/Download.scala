package kui.ui.kernel.file

import scala.scalajs.js

import com.raquo.laminar.api.L.*
import com.raquo.laminar.codecs.StringAsIsCodec
import org.scalajs.dom

/** Handing the user a file the page made itself.
  *
  * ## Why the file is built in the browser
  *
  * Because the rows are already there. An export of what is on screen is an export of data the page has
  * decoded, filtered and laid out; asking the server to read the topic a second time would produce a
  * *different* file — records written in between would be in it, records the browser's own filter removed
  * would come back — and "export" would stop meaning "this, as a file". A bounded export of what a person is
  * looking at is exactly the operation, and it needs no round trip.
  *
  * An export larger than one screenful is a different feature and a server one: it needs the projection
  * pushed down into the read, which is KU-030 and not this.
  *
  * ## The mechanics, and the one line that matters
  *
  * A `Blob`, an object URL, an anchor with `download`, one click, and then the URL is revoked. The revoke is
  * the line worth pointing at: an object URL holds its blob alive for the lifetime of the document, so a
  * screen that exports fifty times without revoking is holding fifty copies of its data with no way to name
  * them. It happens a tick later, because revoking synchronously after `click()` cancels the very download
  * that was just requested in some browsers.
  *
  * The anchor is built through Laminar rather than `createElement`, which is not a style preference: it is
  * what gives a correctly *typed* anchor without a cast, and casts are a scalafix error in this codebase.
  */
object Download {

  /** The prefix a spreadsheet needs in order to read the file as UTF-8.
    *
    * A byte-order mark, and it is here reluctantly. Excel on Windows still reads a plain UTF-8 CSV as the
    * system code page, which turns every non-ASCII character in a Kafka payload into mojibake — and a topic
    * carrying German or Japanese text is the ordinary case, not the exotic one. Every other reader ignores
    * the mark.
    */
  val Bom: String = "﻿"

  /** Offers `content` to the user as a file called `name`. */
  def text(name: String, mediaType: String, content: String, withBom: Boolean = false): Unit = {
    val body = if withBom then Bom + content else content
    // The bag is filled field by field rather than through `dom.BlobPropertyBag(...)`, whose companion
    // scalajs-dom deprecated wholesale in 2.0 — and a deprecated call is a fatal warning in this build.
    val options = new dom.BlobPropertyBag {}
    options.`type` = mediaType

    val blob = new dom.Blob(js.Array(body), options)

    offer(name, dom.URL.createObjectURL(blob))
  }

  /** The attribute that turns a link into a save.
    *
    * Spelled out rather than reached for through `dataAttr`, which was the defect this replaced: `dataAttr`
    * builds a **`data-`** prefixed attribute, so `dataAttr("download")` set `data-download` and the anchor
    * had no `download` attribute at all. An anchor to a `blob:` URL without one is not a save — the browser
    * either opens the blob in place or writes a file with a generated name — so Export CSV built the right
    * document, handed it to the browser, and gave the user nothing they could find afterwards.
    */
  private val downloadAttr = htmlAttr("download", StringAsIsCodec)

  /** The anchor one export is made of. Separated from [[offer]] so that a test can assert the attribute that
    * carries the filename, which is the whole of what went wrong before.
    */
  private[file] def anchorFor(name: String, url: String): dom.html.Anchor =
    // Hidden, because it exists for one click and is gone again; a visible link appearing at the bottom of
    // the page for one frame is the sort of flicker nobody can explain afterwards.
    a(href := url, downloadAttr := name, display := "none").ref

  private def offer(name: String, url: String): Unit = {
    val element = anchorFor(name, url)

    dom.document.body.appendChild(element): Unit
    element.click()
    dom.document.body.removeChild(element): Unit

    // One tick later, so the download has started. Revoking synchronously cancels it in some browsers, and
    // never revoking keeps the whole exported document alive for as long as the page is open.
    dom.window.setTimeout(() => dom.URL.revokeObjectURL(url), 0): Unit
  }
}
