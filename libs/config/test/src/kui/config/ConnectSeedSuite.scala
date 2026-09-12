package kui.config

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import scala.util.Using

import com.sun.net.httpserver.{HttpExchange, HttpServer}

import kui.testkit.KuiSuite

/** The shipped `connect-seed.sh`, run for real against a Connect worker whose answers are written here.
  *
  * ==Why a harness and not an assertion==
  *
  * `ShippedScriptsSuite` and `ShippedScriptGuardPositionSuite` both *read* this script. Reading it closed the
  * shape of one line — a `grep` that can exit 1 on a normal poll, and where the fallback that rescues it has
  * to sit. Neither can say anything about what the script DOES, and the two rows `docs/plan/verification/
  * W13-02.md` filed as V-3 and V-4 are both about behaviour:
  *
  *   - **V-3.** Deleting the whole announce block — `reported=…`, the
  *     `if [ "${reported}" != "${announced}" ]` and its `say` — left `./mill libs.config.test` at 395/395
  *     SUCCESS. The seed then waits in silence, and a seed that waits in silence and a seed that has died are
  *     the same two minutes from outside the container. That half of the wave-13 acceptance item was met only
  *     by a control somebody ran by hand.
  *   - **V-4.** `[ "${running}" -ge 2 ]` → `-ge 1` also left the suite at 395/395 SUCCESS. Against a worker
  *     answering connector `RUNNING` with `tasks[0].state=UNASSIGNED` the seed then exits 0 printing
  *     *"quickstart-file-source and its task are RUNNING"* over a task that is not running. The threshold is
  *     the entire reason the wait loop exists, and the success sentence is not derived from the state it
  *     publishes.
  *
  * Both are closed here by driving the script rather than by reading it, which is the only reader that can
  * tell them apart: the two mutations change no text either static suite matches on.
  *
  * ==The worker==
  *
  * `HttpServer` on `127.0.0.1:0`, so the port is whatever the kernel hands out and two of these can run at
  * once without a port roster to keep. It answers the three requests the script makes — `GET /connectors` for
  * readiness, `PUT /connectors/<name>/config` for the registration, and `GET /connectors/<name>/status` for
  * the poll — and the status answers are a LIST, handed out in order with the last one repeating. A list is
  * what makes the first poll expressible: the real defect TD-053 was filed on only ever appears on a poll
  * that is not the last one, which is exactly what a machine warm enough to answer `RUNNING` immediately
  * never produces.
  *
  * The status documents are written on one line, the way a worker writes them, because the script's own
  * comment says it counts `RUNNING` states with `grep -o | wc -l` rather than `grep -c` for that reason. A
  * pretty-printed fixture would make `grep -c` work and quietly delete the thing that comment is about.
  *
  * ==Why the third case is not decoration==
  *
  * The second case asserts a NON-zero exit and the absence of a sentence. Both are satisfied by a harness
  * that has stopped working — a wrong path, a worker that never binds, a script that dies on its first line —
  * so on its own it is a case that passes hardest when it is measuring nothing. The third case drives the
  * same harness with a worker that is fully `RUNNING` and requires exit 0 and the sentence, so the two
  * together say *the sentence is printed exactly when the task's own state is RUNNING* rather than *the
  * sentence was not seen*.
  */
final class ConnectSeedSuite extends KuiSuite {

  private val seedScript = "deployment/quickstart/seed/connect-seed.sh"
  private val connector = "quickstart-file-source"

  /** A worker that is up, with its one task up. What the script is waiting for. */
  private val bothRunning =
    s"""{"name":"$connector","connector":{"state":"RUNNING","worker_id":"seed:8083"},""" +
      s""""tasks":[{"id":0,"state":"RUNNING","worker_id":"seed:8083"}],"type":"source"}"""

