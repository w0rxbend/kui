package kui.e2e.fixtures

import java.net.ServerSocket
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*

import kui.e2e.Http

/** A KUI stack of separate containers, with a handle on each one.
  *
  * @param baseUrl
  *   the gateway, which is the only container with a published port — exactly as a real deployment would have
  *   it, and the reason a test cannot accidentally talk to the service directly.
  * @param fixture
  *   the stack it came from, so a scenario can stop and start containers through it.
  */
final case class RunningStack(baseUrl: String, fixture: ComposeFixture) {
  def stopService(name: String): Unit = fixture.stopService(name)
  def startService(name: String): Unit = fixture.startService(name)
  def pauseService(name: String): Unit = fixture.pauseService(name)
  def unpauseService(name: String): Unit = fixture.unpauseService(name)
  def logsOf(name: String): String = fixture.logsOf(name)
}

/** Drives `deployment/compose/docker-compose.yml` — the distributed shape of KUI — from a test.
  *
  * ## Why the `docker compose` command line and not Testcontainers
  *
  * ADR-018 named Testcontainers, and its `DockerComposeContainer` is the obvious thing to reach for. It is
  * the wrong tool for this particular job, for three concrete reasons rather than a preference:
  *
  *   1. It takes ownership of the topology. It rewrites the project name, strips `container_name`, and
  *      proxies every exposed port through a helper container — so `docker stop kui-cluster`, the operation
  *      this entire suite is *about*, no longer names anything that exists.
  *   2. The scenario needs `stop`, `start`, `pause` and `unpause` on an individual container while the rest
  *      of the stack keeps running. Testcontainers' Compose wrapper exposes a lifecycle for the stack, not
  *      for one member of it.
  *   3. The sequence being automated already exists and has been run many times:
  *      `deployment/compose/smoke.sh` performs it at the API level. Driving the same commands from Scala
  *      means the browser test and the shell script cannot drift apart, and it keeps the compose file the
  *      single description of the topology.
  *
  * What is lost is Testcontainers' automatic cleanup of a stack whose test process was killed. That is bought
  * back by `down -v --remove-orphans` in the fixture's teardown, and by the fact that the containers have
  * fixed names, so a leftover stack is visible in `docker ps` and removed by one obvious command rather than
  * being an anonymous orphan.
  *
  * ## The readiness interval
  *
  * The gateway polls each service every ten seconds in the shipped configuration, and a suite that waited
  * that out at every transition would be slow enough that people stop running it. An override file lowers it
  * to three seconds for the tests only. The production default is asserted separately, by CFG-001's defaults
  * test — deliberately, so that the end-to-end suite cannot come to depend on a non-default value without the
  * default itself still being checked somewhere.
  */
final class ComposeFixture(file: Path, overrideFile: Option[Path]) {

  private var port: Int = 0
  private var started: Boolean = false

  /** How long the stack is given to come up. Pulling nothing and starting two JVMs; generous so that a slow
    * machine reports a real failure rather than a timeout.
    */
  private val StartTimeoutSeconds = 180

  def baseUrl: String = s"http://localhost:$port"

  /** Brings the stack up and waits until the gateway is serving.
    *
    * `--wait` makes Compose itself wait for both containers' health checks, which is a better wait than any
    * this fixture could write: it is the same definition of healthy that an operator sees.
    */
  def start(): RunningStack = {
    port = freePort()
    val _ = compose("up", "-d", "--wait", "--wait-timeout", StartTimeoutSeconds.toString)
    started = true

    awaitGateway()
    RunningStack(baseUrl, this)
  }

  /** Stops one container: a real process dies, which is the thing the all-in-one shape cannot demonstrate and
    * the reason this suite exists at all.
    */
  def stopService(name: String): Unit = {
    val _ = compose("stop", name)
  }

  def startService(name: String): Unit = {
    val _ = compose("start", name)
  }

