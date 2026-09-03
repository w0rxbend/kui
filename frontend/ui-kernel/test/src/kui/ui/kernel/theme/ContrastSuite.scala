package kui.ui.kernel.theme

import munit.FunSuite

/** Proves that every colour pair KUI documents as "text on a background" is actually legible, in every
  * combination of theme and accent the product can be in.
  *
  * ## Why a test and not a review
  *
  * Contrast is the one visual property that is objectively measurable and invisible to the person choosing
  * the colour. A designer with a good monitor in a lit room picks a grey that a user on a dimmed laptop
  * cannot read, and nobody notices until an accessibility audit. Neither reference product checks it, and the
  * imported design has three pairs that miss the threshold — which is how we know it was worth checking.
  *
  * ## Why it resolves the cascade instead of reading blocks
  *
  * The stylesheet declares the same token many times: once for light, once for each of the three non-default
  * accents, twice for dark (the system preference and the explicit choice), and again for each accent inside
  * each of those. Which declaration a browser actually uses depends on selector specificity and source order.
  * An earlier version of this suite split the file into three text ranges and read each one, which worked
  * only as long as the file had exactly three blocks in a fixed order.
  *
  * So this version does what a browser does: it parses the rules, and for each of the eight states the
  * product can be in — four accents times light and dark — applies every rule whose selector matches, most
  * specific last. What it checks is therefore the colour a user would really see, and reordering the
  * stylesheet cannot make it check the wrong thing.
  *
  * ## Where the inputs come from
  *
  * Both are compiled into this test binary by `build.mill` (Scala.js has no filesystem):
  *
  *   - the token stylesheet, so the checked values are the ones that actually ship;
  *   - `docs/frontend/tokens.md`, so the list of pairs is *documentation* that happens to be executable.
  *     Adding a pair to the table there makes this suite check it. That ordering is deliberate: a pair list
  *     maintained only in a test file drifts out of the docs within a month.
  */
final class ContrastSuite extends FunSuite {

  import ContrastSuite.*

  /** The lower of the two WCAG 2.2 level-AA thresholds, used to sanity-check the parsed table. */
  private val BodyTextMinimum = 4.5

  test("the stylesheet defines every colour in every theme and accent") {
    for {
      state <- States
    } assertEquals(
      colours(state).keySet,
      Tokens.Color.all.toSet,
      s"$state does not define exactly the colours in Tokens.Color"
    )
  }

  test("the documented pair list is not empty") {
    // A parser that silently matched nothing would make every assertion below vacuously true, which
    // is the failure mode this whole suite exists to prevent.
    assert(documentedPairs.nonEmpty, "no contrast pairs parsed out of docs/frontend/tokens.md")
    assert(
      documentedPairs.exists(_.minimum == BodyTextMinimum),
      "no body-text pair (4.5) in the documented list; the table was probably parsed wrongly"
    )
  }

  test("every documented pair names a token that exists") {
    // Previously a pair naming a token the stylesheet does not define was skipped in silence: the
    // lookup returned nothing and the pair simply vanished from the check. That is the worst
    // possible failure for this suite, because a typo in the documentation would read as a pass.
    val known = Tokens.Color.all.toSet
    val unknown =
      documentedPairs.flatMap(pair => List(pair.foreground, pair.background)).distinct.filterNot(known)

    assertEquals(
      unknown.sorted,
      Nil,
      "docs/frontend/tokens.md names colour tokens the stylesheet does not define"
    )
  }

  test("every documented pair meets WCAG AA in every theme and accent") {
    val failures = for {
      state <- States
      palette = colours(state)
      pair <- documentedPairs
      // Safe: the test above has already proved that every documented token resolves, and the one
      // above that has proved every state defines all of them.
      ratio = contrastRatio(palette(pair.foreground), palette(pair.background))
      if ratio < pair.minimum
    } yield f"$state: ${pair.foreground} on ${pair.background} is $ratio%.2f:1, needs ${pair.minimum}%.1f:1"

    assertEquals(failures, List.empty[String], failures.mkString("\n"))
  }

  test("the system's dark preference and an explicit dark choice paint the same colours") {
    // The stylesheet declares dark twice: once for `prefers-color-scheme` and once for
    // `data-theme="dark"`. They must stay identical, or a user who picks dark by hand sees a
    // different product from one whose laptop picked it. Nothing but a test can enforce that.
    for {
      accent <- Accents
    } assertEquals(
      colours(State(accent, Mode.SystemDark)),
      colours(State(accent, Mode.ExplicitDark)),
      s"the two dark palettes disagree for the $accent accent"
    )
  }

