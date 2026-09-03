package kui.config

import java.nio.file.Path

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import kui.testkit.KuiSuite

/** That a bad configuration fails at startup, says everything that is wrong, and never prints a
  * secret while doing it.
  *
  * The alternative — the one every operator has lived through — is a process that starts, ignores
  * the key that was mistyped, and reveals the problem at three in the morning as a null pointer.
  */
final class ValidationSuite extends KuiSuite {

  private def load(
      files: List[Path],
      env: Map[String, String] = Map.empty,
      policy: UrlPolicy = UrlPolicy.Dev
  ): Either[ConfigErrors, KuiConfig] =
    KuiConfigSource.loadFrom[IO](Nil, files, env, policy).unsafeRunSync()

  private def problems(result: Either[ConfigErrors, KuiConfig]): List[ConfigProblem] =
    result match {
      case Left(errors) => errors.problems.toList
      case Right(_) => fail("expected the load to fail, but it succeeded")
    }

  test("the complete valid fixture loads, and every key in it reaches the model") {
    val loaded =
      load(List(ConfigFixtures.fixture("valid.yaml"))).fold(errors => fail(errors.render), identity)

    assertEquals(loaded.server.port.value, 9090)
    assertEquals(loaded.server.basePath, "/kui")
    assertEquals(loaded.gateway.readinessInterval.toMillis, 5000L)
    assertEquals(loaded.gateway.services.keySet.map(_.value), Set("cluster", "topic"))
    assertEquals(loaded.gateway.services.head._2.maxConcurrent.value, 8)
    assertEquals(loaded.gateway.cors, CorsConfig(enabled = true, List("https://console.example.com")))
    assertEquals(loaded.gateway.principalKeys.map(_.kid), List("k1"))
    assertEquals(loaded.telemetry.logFormat, LogFormat.Text)
    assertEquals(loaded.telemetry.prometheusPort.map(_.value), Some(9464))
    assertEquals(loaded.telemetry.hashUserIds, false)
  }

  test("reportsEveryProblemNotJustTheFirst") {
    val found = problems(load(List(ConfigFixtures.fixture("multiple-errors.yaml")), policy = UrlPolicy.Strict))

    assertEquals(
      found.map(_.key).sorted,
      List("kui.gateway.services.cluster.url", "kui.server.port", "kui.telemetry.logFormat")
    )
  }

  test("rejectsUnknownKeys") {
    val found = problems(load(List(ConfigFixtures.fixture("unknown-key.yaml"))))

    assertEquals(found.map(_.key), List("kui.server.prot"))
    assert(found.head.problem.contains("not a KUI configuration key"), found.head.problem)
    // A typo-suggestion feature is explicitly out of scope: naming the key is the whole promise.
    assert(!found.head.problem.contains("did you mean"), found.head.problem)
  }

  test("anEmptyListIsAcceptedWhereItsElementsAreKnown") {
    // The walk that finds unknown keys stops at an empty container, so `origins: []` arrives as
    // the path `kui.gateway.cors.origins` rather than as any element under it. Declaring only
    // `origins.*` therefore rejected an empty allow-list -- which is a legitimate setting, and the
    // one the shipped Compose file uses, so the gateway refused to start with its own defaults.
    val file = ConfigFixtures.yaml(
      """kui:
        |  gateway:
        |    cors:
        |      enabled: false
        |      origins: []
        |""".stripMargin
    )

    val loaded = load(List(file)).fold(errors => fail(errors.render), identity)

    assertEquals(loaded.gateway.cors, CorsConfig(enabled = false, Nil))
  }

  test("the placeholder sections for M1 and M6 are accepted but read no keys") {
    val file = ConfigFixtures.yaml(
      """kui:
        |  clusters:
        |    - name: prod
        |      bootstrapServers: "kafka:9092"
        |  rbac:
        |    roles: []
        |""".stripMargin
    )

    assert(load(List(file)).isRight, load(List(file)).left.map(_.render).merge.toString)
  }

  test("secretValuesAreRedactedInProblemMessages") {
    val file = ConfigFixtures.yaml(
      """kui:
        |  gateway:
        |    principalKeys:
        |      - kid: "k1"
        |        key: ""
        |""".stripMargin
    )

    val found = problems(load(List(file)))
    val rendered = found.map(_.render).mkString("\n")

    assertEquals(found.map(_.key), List("kui.gateway.principalKeys.0.key"))
    assert(rendered.contains("***"), rendered)
  }

