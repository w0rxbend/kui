package kui.build

import munit.FunSuite

/** The build-level test UI-001 asks for: the concatenation order is the one ADR-024 decided, and the
  * output is byte-identical across runs.
  */
final class CssPipelineSuite extends FunSuite {

  private def source(module: String, fileName: String): CssSource =
    CssSource(module, fileName, s"/* body of $fileName */")

  private val reset    = source("uiKernel", "00-reset.css")
  private val tokens   = source("uiKernel", "10-tokens.css")
  private val controls = source("uiKernel", "20-kernel-controls.css")
  private val feature  = source("uiClusters", "00-clusters.css")

  test("orders tokens, then reset, then kernel, then features") {
    val ordered = CssPipeline.order(List(feature, controls, reset, tokens))

    assertEquals(ordered.map(_.fileName), List("10-tokens.css", "00-reset.css", "20-kernel-controls.css", "00-clusters.css"))
  }

  test("a feature file cannot jump the queue with a low numeric prefix") {
    // `00-clusters.css` sorts before every kernel file by name, and must still land last, because
    // the cascade group is decided by role and module, not by the digits in the file name.
    val ordered = CssPipeline.order(List(feature, controls))

    assertEquals(ordered.last, feature)
  }

  test("files inside one group are ordered by module then by file name") {
    val second = source("uiKernel", "21-kernel-overlays.css")
    val other  = source("uiClusters", "50-later.css")

    val ordered = CssPipeline.order(List(other, second, controls))

    assertEquals(ordered.map(_.fileName), List("20-kernel-controls.css", "21-kernel-overlays.css", "50-later.css"))
  }

  test("output is byte-identical across two runs regardless of input order") {
    val first  = CssPipeline.concatenate(List(feature, tokens, controls, reset))
    val second = CssPipeline.concatenate(List(reset, controls, tokens, feature))

    assertEquals(first, second)
  }

  test("every file is preceded by a banner naming its module and file") {
    val output = CssPipeline.concatenate(List(tokens))

    assert(output.startsWith("/* uiKernel/10-tokens.css */\n"), output)
  }

  test("an empty input produces an empty stylesheet rather than failing") {
    assertEquals(CssPipeline.concatenate(Nil), "")
  }
}