  test("the accent seed changes the accent and nothing else") {
    // This is the property that makes a fourth accent nearly free, and it is worth asserting rather
    // than trusting: the moment a seed block redefines a surface, adding an accent stops being a
    // four-line change and every contrast pair has to be re-argued for that accent.
    val seedOwned = Set(
      Tokens.Color.Primary,
      Tokens.Color.PrimaryContrast,
      Tokens.Color.PrimaryContainer,
      Tokens.Color.PrimaryContainerContrast,
      Tokens.Color.Focus
    )

    for {
      mode <- Mode.values.toList
      accent <- Accents.filterNot(_ == DefaultAccent)
    } {
      val default = colours(State(DefaultAccent, mode))
      val reseeded = colours(State(accent, mode))
      val differing = default.filter((token, value) => reseeded(token) != value).keySet

      assertEquals(
        differing -- seedOwned,
        Set.empty[String],
        s"the $accent seed changes colours outside the accent, in $mode"
      )
    }
  }

  test("compact density moves the table row padding and nothing else") {
    val comfortable = declarations(State(DefaultAccent, Mode.Light))
    val compact = declarations(State(DefaultAccent, Mode.Light, compact = true))

    assertEquals(comfortable(Tokens.Density.RowPaddingY), "15px")
    assertEquals(compact(Tokens.Density.RowPaddingY), "9px")
    assertEquals(
      (comfortable.toSet -- compact.toSet).map(_._1),
      Set(Tokens.Density.RowPaddingY),
      "the compact switch is documented as changing exactly one value"
    )
  }
}

private object ContrastSuite {

  /** A colour as the three channel values the contrast formula needs, each 0–255. */
  final case class Rgb(red: Int, green: Int, blue: Int)

  final case class Pair(foreground: String, background: String, minimum: Double)

  /** The three ways the product can be light or dark. `SystemDark` and `ExplicitDark` are separate because
    * they are separate rule sets in the stylesheet and a test exists to keep them equal.
    */
  enum Mode {
    case Light, SystemDark, ExplicitDark
  }

  val DefaultAccent = "blue"

  val Accents: List[String] = List(DefaultAccent, "teal", "green", "amber")

  /** One combination of the three attributes the stylesheet keys off. */
  final case class State(accent: String, mode: Mode, compact: Boolean = false) {
    override def toString: String =
      s"$accent/${mode.toString.toLowerCase}${if compact then "/compact" else ""}"
  }

  val States: List[State] =
    for {
      accent <- Accents
      mode <- Mode.values.toList
    } yield State(accent, mode)

  // --- Reading the stylesheet ------------------------------------------------------------------

  /** A single rule: what has to be true for it to apply, and what it sets.
    *
    * @param needsDarkMedia
    *   the rule is inside `@media (prefers-color-scheme: dark)`.
    * @param required
    *   attribute values the element must have, as name to value.
    * @param forbidden
    *   attribute values the element must not have — the `:not([data-theme="light"])` guard.
    * @param specificity
    *   how strongly the selector binds, in the only unit this file needs: one for the `:root` pseudo-class
    *   plus one for each attribute selector, whether or not it sits inside a `:not`.
    * @param order
    *   position in the file, which breaks ties between equally specific rules.
    */
  final case class Rule(
      needsDarkMedia: Boolean,
      required: Map[String, String],
      forbidden: Map[String, String],
      specificity: Int,
      order: Int,
      declarations: List[(String, String)]
  ) {
    def appliesTo(state: State, attributes: Map[String, String]): Boolean =
      (!needsDarkMedia || state.mode == Mode.SystemDark) &&
        required.forall((name, value) => attributes.get(name).contains(value)) &&
        forbidden.forall((name, value) => !attributes.get(name).contains(value))
  }

  /** The stylesheet with its comments removed, so that a selector quoted in the header prose cannot be
    * mistaken for a rule.
    */
  private val stylesheet: String =
    // `(?s)` makes `.` match newlines, which a CSS block comment is full of.
    """(?s)/\*.*?\*/""".r.replaceAllIn(TokenFixtures.stylesheet, "")

  private val AttributeSelector = """\[([a-z-]+)="([a-z]+)"\]""".r
  private val NotSelector = """:not\(([^)]*)\)""".r
  private val DeclarationLine = """(--kui-[a-z0-9-]+)\s*:\s*([^;]+);""".r

  val rules: List[Rule] = readRules(stylesheet, inDarkMedia = false)

