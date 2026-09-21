package kui.config

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.stream.Collectors

import scala.jdk.CollectionConverters.*
import scala.util.Using

import kui.testkit.KuiSuite

/** Every image that copies one workspace manifest copies all of them, and the roster comes from `git
  * ls-files`.
  *
  * ==The defect, measured before it was repaired==
  *
  * `deployment/storybook/Dockerfile` copied **eight** `frontend/packages/<name>/package.json` files into its
  * build context. `git ls-files` over the workspace manifests answers **eleven**, and
  * `frontend/pnpm-lock.yaml` declares eleven importers. The three it omitted — `feature-alerts`,
  * `feature-connect`, `feature-ksql` — each ship a `.stories.` file, which is the one thing that image exists
  * to serve. The `RUN pnpm install --frozen-lockfile` two lines below the list therefore had nothing to be
  * frozen against for three of the eleven importers the lockfile names.
  *
  * Nothing in this repository built that image: `.github/workflows/ci.yml` runs `pnpm build-storybook` out of
  * the workspace directly, so the accessibility sweep covers all eleven packages' stories and the Dockerfile
  * that is supposed to ship them covers eight. A roster nobody derives, in a file nobody runs, is invisible
  * for exactly as long as nobody clones the repository and builds it.
  *
  * ==Why this is not `BuildWiringSuite`'s case over again==
  *
  * `build-tests`' `theInterfaceImageCopiesEachManifestIntoItsOwnPackageDirectory` reads
  * `deployment/frontend/Dockerfile`, by name, and asserts eleven pairs whose halves agree. It is a good case
  * and it could not have found this: it names one file, and the defect was in the other one. So this suite
  * derives BOTH sides — the packages from `git ls-files`, the images from a walk of `deployment/` — and
  * compares them for every image that copies any manifest at all. House rule 26 for the image roster, house
  * rule 27 for the package roster.
  *
  * ==Why `git ls-files` and not a directory listing==
  *
  * House rule 27, and this wave exists because of the alternative. A working tree carries `node_modules`,
  * another session's scratch directory and anything `.gitignore` hides; `ls frontend/packages` answers a
  * question about this machine and `git ls-files` answers one about the repository, which is what a `COPY`
  * line in a Dockerfile is a statement about. A package whose `package.json` is untracked cannot be copied
  * into any build context by any clone, so it has no business being on either side of this comparison.
  *
  * ==The scope, stated so it is not widened later by accident==
  *
  * The rule is *an image that copies SOME workspace manifests copies ALL of them*, not *every Dockerfile
  * copies manifests*. An image with no pnpm workspace in it — the JVM builder, the nginx runtime stages —
  * copies none, carries no `COPY frontend/packages/…` line, and is not this rule's subject. That is the only
  * form of the rule that is both true of this tree and able to fail.
  */
final class ShippedImageManifestSuite extends KuiSuite {

  /** `COPY frontend/packages/<name>/package.json ./packages/<name>/`, anchored at both ends.
    *
    * Anchored on purpose: `COPY frontend/ ./` further down every one of these files copies all eleven
    * manifests as a side effect of copying the source, and a reader that matched it would find each image
    * complete and this suite would assert nothing. The layer this rule is about is the one ABOVE the install,
    * whose whole point is to be a list.
    */
  private val manifestCopy =
    ("""(?m)^[ \t]*COPY[ \t]+frontend/packages/([A-Za-z0-9_.-]+)/package\.json""" +
      """[ \t]+\./packages/([A-Za-z0-9_.-]+)/[ \t]*$""").r

  /** A line in the shape above, written out, so the reader is driven before it is believed. */
  private val fixture = "COPY frontend/packages/feature-ksql/package.json ./packages/feature-ksql/"

  /** The workspace packages, as the repository carries them rather than as this machine does. */
  private def workspacePackages(root: Path): List[String] = {
    val process =
      new ProcessBuilder("git", "-C", root.toString, "ls-files", "frontend/packages/*/package.json")
        .redirectErrorStream(true)
        .start()
    val output =
      Using.resource(process.getInputStream)(s => new String(s.readAllBytes(), StandardCharsets.UTF_8))
    val status = process.waitFor()
    assertEquals(status, 0, s"`git ls-files frontend/packages/*/package.json` exited $status:\n$output")

    output.linesIterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(_.stripPrefix("frontend/packages/").stripSuffix("/package.json"))
      .toList
      .sorted
  }

