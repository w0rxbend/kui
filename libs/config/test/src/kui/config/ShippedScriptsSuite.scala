package kui.config

import java.nio.file.{Files, Path}
import java.util.stream.Collectors

import scala.jdk.CollectionConverters.*

import kui.testkit.KuiSuite

/** That no shell script this repository ships can die between a failed step and the line written to explain
  * it.
  *
  * ==The defect, measured rather than imagined==
  *
  * `deployment/quickstart/seed/connect-seed.sh` waits for its connector to reach `RUNNING` and counted the
  * running states with
  *
  * {{{running="$(printf '%s' "${status}" | grep -o '"state":"RUNNING"' | wc -l | tr -d ' ')"}}}
  *
  * under `set -euo pipefail`. `grep` exits 1 when it matches nothing, which is the NORMAL first poll: the
  * `PUT .../config` has just answered 201 and the connector is still `UNASSIGNED` with no tasks. `pipefail`
  * makes that 1 the pipeline's status, the assignment carries it out of the command substitution, and `set
  * -e` kills the script on that line — before its own `die()`, before the timeout message, before anything is
  * printed. The wave-12 integrator saw the outside of it: Compose reported *"connect-seed didn't complete
  * successfully: exit 1"* over a container whose log ended cleanly with no error line. It survives only when
  * the very first poll is already green, which is what a warm machine gives you.
  *
  * Reproduced for this suite against a worker whose first status poll answers `UNASSIGNED` and no tasks: the
  * script printed `registered quickstart-file-source …`, exited 1 and said nothing else.
  *
  * ==Why it is a class and not one line==
  *
  * `deployment/compose/smoke.sh` carried the same shape twice — `bytes_in` and `bytes_out`, each reading a
  * Prometheus series out of a scrape with `grep -E … | awk` — two lines above the `fail` written for exactly
  * the "the exporter published no such series" case. An exporter that has not published the series yet would
  * have killed the smoke run silently instead of printing that sentence. Three instances in one tree, found
  * by reading rather than by running, is a class, and a class needs a gate rather than three edits.
  *
  * ==Why the roster is the filesystem==
  *
  * House rule 26: a hand-written list of the places a gate looks is a claim nobody reconciles. This walks
  * `deployment/` for `*.sh` and reads what it finds, so a script added next week is swept the day it lands
  * and nobody has to remember a roster. It is the same reason `ShippedConfigurationSuite` walks for `*.yaml`
  * instead of trusting its own table.
  */
final class ShippedScriptsSuite extends KuiSuite {

  /** One `name="$( … )"` assignment, with every line of it, wherever it started. */
  final private case class Substitution(script: String, line: Int, text: String)

  /** An assignment whose value comes from a command substitution: `x="$(`, `local x="$(`, `x=$(`.
    *
    * Only an assignment, because that is where the death is silent. The same pipeline in an `if` condition,
    * in a `while` head or as a bare statement is either tested by the construct around it or reported by
    * `set -e` at a point a reader can see, and demanding `|| true` there would make the guard meaningless
    * where it matters.
    */
  private val assignsFromSubstitution =
    """^[ \t]*(?:local[ \t]+|export[ \t]+|declare[ \t]+)?[A-Za-z_][A-Za-z0-9_]*=["']?\$\(""".r

  /** A filter whose "I matched nothing" answer is a non-zero exit.
    *
    * `grep` and its two historical spellings, and nothing else on purpose: `sed`, `awk`, `wc`, `cut` and
    * `sort` all exit 0 over input they do not match, so requiring a guard around them would be an assertion
    * that can never fail — which is the shape this repository refuses. `grep -c` and `grep -o` are included
    * and are the two that bite: both print a perfectly good `0` on stdout AND exit 1.
    */
  private val matchOrFail = """(?<![\w-])(?:grep|egrep|fgrep)(?![\w-])""".r

  /** `set -e` in any spelling. A script without it survives a failed `grep`, so it is not this rule's
    * subject.
    */
  private val errexit = """(?m)^[ \t]*set[ \t]+(?:-[a-z]*e[a-z]*\b|-o[ \t]+errexit)""".r

  /** Every `*.sh` under `deployment/`, relative to the repository root. */
  private def deploymentScripts(root: Path): List[String] = {
    val stream = Files.walk(root.resolve("deployment"))
    try
      stream
        .filter(Files.isRegularFile(_))
        .map[String](path => root.relativize(path).toString.replace('\\', '/'))
        .filter(_.endsWith(".sh"))
        .collect(Collectors.toList[String])
        .asScala
        .toList
        .sorted
    finally stream.close()
  }

