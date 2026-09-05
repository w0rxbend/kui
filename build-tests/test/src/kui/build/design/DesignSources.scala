package kui.build.design

import java.nio.file.{Files, Path, Paths}

/** The two files the design-system tests read, found on disk rather than compiled in.
  *
  * ## Why this exists at all
  *
  * `ContrastSuite` and `TokensSuite` used to run under Scala.js, inside a JavaScript engine with no
  * filesystem, so `build.mill` had to read the stylesheet and the documentation and paste them into a
  * generated Scala source file that was compiled into the test binary. That machinery is gone: these
  * suites are ordinary JVM tests now (ADR-048 §5), and a JVM test can simply open the files. The
  * inputs are the same two files, and they are still the shipped ones — not copies, not fixtures.
  *
  * ## Finding the repository root without being told where it is
  *
  * A test does not get to choose its working directory, and the answer differs between running the
  * whole build, running one module, and running a single test from an editor. Rather than depend on
  * any of that, this walks up from the working directory until it finds the directory that contains
  * `build.mill`, which is the repository root by definition. If it reaches the filesystem root
  * without finding one, it fails loudly and says what it was looking for — a wrong path here would
  * otherwise surface as an unreadable file several frames away.
  */
object DesignSources {

  /** The repository root: the nearest ancestor of the working directory containing `build.mill`. */
  val repositoryRoot: Path = {
    def search(candidate: Path): Path =
      if candidate == null then
        throw new IllegalStateException(
          s"no build.mill in any ancestor of ${Paths.get("").toAbsolutePath}; " +
            "the design-system tests locate the repository by that file"
        )
      else if Files.isRegularFile(candidate.resolve("build.mill")) then candidate
      else search(candidate.getParent)

    search(Paths.get("").toAbsolutePath.normalize)
  }

  private def read(relative: String): String = {
    val file = repositoryRoot.resolve(relative)
    if !Files.isRegularFile(file) then
      throw new IllegalStateException(s"the design-system tests need $relative, which is not a file at $file")
    Files.readString(file)
  }

  /** The token stylesheet exactly as it ships, so the values checked are the ones a browser gets. */
  val stylesheet: String = read("frontend/packages/kernel/styles/10-tokens.css")

  /** The token documentation, which is where the checked contrast pairs are listed.
    *
    * Reading the pair list out of the documentation rather than out of a test file is deliberate and
    * is the reason this file is an input at all: adding a row to the table there makes the suite
    * check that pair, so the documentation cannot quietly stop describing what is enforced.
    */
  val documentation: String = read("docs/frontend/tokens.md")
}
