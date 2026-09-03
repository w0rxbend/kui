package kui.ui.kernel.theme

import munit.FunSuite

/** Proves that every colour pair KUI documents as "text on a background" is actually legible.
  *
  * ## Why a test and not a review
  *
  * Contrast is the one visual property that is objectively measurable and invisible to the person
  * choosing the colour. A designer with a good monitor in a lit room picks a grey that a user on a
  * dimmed laptop cannot read, and nobody notices until an accessibility audit. Neither reference
  * product checks it, and several of Kafbat's muted-text pairs fail.
  *
  * ## Where the inputs come from
  *
  * Both are compiled into this test binary by `build.mill` (Scala.js has no filesystem):
  *
  *   - the token stylesheet, so the checked values are the ones that actually ship;
  *   - `docs/frontend/tokens.md`, so the list of pairs is *documentation* that happens to be
  *     executable. Adding a pair to the table there makes this suite check it. That ordering is
  *     deliberate: a pair list maintained only in a test file drifts out of the docs within a month.
  */
final class ContrastSuite extends FunSuite {

  /** The two WCAG 2.2 level-AA thresholds, for reference in failure messages. */
  private val BodyTextMinimum = 4.5

  test("the stylesheet defines every colour in both themes") {
    val light = ContrastSuite.lightPalette
    val dark  = ContrastSuite.darkPalette

    assertEquals(light.keySet, Tokens.Color.all.toSet, "light palette does not match Tokens.Color")
    assertEquals(dark.keySet, Tokens.Color.all.toSet, "dark palette does not match Tokens.Color")
  }

  test("the documented pair list is not empty") {
    // A parser that silently matched nothing would make every assertion below vacuously true, which
    // is the failure mode this whole suite exists to prevent.
    assert(ContrastSuite.documentedPairs.nonEmpty, "no contrast pairs parsed out of docs/frontend/tokens.md")
    assert(
      ContrastSuite.documentedPairs.exists(_.minimum == BodyTextMinimum),
      "no body-text pair (4.5) in the documented list; the table was probably parsed wrongly"
    )
  }

  test("every documented pair meets WCAG AA in the light theme") {
    checkAll("light", ContrastSuite.lightPalette)
  }

  test("every documented pair meets WCAG AA in the dark theme") {
    checkAll("dark", ContrastSuite.darkPalette)
  }

  test("the media-query dark palette and the explicit dark palette agree") {
    // The stylesheet declares dark twice: once for `prefers-color-scheme` and once for an explicit
    // `data-theme="dark"`. They must stay identical, or a user who picks dark by hand sees a
    // different product from one whose laptop picked it. Nothing but a test can enforce that.
    assertEquals(ContrastSuite.mediaQueryDarkPalette, ContrastSuite.darkPalette)
  }

  private def checkAll(themeName: String, palette: Map[String, ContrastSuite.Rgb]): Unit = {
    val failures = ContrastSuite.documentedPairs.flatMap { pair =>
      for {
        foreground <- palette.get(pair.foreground)
        background <- palette.get(pair.background)
        ratio = ContrastSuite.contrastRatio(foreground, background)
        if ratio < pair.minimum
      } yield f"$themeName: ${pair.foreground} on ${pair.background} is $ratio%.2f:1, needs ${pair.minimum}%.1f:1"
    }

    assertEquals(failures, List.empty[String], failures.mkString("\n"))
  }
}

private object ContrastSuite {

  /** A colour as the three channel values the contrast formula needs, each 0–255. */
  final case class Rgb(red: Int, green: Int, blue: Int)

  final case class Pair(foreground: String, background: String, minimum: Double)

  /** `--kui-color-text: #171a1c;` — the name and the value, ignoring the provenance comment. */
  private val Declaration = """(--kui-color-[a-z-]+)\s*:\s*#([0-9a-fA-F]{6})\s*;""".r

  /** The stylesheet, cut into the three blocks that declare colours.
    *
    * The file is ordered: `:root` (light), then the `prefers-color-scheme` media query, then
    * `:root[data-theme="dark"]`. Splitting on those two markers is enough, and is far less
    * machinery than a real CSS parser for a file this project owns and formats itself.
    */
  private val stylesheet = TokenFixtures.stylesheet

  // `lastIndexOf`, because the file's header comment explains both selectors and therefore contains
  // both strings. The real rules are the last occurrence of each.
  private val mediaStart = stylesheet.lastIndexOf("@media (prefers-color-scheme: dark)")
  private val darkStart  = stylesheet.lastIndexOf(":root[data-theme=\"dark\"]")

  private val lightBlock        = stylesheet.substring(0, mediaStart)
  private val mediaQueryBlock   = stylesheet.substring(mediaStart, darkStart)
  private val explicitDarkBlock = stylesheet.substring(darkStart)

  private def palette(block: String): Map[String, Rgb] =
    Declaration.findAllMatchIn(block).map(found => found.group(1) -> parseHex(found.group(2))).toMap

  val lightPalette: Map[String, Rgb]          = palette(lightBlock)
  val mediaQueryDarkPalette: Map[String, Rgb] = palette(mediaQueryBlock)
  val darkPalette: Map[String, Rgb]           = palette(explicitDarkBlock)

  /** Every three-cell table row in `tokens.md` whose first two cells are colour tokens.
    *
    * Shaped so that the colour table (four cells) and every other table in the document are ignored
    * without the parser needing to know where in the file the pair table is.
    */
  val documentedPairs: List[Pair] =
    TokenFixtures.documentation.linesIterator
      .map(_.trim)
      .filter(_.startsWith("|"))
      .flatMap { line =>
        val cells = line.split('|').toList.map(_.trim.replace("`", "")).filter(_.nonEmpty)
        cells match {
          case foreground :: background :: minimum :: Nil
              if foreground.startsWith("--kui-color-") && background.startsWith("--kui-color-") =>
            minimum.toDoubleOption.map(Pair(foreground, background, _))
          case _ => None
        }
      }
      .toList

  private def parseHex(digits: String): Rgb =
    Rgb(
      Integer.parseInt(digits.substring(0, 2), 16),
      Integer.parseInt(digits.substring(2, 4), 16),
      Integer.parseInt(digits.substring(4, 6), 16)
    )

  /** Relative luminance, as WCAG 2.2 defines it.
    *
    * The channel values are "gamma-encoded": the number stored in a hex colour is not proportional
    * to the light the screen emits, because 8 bits are spent where the eye is most sensitive. The
    * piecewise expression below undoes that encoding, and the three weights are how much each
    * primary contributes to perceived brightness — green far more than blue.
    */
  private def luminance(colour: Rgb): Double = {
    def channel(value: Int): Double = {
      val fraction = value / 255.0
      if fraction <= 0.03928 then fraction / 12.92 else Math.pow((fraction + 0.055) / 1.055, 2.4)
    }

    0.2126 * channel(colour.red) + 0.7152 * channel(colour.green) + 0.0722 * channel(colour.blue)
  }

  /** The WCAG contrast ratio: 1 for two identical colours, 21 for black on white.
    *
    * The `+ 0.05` is a deliberate part of the formula, not a guard against division by zero: it
    * models the light a real screen reflects even when displaying black, which is why pure black on
    * pure white is 21:1 and not infinite.
    */
  def contrastRatio(a: Rgb, b: Rgb): Double = {
    val first  = luminance(a)
    val second = luminance(b)
    (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05)
  }
}
