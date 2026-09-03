package kui.e2e.fixtures

import java.net.ServerSocket
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*

import kui.e2e.{Http, RunningKui}

/** Starts the all-in-one jar in a child process, and waits until it is genuinely ready.
  *
  * ## Why the jar and not the classpath
  *
  * `./mill apps.allinone.run` would be quicker to arrange, and it would test something slightly different
  * from what ships. The assembled jar is the artefact that goes into the container image, and the specific
  * thing an end-to-end test is well placed to catch is a resource that exists on the development classpath
  * and is missing from the jar — a stylesheet, `index.html`, the linked JavaScript. Running the jar is what
  * makes "the packaged product starts and serves a working page" a checked fact.
  *
  * ## Why a freely chosen port
  *
  * 8080 is the most contended port on any developer's machine, and a suite that fails because something
  * unrelated is already listening teaches people that the suite is flaky. The fixture asks the operating
  * system for a free port, and hands it to the process as a flag.
  */
object AllInOneFixture {

  /** Where the build put the assembled jar. */
  private val JarVariable = "KUI_E2E_ALLINONE_JAR"

  /** How long the process is given to bind its port and report ready.
    *
    * Generous, because this includes the JVM's own start-up on a cold machine and a first-time class load of
    * the whole server. It is a deadline for reporting a clear failure, not a budget: a healthy start takes a
    * few seconds and the suite continues as soon as it does.
    */
  private val StartTimeoutSeconds = 90

  /** Whether the build handed us a jar to run. When it did not, the suites skip loudly rather than pass: an
    * end-to-end test that quietly tests nothing is worse than one that is visibly absent.
    */
  def jar: Option[Path] =
    sys.env.get(JarVariable).map(Path.of(_)).filter(Files.isRegularFile(_))

  /** Starts the process and waits for `/api/v1/health/ready`.
    *
    * Readiness and not liveness: a live server is one whose port is open, and a browser opened against it a
    * moment too early gets a page whose first API call fails for a reason that has nothing to do with the
    * test.
    */
  def start(): RunningKui = {
    val jarPath = jar.getOrElse(
      throw new IllegalStateException(
        s"$JarVariable is not set to an existing file. Run the suite through `./mill e2e.test`, " +
          "which builds the all-in-one jar and passes its path in."
      )
    )

    val port = freePort()
    val log = Files.createTempFile("kui-e2e-allinone", ".log")
    val java = Path.of(System.getProperty("java.home"), "bin", "java").toString

    val command = List(
      java,
      "-jar",
      jarPath.toString,
      s"--kui.server.port=$port",
      // Readable when a person opens the attached log after a failure. The images default to JSON,
      // which is right for a log collector and unhelpful here.
      "--kui.telemetry.logFormat=text",
      // Plain HTTP on localhost, so the session cookie is sent back. Without it the browser drops
      // the cookie, `/auth/me` never establishes a CSRF token, and the retry button cannot work.
      "--kui.server.devInsecureCookies=true"
    )

    val process = new ProcessBuilder(command.asJava)
      .redirectOutput(log.toFile)
      .redirectErrorStream(true)
      .start()

    val baseUrl = s"http://localhost:$port"
    val readLog = () => scala.util.Try(Files.readString(log)).getOrElse("<the log could not be read>")

    val stop = () => {
      process.destroy()
      if !process.waitFor(20, TimeUnit.SECONDS) then {
        val _ = process.destroyForcibly()
      }
      ()
    }

    awaitReady(baseUrl, process, readLog, stop)
    RunningKui(baseUrl, readLog, stop)
  }

  /** Polls readiness until the deadline, and reports the process's own log when it never arrives.
    *
    * The log is the whole value of this method. A bare "timed out after 90 seconds" sends whoever reads the
    * CI output back to reproduce the failure locally; the configuration error or the bind failure that
    * actually happened is printed by the process itself, and attaching it turns a re-run into a read.
    */
  private def awaitReady(
      baseUrl: String,
      process: Process,
      readLog: () => String,
      stop: () => Unit
  ): Unit = {
    val deadline = System.nanoTime() + StartTimeoutSeconds * 1_000_000_000L

    while !Http.isUp(s"$baseUrl/api/v1/health/ready") do {
      if !process.isAlive then {
        throw new IllegalStateException(
          s"the all-in-one process exited with code ${process.exitValue()} before it was ready.\n" +
            s"Its log:\n${readLog()}"
        )
      }
      if System.nanoTime() > deadline then {
        stop()
        throw new IllegalStateException(
          s"$baseUrl/api/v1/health/ready did not answer within $StartTimeoutSeconds seconds.\n" +
            s"The process's log:\n${readLog()}"
        )
      }
      // The one deliberate sleep in this module, and it is inside a poll rather than instead of one.
      // Nothing here is a browser condition, so Playwright's auto-waiting has nothing to hook into.
      Thread.sleep(250)
    }
  }

  /** A port nothing is listening on, at the moment it is asked for.
    *
    * There is a theoretical race — something else could take the port between the socket closing and the
    * server binding — and the alternative, a fixed port, loses to that race every time somebody has a
    * development server running. A server that cannot bind also stops with a message naming the port, so the
    * rare loss is diagnosable rather than mysterious.
    */
  private def freePort(): Int = {
    val socket = new ServerSocket(0)
    try socket.getLocalPort
    finally socket.close()
  }
}
