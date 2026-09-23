package kui.build

import java.nio.file.{Files, Path, Paths}

import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.matching.Regex

import munit.FunSuite

/** Locale-neutral casing is part of every protocol, configuration and search boundary.
  *
  * Java's no-argument `String.toLowerCase` and `toUpperCase` use the host locale. In a Turkish locale, for
  * example, `i` and `I` do not normalize to the ASCII tokens used by HTTP, Kafka and KUI's wire formats. This
  * repository guard keeps that class of deployment-only defect from returning after individual boundary tests
  * have proved the behavior.
  */
final class LocaleSafetySuite extends FunSuite {

  private val noArgumentCaseConversion: List[Regex] = List(
    raw"\.(?:toLowerCase|toUpperCase)\b(?!\s*\()".r,
    raw"\.(?:toLowerCase|toUpperCase)\s*\(\s*\)".r
  )

  test("production Scala never uses host-locale string case conversion") {
    val violations = productionScalaFiles.flatMap { file =>
      Files
        .readAllLines(file)
        .asScala
        .zipWithIndex
        .collect {
          case (line, index) if containsNoArgumentConversion(codeBeforeComment(line)) =>
            s"${repositoryRoot.relativize(file)}:${index + 1}: ${line.trim}"
        }
    }

    assertEquals(
      violations,
      Nil,
      clue =
        "Use Locale.ROOT (or Character case conversion for one character) at deterministic boundaries:\n" +
          violations.mkString("\n")
    )
  }

  test("the guard distinguishes implicit, empty and explicit locale calls") {
    List("value.toLowerCase", "value.toUpperCase()")
      .foreach(line => assert(containsNoArgumentConversion(line), clue(line)))

    List(
      "value.toLowerCase(Locale.ROOT)",
      "value.toUpperCase(java.util.Locale.ROOT)",
      "// value.toLowerCase",
      "* `value.toUpperCase` is unsafe"
    ).foreach(line => assert(!containsNoArgumentConversion(codeBeforeComment(line)), clue(line)))
  }

  private def containsNoArgumentConversion(line: String): Boolean =
    noArgumentCaseConversion.exists(_.findFirstIn(line).nonEmpty)

  private def codeBeforeComment(line: String): String = {
    val trimmed = line.trim
    if trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*") then ""
    else
      line.take(line.indexOf("//") match {
        case -1 => line.length
        case index => index
      })
  }

  private def productionScalaFiles: List[Path] =
    List("apps", "libs", "services", "tools").flatMap { directory =>
      val root = repositoryRoot.resolve(directory)
      Using.resource(Files.walk(root)) { files =>
        files.iterator.asScala.filter(isProductionScala).toList
      }
    }

  private def isProductionScala(file: Path): Boolean = {
    val relative = repositoryRoot.relativize(file)
    val parts = relative.iterator.asScala.map(_.toString).toList
    val sourceIndex = parts.indexWhere(part => part == "src" || part.startsWith("src-"))

    Files.isRegularFile(file) &&
    file.getFileName.toString.endsWith(".scala") &&
    sourceIndex >= 0 &&
    !parts.take(sourceIndex).contains("test")
  }

  private val repositoryRoot: Path = {
    def find(candidate: Path): Path =
      if candidate == null then fail(s"no build.mill above ${Paths.get("").toAbsolutePath}")
      else if Files.isRegularFile(candidate.resolve("build.mill")) then candidate
      else find(candidate.getParent)

    find(Paths.get("").toAbsolutePath.normalize)
  }
}
