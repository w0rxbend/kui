package kui.testkit

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import io.circe.Json
import io.circe.syntax.*

import munit.FunSuite

/** That the golden helper fails loudly, explains itself, and rewrites only when asked.
  *
  * Every test works in a scratch directory, so the suite can prove the rewrite path without any
  * chance of rewriting a committed sample.
  */
final class GoldenSuite extends FunSuite {

  private val document = Json.obj("b" -> 2.asJson, "a" -> 1.asJson)

  private val scratch = FunFixture[Path](
    setup = _ => Files.createTempDirectory("kui-golden"),
    teardown = directory => {
      Files.list(directory).forEach(file => Files.delete(file))
      Files.delete(directory)
    }
  )

  scratch.test("a missing sample fails with the command that creates it") { directory =>
    val failure = intercept[munit.FailException] {
      Golden.assertJson(document, "absent.json", directory, update = false)
    }
    assert(failure.getMessage.contains("KUI_UPDATE_GOLDEN=1"), failure.getMessage)
    assert(failure.getMessage.contains("absent.json"), failure.getMessage)
  }

  scratch.test("reading a missing sample fails the same way, rather than returning nothing") {
    directory =>
      val failure = intercept[munit.FailException](Golden.read("absent.json", directory))
      assert(failure.getMessage.contains("KUI_UPDATE_GOLDEN=1"), failure.getMessage)
  }

  scratch.test("a document that differs fails with a diff naming the file") { directory =>
    Files.write(
      directory.resolve("sample.json"),
      "{\n  \"a\" : 1,\n  \"b\" : 99\n}".getBytes(StandardCharsets.UTF_8)
    )

    val failure = intercept[munit.ComparisonFailException] {
      Golden.assertJson(document, "sample.json", directory, update = false)
    }
    assert(failure.getMessage.contains("sample.json"), failure.getMessage)
  }

  scratch.test("a document that matches passes, whatever order its keys were built in") {
    directory =>
      Files.write(
        directory.resolve("sample.json"),
        "{\n  \"a\" : 1,\n  \"b\" : 2\n}\n".getBytes(StandardCharsets.UTF_8)
      )
      Golden.assertJson(document, "sample.json", directory, update = false)
  }

  scratch.test("the update flag writes the sample instead of failing, and it reads back") {
    directory =>
      Golden.assertJson(document, "created.json", directory, update = true)

      assertEquals(Golden.read("created.json", directory), document)
      assertEquals(Golden.names(directory), List("created.json"))
      Golden.assertJson(document, "created.json", directory, update = false)
  }

  scratch.test("a rewritten sample is sorted and indented, so a diff shows only real changes") {
    directory =>
      Golden.assertJson(document, "created.json", directory, update = true)

      assertEquals(
        new String(Files.readAllBytes(directory.resolve("created.json")), StandardCharsets.UTF_8),
        "{\n  \"a\" : 1,\n  \"b\" : 2\n}\n"
      )
  }

  test("the golden directory is the one every module uses") {
    assertEquals(Golden.Directory, "test/resources/golden")
  }
}