  /** Every `Dockerfile*` under `deployment/`, relative to the repository root, `.dockerignore` aside. */
  private def deploymentDockerfiles(root: Path): List[String] = {
    val stream = Files.walk(root.resolve("deployment"))
    try
      stream
        .filter(Files.isRegularFile(_))
        .map[String](path => root.relativize(path).toString.replace('\\', '/'))
        .filter(name => name.substring(name.lastIndexOf('/') + 1).startsWith("Dockerfile"))
        .filter(name => !name.endsWith(".dockerignore"))
        .collect(Collectors.toList[String])
        .asScala
        .toList
        .sorted
    finally stream.close()
  }

  private def copiedManifests(root: Path, relative: String): List[(String, String)] =
    manifestCopy
      .findAllMatchIn(Files.readString(root.resolve(relative)))
      .map(found => found.group(1) -> found.group(2))
      .toList

  test("every image that copies a workspace manifest copies all of them, into their own directories") {
    val root = repositoryRoot

    val probe = manifestCopy.findAllMatchIn(fixture).map(found => found.group(1) -> found.group(2)).toList
    assertEquals(
      probe,
      List("feature-ksql" -> "feature-ksql"),
      clue = s"the manifest-COPY reader found $probe in a line that is one: `$fixture`. A reader that " +
        "cannot see that line finds no COPY lines in any Dockerfile either, and every comparison below is " +
        "then true of an empty list — which is the state this whole suite was written to make impossible."
    )

    val packages = workspacePackages(root)
    assert(
      packages.sizeIs > 1,
      s"`git ls-files frontend/packages/*/package.json` answered ${packages.size} package(s). This " +
        "repository has a pnpm workspace with several, so a roster this short means the derivation broke, " +
        "not that the workspace shrank."
    )

    val images = deploymentDockerfiles(root)
      .map(relative => relative -> copiedManifests(root, relative))
      .filter { case (_, copied) => copied.nonEmpty }

    assert(
      images.nonEmpty,
      s"no Dockerfile under deployment/ carries a `COPY frontend/packages/<name>/package.json` line. Two " +
        "did when this suite was written; if both were deleted this case is measuring nothing and should " +
        "be deleted with them, and if they merely moved, the walk above is looking in the wrong place."
    )

    val disagreements = images.flatMap { case (relative, copied) =>
      val sources = copied.map((from, _) => from)
      val omitted = packages.filterNot(sources.contains)
      val unknown = sources.filterNot(packages.contains)
      val misdirected = copied.filter((from, into) => from != into)

      List(
        Option.when(omitted.nonEmpty)(s"$relative omits ${omitted.mkString(", ")}"),
        Option.when(unknown.nonEmpty)(s"$relative copies ${unknown.mkString(", ")}, which git does not"),
        Option.when(misdirected.nonEmpty)(
          s"$relative copies ${misdirected.map((from, into) => s"$from into ./packages/$into/").mkString(", ")}"
        )
      ).flatten
    }

    assertEquals(
      disagreements,
      Nil,
      clue = disagreements.mkString(
        s"the workspace git carries is [${packages.mkString(", ")}], and:\n  ",
        "\n  ",
        "\n\nAn image whose manifest list is short runs `pnpm install --frozen-lockfile` over a workspace " +
          "the lockfile does not describe, so the install fails — or, worse, succeeds against a workspace " +
          "missing the packages whose stories or screens the image exists to serve. An image that copies a " +
          "manifest git does not carry cannot be built by any clone at all. Both halves are checked because " +
          "an omission is invisible to a rule that only reads what is written down: `deployment/storybook/" +
          "Dockerfile` shipped eight of eleven for three packages' worth of history and no gate in this " +
          "repository could see it."
      )
    )
  }

