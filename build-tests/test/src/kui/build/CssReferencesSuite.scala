package kui.build

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

import munit.FunSuite

import kui.build.design.DesignSources

/** The replacement for `CssPipeline`'s discovery property (ADR-048 §5, decision D3).
  *
  * Two halves. The first half checks the rules against made-up inputs, so that each way of getting
  * it wrong is described once and cannot be argued about. The second half runs them over the real
  * repository, which is the check that actually fails a build.
  */
final class CssReferencesSuite extends FunSuite {

  test("agreeing inputs produce no complaint") {
    val files = Set("packages/kernel/styles/10-tokens.css", "packages/shell/styles/30-shell.css")

    val violations = CssReferences.violations(tracked = files, onDisk = files, referenced = files.toList)

    assertEquals(violations, Nil)
  }

  test("a committed stylesheet nobody imports is reported") {
    // The failure this whole check exists for: the file is written, the screen is styled against
    // it, and not one rule in it reaches the browser.
    val orphan = "packages/kernel/styles/27-new.css"

    val violations = CssReferences.violations(tracked = Set(orphan), onDisk = Set(orphan), referenced = Nil)

    assertEquals(violations.size, 1)
    assert(violations.head.contains("27-new.css"), violations.head)
    assert(violations.head.contains("not imported"), violations.head)
  }

  test("an uncommitted stylesheet is nobody's problem yet") {
    // A file someone is part-way through writing is not part of the product, and a shared build
    // that goes red over it punishes the wrong person. The obligation to import it starts when it
    // is committed.
    val draft = "packages/kernel/styles/28-draft.css"

    val violations = CssReferences.violations(tracked = Set.empty, onDisk = Set(draft), referenced = Nil)

    assertEquals(violations, Nil)
  }

  test("an import written alongside its stylesheet is allowed before either is committed") {
    val pair = "packages/kernel/styles/28-draft.css"

    val violations =
      CssReferences.violations(tracked = Set.empty, onDisk = Set(pair), referenced = List(pair))

    assertEquals(violations, Nil)
  }

  test("an import of a file that does not exist is reported") {
    val violations = CssReferences.violations(
      tracked = Set.empty,
      onDisk = Set.empty,
      referenced = List("packages/kernel/styles/deleted.css")
    )

    assertEquals(violations.size, 1)
    assert(violations.head.contains("does not exist"), violations.head)
  }

  test("importing the same stylesheet twice is reported") {
    // Not pedantry: the second copy of a rule set is written later, so it wins the cascade, and a
    // screen quietly moves to a position in the cascade nobody chose.
    val duplicated = "packages/shell/styles/30-shell.css"

    val violations =
      CssReferences.violations(Set(duplicated), Set(duplicated), List(duplicated, duplicated))

    assertEquals(violations.size, 1)
    assert(violations.head.contains("2 times"), violations.head)
  }

  test("the import reader finds every entry, in the order written") {
    val index =
      """/* a comment mentioning @import "not-a-real-import.css" is still matched, so keep prose plain */
        |@import "./10-tokens.css";
        |@import "../../shell/styles/30-shell.css";
        |""".stripMargin

    assertEquals(
      CssReferences.imports(index),
      List("not-a-real-import.css", "./10-tokens.css", "../../shell/styles/30-shell.css")
    )
  }

  // --- The real repository ----------------------------------------------------------------------

  private val stylesRoot: Path = DesignSources.repositoryRoot.resolve("frontend/packages")

  private def relative(file: Path): String =
    DesignSources.repositoryRoot.relativize(file).toString.replace('\\', '/')

  /** Every stylesheet git is tracking in a package's `styles` directory, except `index.css` itself.
    *
    * Tracked, and not merely present, for one reason: the property being checked is about what
    * *ships*, and an untracked file ships nothing. A stylesheet someone is part-way through writing
    * is not yet a promise to anybody, and failing the build over it would punish the wrong person at
    * the wrong moment. It becomes subject to this check the instant it is committed — which is
    * exactly the moment its author has to have added the `@import` — and until then the file is
    * simply not part of the product.
    */
  private def discovered: Set[String] = {
    val listing = new ProcessBuilder("git", "ls-files", "--", "frontend/packages")
      .directory(DesignSources.repositoryRoot.toFile)
      .redirectErrorStream(true)
      .start()

    val tracked = scala.io.Source.fromInputStream(listing.getInputStream).getLines().toList
    val status  = listing.waitFor()
    assertEquals(status, 0, s"git ls-files failed:\n${tracked.mkString("\n")}")

    tracked
      .filter(_.endsWith(".css"))
      .filter(_.split('/').dropRight(1).lastOption.contains("styles"))
      .filterNot(_ == CssReferences.IndexFile)
      .toSet
  }

  /** Every stylesheet present under the packages, committed or not, as the bundler would find it. */
  private def onDisk: Set[String] = {
    val stream = Files.walk(stylesRoot)
    try
      stream
        .iterator()
        .asScala
        .filter(Files.isRegularFile(_))
        .filter(file => file.getFileName.toString.endsWith(".css"))
        .filter(file => file.getParent.getFileName.toString == "styles")
        .map(relative)
        .filterNot(_ == CssReferences.IndexFile)
        .toSet
    finally stream.close()
  }

  private def referenced: List[String] = {
    val index = DesignSources.repositoryRoot.resolve(CssReferences.IndexFile)
    CssReferences
      .imports(Files.readString(index))
      .map(target => relative(index.getParent.resolve(target).normalize))
  }

  test("the shipped index.css names every stylesheet in the workspace exactly once") {
    val problems = CssReferences.violations(discovered, onDisk, referenced)

    assertEquals(problems, Nil, problems.mkString("\n"))
  }

  test("there are stylesheets to check") {
    // If the walk above ever found nothing — a renamed directory, a changed layout — every
    // assertion in this file would pass while checking nothing at all. The sixteen files are the
    // ones ADR-048 §5 lists by name; the count is asserted loosely so that adding a stylesheet is
    // not a two-file change, but a collapse to zero or one is impossible to miss.
    assert(discovered.size >= 16, s"only ${discovered.size} stylesheets tracked under $stylesRoot")
  }
}
