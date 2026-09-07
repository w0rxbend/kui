package kui.tools

import munit.FunSuite

import kui.kernel.error.ErrorCode

/** The generator's one refusal, and the fact that it is not refusing today.
  *
  * `BrowserConstants.render` answers `Left` for exactly one thing, and it is not staleness: an RBAC
  * vocabulary in which a connector action's fallback is spelled differently from the action itself. The
  * browser's permission evaluator reads `ConnectorFallbackActions` as a *list* and applies the same action
  * name to the parent connect cluster, so a differently-named parent would silently grant or withhold the
  * wrong permission -- a disabled button, which looks exactly like a permission the user does not hold.
  *
  * Until 2026-09-07 that refusal was a `throw` inside a private method whose only caller was the renderer,
  * over a fold of `Action.values` that no test can put into the failing state. It failed `DisableSyntax` and
  * nothing could reach it. It is now a value over the pairs, which is what makes the two cases below
  * possible at all.
  */
final class BrowserConstantsSuite extends FunSuite {

  test("a fallback spelled differently from its own action is named, in both directions") {
    val mismatched = BrowserConstants.mismatchedFallbacks(
      List("EDIT" -> "EDIT", "RESTART" -> "OPERATE", "VIEW" -> "VIEW", "DELETE" -> "REMOVE")
    )

    assertEquals(mismatched, List("RESTART -> OPERATE", "DELETE -> REMOVE"))
    assert(clue(BrowserConstants.fallbackProblem(mismatched)).contains("RESTART -> OPERATE"))
  }

  test("the vocabulary this build ships has no mismatch, so the file renders") {
    val declared = BrowserConstants.declaredFallbacks

    assert(declared.nonEmpty, "no connector action declares a parent fallback at all")
    assertEquals(BrowserConstants.mismatchedFallbacks(declared), Nil)
    assert(BrowserConstants.render(ErrorCode.values.toList).isRight)
  }

  test("a mismatch refuses the whole rendering rather than emitting a list the browser misreads") {
    // The seam, not the fold: `render` is what `BrowserConstantsMain` calls, and what it must not do is
    // return a `Right` carrying a `ConnectorFallbackActions` array whose names do not mean what the
    // evaluator assumes. Feeding the check the vocabulary's own pairs with one renamed shows the sentence
    // the caller would print; feeding it the real ones shows the file rendering.
    val renamed = BrowserConstants.declaredFallbacks.map((action, parent) => (action, parent + "_RENAMED"))

    val problem = BrowserConstants.fallbackProblem(BrowserConstants.mismatchedFallbacks(renamed))
    assert(problem.contains("_RENAMED"), problem)
    assert(problem.contains("fix both together"), problem)

    BrowserConstants.render(ErrorCode.values.toList) match {
      case Right(rendered) => assert(rendered.contains("export const ConnectorFallbackActions"), rendered)
      case Left(unexpected) => fail(s"the shipped vocabulary should render: $unexpected")
    }
  }
}