  test("a file: secret reference that cannot be read never prints the file's contents") {
    val secretFile = ConfigFixtures.yaml("hunter2-DO-NOT-LEAK")
    val file = ConfigFixtures.yaml(
      s"""kui:
         |  gateway:
         |    principalKeys:
         |      - kid: "k1"
         |        key: "file:${secretFile.toString}/not-a-directory"
         |""".stripMargin
    )

    val rendered = problems(load(List(file))).map(_.render).mkString("\n")

    assert(!rendered.contains("hunter2-DO-NOT-LEAK"), rendered)
    assert(rendered.contains("could not be read"), rendered)
  }

  test("a file: secret reference that can be read produces the secret") {
    val secretFile = ConfigFixtures.yaml("hunter2-DO-NOT-LEAK")
    val file = ConfigFixtures.yaml(
      s"""kui:
         |  gateway:
         |    principalKeys:
         |      - kid: "k1"
         |        key: "file:${secretFile.toString}"
         |""".stripMargin
    )

    val loaded = load(List(file)).fold(errors => fail(errors.render), identity)
    assertEquals(loaded.gateway.principalKeys.head.key.value, "hunter2-DO-NOT-LEAK")
    assertEquals(loaded.gateway.principalKeys.head.key.toString, "Secret(***)")
  }

  test("an env: secret reference reads the environment") {
    val file = ConfigFixtures.yaml(
      """kui:
        |  gateway:
        |    principalKeys:
        |      - kid: "k1"
        |        key: "env:KUI_SIGNING_KEY"
        |""".stripMargin
    )

    val loaded = load(List(file), env = Map("KUI_SIGNING_KEY" -> "from-the-environment"))
      .fold(errors => fail(errors.render), identity)

    assertEquals(loaded.gateway.principalKeys.head.key.value, "from-the-environment")
  }

  test("authTypeOtherThanDisabledIsRejectedInM0") {
    val file = ConfigFixtures.yaml("kui:\n  auth:\n    type: oauth2\n")
    val found = problems(load(List(file)))

    assertEquals(found.map(_.key), List("kui.auth.type"))
    assert(found.head.problem.contains("M6"), found.head.problem)
  }

  test("a cors origin list containing '*' is refused at load time") {
    val file = ConfigFixtures.yaml(
      """kui:
        |  gateway:
        |    cors:
        |      enabled: true
        |      origins: ["*"]
        |""".stripMargin
    )

    val found = problems(load(List(file)))
    assertEquals(found.map(_.key), List("kui.gateway.cors.origins"))
  }

  test("an upstream service without a url is a named, required-key problem") {
    val file = ConfigFixtures.yaml(
      "kui:\n  gateway:\n    services:\n      cluster:\n        timeout: \"3s\"\n"
    )

    val found = problems(load(List(file)))
    assertEquals(found.map(_.key), List("kui.gateway.services.cluster.url"))
    assert(found.head.problem.contains("is required"), found.head.problem)
  }

  test("every problem names its source, so the operator knows which layer to edit") {
    val file = ConfigFixtures.yaml("kui:\n  server:\n    port: 0\n")

    val fromFile = problems(load(List(file))).head
    assert(fromFile.source.isInstanceOf[ConfigSourceName.File], fromFile.render)

    val fromEnv = problems(load(Nil, env = Map("KUI_SERVER_PORT" -> "0"))).head
    assertEquals(fromEnv.source, ConfigSourceName.Env)
  }

  test("a YAML file that does not parse fails the load with the file named") {
    val file = ConfigFixtures.yaml("kui:\n  server:\n  port: [unclosed\n")
    val found = problems(load(List(file)))

    assert(found.exists(_.source.isInstanceOf[ConfigSourceName.File]), found.map(_.render).mkString)
  }

  test("the rendered report is one line per problem, in key order") {
    val rendered = load(List(ConfigFixtures.fixture("multiple-errors.yaml")), policy = UrlPolicy.Strict)
      .swap
      .map(_.render)
      .getOrElse(fail("expected a failure"))

    val lines = rendered.linesIterator.toList
    assertEquals(lines.size, 3)
    assertEquals(lines.map(_.takeWhile(_ != ':')), lines.map(_.takeWhile(_ != ':')).sorted)
  }
}