  /** Is this image's own install resolved against the workspace the manifests describe?
    *
    * Read off the instruction lines only. Every one of these files argues about `pnpm install` in prose above
    * the line that runs it — `deployment/storybook/Dockerfile:30` does it in the paragraph explaining why the
    * lockfile check is the version gate — so a reader that counted comments would put an image in scope for
    * discussing the rule rather than for being subject to it.
    */
  private def installsTheWorkspace(text: String): Boolean = {
    val instructions = text.linesIterator.filterNot(_.trim.startsWith("#")).mkString("\n")
    instructions.contains("frontend/pnpm-lock.yaml") || instructions.contains("pnpm install")
  }

  test("an image that installs the workspace copies every manifest, and copying none is not an exemption") {
    val root = repositoryRoot
    val packages = workspacePackages(root)

    // THE SCOPE IS DERIVED FROM WHAT MAKES THE ROSTER LOAD-BEARING, AND NOT FROM WHAT THE ROSTER SAYS.
    //
    // The case above scopes itself with `.filter { case (_, copied) => copied.nonEmpty }` — an image that
    // copies SOME manifests must copy ALL of them. That reads the answer to decide whether to ask the
    // question, so an image that copies NONE is out of scope: deleting all eleven `COPY
    // frontend/packages/<n>/package.json` lines from `deployment/storybook/Dockerfile` left
    // `./mill libs.config.test` at 395/395 SUCCESS, while deleting three of them was red. The maximal form
    // of the defect wave 14 repaired was ungated and the minimal one was not.
    //
    // The subject of the rule is an image whose `pnpm install` resolves against `frontend/pnpm-lock.yaml`:
    // that install is the thing the manifest layer exists to feed, and it needs the whole workspace or
    // `--frozen-lockfile` is frozen against a workspace the lockfile does not describe. So the scope is read
    // off the install, the roster is read off `git ls-files`, and neither side can be quieted by editing the
    // list this case is about.
    //
    // The scope reader is driven by this tree in both directions, which is why it carries no fixture of its
    // own: `deployment/quickstart/Dockerfile` copies no manifest and runs no pnpm at all, so a reader that
    // answered `true` for everything fails the comparison below on that file, and a reader that answered
    // `false` for everything fails the emptiness assertion above it.
    val subjects = deploymentDockerfiles(root)
      .map(relative => relative -> Files.readString(root.resolve(relative)))
      .filter { case (_, text) => installsTheWorkspace(text) }
      .map { case (relative, _) => relative -> copiedManifests(root, relative).map((from, _) => from).sorted }

    assert(
      subjects.sizeIs >= 2,
      s"${subjects.size} Dockerfile(s) under deployment/ install the pnpm workspace. Two did when this case " +
        "was written — the interface image and the Storybook image — and both name `frontend/pnpm-lock.yaml` " +
        "on a COPY line. A scope this small means the reader above stopped recognising an install, and every " +
        "comparison below is then true of an empty list."
    )

    val incomplete = subjects.collect {
      case (relative, copied) if copied != packages =>
        val omitted = packages.filterNot(copied.contains)
        s"$relative installs the workspace and copies ${copied.size} of ${packages.size} manifests" +
          (if omitted.isEmpty then "" else s", omitting ${omitted.mkString(", ")}")
    }

    assertEquals(
      incomplete,
      Nil,
      clue = incomplete.mkString(
        s"the workspace git carries is [${packages.mkString(", ")}], and:\n  ",
        "\n  ",
        "\n\nThe manifest layer sits above `RUN pnpm install --frozen-lockfile` so that a source edit does " +
          "not invalidate the dependency cache. An image that installs the workspace from a partial manifest " +
          "list gets a cache layer built over a workspace the lockfile does not describe, so " +
          "`--frozen-lockfile` asserts nothing about the importers that were left out — which is the " +
          "guarantee the flag is there to obtain, and it is lost silently, because `COPY frontend/ ./` " +
          "further down repairs the workspace for everything after the install."
      )
    )
  }

  /** The repository root, found by walking up to the directory holding `build.mill`. See `ConnectSeedSuite`'s
    * copy of this method for the census: fourteen in the tree, and moving them into `libs/testkit` is carried
    * as a row in `TECH_DEBT.md` rather than done mid-wave.
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