  /** The answer the first poll really gets: `PUT .../config` has just answered 201 and nothing has been
    * assigned yet. No `RUNNING` anywhere in the document, which is the poll that used to kill the script.
    */
  private val connectorUnassigned =
    s"""{"name":"$connector","connector":{"state":"UNASSIGNED","worker_id":"seed:8083"},""" +
      s""""tasks":[],"type":"source"}"""

  /** The connector up and its task not — one `RUNNING` state in the document, not two. This is the state
    * V-4's mutation cannot tell apart from success.
    */
  private val taskUnassigned =
    s"""{"name":"$connector","connector":{"state":"RUNNING","worker_id":"seed:8083"},""" +
      s""""tasks":[{"id":0,"state":"UNASSIGNED","worker_id":"seed:8083"}],"type":"source"}"""

  /** One answer from the scripted worker. */
  final private case class Answer(code: Int, body: String)

  /** What one driven run printed, and what the worker saw while it ran. */
  final private case class Run(exit: Int, output: String, statusPolls: Int, configured: Boolean)

  /** A value that exists to be thrown away, named so that `-Wvalue-discard` does not have to guess. */
  private def discard(value: Any): Unit = { val _ = value }

  /** The connector up and its task dead. `PUT .../config` has already answered 201 — the task is loaded and
    * then fails, which is the case the wait loop's own header is written about — so nothing before the poll
    * can see it.
    */
  private val taskFailed =
    s"""{"name":"$connector","connector":{"state":"RUNNING","worker_id":"seed:8083"},""" +
      s""""tasks":[{"id":0,"state":"FAILED","worker_id":"seed:8083",""" +
      s""""trace":"java.io.FileNotFoundException: /opt/kafka/LICENSE"}],"type":"source"}"""

  /** Run the shipped seed against a worker that answers `statuses` in order, the last one repeating.
    *
    * `configAnswer` is a declared seam and it is behaviour-preserving: every call site that existed before it
    * omits it and gets the 201 the parameter defaults to. It exists because `Answer(code, body)` was written
    * to express a rejection and no case used it with a non-2xx code, so the `2*)` arm of the script's `case`
    * — and the `die` under it that the script's longest comment argues for — were driven by nothing.
    */
  private def drive(
      statuses: List[String],
      timeoutSeconds: Int,
      configAnswer: Answer = Answer(201, s"""{"name":"$connector"}""")
  ): Run = {
    assert(statuses.nonEmpty, "a scripted worker with no answers would hang the script rather than test it")

    val polls = new AtomicInteger(0)
    val configured = new AtomicBoolean(false)
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)

    server.createContext(
      "/",
      (exchange: HttpExchange) => {
        val path = exchange.getRequestURI.getPath
        // Drained before the answer goes out: the registration is a PUT with a body, and a body left
        // unread is a connection curl finishes reporting as an error rather than as the 201 it got.
        discard(Using.resource(exchange.getRequestBody)(_.readAllBytes()))

        val answer =
          if path == "/connectors" then Answer(200, s"""["$connector"]""")
          else if path.endsWith("/config") then {
            configured.set(true)
            configAnswer
          } else if path.endsWith("/status") then
            Answer(200, statuses(math.min(polls.getAndIncrement(), statuses.size - 1)))
          else Answer(404, s"""{"error_code":404,"message":"no handler for $path"}""")

        val bytes = answer.body.getBytes(StandardCharsets.UTF_8)
        exchange.getResponseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(answer.code, bytes.length.toLong)
        Using.resource(exchange.getResponseBody)(_.write(bytes))
      }
    )
    server.start()

