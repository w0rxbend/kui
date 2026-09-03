package kui.build

/** What a running KUI process can say about the build it came from.
  *
  * A support conversation starts with "which version are you running?", and the honest answer is not a
  * container tag — a tag can be moved, rebuilt or hand-typed. It is the commit the code was compiled from,
  * whether that working tree had uncommitted changes, and when the compilation happened.
  *
  * @param version
  *   the product version, e.g. `0.1.0-SNAPSHOT`
  * @param gitCommit
  *   the full 40-character commit hash, or [[BuildFacts.Unknown]] outside a git checkout
  * @param gitCommitShort
  *   the first seven characters of the same hash, which is what a person actually pastes into a message
  * @param gitDirty
  *   whether the working tree had uncommitted changes when the build ran. A `true` here is why "it works on
  *   the commit you gave me" and "it does not work for you" can both be right at once.
  * @param builtAt
  *   when the build ran, as an RFC 3339 instant in UTC
  * @param scalaVersion
  *   the compiler version, so a bug report says which one produced the bytecode
  * @param jdkVersion
  *   the JDK the build ran on
  */
final case class BuildFacts(
    version: String,
    gitCommit: String,
    gitCommitShort: String,
    gitDirty: Boolean,
    builtAt: String,
    scalaVersion: String,
    jdkVersion: String
)

/** Turning what git and the JDK report into [[BuildFacts]], and [[BuildFacts]] into a Scala source file.
  *
  * All of it is pure functions over strings, and that is the point rather than a style preference. The build
  * cannot run a test against "a checkout with uncommitted changes" or "a source tarball with no git
  * directory" — those are states of the machine, not values a suite can construct. Parsing what the commands
  * *printed*, on the other hand, is something a table test covers completely, so the rules below are checked
  * rather than hoped for.
  */
object BuildFacts {

  /** What every field becomes when the build cannot find out.
    *
    * Not an empty string and not a failure. A source checkout without a `.git` directory is a perfectly
    * ordinary way to build KUI — it is what a release tarball is — and a build that refused to run there, or
    * that produced a blank version the UI footer rendered as a gap, would be worse than one that says plainly
    * that it does not know.
    */
  val Unknown: String = "unknown"

  /** The length of the short hash. Seven is what git itself abbreviates to by default and what every tool
    * that reads a commit id expects to be handed.
    */
  val ShortHashLength: Int = 7

  /** The facts, from the raw output of the two git commands and the build's own values.
    *
    * @param revParse
    *   what `git rev-parse HEAD` printed, or `None` if the command could not be run at all
    * @param status
    *   what `git status --porcelain` printed, or `None` as above. Note the difference between `Some("")` and
    *   `None`: an empty string is a clean tree, and no answer at all is a tree we know nothing about.
    */
  def of(
      version: String,
      revParse: Option[String],
      status: Option[String],
      builtAt: String,
      scalaVersion: String,
      jdkVersion: String
  ): BuildFacts = {
    val commit = revParse.map(_.trim).filter(isCommitHash).getOrElse(Unknown)

    BuildFacts(
      version = version,
      gitCommit = commit,
      gitCommitShort = shorten(commit),
      // Not knowing is reported as clean. The alternative — treating an unavailable git as dirty — would put
      // a permanent "modified" mark on every release build made from a tarball, which is exactly backwards:
      // the field would then be alarming in the case where nothing is wrong and say nothing where something
      // is.
      gitDirty = status.exists(_.trim.nonEmpty),
      builtAt = builtAt,
      scalaVersion = scalaVersion,
      jdkVersion = jdkVersion
    )
  }

  /** Whether a string is a full git commit hash: forty lowercase hexadecimal characters.
    *
    * Checked rather than trusted because the value is compiled into a source file. `git rev-parse HEAD` in a
    * repository with no commits prints an error to standard output on some versions, and a build that spliced
    * that text into Scala source would fail to compile with a message about the wrong thing entirely.
    */
  def isCommitHash(raw: String): Boolean =
    raw.length == 40 && raw.forall(character => character.isDigit || ('a' to 'f').contains(character))

  /** The first seven characters of a hash, and `unknown` left alone — abbreviating it to `unknow` would
    * produce a string that looks like a commit id and is not.
    */
  def shorten(commit: String): String =
    if commit == Unknown then Unknown else commit.take(ShortHashLength)

  /** The generated Scala source, as text.
    *
    * Every value is a string literal, so nothing has to be parsed at runtime and the endpoint cannot fail
    * because a field was malformed. The escaping is deliberate rather than defensive: a version string comes
    * from this build file and a commit hash is validated above, but the day one of them contains a quote is
    * the day a mysterious compile error appears in generated code nobody has open.
    */
  def render(packageName: String, objectName: String, facts: BuildFacts): String =
    s"""package $packageName
       |
       |/** Generated by `build.mill`. Do not edit.
       |  *
       |  * A browser has no filesystem and a container has no git checkout, so the only way for a running
       |  * process to know which build it is, is for the build to compile the answer in.
       |  */
       |object $objectName {
       |  val version: String = ${literal(facts.version)}
       |  val gitCommit: String = ${literal(facts.gitCommit)}
       |  val gitCommitShort: String = ${literal(facts.gitCommitShort)}
       |  val gitDirty: Boolean = ${facts.gitDirty}
       |  val builtAt: String = ${literal(facts.builtAt)}
       |  val scalaVersion: String = ${literal(facts.scalaVersion)}
       |  val jdkVersion: String = ${literal(facts.jdkVersion)}
       |}
       |""".stripMargin

  /** A Scala string literal for `raw`, with the four characters that would end or reshape it escaped. */
  def literal(raw: String): String = {
    val escaped = raw
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
    s"\"$escaped\""
  }
}
