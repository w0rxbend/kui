package kui.config

import scala.concurrent.duration.DurationInt

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import kui.testkit.KuiSuite

/** That `kui.clusters[].connect` and `kui.clusters[].ksql` mean what an operator would expect, and that a
  * file which does not mention either loads exactly as it did before they existed.
  *
  * These two sections are here before the services that read them (M9), which makes one case below the most
  * important one in the file: `kui.clusters.0.ksql.url` used to be a *failed boot*, because the unknown-key
  * check refuses anything it does not recognise. Every deployment that had written its ksqlDB address in
  * advance was refused with "is not a KUI configuration key", and the fix could not be local to the service
  * that eventually needed it.
  */
final class ClusterEcosystemSuite extends KuiSuite {

  private def load(
      yaml: String,
      env: Map[String, String] = Map.empty
  ): Either[ConfigErrors, KuiConfig] =
    KuiConfigSource
      .loadFrom[IO](Nil, List(ConfigFixtures.yaml(yaml)), env, UrlPolicy.Dev)
      .unsafeRunSync()

  private def cluster(yaml: String): ClusterConfig =
    load(yaml).fold(errors => fail(errors.render), _.clusters.head)

  private def problems(yaml: String, env: Map[String, String] = Map.empty): List[ConfigProblem] =
    load(yaml, env) match {
      case Left(errors) => errors.problems.toList.sortBy(_.key)
      case Right(_) => fail("expected the load to fail, but it succeeded")
    }

  private def base(section: String): String =
    s"""kui:
       |  clusters:
       |    - name: "Production"
       |      bootstrapServers:
       |        - "kafka:9092"
       |$section""".stripMargin

  test("a cluster that mentions neither section has no Connect cluster and no ksqlDB") {
    val configured = cluster(base(""))
    assertEquals(configured.connect, Nil)
    assertEquals(configured.ksql, None)
  }

