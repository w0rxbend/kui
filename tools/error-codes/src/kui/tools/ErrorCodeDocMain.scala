package kui.tools

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import kui.kernel.error.ErrorCode

/** Writes — or, with `--check`, verifies — the generated error-code document.
  *
  * Two modes rather than two tools: the same rendering decides both, so a document that passes the check is
  * exactly the document a regeneration would produce. CI runs the check, which is what turns "somebody forgot
  * to regenerate" from a review comment into a build failure.
  *
  * ==Why the decision is a value and `main` is four lines==
  *
  * The same shape [[BrowserConstantsMain]] was given on 2026-09-07, and for the same reason, one file over:
  * every branch of this object lived inside `main`, which reads a real path, writes a real file and calls
  * `sys.exit`, so no case could drive any of it. The sibling's version of that hole was measured — with
  * `if outcome.status != 0` changed to `< 0`, a stale committed file passed `--check` at `234/234 SUCCESS`
  * while printing *"is out of date"* — and this object had the identical seam and no suite at all over it.
  * [[decide]] answers what to print, what to write and what to exit with; [[exitStatus]] is the translation
  * from a decision into a process status; `main` does only the two things a case cannot, which are touching
  * the filesystem and ending the process.
  */
object ErrorCodeDocMain {

  /** What one run has decided: the line to print, the file to write if any, and the exit status. */
  final case class Outcome(message: String, write: Option[String], status: Int)

  /** The whole decision, over values a case can supply.
    *
    * @param target
    *   the path the message names; nothing is read through it here
    * @param checkOnly
    *   `--check` was passed, so a disagreement is a failure rather than something to overwrite
    * @param expected
    *   what [[ErrorCodeDoc.render]] answered for the shipped vocabulary
    * @param committed
    *   the committed document's content, or `None` when there is no such file
    */
  def decide(target: Path, checkOnly: Boolean, expected: String, committed: => Option[String]): Outcome =
    if !checkOnly then Outcome(s"wrote $target (${ErrorCode.values.length} codes)", Some(expected), 0)
    else
      committed match {
        case None =>
          Outcome(s"$target does not exist; run ./mill docs.errorCodes", None, 1)
        case Some(actual) if actual == expected =>
          Outcome(s"$target is up to date (${ErrorCode.values.length} codes)", None, 0)
        case Some(_) =>
          Outcome(
            s"$target is out of date: the ErrorCode enum has changed since it was generated.\n" +
              "Run ./mill docs.errorCodes and commit the result.",
            None,
            1
          )
      }

  /** Whether this outcome ends the process, and with what. `None` means it ends normally.
    *
    * A separate value from [[decide]] because the mutation that matters is not in the decision: it is in the
    * operator that turns a status into an exit, and `!= 0` -> `< 0` there makes a failing `--check` exit zero
    * while still printing the sentence that says it failed.
    */
  private[tools] def exitStatus(outcome: Outcome): Option[Int] =
    Option.when(outcome.status != 0)(outcome.status)

  def main(args: Array[String]): Unit = {
    val checkOnly = args.contains("--check")
    val target = args.find(!_.startsWith("--")).map(Paths.get(_)).getOrElse(defaultTarget)

    val outcome = decide(target, checkOnly, ErrorCodeDoc.render(ErrorCode.values.toList), read(target))
    outcome.write.foreach(content => write(target, content))
    println(outcome.message)
    exitStatus(outcome).foreach(status => sys.exit(status))
  }

  private val defaultTarget: Path = Paths.get("docs", "api", "error-codes.md")

  private def read(target: Path): Option[String] =
    Option.when(Files.exists(target))(new String(Files.readAllBytes(target), StandardCharsets.UTF_8))

  private def write(target: Path, content: String): Unit = {
    Option(target.getParent).foreach(parent => Files.createDirectories(parent))
    val _ = Files.write(target, content.getBytes(StandardCharsets.UTF_8))
  }
}
