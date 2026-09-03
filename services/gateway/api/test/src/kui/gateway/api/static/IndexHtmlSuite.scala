package kui.gateway.api.static

import io.circe.parser.decode
import munit.FunSuite

/** That the bootstrap block `ApiClient` reads is exactly what the configuration says, with no server
  * involved. `StaticRoutesSuite` proves the same document reaches a real response; this proves the
  * substitution itself is correct, which is the smaller and far more thoroughly checkable claim.
  */
final class IndexHtmlSuite extends FunSuite {

  private val template =
    s"""<!doctype html><html><head>${IndexHtml.BaseHrefPlaceholder}</head>
       |<body>${IndexHtml.BootstrapPlaceholder}</body></html>""".stripMargin

  test("theBootstrapElementCarriesExactlyTheConfiguredValues") {
    val rendered = IndexHtml.render(template, BootstrapConfig("/kui", "/kui/api/v1", "0.1.0-SNAPSHOT"))

    val marker = s"""id="${IndexHtml.BootstrapElementId}""""
    assert(rendered.contains(marker), rendered)

    val jsonText = rendered
      .split(s"""type="application/json">""")(1)
      .split("</script>")(0)

    val decoded = decode[Map[String, String]](jsonText).fold(error => fail(error.toString), identity)
    assertEquals(decoded("basePath"), "/kui")
    assertEquals(decoded("apiBase"), "/kui/api/v1")
    assertEquals(decoded("buildVersion"), "0.1.0-SNAPSHOT")
  }

  test("theBaseHrefFollowsTheBasePath") {
    val rendered = IndexHtml.render(template, BootstrapConfig("/kui", "/kui/api/v1", "0.1.0-SNAPSHOT"))
    assert(rendered.contains("""<base href="/kui/ui/">"""), rendered)
  }

  test("anEmptyBasePathProducesARootBaseHref") {
    val rendered = IndexHtml.render(template, BootstrapConfig("", "/api/v1", "0.1.0-SNAPSHOT"))
    assert(rendered.contains("""<base href="/ui/">"""), rendered)
  }

  test("neitherPlaceholderSurvivesRendering") {
    val rendered = IndexHtml.render(template, BootstrapConfig("", "/api/v1", "0.1.0-SNAPSHOT"))
    assert(!rendered.contains(IndexHtml.BootstrapPlaceholder), rendered)
    assert(!rendered.contains(IndexHtml.BaseHrefPlaceholder), rendered)
  }

  test("aQuoteInAConfiguredValueCannotBreakOutOfTheEmbeddedJson") {
    // None of these three values can contain a quote or a `</script>` in practice — one is normalised, one
    // is a fixed prefix, one is a build version — but the day one does, this is what stops it turning into
    // invalid JSON, or worse, HTML that ends the script element early and runs whatever text followed as a
    // new one.
    val malicious = """/kui"</script><script>alert(1)"""
    val rendered = IndexHtml.render(template, BootstrapConfig(malicious, "/api/v1", "1"))

    // The literal text an HTML parser watches for to end a `<script>` element must not appear anywhere in
    // the page — not merely inside what looks like the JSON payload.
    assert(!rendered.toLowerCase.contains("</script><script>"), rendered)

    val marker = s"""type="application/json">"""
    val start = rendered.indexOf(marker) + marker.length
    val end = rendered.indexOf("</script>", start)
    val jsonText = rendered.substring(start, end)

    val decoded = decode[Map[String, String]](jsonText).fold(error => fail(s"$jsonText ($error)"), identity)
    assertEquals(decoded("basePath"), malicious)
  }
}
