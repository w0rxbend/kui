package kui.build

import munit.FunSuite

/** That the build tells the truth about itself, in every state a build machine can be in.
  *
  * None of these states can be arranged for a real build — a suite cannot make the checkout dirty, remove
  * `.git`, or roll the clock — which is exactly why the derivation was written as a function over command
  * output. What the commands print is something a table can enumerate completely.
  */
final class BuildFactsSuite extends FunSuite {

  private val hash: String = "a8dcd14f2b3c4d5e6f708192a3b4c5d6e7f80912"

  private def facts(revParse: Option[String], status: Option[String]): BuildFacts =
    BuildFacts.of(
      version = "0.1.0-SNAPSHOT",
      revParse = revParse,
      status = status,
      builtAt = "2026-09-03T10:00:00Z",
      scalaVersion = "3.9.0",
      jdkVersion = "21"
    )

  test("aCleanCheckoutReportsItsCommit") {
    val built = facts(Some(s"$hash\n"), Some(""))

    assertEquals(built.gitCommit, hash)
    assertEquals(built.gitCommitShort, "a8dcd14")
    assertEquals(built.gitDirty, false)
  }

  test("gitDirtyIsTrueForALocalBuildWithChanges") {
    // This is the field that explains why "it works on the commit you gave me" and "it does not work for
    // you" can both be true. `git status --porcelain` printing anything at all means the tree was modified.
    val built = facts(Some(hash), Some(" M build.mill\n?? scratch.txt\n"))

    assertEquals(built.gitDirty, true)
  }

  test("aCheckoutWithNoGitDirectoryIsUnknownAndClean") {
    // A release tarball. Every field says `unknown` rather than being blank, and the tree is reported clean
    // rather than dirty: marking every tarball build as modified would make the field alarming in exactly
    // the case where nothing is wrong.
    val built = facts(None, None)

    assertEquals(built.gitCommit, BuildFacts.Unknown)
    assertEquals(built.gitCommitShort, BuildFacts.Unknown)
    assertEquals(built.gitDirty, false)
    assertEquals(built.version, "0.1.0-SNAPSHOT")
  }

  test("outputThatIsNotACommitHashIsRefused") {
    // Some git versions print an error to standard output in a repository with no commits. Splicing that
    // text into a generated source file would produce a compile error about the wrong thing entirely.
    val notHashes = List(
      "fatal: ambiguous argument 'HEAD'",
      "",
      "a8dcd14",
      s"${hash}extra",
      hash.toUpperCase,
      hash.replace('a', 'z')
    )

    notHashes.foreach { raw =>
      assertEquals(facts(Some(raw), Some("")).gitCommit, BuildFacts.Unknown, s"accepted '$raw' as a hash")
    }
  }

  test("shortenLeavesUnknownAlone") {
    // `unknown`.take(7) is `unknow`, which looks like a commit id and is not.
    assertEquals(BuildFacts.shorten(BuildFacts.Unknown), BuildFacts.Unknown)
  }

  test("theGeneratedSourceIsCompilableScalaWithEveryFieldPresent") {
    val rendered = BuildFacts.render("kui.gateway.api", "GatewayBuildInfo", facts(Some(hash), Some("")))

    assert(rendered.startsWith("package kui.gateway.api"), rendered)
    assert(rendered.contains("object GatewayBuildInfo {"), rendered)
    List("version", "gitCommit", "gitCommitShort", "gitDirty", "builtAt", "scalaVersion", "jdkVersion")
      .foreach(field => assert(rendered.contains(s"val $field"), s"$field is missing:\n$rendered"))
    assert(rendered.contains(s"""val gitCommit: String = "$hash""""), rendered)
    assert(rendered.contains("val gitDirty: Boolean = false"), rendered)
  }

  test("aValueContainingAQuoteCannotBreakOutOfTheGeneratedLiteral") {
    // Not a threat model so much as a debugging one: the day a version string contains a quote is the day a
    // mysterious compile error appears in generated code nobody has open in an editor.
    assertEquals(BuildFacts.literal("""1.0-"; val x = 1"""), """"1.0-\"; val x = 1"""")
    assertEquals(BuildFacts.literal("a\\b"), """"a\\b"""")
    assertEquals(BuildFacts.literal("line\nbreak"), """"line\nbreak"""")
  }
}
