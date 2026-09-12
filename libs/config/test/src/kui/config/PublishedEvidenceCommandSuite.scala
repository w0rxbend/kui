package kui.config

import java.nio.file.{Files, Path}

import scala.util.Using

import kui.testkit.KuiSuite

/** A comment that publishes a command publishes a gate, and this runs the one `kui-quickstart-auth.yaml`
  * publishes.
  *
  * ==The class, and this is its fourth instance==
  *
  * TD-051: nothing in this repository reads prose under `deployment/`. Wave 12 answered *"the two quickstarts
  * carry the same masking rules"* by replacing the claim with the command a reader could run —
  *
  * {{{diff <(awk '/^      masking:/,/^\$/' kui-quickstart.yaml) <(awk … kui-quickstart-auth.yaml)}}}
  *
  * — and the command could not fail. `awk`'s range ended at the first BLANK line; the blank line falls
  * between the two rules; both extracts therefore held the `kind: mask` rule alone and the `kind: replace`
  * rule below it was compared with nothing. Measured with the auth file's `replacement: "<redacted>"` changed
  * to `"<name>"`, the published diff printed nothing and exited 0 while `ShippedConfigurationSuite` went red.
  * The gate was fine; the published evidence was the thing that could not fail.
  *
  * W13-02 repaired the range to end where the block ends. That repair is this suite's subject: reverting it
  * to wave 12's range left `./scripts/feature-matrix-check.sh` at `all true` and `./mill libs.config.test` at
  * 395/395 SUCCESS, because the only reader of that paragraph was a person.
  *
  * ==What is asserted, and why it is the range and not the rules==
  *
  * `ShippedConfigurationSuite` already compares the masking rules themselves as parsed configuration, so this
  * suite does not compare them again. It asserts the thing that suite cannot see: that the extractor the
  * comment hands the reader actually reaches the end of the block it claims to extract. The range is read out
  * of the comment rather than written here — a copy would drift from the published text exactly the way the
  * published text drifted from the rules.
  */
final class PublishedEvidenceCommandSuite extends KuiSuite {

  private val quickstart = "deployment/quickstart/kui-quickstart.yaml"
  private val quickstartAuth = "deployment/quickstart/kui-quickstart-auth.yaml"

  /** The `ex='…'` line of the published command, as the comment writes it. */
  private val publishedRange = """(?m)^\s*#\s*ex='(.*)'\s*$""".r

  /** Both rules the masking block carries. A range that stops at the first blank line holds only the first.
    */
  private val ruleKinds = List("kind: mask", "kind: replace")

  private def repositoryRoot: Path = {
    val start = Path.of("").toAbsolutePath
    Iterator
      .iterate(Option(start))(_.flatMap(path => Option(path.getParent)))
      .takeWhile(_.isDefined)
      .flatten
      .find(candidate => Files.exists(candidate.resolve("build.mill")))
      .getOrElse(fail(s"no build.mill above $start, so the repository root could not be found"))
  }

  /** `awk "$program" <file>`, run for real, with comment lines dropped the way the published diff drops them.
    */
  private def extract(root: Path, program: String, relative: String): List[String] = {
    val process = new ProcessBuilder("awk", program, root.resolve(relative).toString)
      .redirectErrorStream(true)
      .start()
    val output = Using.resource(process.getInputStream)(stream => new String(stream.readAllBytes(), "UTF-8"))
    val status = process.waitFor()
    assertEquals(status, 0, s"`awk` over $relative exited $status:\n$output")
    output.linesIterator.filterNot(_.trim.startsWith("#")).toList
  }

  test("the masking extractor the auth quickstart publishes reaches the end of the block it extracts") {
    val root = repositoryRoot
    val comment = Files.readString(root.resolve(quickstartAuth))
    val programs = publishedRange.findAllMatchIn(comment).map(_.group(1)).toList

    assertEquals(
      programs.size,
      1,
      s"$quickstartAuth publishes ${programs.size} `ex='…'` extractors; this suite runs the one it " +
        "publishes, so it can neither guess between two nor pass over none. If the paragraph was rewritten, " +
        "this assertion is the notice that its evidence command moved"
    )

    val program = programs.head

    List(quickstart, quickstartAuth).foreach { relative =>
      val lines = extract(root, program, relative)
      ruleKinds.foreach { kind =>
        assert(
          lines.exists(_.contains(kind)),
          s"the extractor $quickstartAuth publishes —\n  $program\n— returns no `$kind` line out of " +
            s"$relative. It extracted:\n${lines.mkString("\n")}\n\nThe masking block carries two rules with " +
            "a blank line between them, so a range that ends at the first blank line holds the first rule " +
            "alone and compares the second with nothing. That is what wave 12 published: the diff it names " +
            "as the reader's proof printed nothing and exited 0 over a tree whose second rule had been " +
            "changed. A comment that publishes a command publishes a gate."
        )
      }
    }
  }

  test("the two quickstarts' masking blocks agree under the extractor they publish, comments aside") {
    val root = repositoryRoot
    val comment = Files.readString(root.resolve(quickstartAuth))
    val program = publishedRange.findFirstMatchIn(comment).map(_.group(1)).getOrElse {
      fail(s"$quickstartAuth publishes no `ex='…'` extractor to run")
    }

    val fromDefault = extract(root, program, quickstart).filter(_.trim.nonEmpty)
    val fromAuth = extract(root, program, quickstartAuth).filter(_.trim.nonEmpty)

    assert(
      fromDefault.nonEmpty,
      s"the published extractor returned nothing at all out of $quickstart, so the comparison below is " +
        "true of two empty lists — which is the state the wave-12 command was one blank line away from"
    )

    assertEquals(
      fromAuth,
      fromDefault,
      s"the published `diff` is the sentence $quickstartAuth asks the reader to trust, and it is not empty. " +
        "Whichever file moved, the other has to move with it: `--with-auth` is the one deployment in this " +
        "repository with a `viewer` role, and a masking rule that is in one file and not the other is the " +
        "difference between that person seeing a redacted record and seeing every customer's name."
    )
  }
}
