package kui.build.design

import munit.FunSuite

/** Keeps `Tokens.scala` and `10-tokens.css` from drifting apart.
  *
  * Scala cannot see a CSS file, so nothing but this test stops the two lists diverging: a token added to the
  * stylesheet and forgotten in `Tokens` is invisible to every later reader, and a constant in `Tokens` naming
  * a property nothing defines resolves to an empty string at run time with no error anywhere.
  */
final class TokensSuite extends FunSuite {

  /** Every `--kui-*` custom property the stylesheet declares, whatever its value. */
  private val declared: Set[String] =
    """(--kui-[a-z0-9-]+)\s*:""".r
      .findAllMatchIn(TokensSuite.stylesheetRules)
      .map(_.group(1))
      .toSet

  test("every token in the stylesheet has a constant in Tokens") {
    assertEquals((declared -- Tokens.all.toSet).toList.sorted, Nil)
  }

  test("every constant in Tokens is declared by the stylesheet") {
    assertEquals((Tokens.all.toSet -- declared).toList.sorted, Nil)
  }

  test("Tokens.all has no duplicates") {
    assertEquals(Tokens.all.distinct.size, Tokens.all.size)
  }

  test("the set is dozens of tokens, not hundreds") {
    // Not a style preference: the decision in UI-002 is that KUI has semantic tokens and no
    // component-scoped ones. A token count drifting into the hundreds is the measurable symptom of
    // that rule being abandoned, and this is where it gets noticed.
    //
    // The ceiling was 60, then 80, and is 88. It moved the first time when the design import
    // (UI-013) brought a five-deep surface ramp, a paired text colour for every container, and a
    // second and third radius step. It moved the second time when the design screenshots were read
    // in full and turned out to draw five things the palette could not name: two more steps of the
    // text ramp (`text-strong`, `text-subtle`), five chart series, a card border that differs by
    // theme, the brand gradient, and the three fixed measurements of the application frame.
    //
    // Both moves are the palette getting *more complete*, which is the opposite of the failure this
    // guard exists to catch. The five series tokens in particular add no colour at all — each is an
    // alias of ink that already existed. Move it again only for the same kind of reason, and write
    // down what the reason was.
    assert(Tokens.all.size <= 88, s"${Tokens.all.size} tokens; see docs/frontend/tokens.md")
  }

  test("no colour, spacing or typography token names a component") {
    // The Kafbat trap this decision exists to avoid is `theme.button.primary.hover.background`.
    // Stacking order is the one exception and is checked separately below: `--kui-z-dialog` names a
    // *layer*, and a layer has no meaningful name other than what sits on it.
    val componentWords = List("button", "input", "dialog", "table", "card", "tab", "toast", "drawer")
    val checked = Tokens.Color.all ++ Tokens.Space.all ++ Tokens.Font.all ++ Tokens.Radius.all
    val offenders = checked.filter(token => componentWords.exists(token.contains))

    assertEquals(offenders, Nil, "component-scoped tokens are forbidden; see docs/frontend/tokens.md")
  }
}

private object TokensSuite {

  /** The stylesheet with its comments stripped, so that a token name merely *mentioned* in prose is not
    * mistaken for a declaration.
    */
  val stylesheetRules: String =
    // `(?s)` makes `.` match newlines, which a CSS block comment is full of.
    """(?s)/\*.*?\*/""".r.replaceAllIn(DesignSources.stylesheet, "")
}
