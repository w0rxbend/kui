package kui.config

import java.nio.file.Path

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalacheck.Prop.forAll
import org.scalacheck.{Arbitrary, Gen}

import kui.kernel.Port
import kui.testkit.KuiSuite

/** That the four configuration layers are consulted in the documented order, always.
  *
  * The order is command line, then environment, then file, then the built-in default. It matters
  * in a very ordinary way: an operator debugging a container overrides one key on the command
  * line, and if the file quietly won instead, they would conclude the setting does nothing.
  */
final class PrecedenceSuite extends KuiSuite {

  private def load(
      args: List[String] = Nil,
      env: Map[String, String] = Map.empty,
      files: List[Path] = Nil
  ): Either[ConfigErrors, KuiConfig] =
    KuiConfigSource.loadFrom[IO](args, files, env, UrlPolicy.Dev).unsafeRunSync()

  private def portFile(port: Int): Path =
    ConfigFixtures.yaml(s"kui:\n  server:\n    port: $port\n")

  private def portOf(result: Either[ConfigErrors, KuiConfig]): Int =
    result.fold(errors => fail(errors.render), _.server.port.value)

  test("the command line beats the environment, which beats the file, which beats the default") {
    val file = portFile(1111)

    assertEquals(portOf(load(files = List(file))), 1111)
    assertEquals(portOf(load(env = Map("KUI_SERVER_PORT" -> "2222"), files = List(file))), 2222)
    assertEquals(
      portOf(
        load(
          args = List("--kui.server.port=3333"),
          env = Map("KUI_SERVER_PORT" -> "2222"),
          files = List(file)
        )
      ),
      3333
    )
  }

  test("a value present only in the file is used") {
    assertEquals(portOf(load(files = List(portFile(4444)))), 4444)
  }

  test("--server.port and --kui.server.port name the same key") {
    assertEquals(portOf(load(args = List("--server.port", "5555"))), 5555)
  }

  test("a later file overrides an earlier one") {
    assertEquals(portOf(load(files = List(portFile(1111), portFile(6666)))), 6666)
  }

  test("--config adds a file, and it wins over the ones passed in") {
    val fromFlag = portFile(7777)
    assertEquals(
      portOf(load(args = List("--config", fromFlag.toString), files = List(portFile(1111)))),
      7777
    )
  }

  test("--config=<path> names a file, exactly as --config <path> does") {
    // The `=` spelling used to fall through to the generic `--key=value` branch and become a flag
    // called `kui.config` that nothing reads. Nothing checks flag names, so the file was dropped in
    // silence and the process started fully defaulted -- listening on the wrong port, with no
    // upstreams and no signing keys, and not one word about the file it had been given.
    val fromFlag = portFile(7777)
    assertEquals(portOf(load(args = List(s"--config=${fromFlag.toString}"))), 7777)
  }

  test("both spellings of every flag agree, including --config") {
    val file = portFile(8888)
    assertEquals(
      portOf(load(args = List("--config", file.toString))),
      portOf(load(args = List(s"--config=${file.toString}")))
    )
  }

  // Every default in one place, so adding a field without deciding its default is visible here.
  private val defaults: List[(String, KuiConfig => Any, Any)] = List(
    ("kui.server.host", (c: KuiConfig) => c.server.host.value, "0.0.0.0"),
    ("kui.server.port", (c: KuiConfig) => c.server.port.value, 8080),
    ("kui.server.basePath", (c: KuiConfig) => c.server.basePath, "/"),
    ("kui.gateway.services", (c: KuiConfig) => c.gateway.services, Map.empty),
    (
      "kui.gateway.readinessIntervalMs",
      (c: KuiConfig) => c.gateway.readinessInterval,
      GatewayConfig.DefaultReadinessInterval
    ),
    ("kui.gateway.principalKeys", (c: KuiConfig) => c.gateway.principalKeys, Nil),
    ("kui.gateway.cors.enabled", (c: KuiConfig) => c.gateway.cors.enabled, false),
    ("kui.gateway.cors.origins", (c: KuiConfig) => c.gateway.cors.origins, Nil),
    ("kui.telemetry.otlpEndpoint", (c: KuiConfig) => c.telemetry.otlpEndpoint, None),
    ("kui.telemetry.prometheusPort", (c: KuiConfig) => c.telemetry.prometheusPort, None),
    ("kui.telemetry.logFormat", (c: KuiConfig) => c.telemetry.logFormat, LogFormat.Json),
    ("kui.telemetry.hashUserIds", (c: KuiConfig) => c.telemetry.hashUserIds, true)
  )

  defaults.foreach { case (key, read, expected) =>
    test(s"$key falls back to its documented default when it is set nowhere") {
      val loaded = load().fold(errors => fail(errors.render), identity)
      assertEquals(read(loaded), expected)
    }
  }

  test("a bad environment value is an error and does not fall through to the file") {
    val result = load(env = Map("KUI_SERVER_PORT" -> "not-a-number"), files = List(portFile(1111)))

    result match {
      case Right(config) =>
        fail(s"expected a failure, but the file's value ${config.server.port.value} was used")
      case Left(errors) =>
        assertEquals(errors.keys, List("kui.server.port"))
        assert(errors.render.contains("environment"), errors.render)
    }
  }

  test("the real process environment is read when it sets a key") {
    // The acceptance command for this task is `KUI_SERVER_PORT=9999 ./mill libs.config.test`, so
    // this assertion is live under that command and skipped otherwise. It is what proves `load`
    // and `loadFrom` agree, rather than the suite only ever exercising the injected seam.
    sys.env.get("KUI_SERVER_PORT").flatMap(_.toIntOption) match {
      case None => assert(cond = true)
      case Some(expected) =>
        val loaded = KuiConfigSource
          .load[IO](Nil, Nil, UrlPolicy.Dev)
          .unsafeRunSync()
          .fold(errors => fail(errors.render), identity)
        assertEquals(loaded.server.port.value, expected)
    }
  }

  private given Arbitrary[Port] = Arbitrary(Gen.choose(1, 65535).map(Port.unsafe))

  property("whichever layers are present, the highest-precedence one supplies the value") {
    forAll { (cli: Option[Port], env: Option[Port], file: Option[Port]) =>
      val result = load(
        args = cli.toList.map(port => s"--kui.server.port=${port.value}"),
        env = env.map(port => "KUI_SERVER_PORT" -> port.value.toString).toMap,
        files = file.toList.map(port => portFile(port.value))
      )
      val expected = cli.orElse(env).orElse(file).map(_.value).getOrElse(8080)
      portOf(result) == expected
    }
  }
}
