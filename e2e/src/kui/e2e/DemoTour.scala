package kui.e2e

import java.nio.file.{Files, Path}

import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.{Browser, BrowserType, Page, Playwright}

/** A throwaway driver for the final manual pass: opens paths under a running demonstration deployment,
  * screenshots each one and prints what the page says. Not a test; not part of any gate.
  */
object DemoTour {

  def main(args: Array[String]): Unit = {
    val baseUrl = sys.env.getOrElse("KUI_TOUR_BASE", "http://localhost:18080")
    val outDir = Path.of(sys.env.getOrElse("KUI_TOUR_OUT", "/tmp/kui-tour"))
    Files.createDirectories(outDir)

    val playwright = Playwright.create()
    val browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))
    val context = browser.newContext(new Browser.NewContextOptions().setViewportSize(1440, 1000))
    val page = context.newPage()
    page.onConsoleMessage(m => if m.`type`() == "error" then println(s"CONSOLE-ERROR ${m.text()}"))
    page.onPageError(e => println(s"PAGE-ERROR $e"))
    page.onResponse(r => if r.status() >= 400 then println(s"HTTP ${r.status()} ${r.url()}"))

    args.foreach { step =>
      step.split("::").toList match {
        case name :: path :: rest =>
          println(s"\n########## $name  ($path)")
          val _ = page.navigate(s"$baseUrl$path")
          settle(page)
          rest.foreach(action(page, _))
          shoot(page, outDir, name)
          println(text(page))
        case _ => println(s"bad step: $step")
      }
    }

    context.close()
    browser.close()
    playwright.close()
  }

  private def action(page: Page, spec: String): Unit = {
    val parts = spec.split("\\|").toList
    val verb = parts.headOption.getOrElse("")
    val rest = parts.drop(1)
    verb match {
      case "click" =>
        println(s"-- click ${rest.head}")
        page.locator(rest.head).first().click()
        settle(page)
      case "fill" =>
        println(s"-- fill ${rest.head} = ${rest(1)}")
        page.locator(rest.head).first().fill(rest(1))
        settle(page)
      case "select" =>
        println(s"-- select ${rest.head} = ${rest(1)}")
        val _ = page.locator(rest.head).first().selectOption(rest(1))
        settle(page)
      case "wait" => page.waitForTimeout(rest.head.toDouble)
      case "url" => println(s"-- url now ${page.url()}")
      case other => println(s"unknown action $other")
    }
  }

  private def settle(page: Page): Unit = {
    try page.waitForLoadState(LoadState.NETWORKIDLE)
    catch { case _: Throwable => () }
    page.waitForTimeout(700)
  }

  private def shoot(page: Page, outDir: Path, name: String): Unit = {
    val file = outDir.resolve(s"$name.png")
    val _ = page.screenshot(
      new Page.ScreenshotOptions().setPath(file).setFullPage(true)
    )
    println(s"SHOT $file  url=${page.url()}")
  }

  private def text(page: Page): String = {
    val body = page.locator("body").innerText()
    body.linesIterator.map(_.trim).filter(_.nonEmpty).mkString("\n")
  }
}
