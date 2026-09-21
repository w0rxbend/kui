package kui.build

/** What `KuiJvmModule.runDocumentMain` decides once a forked document generator has exited.
  *
  * This is the whole of `openApiCheck`'s power, and of `docs.errorCodes --check` and
  * `frontend.apiConstants --check`, expressed as a function so that something can read it. Twenty-two call
  * sites in `build.mill` route through it and until this file existed not one of them was covered: measured
  * by W10-05's verifier, turning the failure branch below into a log line left
  * `./mill services.topic.api.openApiCheck` reporting `629/629, SUCCESS` over a committed
  * `services/topic/api/openapi.json` with a path deleted from it. A gate whose only failure path is one
  * unread word in the one file outside `__.checkFormat`, outside `__.fix` and outside every test module is
  * not a gate.
  *
  * The decision is separated from the fork rather than the fork from the decision, and that direction
  * matters: the three states below are states of a CHILD PROCESS, and a suite cannot arrange for a JVM to die
  * before `main` on demand any more than `BuildFactsSuite` can make a checkout dirty. What the child printed
  * and what it exited with are values, and a table covers values completely.
  */
object ForkedDocumentRun {

  /** The three things a forked generator can have done, and what each one means for the build.
    *
    *   - exit 0: the document matched, or was rewritten. `Right(Some(line))` when the child nevertheless said
    *     something, because a warning printed by a run that still exited 0 is exactly the kind of thing that
    *     goes unread for three waves.
    *   - non-zero having said something: the generator's own sentence is the finding, so it is carried
    *     verbatim into the failure and the caller prints nothing of its own.
    *   - non-zero having said nothing: the fork died before it reached `main`. This is the only shape in
    *     which the task can fail without saying why, so it is named in words rather than being left as an
    *     exit code, and the message sends a reader to the machine rather than to `openapi.json`. Roughly one
    *     `./mill __.openApiCheck` run in four under load failed this way across waves 8 and 9 and cost two
    *     waves an hour each, because it is byte-for-byte what a genuinely stale document looked like.
    *
    * @param mainClass
    *   the generator that ran, named in every message: under `__.openApiCheck` ten of these fail into the
    *   same log and the module segment alone does not say which document is stale
    * @param exitCode
    *   the child's exit status
    * @param out
    *   everything the child wrote to stdout, untrimmed
    * @param err
    *   everything the child wrote to stderr, untrimmed
    * @return
    *   `Left(message)` to fail the task with, or `Right(line)` to forward to the log on success
    */
  def decide(mainClass: String, exitCode: Int, out: String, err: String): Either[String, Option[String]] =
    (exitCode, spoke(out, err)) match {
      case (0, said) => Right(said)
      case (code, Some(text)) => Left(s"$mainClass exited $code:\n$text")
      case (code, None) => Left(silence(mainClass, code))
    }

  /** Both of the child's streams as one block of text, or nothing at all.
    *
    * Joined rather than reported separately because a generator's sentence can arrive on either stream and a
    * reader does not care which; `Option` rather than the empty string because "said nothing" is a distinct
    * finding above and a caller that compared against `""` would be restating that decision in a second
    * place.
    */
  def spoke(out: String, err: String): Option[String] =
    Seq(out.trim, err.trim).filter(_.nonEmpty) match {
      case Nil => None
      case lines => Some(lines.mkString("\n"))
    }

  /** The sentence a dead fork gets, which is the one thing this whole file exists to keep sayable. */
  def silence(mainClass: String, exitCode: Int): String =
    s"$mainClass exited $exitCode and printed nothing on stdout or stderr.\n" +
      "The fork died before it reached `main`, so this is not a stale document: re-run this\n" +
      "one module on an unloaded machine before touching any openapi.json."
}