    try {
      val builder = new ProcessBuilder("bash", repositoryRoot.resolve(seedScript).toString)
        .redirectErrorStream(true)
      discard(builder.environment().put("KUI_CONNECT_URL", s"http://127.0.0.1:${server.getAddress.getPort}"))
      discard(builder.environment().put("KUI_SEED_TIMEOUT_SECONDS", timeoutSeconds.toString))

      val process = builder.start()
      // Read to EOF first and wait afterwards: stdout closes when the process exits, so this is the
      // ordering that cannot deadlock on a script whose output outgrows the pipe buffer.
      val output = Using.resource(process.getInputStream)(stream =>
        new String(stream.readAllBytes(), StandardCharsets.UTF_8)
      )
      // The script carries its own deadline, so anything past it plus a wide margin is a hang and not a
      // slow machine. Killed rather than left behind: a leaked `bash` polling a dead port outlives the run.
      if !process.waitFor(timeoutSeconds.toLong + 120L, TimeUnit.SECONDS) then {
        discard(process.destroyForcibly())
        fail(s"$seedScript had not exited ${timeoutSeconds + 120}s after it started. It printed:\n$output")
      }
      Run(process.exitValue(), output, polls.get(), configured.get())
    } finally server.stop(0)
  }

  test("a first poll with nothing assigned is waited through, and the seed says what it is waiting for") {
    val run = drive(List(connectorUnassigned, bothRunning), timeoutSeconds = 30)

    assert(
      run.configured && run.statusPolls >= 2,
      s"the scripted worker saw configured=${run.configured} and ${run.statusPolls} status poll(s), so the " +
        s"shipped script did not drive it the way the rest of this case assumes. It printed:\n${run.output}"
    )

    assertEquals(
      run.exit,
      0,
      clue = s"$seedScript exited ${run.exit} against a worker whose FIRST status poll answers UNASSIGNED " +
        s"with no tasks and whose next one answers RUNNING. That poll is the normal one — `PUT .../config` " +
        s"has just answered 201 — and a seed that cannot survive it takes the quickstart's Connect screen " +
        s"down on every cold machine. It printed:\n${run.output}"
    )

    assert(
      run.output.contains(s"waiting for $connector: connector UNASSIGNED, 0 RUNNING state(s)"),
      s"$seedScript exited 0 without ever naming the state it was waiting in. Compose shows one line per " +
        s"container, so a seed that waits in silence and a seed that has died are the same two minutes from " +
        s"outside it; the announce block exists so the first poll is reported rather than inferred. " +
        s"Deleting that block leaves every static reader of this script green. It printed:\n${run.output}"
    )
  }

  test("a task that is not RUNNING is never announced as RUNNING, and the seed dies saying so") {
    // Four seconds, so the script reaches its own `die` in two polls. Long enough that a machine under
    // load still gets two answers out of the worker, short enough that a red run is red in seconds.
    val run = drive(List(taskUnassigned), timeoutSeconds = 4)

    assert(
      !run.output.contains(s"$connector and its task are RUNNING"),
      s"$seedScript announced *$connector and its task are RUNNING* over a worker answering connector " +
        s"RUNNING with `tasks[0].state=UNASSIGNED`. The success sentence names the task, so it has to be " +
        s"derived from the task's own state: the threshold `-ge 2` is the connector's own RUNNING plus at " +
        s"least one task's, and at `-ge 1` the connector alone satisfies it. It printed:\n${run.output}"
    )

    assertEquals(
      run.exit,
      1,
      clue = s"$seedScript exited ${run.exit} over a connector whose only task is UNASSIGNED. A connector " +
        s"with no running task is the *connector nobody can explain* this wait loop was written for, and " +
        s"the seed's contract is exit 0 only when the connector AND at least one task report RUNNING. It " +
        s"printed:\n${run.output}"
    )

    assert(
      run.output.contains("it did not reach RUNNING within 4s"),
      s"$seedScript exited non-zero without its own timeout line, which means it died somewhere other than " +
        s"the `die` written to explain this state — the TD-053 shape, one stage over. It " +
        s"printed:\n${run.output}"
    )

    assert(
      run.output.contains(s"waiting for $connector: connector RUNNING, 1 RUNNING state(s)"),
      s"$seedScript timed out without reporting the half-up state it spent the whole timeout in. That line " +
        s"is the difference between an operator reading *the task never started* and reading nothing at " +
        s"all. It printed:\n${run.output}"
    )
  }

  test("a connector and task that are both RUNNING are announced, which is what the case above denies") {
    // THE ANTI-VACUITY ARM. The case above asserts a non-zero exit and the ABSENCE of a sentence, and both
    // are satisfied by a harness that has stopped working — a moved script, a worker that never bound, a
    // bash that is not on this machine. This one requires the same harness to produce exit 0 and that same
    // sentence, so the pair asserts *printed exactly when the task is RUNNING* and not *not seen*.
    val run = drive(List(bothRunning), timeoutSeconds = 30)

    assertEquals(
      run.exit,
      0,
      clue = s"$seedScript exited ${run.exit} against a worker that is entirely RUNNING on its first poll, " +
        s"which is the state the whole seed exists to reach. It printed:\n${run.output}"
    )

    assert(
      run.output.contains(s"$connector and its task are RUNNING"),
      s"$seedScript reached RUNNING and did not say so, so the case above — which requires this sentence to " +
        s"be ABSENT over a half-up worker — is true of a script that never prints it at all. It " +
        s"printed:\n${run.output}"
    )
  }

  test("a FAILED task is reported as a failure, at once, and not waited out as a timeout") {
    // Twenty seconds of deadline against a worker that is FAILED on its first poll. The short-circuit is the
    // whole difference between the two readings an operator gets: with it, the seed exits in a second or two
    // naming the state and printing the worker's trace; without it, the same stack reports `it did not reach
    // RUNNING within 20s` twenty seconds later, which reads as a slow machine and is a dead task. Deleting
    // the block leaves `./mill libs.config.test` at 395/395 SUCCESS, because both readings exit 1.
    //
    // Twenty and not the sixty this harness could afford, because munit fails a case at thirty seconds of its
    // own accord: a deadline past that turns the assertion below — which names what went wrong — into a bare
    // `TimeoutException`, and a red that explains nothing is how a case gets deleted rather than read.
    val started = System.nanoTime()
    val run = drive(List(taskFailed), timeoutSeconds = 20)
    val elapsedSeconds = (System.nanoTime() - started) / 1000000000.0

    assertEquals(
      run.exit,
      1,
      clue = s"$seedScript exited ${run.exit} over a connector whose only task is FAILED. It printed:\n" +
        run.output
    )

    assert(
      run.output.contains("the connector or its task FAILED"),
      s"$seedScript exited 1 over a FAILED task without naming the failure. The wait loop's own header says " +
        s"a task that fails to start `turns up as FAILED a second or two later with the 201 already " +
        s"returned`, and the short-circuit is what turns that into a message. It printed:\n${run.output}"
    )

    // THE HALF THAT MAKES THE TWO ASSERTIONS ABOVE NON-VACUOUS. A timeout also exits 1, and a timeout also
    // prints the status document the FAILED state is in — so exit code and a substring of the worker's own
    // JSON cannot tell the short-circuit from its absence. The time can: 30 seconds against 2.
    assert(
      elapsedSeconds < 10.0,
      f"$seedScript took $elapsedSeconds%.1fs to report a task that was FAILED on its first poll, against a " +
        f"deadline of 20s. That is the timeout path, not the short-circuit: the state was knowable on the " +
        f"first poll and the seed sat through the whole wait before saying so. It printed:\n${run.output}"
    )
  }

  test("a configuration the worker rejects is reported with the body, and nothing is waited for") {
    // The refusal the script's longest comment exists for: *a Connect rejection is a 400 whose BODY names the
    // offending configuration key*, which is why the registration is read with `-o` and `-w '%{http_code}'`
    // rather than with `curl -f`. Widening the `case` arm from `2*)` to `*)` makes the `die` below it
    // unreachable and leaves every static reader of this script green.
    val rejection =
      """{"error_code":400,"message":"Connector configuration is invalid and contains the following """ +
        """1 error(s):\nInvalid value for configuration file: must be non-null"}"""
    val run = drive(List(bothRunning), timeoutSeconds = 4, configAnswer = Answer(400, rejection))

    assertEquals(
      run.exit,
      1,
      clue = s"$seedScript exited ${run.exit} against a worker that answered 400 to `PUT .../config`. The " +
        s"connector was never registered, so every second the seed spends after this is spent waiting for " +
        s"something nobody asked the worker to create. It printed:\n${run.output}"
    )

    assert(
      run.output.contains("Invalid value for configuration file"),
      s"$seedScript refused the registration without printing the worker's own body, which is the only " +
        s"place the offending configuration key is named — the reason this call is not `curl -f`, written " +
        s"out at length above it. It printed:\n${run.output}"
    )

    // The non-vacuous half, twice over: a seed that accepts the rejection goes on to announce a registration
    // that did not happen and then polls the status of a connector that does not exist, and it exits 1 at the
    // end of that too. Neither the exit code nor the absence of a sentence can tell those apart.
    assertEquals(
      run.statusPolls,
      0,
      clue =
        s"$seedScript polled the connector's status ${run.statusPolls} time(s) after the worker refused " +
          s"its configuration. The `die` under the `2*)` arm is what stops it; with the arm widened to `*)` " +
          s"the seed waits out its whole deadline on a connector the worker never accepted. It " +
          s"printed:\n${run.output}"
    )

    assert(
      !run.output.contains(s"registered $connector"),
      s"$seedScript announced *registered $connector* over a configuration the worker answered 400 to. It " +
        s"printed:\n${run.output}"
    )
  }

  test("the waiting line is printed once per change of state, and not once per poll") {
    // Three polls in one state and then success, so the repeat this case forbids has two chances to happen.
    // `contains` cannot see a repeat, which is why the existing cases are green under the deletion of the
    // `if [ "${reported}" != "${announced}" ]` guard: unguarded, a 120-second wait writes sixty identical
    // lines into the Compose log, which is the unreadable-log failure the announce block was added to fix
    // arriving from the other direction.
    val run = drive(List(connectorUnassigned, connectorUnassigned, bothRunning), timeoutSeconds = 30)

    val waiting = s"waiting for $connector: connector UNASSIGNED, 0 RUNNING state(s)"
    val repeats = run.output.linesIterator.count(_.contains(waiting))

    assert(
      run.statusPolls >= 3,
      s"the scripted worker saw ${run.statusPolls} status poll(s), so the seed never spent two polls in one " +
        s"state and this case cannot tell a guarded `say` from an unguarded one. It printed:\n${run.output}"
    )

    assertEquals(
      repeats,
      1,
      clue = s"$seedScript printed *$waiting* $repeats time(s) over ${run.statusPolls} polls in the same " +
        s"state. The contract written three lines above that `say` is *once per change of state rather than " +
        s"once per poll*, and it is the difference between a log a reader scrolls and a log a reader " +
        s"abandons. It printed:\n${run.output}"
    )
  }

  /** The repository root, found by walking up to the directory holding `build.mill`.
    *
    * Measured rather than guessed at, because a comment beside a copy of this method is how the count went
    * wrong in the first place: `grep -rln 'resolve("build.mill")' --include='*.scala'` answered **12** files
    * on 2026-09-12 before this packet and **14** after it, 7 of them in this package, every one a test
    * source. `ShippedScriptsSuite`'s copy calls itself *the fourth copy in the repository and the second in
    * this package* and says a fifth is the point at which it should move; both numbers were already wrong
    * when they were written. Moving it into `libs/testkit` edits files this packet owns only for additions,
    * so the census is carried as a row in `TECH_DEBT.md` rather than acted on mid-wave.
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
