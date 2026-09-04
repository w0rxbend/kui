package kui.config

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import kui.kernel.serde.SerdeName
import kui.testkit.KuiSuite

/** That `kui.clusters[].serde` means what the operator documentation says it means (SD-003).
  *
  * The section exists so that "this cluster is Avro" can be said once, in a file, instead of every reader of
  * every topic guessing. Every case below is a sentence an operator would write, and the failures are the
  * mistakes they would make.
  */
final class ClusterSerdeConfigSuite extends KuiSuite {

  private def load(yaml: String): Either[ConfigErrors, KuiConfig] =
    KuiConfigSource
      .loadFrom[IO](Nil, List(ConfigFixtures.yaml(yaml)), Map.empty, UrlPolicy.Dev)
      .unsafeRunSync()

  private def cluster(yaml: String): ClusterConfig =
    load(yaml).fold(errors => fail(errors.render), _.clusters.head)

  private def problems(yaml: String): List[ConfigProblem] =
    load(yaml) match {
      case Left(errors) => errors.problems.toList.sortBy(_.key)
      case Right(_) => fail("expected the load to fail, but it succeeded")
    }

  private def base(serde: String): String =
    s"""kui:
       |  auth:
       |    type: disabled
       |  clusters:
       |    - name: "Production"
       |      bootstrapServers:
       |        - "kafka:9092"
       |$serde""".stripMargin

  test("a cluster with no serde section keeps the behaviour it had before the section existed") {
    val configured = cluster(base(""))
    assertEquals(configured.serde, ClusterSerdeConfig.empty)
    assertEquals(configured.serde.defaultKey, None)
    assertEquals(configured.serde.defaultValue, None)
    assertEquals(configured.serde.patterns, Nil)
  }

  test("'this cluster is Avro' is one line") {
    val configured = cluster(base("""      serde:
                                    |        defaultValue: SchemaRegistry""".stripMargin))
    assertEquals(configured.serde.defaultValue, Some(SerdeName.SchemaRegistry))
    assertEquals(configured.serde.defaultKey, None)
  }

  test("patterns keep the order the operator wrote them, because the first match wins") {
    val configured = cluster(
      base("""      serde:
             |        patterns:
             |          - serde: SchemaRegistry
             |            topicValuesPattern: "orders\\..*"
             |          - serde: Json
             |            topicValuesPattern: ".*"
             |          - serde: String
             |            topicKeysPattern: ".*"
             |            topicValuesPattern: "logs\\..*"""".stripMargin)
    )

    assertEquals(
      configured.serde.patterns.map(_.serde.value),
      List("SchemaRegistry", "Json", "String")
    )
    assertEquals(configured.serde.patterns.head.topicKeysPattern, None)
    assertEquals(configured.serde.patterns(2).topicKeysPattern.map(_.regex), Some(".*"))
  }

  test("a pattern that names neither half of a record can never fire, and is refused") {
    val found = problems(
      base("""      serde:
             |        patterns:
             |          - serde: SchemaRegistry""".stripMargin)
    )
    assertEquals(found.map(_.key), List("kui.clusters.0.serde.patterns.0"))
    assert(found.head.problem.contains("can never select anything"), found.head.problem)
  }

  test("a serde name no KUI has is refused at load time, with the names that exist") {
    val found = problems(base("""      serde:
                                |        defaultValue: Avro""".stripMargin))
    assertEquals(found.map(_.key), List("kui.clusters.0.serde.defaultValue"))
    assert(found.head.problem.contains("SchemaRegistry"), found.head.problem)
  }

  test("a topic pattern that is not a regular expression is refused, naming the expression") {
    val found = problems(
      base("""      serde:
             |        patterns:
             |          - serde: Json
             |            topicValuesPattern: "orders(["""".stripMargin)
    )
    assertEquals(found.map(_.key), List("kui.clusters.0.serde.patterns.0.topicValuesPattern"))
  }

  test("the two cache knobs are bounded, and a value outside the bounds says what the bounds are") {
    val found = problems(
      base("""      serde:
             |        schemaCacheSize: 0
             |        subjectCacheTtl: 3h""".stripMargin)
    )
    assertEquals(
      found.map(_.key),
      List("kui.clusters.0.serde.schemaCacheSize", "kui.clusters.0.serde.subjectCacheTtl")
    )
  }

  test("the cache knobs default to the values the decoder was tuned for") {
    val configured = cluster(base(""))
    assertEquals(configured.serde.schemaCacheSize, ClusterSerdeConfig.DefaultSchemaCacheSize)
    assertEquals(configured.serde.subjectCacheTtl, ClusterSerdeConfig.DefaultSubjectCacheTtl)
  }

  test("the serde section and the schema registry section are read together") {
    val configured = cluster(
      base("""      schemaRegistry:
             |        url:
             |          - "http://schema-registry:8081"
             |      serde:
             |        defaultValue: SchemaRegistry
             |        schemaCacheSize: 25""".stripMargin)
    )
    assertEquals(configured.schemaRegistry.map(_.urls.head.value), Some("http://schema-registry:8081"))
    assertEquals(configured.serde.defaultValue, Some(SerdeName.SchemaRegistry))
    assertEquals(configured.serde.schemaCacheSize, 25L)
  }
}
