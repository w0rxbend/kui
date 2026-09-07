package kui.tools

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import kui.kernel.error.ErrorCode

/** Writes -- or, with `--check`, verifies -- the TypeScript constants the browser shares with the server.
  *
  * The same two modes, for the same reason, as [[ErrorCodeDocMain]]: one rendering decides both, so a file
  * that passes the check is exactly the file a regeneration would produce, and "somebody forgot to
  * regenerate" becomes a build failure rather than a review comment.
  */
object BrowserConstantsMain {

  private val defaultTarget: Path =
    Paths.get("frontend", "packages", "api", "src", "constants.generated.ts")

  def main(args: Array[String]): Unit = {
    val checkOnly = args.contains("--check")
    val target = args.find(!_.startsWith("--")).map(Paths.get(_)).getOrElse(defaultTarget)

    // `render` answers `Left` for the one thing a regeneration cannot repair: an RBAC vocabulary whose
    // connector fallback no longer matches by name, which would emit a list the browser's evaluator reads
    // as a mapping. Both modes fail on it, because writing that file is worse than not writing it.
    BrowserConstants.render(ErrorCode.values.toList) match {
      case Left(problem) =>
        println(s"$target was not generated: $problem")
        sys.exit(1)
      case Right(expected) =>
        if checkOnly then check(target, expected) else write(target, expected)
    }
  }

  private def write(target: Path, content: String): Unit = {
    Option(target.getParent).foreach(parent => Files.createDirectories(parent))
    Files.write(target, content.getBytes(StandardCharsets.UTF_8))
    println(s"wrote $target (${ErrorCode.values.length} codes)")
  }

  private def check(target: Path, expected: String): Unit =
    if !Files.exists(target) then {
      println(s"$target does not exist; run ./mill frontend.apiConstants")
      sys.exit(1)
    } else {
      val actual = new String(Files.readAllBytes(target), StandardCharsets.UTF_8)
      if actual == expected then println(s"$target is up to date (${ErrorCode.values.length} codes)")
      else {
        println(
          s"$target is out of date: the ErrorCode enum or a shared header name has changed since it was " +
            "generated.\nRun ./mill frontend.apiConstants and commit the result."
        )
        sys.exit(1)
      }
    }
}
