package kui.build

import java.nio.file.Files

import munit.FunSuite

/** That `build.mill` still wires the decisions this module tests.
  *
  * [[ForkedDocumentRunSuite]] proves that [[ForkedDocumentRun.decide]] refuses a stale document and names a
  * dead fork. It cannot prove that anything ASKS it. `build.mill` is not a module: it is outside
  * `__.checkFormat`, outside `__.fix` and outside every test module (TD-036), so the two lines that carry
  * `decide`'s verdict into the build are reachable by no ordinary suite — and one of them is a `Task.fail`
  * that one word turns into a log line. Measured on this tree, with the decision itself untouched:
  * `case Left(finding) => Task.fail(finding)` → `Task.log.info(finding)` leaves `build-tests.test` at 137/137
  * SUCCESS and `./mill services.topic.api.openApiCheck` at **629/629 SUCCESS over a committed `openapi.json`
  * with a path deleted from it**, printing the generator's own complaint as information.
  *
  * So this suite reads the file as text. That is a weaker instrument than a case over a value and it is said
  * plainly: it cannot tell whether the build WORKS, only whether the two sentences that make it fail are
  * still written. What it buys is that removing them is no longer one silent word — it is a diff in a file
  * whose every line is quoted here, which is the same trade `-Wunused` makes and the reason wave 10's hunter
  * counted it as a gate.
  *
  * EVERY ASSERTION BELOW HAS AN ANCHOR THAT WOULD FAIL FIRST. A source-reading guard whose pattern has
  * stopped matching answers "no violations" over nothing at all, which is how a green gate measures nothing.
  * Each `assert` that forbids something is therefore preceded by one that requires the shape it is written
  * against to still be found.
  */
final class BuildWiringSuite extends FunSuite {

  /** `build.mill` as it ships. The repository is located the way the design suites locate it — by the
    * presence of this very file — so there is one definition of "the root" in this module.
    */
  private val buildFile: String =
    Files.readString(design.DesignSources.repositoryRoot.resolve("build.mill"))

  /** `build.mill` with its comment lines removed.
    *
    * The rule below is written receiver-independently -- `runMain(` with or without a dot in front of it --
    * and `build.mill`'s own scaladoc for `runDocumentMain` quotes ``runMain(...)()`` while explaining why it
    * is not used. Reading the prose would make the assertion fail on the sentence that argues for it, so the
    * prose is dropped and only code is read. Scaladoc continuation lines start with `*`, so they go too.
    */
  private val buildCode: String =
    buildFile.linesIterator
      .filterNot { line =>
        val trimmed = line.trim
        trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
      }
      .mkString("\n")

  test("everyForkedDocumentGeneratorGoesThroughRunDocumentMain") {
    // THE ANCHOR. Twenty-three call sites as this is written: ten `openApiCheck` pairs, `docs.errorCodes`
    // and `frontend.apiConstants`, plus the definition. If this number collapses the assertion below is
    // being made against a file that no longer names the task, and that is the failure to report.
    val routed = "\\.?runDocumentMain\\(".r.findAllIn(buildFile).size
    assert(routed >= 20, clue = s"build.mill names runDocumentMain $routed times, which is too few to be it")

    // AND THE RULE. `runMain(...)()` reports a dead child as the bare `Subprocess failed` with no exit
    // code and no stderr, under a task key detached from the one that asked for it. Two waves spent an
    // hour each telling that apart from a genuinely stale document, W10-05 wrote `runDocumentMain` to end
    // it, and two call sites were left on the old call anyway -- one of them `frontend.apiConstants`,
    // which `.github/workflows/ci.yml` runs on every push. This is what stops a third.
    //
    // THE BYPASS THIS PATTERN USED TO HAVE, filed by this packet's verifier as W11-03's first finding.
    // The rule was `\.runMain\(`, which requires a RECEIVER DOT -- and every `openApiCheck` body calls the
    // task bare, `runDocumentMain(...)`, because it is a method of the enclosing module. So
    // `runDocumentMain(...)` -> `runMain(...)` inside a body was invisible to this case: measured on this
    // tree, `build-tests.test` stayed at 137/137 SUCCESS and, with `/capabilities` deleted from
    // `services/cluster/api/openapi.json`, `./mill services.cluster.api.openApiCheck` printed
    // `wrote .../openapi.json` and `642/642, SUCCESS` -- the deleted path silently back in the committed
    // file. The lookbehind forbids a letter and allows a dot, so both the bare call and the qualified one
    // are read.
    val legacy = "(?<![A-Za-z])runMain\\(".r.findAllIn(buildCode).toList
    assertEquals(
      legacy,
      Nil,
      clue = "build.mill calls runMain for a document generator. Use runDocumentMain: it captures the " +
        "fork's two streams and reports silence on both as its own finding, which runMain renders " +
        "identically to a stale document."
    )
  }

