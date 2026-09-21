package kui.config

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import kui.kernel.Secret
import kui.testkit.KuiSuite

final class UpstreamAuthConfigSuite extends KuiSuite {

  private def load(yaml: String): Either[ConfigErrors, KuiConfig] =
    KuiConfigSource
      .loadFrom[IO](Nil, List(ConfigFixtures.yaml(yaml)), Map.empty, UrlPolicy.Dev)
      .unsafeRunSync()

  private def legacyCluster(section: String): String =
    s"""kui:
       |  clusters:
       |    - name: local
       |      bootstrapServers: kafka:9092
       |$section""".stripMargin

  test("bearer material stays redacted in the shared authentication value") {
    val auth = UpstreamAuthConfig.Bearer(Secret("bearer-canary"))

    assertEquals(auth.describe, "bearer token")
    assert(!auth.toString.contains("bearer-canary"), auth.toString)
    assert(!auth.describe.contains("bearer-canary"), auth.describe)
  }

  test("Connect and ksqlDB reject bearer without disclosing the token") {
    val documents = List(
      legacyCluster("""      connect:
                      |        - url: http://connect:8083
                      |          auth:
                      |            type: bearer
                      |            token: connect-token-canary
                      |""".stripMargin),
      legacyCluster("""      ksql:
                      |        url: http://ksql:8088
                      |        auth:
                      |          type: bearer
                      |          token: ksql-token-canary
                      |""".stripMargin)
    )

    documents.foreach { yaml =>
      val rendered = load(yaml) match {
        case Left(errors) => errors.render
        case Right(_) => fail("a legacy upstream accepted bearer authentication")
      }
      assert(rendered.contains("bearer"), rendered)
      assert(rendered.contains("not supported"), rendered)
      assert(!rendered.contains("token-canary"), rendered)
    }
  }
}
