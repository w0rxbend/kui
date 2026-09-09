package kui.tools

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import kui.kernel.error.ErrorCode

/** Writes -- or, with `--check`, verifies -- the TypeScript constants the browser shares with the server.
  *
  * The same two modes, for the same reason, as [[ErrorCodeDocMain]]: one rendering decides both, so a file
  * that passes the check is exactly the file a regeneration would produce, and "somebody forgot to
  * regenerate" becomes a build failure rather than a review comment.
  *
  * ==Why the decisions are values and `main` is four lines==
  *
  * Everything below used to be inside `main`, which reads a real path, writes a real file and calls
  * `sys.exit`. No case can drive any of that, so every branch was ungated: on 2026-09-07 the refusal
  * `case Left(problem) => println(...); sys.exit(1)` was changed to `case Left(_) => ()` and
  * `./mill tools.errorCodes.test` stayed at 291/291 with `./mill frontend.apiConstants --check` green -- the
  * generator would have written nothing, said nothing and exited zero on the one input it exists to refuse.
  * The seam is [[decide]]: it takes the rendering and a reader of the target file and answers what to print
  * and what to exit with, so a case can put it in each of the four states. `main` does the two things a case
  * cannot -- touch the filesystem and end the process -- and decides nothing.
  */
object BrowserConstantsMain {

  private val defaultTarget: Path =
    Paths.get("frontend", "packages", "api", "src", "constants.generated.ts")

  /** What one run has decided: the line to print, the file to write if any, and the exit status. */
  final case class Outcome(message: String, write: Option[String], status: Int)

  /** The whole decision, over values a case can supply.
    *
    * @param target
    *   the path the message names; nothing is read through it here
    * @param checkOnly
    *   `--check` was passed, so a disagreement is a failure rather than something to overwrite
    * @param rendered
    *   what [[BrowserConstants.render]] answered -- `Left` is the one thing a regeneration cannot repair
    * @param committed
    *   the committed file's content, or `None` when there is no such file
    */
  def decide(
      target: Path,
      checkOnly: Boolean,
      rendered: Either[String, String],
      committed: => Option[String]
  ): Outcome =
    rendered match {
      // `render` answers `Left` for the one thing a regeneration cannot repair: an RBAC vocabulary whose
      // connector fallback no longer matches by name, which would emit a list the browser's evaluator reads
      // as a mapping. Both modes fail on it, because writing that file is worse than not writing it.
      case Left(problem) =>
        Outcome(s"$target was not generated: $problem", None, 1)

      case Right(expected) if !checkOnly =>
        Outcome(s"wrote $target (${ErrorCode.values.length} codes)", Some(expected), 0)

      case Right(expected) =>
        committed match {
          case None =>
            Outcome(s"$target does not exist; run ./mill frontend.apiConstants", None, 1)
          case Some(actual) if actual == expected =>
            Outcome(s"$target is up to date (${ErrorCode.values.length} codes)", None, 0)
          case Some(_) =>
            Outcome(
              s"$target is out of date: the ErrorCode enum or a shared header name has changed since it " +
                "was generated.\nRun ./mill frontend.apiConstants and commit the result.",
              None,
              1
            )
        }
    }

  def main(args: Array[String]): Unit = {
    val checkOnly = args.contains("--check")
    val target = args.find(!_.startsWith("--")).map(Paths.get(_)).getOrElse(defaultTarget)

    val outcome = decide(target, checkOnly, BrowserConstants.render(ErrorCode.values.toList), read(target))
    outcome.write.foreach(content => write(target, content))
    println(outcome.message)
    if outcome.status != 0 then sys.exit(outcome.status)
  }

  private def read(target: Path): Option[String] =
    Option.when(Files.exists(target))(new String(Files.readAllBytes(target), StandardCharsets.UTF_8))

  private def write(target: Path, content: String): Unit = {
    Option(target.getParent).foreach(parent => Files.createDirectories(parent))
    val _ = Files.write(target, content.getBytes(StandardCharsets.UTF_8))
  }
}