  test("everyOpenApiCheckStillChecksRatherThanWrites") {
    // THE CHEAPEST ATTACK ON THIS PACKET'S OWN WORK, found in its last hour. `openApiCheck` and
    // `openApi` are the same generator with one argument between them, and `runDocumentMain` cannot
    // tell them apart: a task that was never asked to CHECK exits 0 by rewriting the file. Measured
    // on this tree, with `"--check"` deleted from `services.topic.api.openApiCheck` and one path
    // deleted from its committed document at the same time:
    //
    //   629] services.topic.api.openApiCheck wrote .../services/topic/api/openapi.json
    //   629/629, SUCCESS      -- and the deleted path was back in the file
    //
    // Every other guard in this module is satisfied by that mutation, including the one above: the
    // decision IS asked and IS acted on, over a run whose answer was never in doubt. So the
    // argument is asserted, per target, and this is the only thing that reads it.
    val bodies =
      "def openApiCheck\\(\\): Command\\[Unit\\] = Task\\.Command \\{\\s*runDocumentMain\\([^)]*\\)".r
        .findAllIn(buildFile)
        .toList

    // The anchor, and it is an EQUALITY rather than a floor since wave 11. `>= 9` over ten declared
    // targets tolerated exactly one target dropping out of `bodies` -- and a target whose body stops
    // matching this shape is precisely the target that is no longer being checked for `"--check"`, so the
    // floor excused the one case it existed to catch. The count of `def openApiCheck()` declarations is
    // derived from the same file by a different expression, so the two cannot be brought into agreement by
    // one edit: a body rewritten out of this shape now fails here instead of vanishing from the list.
    val declared = "def openApiCheck\\(\\): Command\\[Unit\\]".r.findAllIn(buildFile).size
    assert(
      declared >= 9,
      clue = s"build.mill declares $declared openApiCheck targets, which is too few to be the ten " +
        "services that publish a document; the derivation has stopped matching."
    )
    assertEquals(
      bodies.size,
      declared,
      clue = s"build.mill declares $declared openApiCheck targets and ${bodies.size} of them are bodies " +
        "this case can read. A target outside the shape is a target whose \"--check\" argument is " +
        "asserted by nothing, which is how a silently rewriting gate gets back in."
    )

    val writing = bodies.filterNot(_.contains("\"--check\""))
    assertEquals(
      writing,
      Nil,
      clue = "an openApiCheck target does not pass \"--check\", so it REWRITES the committed document " +
        "instead of comparing against it and can never disagree with anything again. A stale document " +
        "is then repaired in silence and the gate reports SUCCESS."
    )
  }

  test("runDocumentMainStillFailsTheBuildOnTheDecisionItReads") {
    // The two lines that carry `ForkedDocumentRun`'s verdict into Mill, quoted. Both are anchors and
    // assertions at once: the first proves the build asks, the second proves it acts on the answer.
    assert(
      buildFile.contains("ForkedDocumentRun.decide(mainClass, ran.exitCode, ran.out.text(), ran.err.text())"),
      clue = "runDocumentMain no longer asks ForkedDocumentRun.decide, so every case in " +
        "ForkedDocumentRunSuite is now about code the build does not run."
    )
    assert(
      buildFile.contains("case Left(finding) => Task.fail(finding)"),
      clue = "runDocumentMain reads the decision and does not fail on it. A `Left` that becomes " +
        "Task.log.info leaves every openApiCheck green over a stale committed document -- measured, " +
        "629/629 SUCCESS -- because the generator's complaint is then printed as information."
    )
  }