  test("one Connect address is one Connect cluster, and it does not have to be named") {
    val configured = cluster(base("""      connect:
                                    |        - url: "http://connect:8083"""".stripMargin))

    assertEquals(configured.connect.size, 1)
    val connect = configured.connect.head
    assertEquals(connect.urls.toList.map(_.value), List("http://connect:8083"))
    assertEquals(connect.name.value, "connect-0")
    assertEquals(connect.auth, UpstreamAuthConfig.Anonymous)
    assertEquals(connect.callTimeout, ConnectClusterSettings.DefaultCallTimeout)
  }

  test("the source cluster and the sink cluster are two entries, each with its own name and credentials") {
    val configured = cluster(
      base("""      connect:
             |        - name: sources
             |          url: "http://connect-a:8083,http://connect-a2:8083"
             |          callTimeout: 20s
             |        - name: sinks
             |          url: "http://connect-b:8083"
             |          auth:
             |            type: basic
             |            username: kui
             |            password: a-connect-password""".stripMargin)
    )

    assertEquals(configured.connect.map(_.name.value), List("sources", "sinks"))
    assertEquals(configured.connect.head.urls.toList.size, 2)
    assertEquals(configured.connect.head.callTimeout, 20.seconds)
    configured.connect(1).auth match {
      case UpstreamAuthConfig.Basic(username, password) =>
        assertEquals(username, "kui")
        assertEquals(password.value, "a-connect-password")
      case other => fail(s"the second Connect cluster is not using basic credentials: $other")
    }
  }

  test("two Connect clusters with one name are refused, because a connector is addressed by that name") {
    val found = problems(
      base("""      connect:
             |        - name: etl
             |          url: "http://connect-a:8083"
             |        - name: etl
             |          url: "http://connect-b:8083"""".stripMargin)
    )

    assertEquals(found.map(_.key), List("kui.clusters.0.connect.0.name"))
    assert(found.head.problem.contains("unreachable"), found.head.problem)
  }

  test("a gap in the Connect list is refused rather than renumbered, because the index is a default name") {
    // Spelled in the environment because that is the layer a gap can actually be written in — a YAML
    // sequence has no holes, and the mistake this rule catches is a mistyped `KUI_CLUSTERS_0_CONNECT_1_*`
    // beside a `..._0_*` that was deleted.
    val found = problems(
      base(""),
      env = Map("KUI_CLUSTERS_0_CONNECT_1_URL" -> "http://connect-b:8083")
    )

    assertEquals(found.map(_.key), List("kui.clusters.0.connect.1"))
    assert(found.head.problem.contains("numbered from 0"), found.head.problem)
  }

  test("a Connect entry with credentials for two mechanisms is refused, naming the surplus keys") {
    val found = problems(
      base("""      connect:
             |        - url: "http://connect:8083"
             |          auth:
             |            type: basic
             |            username: kui
             |            password: a-connect-password
             |            clientId: kui-connect""".stripMargin)
    )

    assertEquals(found.map(_.key), List("kui.clusters.0.connect.0.auth.type"))
    assert(found.head.problem.contains("Kafka Connect cluster"), found.head.problem)
    assert(found.head.problem.contains("never both"), found.head.problem)
  }

  test("a ksqlDB address is the whole section, and the two budgets take their defaults") {
    val configured = cluster(base("""      ksql:
                                    |        url: "http://ksqldb:8088"""".stripMargin))

    val ksql = configured.ksql.getOrElse(fail("kui.clusters.0.ksql.url did not produce a ksqlDB section"))
    assertEquals(ksql.urls.toList.map(_.value), List("http://ksqldb:8088"))
    assertEquals(ksql.callTimeout, KsqlSettings.DefaultCallTimeout)
    assertEquals(ksql.streamTimeout, KsqlSettings.DefaultStreamTimeout)
    assertEquals(ksql.auth, UpstreamAuthConfig.Anonymous)
  }

  test("a push query's budget below an ordinary call's is refused, naming both keys") {
    val found = problems(
      base("""      ksql:
             |        url: "http://ksqldb:8088"
             |        callTimeout: 30s
             |        streamTimeout: 10s""".stripMargin)
    )

    assertEquals(found.map(_.key), List("kui.clusters.0.ksql.streamTimeout"))
    assert(found.head.problem.contains("kui.clusters.0.ksql.callTimeout"), found.head.problem)
  }

  test("an unknown key under ksql still fails startup, naming the key") {
    val found = problems(base("""      ksql:
                                |        url: "http://ksqldb:8088"
                                |        nonsense: 3""".stripMargin))

    assertEquals(found.map(_.key), List("kui.clusters.0.ksql.nonsense"))
    assert(found.head.problem.contains("not a KUI configuration key"), found.head.problem)
  }

  test("the ksqlDB credential is resolved through env: like every other per-cluster secret") {
    val configured = KuiConfigSource
      .loadFrom[IO](
        Nil,
        List(
          ConfigFixtures.yaml(
            base("""      ksql:
                   |        url: "http://ksqldb:8088"
                   |        auth:
                   |          type: basic
                   |          username: kui
                   |          password: "env:KUI_KSQL_PASSWORD"""".stripMargin)
          )
        ),
        Map("KUI_KSQL_PASSWORD" -> "a-ksql-password"),
        UrlPolicy.Dev
      )
      .unsafeRunSync()
      .fold(errors => fail(errors.render), _.clusters.head)

    configured.ksql.map(_.auth) match {
      case Some(UpstreamAuthConfig.Basic(_, password)) => assertEquals(password.value, "a-ksql-password")
      case other => fail(s"the ksqlDB credential was not resolved: $other")
    }
  }

  test("neither section prints a credential when the cluster is rendered for a log line") {
    val configured = cluster(
      base("""      connect:
             |        - url: "http://connect:8083"
             |          auth:
             |            type: basic
             |            username: kui
             |            password: a-connect-password
             |      ksql:
             |        url: "http://ksqldb:8088"
             |        auth:
             |          type: basic
             |          username: kui
             |          password: a-ksql-password""".stripMargin)
    )

    val rendered = configured.toString
    assert(!rendered.contains("a-connect-password"), rendered)
    assert(!rendered.contains("a-ksql-password"), rendered)
    assert(rendered.contains("http://connect:8083"), rendered)
    assert(rendered.contains("http://ksqldb:8088"), rendered)
  }

  test("an address this deployment may not call is refused by the same URL rule as every other upstream") {
    val found = KuiConfigSource
      .loadFrom[IO](
        Nil,
        List(ConfigFixtures.yaml(base("""      ksql:
                                        |        url: "http://169.254.169.254/"""".stripMargin))),
        Map.empty,
        UrlPolicy.Strict
      )
      .unsafeRunSync() match {
      case Left(errors) => errors.problems.toList
      case Right(_) => fail("a link-local ksqlDB address was accepted under the strict policy")
    }

    assertEquals(found.map(_.key), List("kui.clusters.0.ksql.url"))
  }
}