  /** Cuts the stylesheet into rules, descending into the one `@media` block it contains.
    *
    * This is a reader for a file this project owns and formats itself, not a CSS parser: it assumes every
    * declaration block is flat and every at-rule is a media query. Both hold here, and both are far cheaper
    * to guarantee than a real parser is to carry.
    */
  private def readRules(css: String, inDarkMedia: Boolean): List[Rule] = {
    val collected = List.newBuilder[Rule]
    var cursor = 0

    while css.indexOf('{', cursor) >= 0 do {
      val open = css.indexOf('{', cursor)
      val prelude = css.substring(cursor, open).trim
      val close = matchingBrace(css, open)
      val body = css.substring(open + 1, close)

      if prelude.startsWith("@media") then
        collected ++= readRules(body, inDarkMedia || prelude.contains("prefers-color-scheme: dark"))
      else if prelude.nonEmpty then collected += rule(prelude, body, inDarkMedia)

      cursor = close + 1
    }

    // Numbered after the fact, so that a rule nested in a media query still sorts after everything
    // written above that media query — which is what the cascade does.
    collected.result().zipWithIndex.map((parsed, index) => parsed.copy(order = index))
  }

  private def matchingBrace(css: String, open: Int): Int = {
    var depth = 0
    var index = open

    while index < css.length do {
      if css.charAt(index) == '{' then depth += 1
      else if css.charAt(index) == '}' then {
        depth -= 1
        if depth == 0 then return index
      }
      index += 1
    }

    throw new IllegalStateException(s"unbalanced braces in the token stylesheet at offset $open")
  }

  private def rule(selector: String, body: String, inDarkMedia: Boolean): Rule = {
    val negated = NotSelector.findAllMatchIn(selector).map(_.group(1)).mkString(" ")
    val positive = NotSelector.replaceAllIn(selector, "")

    def attributes(text: String): Map[String, String] =
      AttributeSelector.findAllMatchIn(text).map(found => found.group(1) -> found.group(2)).toMap

    val required = attributes(positive)
    val forbidden = attributes(negated)

    Rule(
      needsDarkMedia = inDarkMedia,
      required = required,
      forbidden = forbidden,
      specificity = 1 + required.size + forbidden.size,
      order = 0,
      declarations = DeclarationLine
        .findAllMatchIn(body)
        .map(found => found.group(1) -> found.group(2).trim)
        .toList
    )
  }

  /** The custom properties a browser would end up with, for one state. */
  def declarations(state: State): Map[String, String] = {
    val attributes = Map.newBuilder[String, String]
    state.mode match {
      case Mode.Light => attributes += "data-theme" -> "light"
      case Mode.ExplicitDark => attributes += "data-theme" -> "dark"
      case Mode.SystemDark => ()
    }
    if state.accent != DefaultAccent then attributes += "data-accent" -> state.accent
    if state.compact then attributes += "data-density" -> "compact"

    val present = attributes.result()

    rules
      .filter(_.appliesTo(state, present))
      .sortBy(applicable => (applicable.specificity, applicable.order))
      .foldLeft(Map.empty[String, String]) { (resolved, applicable) =>
        resolved ++ applicable.declarations
      }
  }

  /** The colour tokens a browser would end up with, for one state. */
  def colours(state: State): Map[String, Rgb] =
    declarations(state).collect {
      case (name, value) if name.startsWith("--kui-color-") => name -> parseColour(name, value)
    }

  private val Hex = """#([0-9a-fA-F]{6})""".r
  private val Rgba = """rgba\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*[\d.]+\s*\)""".r

  /** Reads either notation the stylesheet uses.
    *
    * `rgba` appears once, for the translucent hover wash. Its alpha is dropped here because no documented
    * pair names it: a state layer is composited over whatever is beneath it, so its legibility is a property
    * of the pair underneath and not of the wash.
    */
  private def parseColour(name: String, value: String): Rgb =
    value match {
      case Hex(digits) =>
        Rgb(
          Integer.parseInt(digits.substring(0, 2), 16),
          Integer.parseInt(digits.substring(2, 4), 16),
          Integer.parseInt(digits.substring(4, 6), 16)
        )
      case Rgba(red, green, blue) => Rgb(red.toInt, green.toInt, blue.toInt)
      case other => throw new IllegalArgumentException(s"$name has an unreadable colour: $other")
    }

  /** Every three-cell table row in `tokens.md` whose first two cells are colour tokens.
    *
    * Shaped so that the colour table (four cells) and every other table in the document are ignored without
    * the parser needing to know where in the file the pair table is.
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

  /** Relative luminance, as WCAG 2.2 defines it.
    *
    * The channel values are "gamma-encoded": the number stored in a hex colour is not proportional to the
    * light the screen emits, because 8 bits are spent where the eye is most sensitive. The piecewise
    * expression below undoes that encoding, and the three weights are how much each primary contributes to
    * perceived brightness — green far more than blue.
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
    * The `+ 0.05` is a deliberate part of the formula, not a guard against division by zero: it models the
    * light a real screen reflects even when displaying black, which is why pure black on pure white is 21:1
    * and not infinite.
    */
  def contrastRatio(a: Rgb, b: Rgb): Double = {
    val first = luminance(a)
    val second = luminance(b)
    (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05)
  }
}