  test("theInterfaceTypeGateStillReadsTheBrowserSuite") {
    // A GATE ABOUT A GATE, and the third of this wave's three holes of the same shape. Filed as
    // W11-02/F-2. `frontend/e2e/**` -- 5,169 lines, the largest body of TypeScript outside
    // `packages/` -- was outside every type gate for eight waves, because `tsc --build` at the root
    // checks the ten package projects and `e2e/tsconfig.json` is `composite: false` and cannot be a
    // reference. W11-02 repaired it by making `typecheck` TWO `tsc` invocations, and the repair lives
    // entirely inside a string in `package.json` that nothing anywhere asserts: measured, deleting
    // the second invocation leaves `pnpm -C frontend typecheck` at exit 0 printing only
    // `$ tsc --build --pretty false`, over a tree whose `shell.spec.ts` holds
    // `const x: number = "not a number";` -- the identical tree exits 2 under the shipped script, and
    // `.github/workflows/ci.yml` runs `pnpm typecheck` and inherits whatever the script says.
    //
    // Read here rather than in a vitest case because vitest is configured by the same workspace this
    // is about, and because this module already owns the two gates-about-gates above. It is a text
    // read for the reason the whole suite is: it cannot tell whether the gate WORKS, only whether the
    // sentence that makes it run is still written.
    val manifest =
      Files.readString(design.DesignSources.repositoryRoot.resolve("frontend/package.json"))

    val script = "\"typecheck\": \"([^\"]*)\"".r
      .findFirstMatchIn(manifest)
      .map(_.group(1))
      .getOrElse(fail("frontend/package.json declares no `typecheck` script at all"))

    // The anchor: the root solution build, which is the half that was never in doubt.
    assert(
      script.contains("tsc --build"),
      clue = s"frontend's typecheck script no longer runs the root solution build: `$script`"
    )
    assert(
      script.contains("e2e/tsconfig.json"),
      clue = s"frontend's typecheck script is `$script`, which does not name e2e/tsconfig.json. The " +
        "browser suite is then outside every type gate again -- `tsc --build` cannot reach it, " +
        "because that project is composite: false and cannot be a reference -- and CI's " +
        "`pnpm typecheck` step exits 0 over it."
    )
  }

  test("everyDocumentGateTheWorkflowRunsAsksItToCheckRatherThanWrite") {
    // THE SAME HOLE ON THE YAML SIDE, filed by this packet's verifier as W11-03's third finding.
    // `openApiCheck` is a target and bakes its own `"--check"` in, which the case above asserts per target.
    // The other two document gates do not: `docs.errorCodes` and `frontend.apiConstants` take the argument
    // from whoever invokes them, and the only invoker is `.github/workflows/ci.yml`. Measured on this
    // tree: `run: ./mill docs.errorCodes --check` -> `run: ./mill docs.errorCodes` left
    // `build-tests.test` and `./scripts/run-tests.sh` both green, because nothing in this repository read
    // `.github/workflows/**` at all -- and in the runner that step regenerates `docs/api/error-codes.md`
    // and exits 0, so the committed table can never disagree with the Scala again.
    //
    // Only `run:` lines are read. The job's own comment two screens up names `./mill docs.errorCodes`
    // without the argument while explaining what a person types to REGENERATE the file, and that sentence
    // is correct; a case that read the prose would refuse it.
    val workflow = Files.readString(design.DesignSources.repositoryRoot.resolve(".github/workflows/ci.yml"))

    val invocations = workflow.linesIterator
      .map(_.trim)
      .filter(line => line.startsWith("run:") && line.contains("./mill "))
      .filter(line => line.contains("docs.errorCodes") || line.contains("frontend.apiConstants"))
      .toList

    // The anchor. Both generators are run by this workflow; if this finds fewer, the steps were renamed or
    // deleted and the rule below is being enforced over nothing.
    assertEquals(
      invocations.size,
      2,
      clue = s"ci.yml runs ${invocations.size} of the two hand-invoked document gates: $invocations. " +
        "A gate that stopped being run is a stronger finding than one run without --check."
    )

    val writing = invocations.filterNot(_.contains("--check"))
    assertEquals(
      writing,
      Nil,
      clue = "a ci.yml step runs a document generator without --check, so the step REGENERATES the " +
        "committed file and exits 0 instead of comparing against it. docs/api/error-codes.md and " +
        "frontend/packages/api/src/constants.generated.ts can then drift from the Scala for ever with " +
        "the workflow green."
    )
  }
}