  /** Freezes a container without killing it: it holds its connections open and answers nothing, which is what
    * "slow, not dead" looks like from the gateway's side and what drives the circuit breaker (ADR-037). A
    * stopped container refuses connections immediately; a paused one is the harder and more realistic
    * failure.
    */
  def pauseService(name: String): Unit = {
    val _ = compose("pause", name)
  }

  def unpauseService(name: String): Unit = {
    val _ = compose("unpause", name)
  }

  def logsOf(name: String): String =
    scala.util.Try(compose("logs", "--no-color", "--tail", "200", name)).getOrElse("<no logs>")

  /** Tears the stack down, including its volumes and anything left over from an earlier run.
    *
    * Never throws. A teardown that fails hides the test failure that preceded it, and by this point the only
    * thing that matters is that the next run starts from nothing.
    */
  def stop(): Unit =
    if started then {
      val _ = scala.util.Try(compose("down", "-v", "--remove-orphans"))
      started = false
    }

  private def awaitGateway(): Unit = {
    val deadline = System.nanoTime() + StartTimeoutSeconds * 1_000_000_000L
    while !Http.isUp(s"$baseUrl/api/v1/health/ready") do {
      if System.nanoTime() > deadline then {
        val logs = logsOf("kui-gateway")
        stop()
        throw new IllegalStateException(
          s"$baseUrl/api/v1/health/ready did not answer within $StartTimeoutSeconds seconds.\n" +
            s"The gateway's log:\n$logs"
        )
      }
      Thread.sleep(500)
    }
  }

  /** Runs one `docker compose` command against this topology and answers what it printed.
    *
    * `KUI_PORT` is passed in the environment rather than written into the compose file: the file is the
    * deployment artefact an operator reads and copies, and a test must not need it to be different from what
    * ships.
    */
  private def compose(arguments: String*): String = {
    val files = List("-f", file.toString) ++ overrideFile.toList.flatMap(path => List("-f", path.toString))
    val command = List("docker", "compose") ++ files ++ arguments.toList

    val builder = new ProcessBuilder(command.asJava)
      .directory(file.getParent.toFile)
      .redirectErrorStream(true)
    val _ = builder.environment().put("KUI_PORT", port.toString)

    val process = builder.start()
    val output = new String(process.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)

    if !process.waitFor(StartTimeoutSeconds + 60L, TimeUnit.SECONDS) then {
      val _ = process.destroyForcibly()
      throw new IllegalStateException(s"`${command.mkString(" ")}` never finished. Output so far:\n$output")
    }
    if process.exitValue() != 0 then {
      throw new IllegalStateException(
        s"`${command.mkString(" ")}` exited with ${process.exitValue()}:\n$output"
      )
    }
    output
  }

  private def freePort(): Int = {
    val socket = new ServerSocket(0)
    try socket.getLocalPort
    finally socket.close()
  }
}

object ComposeFixture {

  private val FileVariable = "KUI_E2E_COMPOSE_FILE"
  private val OverrideVariable = "KUI_E2E_COMPOSE_OVERRIDE"

  /** The topology the build pointed us at, when it exists. `None` makes the suites skip loudly. */
  def fromEnvironment: Option[ComposeFixture] =
    sys.env
      .get(FileVariable)
      .map(Path.of(_))
      .filter(Files.isRegularFile(_))
      .map(file =>
        new ComposeFixture(file, sys.env.get(OverrideVariable).map(Path.of(_)).filter(Files.isRegularFile(_)))
      )

  /** Whether a Docker daemon is there to talk to.
    *
    * Asked before the suites run, because "Docker is not installed" and "the fault-isolation test failed"
    * must not look the same in a build log. E2E-002's degraded case is that CI marks the milestone criterion
    * unverified — which is only possible if the skip is visible.
    */
  def dockerAvailable: Boolean =
    scala.util
      .Try {
        val process = new ProcessBuilder("docker", "info").redirectErrorStream(true).start()
        val _ = process.getInputStream.readAllBytes()
        process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0
      }
      .getOrElse(false)

  val SkipHint: String =
    "Docker is not available, so the distributed stack cannot be started. The fault-isolation " +
      "criterion is UNVERIFIED, not passing."
}
