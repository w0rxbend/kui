package kui.build

import munit.FunSuite

/** That a forked document generator cannot fail without the build saying why.
  *
  * Every `openApiCheck` in this repository — ten of them, 2,544 tasks — plus `docs.errorCodes --check` and
  * the `frontend.apiConstants --check` that CI runs on every push, reach their verdict through
  * [[ForkedDocumentRun.decide]]. Before this suite existed, changing one word inside it left
  * `./mill services.topic.api.openApiCheck` reporting `629/629, SUCCESS` over a committed `openapi.json` with
  * a path deleted from it: measured by W10-05's verifier, on the shipped tree.
  *
  * The three states below are states of a child process — a document that no longer matches its endpoints, a
  * JVM that never reached `main`, and a clean run — and no suite can arrange the middle one on demand. What
  * the child printed and what it exited with are values, so that is what is cased here.
  */
final class ForkedDocumentRunSuite extends FunSuite {

  private val generator: String = "kui.topic.api.OpenApiDocument"

  test("aCleanRunFailsNothing") {
    assertEquals(ForkedDocumentRun.decide(generator, 0, "", ""), Right(None))
  }

  test("aCleanRunStillForwardsWhatTheGeneratorSaid") {
    // Not a failure and not discarded. The generators are quiet on success, so anything here is a warning
    // the generator chose to print while still agreeing with its document, and a build that swallowed it
    // would be hiding the one signal that arrives before the gate goes red.
    assertEquals(
      ForkedDocumentRun.decide(generator, 0, "warning: two operations share an operationId\n", ""),
      Right(Some("warning: two operations share an operationId"))
    )
  }

  test("aStaleDocumentFailsWithTheGeneratorsOwnSentence") {
    // THE DEFECT THIS GATE EXISTS FOR. The generator has already written the useful sentence — which
    // document, and the command that regenerates it — so the decision carries it verbatim rather than
    // paraphrasing it, and names the class so that a `__.openApiCheck` run with ten failures says which of
    // the ten documents is stale.
    val said =
      "services/topic/api/openapi.json is out of date with the endpoints it is generated from;\n" +
        "run ./mill services.topic.api.openApi"

    assertEquals(
      ForkedDocumentRun.decide(generator, 1, said, ""),
      Left(s"$generator exited 1:\n$said")
    )
  }

  test("aGeneratorThatFailsOnStderrIsReportedTheSameWay") {
    // Which stream the sentence arrived on is the JVM's business and not the reader's: an exception thrown
    // out of `main` lands on stderr and a checked document's complaint lands on stdout, and both are the
    // same finding. A decision that read only stdout would report the exception as silence, which is the
    // branch below and the wrong repair.
    assertEquals(
      ForkedDocumentRun.decide(generator, 1, "", "Exception in thread \"main\" java.io.IOException\n"),
      Left(s"$generator exited 1:\nException in thread \"main\" java.io.IOException")
    )
  }

  test("bothStreamsAreReplayedWhenTheChildUsedBoth") {
    assertEquals(
      ForkedDocumentRun.decide(generator, 2, "the document is stale\n", "and the temp file was kept\n"),
      Left(s"$generator exited 2:\nthe document is stale\nand the temp file was kept")
    )
  }

  test("aDeadForkIsNamedAsOneRatherThanAsAStaleDocument") {
    // THE FAILURE THAT COST TWO WAVES AN HOUR EACH. Roughly one `__.openApiCheck` run in four under load
    // died this way, and `runMain`'s `Subprocess failed` is byte-for-byte what a stale document looked
    // like. The whole value of this branch is the two sentences: that silence on both streams means the
    // fork never reached `main`, and that the repair is on the machine and not in any `openapi.json`.
    val decided = ForkedDocumentRun.decide(generator, 1, "", "")

    assertEquals(
      decided,
      Left(
        s"$generator exited 1 and printed nothing on stdout or stderr.\n" +
          "The fork died before it reached `main`, so this is not a stale document: re-run this\n" +
          "one module on an unloaded machine before touching any openapi.json."
      )
    )

    // Asserted as substrings as well as as a whole, because these two are the sentences a reader acts on
    // and the equality above would be repaired by anybody who reworded the message.
    val message = decided.swap.getOrElse(fail("a dead fork must fail the build"))
    assert(message.contains("printed nothing on stdout or stderr"), clue = message)
    assert(message.contains("this is not a stale document"), clue = message)
  }

  test("whitespaceOnlyOutputIsSilence") {
    // A child killed by the OOM killer can still have flushed a newline. Trimming happens before the
    // silence decision so that a blank line does not get reported as the generator's own sentence, which
    // would send a reader to the document with nothing to read.
    assertEquals(
      ForkedDocumentRun.decide(generator, 137, "\n", "   \n\t"),
      Left(ForkedDocumentRun.silence(generator, 137))
    )
  }

  test("theExitCodeIsCarriedRatherThanFlattenedToOne") {
    // 137 is SIGKILL and 1 is a generator that disagreed with its document. They lead to different
    // repairs, so the number is in both messages.
    assert(ForkedDocumentRun.decide(generator, 137, "", "").swap.exists(_.contains("exited 137")))
    assert(ForkedDocumentRun.decide(generator, 3, "stale", "").swap.exists(_.contains("exited 3")))
  }
}
