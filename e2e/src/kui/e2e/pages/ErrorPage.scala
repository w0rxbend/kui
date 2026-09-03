package kui.e2e.pages

import com.microsoft.playwright.Page

/** The 404 page, and the property that matters about it.
  *
  * A single-page application that renders "not found" as a bare message in an empty document has turned a
  * mistyped URL into a dead end. KUI renders it inside the frame, with the navigation still there, so the
  * user's next click gets them somewhere — which is why [[ErrorPage]] is checked alongside the navigation
  * rather than on its own.
  */
final class ErrorPage(page: Page) {

  def isNotFound: Boolean = page.locator("[data-testid='page-not-found']").count() > 0

  /** The address that was not found, echoed back so the user can see the typo. */
  def attemptedUrl: String = page.locator("[data-testid='not-found-url']").innerText().trim

  def hasHomeLink: Boolean = page.locator("[data-testid='not-found-home']").count() > 0
}
