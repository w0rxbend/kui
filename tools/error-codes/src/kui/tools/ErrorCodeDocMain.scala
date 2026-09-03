package kui.tools

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import kui.kernel.error.ErrorCode

/** Writes — or, with `--check`, verifies — the generated error-code document.
  *
  * Two modes rather than two tools: the same rendering decides both, so a document that passes the check is
  * exactly the document a regeneration would produce. CI runs the check, which is what turns "somebody forgot
  * to regenerate" from a review comment into a build failure.
  */
object ErrorCodeDocMain {

  def main(args: Array[String]): Unit = {
    val checkOnly = args.contains("--check")
    val target = args.find(!_.startsWith("--")).map(Paths.get(_)).getOrElse(defaultTarget)
    val expected = ErrorCodeDoc.render(ErrorCode.values.toList)

    if checkOnly then check(target, expected) else write(target, expected)
  }

  private val defaultTarget: Path = Paths.get("docs", "api", "error-codes.md")

  private def write(target: Path, content: String): Unit = {
    Option(target.getParent).foreach(parent => Files.createDirectories(parent))
    Files.write(target, content.getBytes(StandardCharsets.UTF_8))
    println(s"wrote $target (${ErrorCode.values.length} codes)")
  }

  private def check(target: Path, expected: String): Unit =
    if !Files.exists(target) then {
      println(s"$target does not exist; run ./mill docs.errorCodes")
      sys.exit(1)
    } else {
      val actual = new String(Files.readAllBytes(target), StandardCharsets.UTF_8)
      if actual == expected then println(s"$target is up to date (${ErrorCode.values.length} codes)")
      else {
        println(
          s"$target is out of date: the ErrorCode enum has changed since it was generated.\n" +
            "Run ./mill docs.errorCodes and commit the result."
        )
        sys.exit(1)
      }
    }
}
