package kui.e2e.pages

import com.microsoft.playwright.Page

/** The settings screen.
  *
  * It earns a page object for one reason: it is the "everything else still works" check in the
  * fault-isolation scenario. It is a shell page with no service behind it, so if it stops working while the
  * cluster service is down, the failure has escaped the feature it belongs to — which is precisely the thing
  * KUI claims cannot happen.
  */
final class SettingsPage(page: Page) {

  def isVisible: Boolean = page.locator("[data-testid='page-settings']").count() > 0

  /** The build the settings screen reports, which comes from the same source as the header's. */
  def build: String = page.locator("[data-testid='settings-build']").innerText().trim
}