  /** Every command substitution a script assigns from, each folded to one line.
    *
    * A pipeline is routinely written over two or three lines — `smoke.sh` writes both of its offenders that
    * way — so the accumulation runs until the parentheses balance rather than stopping at the newline, and
    * the text is then whitespace-folded before it is read. Reading line by line would have found neither of
    * the two instances this suite was written for, and a guard that cannot see the defect it was written for
    * is worse than none.
    */
  private def substitutions(script: String, contents: String): List[Substitution] = {
    val lines = contents.linesIterator.toVector

    lines.indices.toList.flatMap { index =>
      val line = lines(index)
      Option.when(assignsFromSubstitution.findFirstIn(line).isDefined) {
        var text = line.substring(line.indexOf("$("))
        var last = index
        while (text.count(_ == '(') > text.count(_ == ')')) && last + 1 < lines.size do {
          last += 1
          text = text + " " + lines(last)
        }
        Substitution(script, index + 1, text.replaceAll("\\s+", " "))
      }
    }
  }

  /** Whether a substitution can survive its filter matching nothing.
    *
    * `|| true` and `|| :` anywhere inside it, because under `pipefail` a fallback at the END of a pipeline
    * already answers for every stage of that pipeline: `a | grep x | c || true` exits 0 whichever stage
    * failed. `{ grep … || true; } | c` is the other shape and is the one to reach for when the pipeline's own
    * exit code is still wanted for something else.
    */
  private def guarded(text: String): Boolean = text.contains("|| true") || text.contains("|| :")

  test("no shipped script assigns from a `grep` that can exit 1 on a normal run without a fallback") {
    val root = repositoryRoot
    val scripts = deploymentScripts(root)

    assert(
      scripts.nonEmpty,
      "the walk over deployment/ found no shell script at all, so everything below is true of nothing"
    )

    val candidates = scripts.flatMap { relative =>
      val contents = Files.readString(root.resolve(relative))
      if errexit.findFirstIn(contents).isEmpty then Nil
      else substitutions(relative, contents).filter(sub => matchOrFail.findFirstIn(sub.text).isDefined)
    }

    // THE ANTI-VACUITY HALF, AND IT IS NOT DECORATION. Every regex above is hand-written, and a regex that
    // has stopped matching reports an empty list of offenders — a green run that measured nothing, which is
    // the failure this wave exists to close. The tree carries several correctly guarded substitutions of
    // exactly this shape, so a parser that still works finds them; one that does not, finds none and fails
    // here rather than passing silently.
    assert(
      candidates.exists(sub => guarded(sub.text)),
      "this suite found no guarded `grep` substitution anywhere under deployment/, which means its own " +
        "reader has stopped matching the shape it reads. The offender list below it is then empty for the " +
        "wrong reason"
    )

    val unguarded = candidates.filterNot(sub => guarded(sub.text))

    assertEquals(
      unguarded.map(sub => s"${sub.script}:${sub.line}"),
      Nil,
      clue = unguarded
        .map(sub => s"  ${sub.script}:${sub.line}\n    ${sub.text}")
        .mkString(
          "a `grep` inside an assignment's command substitution, in a script that sets `-e`, with no " +
            "`|| true` fallback:\n",
          "\n",
          "\nGrep exits 1 when it matches nothing, which for a poll loop or a scrape is a normal answer " +
            "and not a failure. Under `set -e` (with `pipefail`, for any stage; without it, for the last) " +
            "that 1 leaves the script on this line, before the `die`/`fail` written to explain the state " +
            "it is in, and the process exits non-zero having printed nothing. Wrap the stage as " +
            "`{ grep … || true; }`, or end the pipeline with `|| true`, and let the check below the " +
            "assignment say what happened."
        )
    )
  }

  /** The repository root, found by walking up to the directory holding `build.mill`.
    *
    * The fourth copy of this walk in the repository and the second in this package: a test runs in a sandbox
    * directory, so every suite that reads a committed file needs it. It is four lines and duplicated rather
    * than shared because the other copies are private to suites that are named guard files, and moving one of
    * them into `libs/testkit` mid-wave would edit a file this packet does not own. A fifth copy is the point
    * at which it should move.
    */
  private def repositoryRoot: Path = {
    val start = Path.of("").toAbsolutePath
    Iterator
      .iterate(Option(start))(_.flatMap(path => Option(path.getParent)))
      .takeWhile(_.isDefined)
      .flatten
      .find(candidate => Files.exists(candidate.resolve("build.mill")))
      .getOrElse(fail(s"no build.mill above $start, so the repository root could not be found"))
  }
}
