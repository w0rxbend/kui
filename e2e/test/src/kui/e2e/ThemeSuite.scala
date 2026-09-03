package kui.e2e

/** The theme switch, and the property that makes it worth a browser test.
  *
  * Switching a theme is easy; making the choice survive a page load is the part that goes wrong,
  * because it needs the value to be written to storage *and* read back before the first paint. Both
  * halves involve the browser, and neither can be checked without one.
  */
final class ThemeSuite extends AllInOneE2ESuite {

  test("theme switch sets data-theme and survives a reload") {
    val page = shell.open("/ui/")

    val before = page.themeAttribute
    page.switchTheme()

    waitForCondition(s"the theme attribute to change from '$before'") {
      page.themeAttribute != before
    }
    val chosen = page.themeAttribute
    assert(chosen.nonEmpty, "switching the theme left no data-theme on <html>")

    val _ = page.reload()

    assertEquals(
      page.themeAttribute,
      chosen,
      "the theme was not restored after a reload, so the user's choice is forgotten every visit"
    )
  }
}
