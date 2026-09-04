package kui.config

import scala.concurrent.duration.DurationInt

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import kui.testkit.KuiSuite

/** `kui.consumers.*` and `kui.streaming.*`: the two sections that replaced two constants.
  *
  * Both existed as hard-coded values in a composition root, and both were wrong for the same reason — a
  * number or a secret that a deployment cannot change is a number or a secret that was chosen for somebody
  * else's cluster. What is asserted here is the part an operator can observe: the key is read, the bounds are
  * enforced, an absent section is the documented default rather than a failure, and a signing key too short
  * to be one stops the process instead of quietly weakening every token it signs.
  */
final class ConsumersAndStreamingSuite extends KuiSuite {

  private def load(yaml: String, env: Map[String, String]): Either[ConfigErrors, KuiConfig] =
    KuiConfigSource
      .loadFrom[IO](Nil, List(ConfigFixtures.yaml(yaml)), env, UrlPolicy.Dev)
      .unsafeRunSync()

  private def loaded(yaml: String, env: Map[String, String] = Map.empty): KuiConfig =
    load(yaml, env).fold(errors => fail(errors.render), identity)

  private def problems(yaml: String, env: Map[String, String] = Map.empty): List[ConfigProblem] =
    load(yaml, env) match {
      case Left(errors) => errors.problems.toList
      case Right(_) => fail("expected the load to fail, but it succeeded")
    }

  // -------------------------------------------------------------------------------------------
  // kui.consumers
  // -------------------------------------------------------------------------------------------

  test("anAbsentConsumersSectionIsTheDocumentedDefault") {
    assertEquals(loaded("kui:\n  server:\n    port: 8080\n").consumers, ConsumersConfig.Default)
    assertEquals(ConsumersConfig.Default.refreshInterval, 30.seconds)
  }

  test("theConsumerRefreshIntervalIsRead") {
    assertEquals(
      loaded("kui:\n  consumers:\n    refreshInterval: 90s\n").consumers.refreshInterval,
      90.seconds
    )
  }

  test("theConsumerRefreshIntervalIsSeparateFromTheTopicOne") {
    // The whole reason this section exists rather than reusing `kui.topics.refreshInterval`: describing
    // every group and describing every topic are different costs, and one knob for both would tune the
    // cheap scrape by the expensive one.
    val config = loaded(
      """kui:
        |  topics:
        |    refreshInterval: 300s
        |  consumers:
        |    refreshInterval: 10s
        |""".stripMargin
    )

    assertEquals(config.topics.refreshInterval, 300.seconds)
    assertEquals(config.consumers.refreshInterval, 10.seconds)
  }

  test("aConsumerRefreshIntervalOutsideTheBoundsIsRefusedAndNamesTheBounds") {
    val problem = problems("kui:\n  consumers:\n    refreshInterval: 1s\n").head
    assertEquals(problem.key, "kui.consumers.refreshInterval")
    assert(problem.problem.contains("5 seconds"), clue = problem.problem)
  }

  // -------------------------------------------------------------------------------------------
  // kui.streaming.cursorKey
  // -------------------------------------------------------------------------------------------

  private val LongEnough: String = "0123456789abcdef0123456789abcdef"

  test("noCursorKeyIsLegalAndMeansOnePerProcess") {
    assertEquals(loaded("kui:\n  server:\n    port: 8080\n").streaming.cursorKey, None)
  }

  test("aCursorKeyIsReadFromTheEnvironmentLikeEveryOtherSecret") {
    val config = loaded(
      "kui:\n  streaming:\n    cursorKey: env:KUI_CURSOR_KEY\n",
      env = Map("KUI_CURSOR_KEY" -> LongEnough)
    )

    assertEquals(config.streaming.cursorKey.map(_.value), Some(LongEnough))
  }

  test("aCursorKeyTooShortForHmacSha256StopsTheProcess") {
    // Silently accepting it would weaken every browse cursor and every reset plan token with nothing
    // anywhere looking wrong, which is the one failure mode a startup check is unarguably worth.
    val problem = problems(
      "kui:\n  streaming:\n    cursorKey: env:KUI_CURSOR_KEY\n",
      env = Map("KUI_CURSOR_KEY" -> "too-short")
    ).head

    assertEquals(problem.key, "kui.streaming.cursorKey")
    assert(problem.problem.contains("32"), clue = problem.problem)
  }

  test("aCursorKeyNamingAnUnsetVariableStopsTheProcessAndNamesTheVariable") {
    val problem = problems("kui:\n  streaming:\n    cursorKey: env:KUI_CURSOR_KEY\n").head
    assertEquals(problem.key, "kui.streaming.cursorKey")
    assert(problem.problem.contains("KUI_CURSOR_KEY"), clue = problem.problem)
  }

  test("aMisspeltKeyInEitherSectionIsRefusedRatherThanIgnored") {
    assertEquals(
      problems("kui:\n  consumers:\n    refreshIntervl: 90s\n").map(_.key),
      List("kui.consumers.refreshIntervl")
    )
  }
}
