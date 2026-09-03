package kui.build

import kui.build.BundleShape.{Feature, LinkerOutput, Result}
import munit.FunSuite

/** The bundle-shape rules, exercised against synthetic linker output.
  *
  * Running the real linker to test the rules would make this suite slow and would only ever cover the one
  * shape the current build happens to produce. Handing the rules a made-up directory listing instead lets
  * every failure mode be tested, including the ones that have not happened yet.
  */
final class BundleShapeSuite extends FunSuite {

  private val clusters = Feature("kui.ui.clusters.ClustersFeature", "kui.ui.clusters")

  private val budget = 1_500_000L

  private def output(
      fileNames: Seq[String] = Seq("main.js", "kui.ui.clusters.ClustersFeature.js"),
      mainJsContent: String = "// shell only",
      mainJsSizeBytes: Long = 1_000L
  ): LinkerOutput = LinkerOutput(fileNames, mainJsContent, mainJsSizeBytes)

  test("a class name becomes the symbol the linker actually emits") {
    assertEquals(
      BundleShape.emittedSymbol("kui.ui.clusters.ClustersFeature"),
      "Lkui_ui_clusters_ClustersFeature"
    )
  }

  test("no configured features skips, and says so, rather than passing") {
    BundleShape.check(output(), features = Seq.empty, budget) match {
      case Result.Skipped(message) => assert(message.contains("no feature packages configured"), message)
      case other => fail(s"expected a skip, got $other")
    }
  }

  test("a split feature, a clean main.js and a small main.js pass") {
    BundleShape.check(output(), Seq(clusters), budget) match {
      case Result.Passed(message) => assert(message.contains("1 feature module"), message)
      case other => fail(s"expected a pass, got $other")
    }
  }

  test("a feature with no module file of its own fails and lists what was linked") {
    val problems =
      failureOf(BundleShape.check(output(fileNames = Seq("main.js", "internal-0.js")), Seq(clusters), budget))

    assertEquals(problems.size, 1)
    assert(problems.head.contains("no module file matching kui.ui.clusters*.js"), problems.head)
    assert(problems.head.contains("internal-0.js"), problems.head)
  }

  test("main.js is not accepted as a feature's own module") {
    // The prefix match must not be satisfied by `main.js` itself, however the file is named.
    val problems = failureOf(BundleShape.check(output(fileNames = Seq("main.js")), Seq(clusters), budget))

    assert(problems.exists(_.contains("no module file matching")), problems.toString)
  }

  test("a feature symbol inside main.js fails, even though the source name is absent") {
    val leaked = output(mainJsContent = "function $c_Lkui_ui_clusters_ClustersFeature() {}")

    val problems = failureOf(BundleShape.check(leaked, Seq(clusters), budget))

    assert(problems.exists(_.contains("ships with the shell")), problems.toString)
  }

  test("a method the optimiser inlined across the module border does not fail the check") {
    // The linker is allowed to copy a small method into `main.js` while the class itself stays in its
    // own module, and it does. What rule 2 defends is that `main.js` cannot *construct* the feature,
    // so only the class-definition symbol counts. Flagging an inlined accessor as loudly as a real
    // leak is how a check ends up switched off.
    val inlined = output(mainJsContent = "$p_Lkui_ui_clusters_ClustersFeature__page__O(x)")

    BundleShape.check(inlined, Seq(clusters), budget) match {
      case Result.Passed(_) => ()
      case other => fail(s"expected a pass, got $other")
    }
  }

  test("the source name in a comment does not fail the check") {
    val commented = output(mainJsContent = "// loads kui.ui.clusters.ClustersFeature on demand")

    BundleShape.check(commented, Seq(clusters), budget) match {
      case Result.Passed(_) => ()
      case other => fail(s"expected a pass, got $other")
    }
  }

  test("main.js over budget fails and reports the overshoot") {
    val problems = failureOf(BundleShape.check(output(mainJsSizeBytes = budget + 42L), Seq(clusters), budget))

    assert(problems.exists(_.contains("over the 1500000 B budget by 42 B")), problems.toString)
  }

  test("main.js exactly on budget passes") {
    BundleShape.check(output(mainJsSizeBytes = budget), Seq(clusters), budget) match {
      case Result.Passed(_) => ()
      case other => fail(s"expected a pass, got $other")
    }
  }

  test("a missing main.js is reported once, not as three separate rule failures") {
    val problems = failureOf(BundleShape.check(output(fileNames = Seq.empty), Seq(clusters), budget))

    assertEquals(problems.size, 1)
    assert(problems.head.contains("no main.js"), problems.head)
  }

  test("every broken rule is reported in one run") {
    val broken = output(
      fileNames = Seq("main.js"),
      mainJsContent = "$c_Lkui_ui_clusters_ClustersFeature",
      mainJsSizeBytes = budget + 1L
    )

    assertEquals(failureOf(BundleShape.check(broken, Seq(clusters), budget)).size, 3)
  }

  private def failureOf(result: Result): Seq[String] = result match {
    case Result.Failed(problems) => problems
    case other => fail(s"expected a failure, got $other")
  }
}
