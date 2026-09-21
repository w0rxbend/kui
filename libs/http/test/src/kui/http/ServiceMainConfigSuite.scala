package kui.http

import java.time.Instant

import scala.concurrent.duration.DurationInt

import munit.FunSuite

import kui.config.{ConfigErrors, ConfigProblem, ConfigSourceName, KuiConfig, PrincipalKeyConfig}
import kui.kernel.Secret

/** The slice of a loaded configuration the process shell reads, and the one field in it that is a
  * deployment-wide contract rather than a local setting.
  *
  * `ServiceMain` is constructed by six `Main` objects and by no suite anywhere, so `ProcessConfig.from` —
  * nine words that decide whether a service can verify anything the gateway signed — was executed by no case:
  * replacing `config.gateway.principalKeys` with `Nil` left 822 cases over eight `libs` modules green, and a
  * service built that way accepts no principal at all and answers `401 KUI-UNAUTHENTICATED` to every request
  * the gateway forwards.
  *
  * The reason it is easy to get wrong is written in the source: the keys are read from
  * `kui.gateway.principalKeys`, which *looks* like a gateway setting and is in fact the shared key set of one
  * deployment — the gateway signs with the newest key whose `notBefore` has passed and every service accepts
  * any key in the set, which is what makes a rotation a rolling change rather than an outage.
  */
final class ServiceMainConfigSuite extends FunSuite {

  private val keys: List[PrincipalKeyConfig] = List(
    PrincipalKeyConfig("k-2025", Secret("older"), Instant.parse("2025-01-01T00:00:00Z")),
    PrincipalKeyConfig("k-2026", Secret("newer"), Instant.parse("2026-01-01T00:00:00Z"))
  )

  private val config: KuiConfig =
    KuiConfig.Default.copy(
      gateway = KuiConfig.Default.gateway.copy(principalKeys = keys)
    )

  test("a service process verifies principals with the deployment's whole key set") {
    // Every key, not the newest one: a service that kept only the newest refuses every request still
    // carrying a token signed by the previous key, which is exactly the window a rotation opens.
    val process = ServiceMain.ProcessConfig.from(config)

    assertEquals(process.principalKeys, keys)
    assertEquals(process.principalKeys.map(_.kid), List("k-2025", "k-2026"))
  }

  test("the server and telemetry slices are the loaded ones and not defaults") {
    // The other two fields, so that the case above cannot be satisfied by a `from` that returns the
    // configuration wholesale in one field and defaults in the rest.
    val narrowed = KuiConfig.Default.copy(
      server = KuiConfig.Default.server.copy(port = kui.kernel.Port.unsafe(9099))
    )

    assertEquals(ServiceMain.ProcessConfig.from(narrowed).server.port.value, 9099)
    assertEquals(ServiceMain.ProcessConfig.from(narrowed).telemetry, KuiConfig.Default.telemetry)
  }

  test("a deployment that configures no keys gets none, rather than an invented one") {
    // The honest empty: a process with no keys cannot verify anything and says so per request. An
    // invented default key would be a shared secret every KUI in the world knows.
    assertEquals(ServiceMain.ProcessConfig.from(KuiConfig.Default).principalKeys, Nil)
  }

  test("a refusal to start names every configuration problem, and not the first one") {
    // "All of them rather than the first, because fixing configuration one message per restart is
    // miserable and slow" is this function's own docstring, and truncating the render to one line left
    // 825 cases over eight `libs` modules green. It is the one message an operator sees when a
    // container will not come up, and it is written to standard error rather than to the logger
    // precisely because there may not be a logger yet.
    val errors = ConfigErrors.of(
      ConfigProblem("kui.server.port", "expected a port, found 'eighty'", ConfigSourceName.Env),
      ConfigProblem("kui.clusters.0.bootstrapServers", "must not be empty", ConfigSourceName.File("kui.yaml"))
    )

    val message = ServiceMain.configProblems("kui-schema", errors)

    assert(message.contains("kui-schema cannot start"), message)
    assert(message.contains("kui.server.port"), message)
    assert(message.contains("kui.clusters.0.bootstrapServers"), message)
    // Every problem is on its own line, so a reader counts them rather than parsing prose.
    assertEquals(message.linesIterator.size, 3)
  }

  test("a stopping service waits longer for its requests than the listener under it does") {
    // Fifteen seconds, and the relation to `KuiServer.DefaultGracefulShutdown` is the rule rather than
    // either number on its own: a service call arrives through the gateway, which is itself waiting on
    // it, so a drain that ends before the listener's does strands two processes instead of one. Set to
    // zero, 827 cases over eight `libs` modules stayed green and a rolling deployment cuts people off
    // mid-answer. This is the one place in the build that can see both numbers.
    assertEquals(ServiceMain.DrainTimeout, 15.seconds)
    assert(
      ServiceMain.DrainTimeout > KuiServer.DefaultGracefulShutdown,
      s"${ServiceMain.DrainTimeout} is not longer than ${KuiServer.DefaultGracefulShutdown}"
    )
  }
}
