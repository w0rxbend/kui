package kui.schema.app

import cats.data.NonEmptyList
import cats.effect.IO

import kui.config.{ClusterConfig, RegistryAuthConfig, SafeUrl, SchemaRegistrySettings, UrlPolicy}
import kui.kernel.cluster.{AdminTuning, BootstrapServers, ClientProperties, ClusterSecurity}
import kui.kernel.{ClusterId, Secret}
import kui.testkit.KuiIOSuite
import kui.testkit.fakes.FakeStructuredLogger

/** The two numbers that decide how hard this service is allowed to push a Schema Registry.
  *
  * `services/schema/app` had a test module in `build.mill` and no test source at all until wave 5, so every
  * constant in the composition root could be changed with the whole service green. These two are the ones
  * with a consequence outside KUI: a Schema Registry is a single-writer JVM in front of a Kafka topic, not a
  * scalable cluster, and it is routinely the slowest component of a deployment. Sixteen concurrent requests
  * is more than any screen can generate and few enough that KUI cannot be the reason a registry falls over;
  * raising it is how KUI becomes that reason, and nothing else in this repository would notice.
  *
  * The numbers are written out rather than read from the object under test, for the reason
  * `SchemaRegistrationSuite` gives about the document size bound: a case that reads the constant it is
  * checking passes at every value of it.
  */
final class SchemaWiringSuite extends KuiIOSuite {

  test("one registry is asked for at most sixteen things at once") {
    assertEquals(
      SchemaWiring.MaxConcurrentPerRegistry.value,
      16,
      "the bulkhead in front of a single-writer registry moved without an ADR saying why"
    )
    // The subject list's fan-out is half of it, and deliberately not equal: the bulkhead is the limit for
    // everything KUI sends one registry, so a single list page that filled it would queue every other
    // screen's request behind itself. This is the one place in the build that can see both numbers.
    assertEquals(
      kui.schema.application.SubjectListUseCase.MaxConcurrentRows * 2,
      SchemaWiring.MaxConcurrentPerRegistry.value
    )
  }

  test("the bulkhead this service pins is the one the registry's upstream is built with") {
    // The case above asserts the number; nothing asserted that the number reaches anything. Replacing
    // `maxConcurrent = MaxConcurrentPerRegistry` with `PositiveInt.unsafe(512)` inside `upstreamConfig`
    // left all 154 cases of this service green, so the cap in front of a single-writer registry was a
    // constant with no consumer — which is the shape this suite was written to close for `MaxRetries`
    // and left open beside it.
    val registry = SchemaWiring.upstreamConfig(cluster, oauthSettings, UrlPolicy.Dev)
    val token = SchemaWiring.tokenUpstreamConfig(cluster, oauthSettings, issuer, UrlPolicy.Dev)

    assertEquals(registry.maxConcurrent.value, 16)
    // The token endpoint is capped too: it is a separate upstream, so its own bulkhead is the only
    // thing bounding how many client-credentials grants KUI has in flight at once.
    assertEquals(token.maxConcurrent.value, 16)
  }

  test("a refused connection is retried, because everything this service sends is idempotent") {
    // Setting a level to BACKWARD twice leaves it BACKWARD, and registering the same schema under the
    // same subject twice is idempotent by the registry's own definition — the second call answers the id
    // and version the first produced. So a retry can never apply something twice, and dropping it to
    // zero turns one refused connection during a rolling restart into a failed screen.
    assertEquals(SchemaWiring.MaxRetries, 2)
  }

  test("the token endpoint is its own upstream, and a registry address is never on it") {
    // The rule the file argues for at length: the registry's client fails over between the registry's
    // addresses, so a token request routed through it because the issuer was briefly slow would be a
    // client secret posted to a Schema Registry. Aimed at `settings.urls` instead, every case in this
    // service stayed green — which is what this one exists to stop.
    val settings = oauthSettings

    val token = SchemaWiring.tokenUpstreamConfig(cluster, settings, issuer, UrlPolicy.Dev)
    val registry = SchemaWiring.upstreamConfig(cluster, settings, UrlPolicy.Dev)

    assertEquals(token.urls.toList.map(_.value), List(issuer.value))
    // Stated as a set difference rather than as "the first URL is the issuer": a failover list whose
    // *second* entry is the registry sends the secret there on the first slow issuer, and a head-only
    // assertion would not see it.
    assertEquals(
      token.urls.toList.map(_.value).toSet.intersect(settings.urls.toList.map(_.value).toSet),
      Set.empty[String]
    )
    assertNotEquals(token.name, registry.name)
    assertEquals(registry.urls.toList.map(_.value), settings.urls.toList.map(_.value))
  }

  test("a token request is retried at most once, and a registry read twice") {
    // Both numbers in one place, because the reason they differ is the point: everything sent to the
    // registry is idempotent, and a token POST is only *nearly* so — a duplicate costs a spare token.
    val token = SchemaWiring.tokenUpstreamConfig(cluster, oauthSettings, issuer, UrlPolicy.Dev)

    assertEquals(token.maxRetries, 1)
    assertEquals(SchemaWiring.upstreamConfig(cluster, oauthSettings, UrlPolicy.Dev).maxRetries, 2)
  }

  test("a deployment that configures no registry says so at startup") {
    // "The 'no registry configured' line matters as much as the others" is this method's own docstring,
    // and it was true of nothing: the line could be deleted with 141 of 141 green. It is the sentence
    // that tells an operator who expected a Schemas tab that KUI is behaving as configured rather than
    // failing, and after the fact it is unanswerable unless the process said it.
    val logger = FakeStructuredLogger[IO]

    logger
      .flatMap(fake => SchemaWiring.startupLog[IO](List(bare), fake) *> fake.entries.map(fake -> _))
      .map { (_, entries) =>
        assertEquals(entries.map(_.level), List("info"))
        val line = entries.head.message
        assert(line.contains("schemaRegistry.url"), line)
        assert(line.contains("not configured"), line)
      }
  }

  test("a deployment with a registry logs which address that cluster reads, and not its secret") {
    val logger = FakeStructuredLogger[IO]

    logger
      .flatMap(fake => SchemaWiring.startupLog[IO](List(bare, configured), fake) *> fake.entries)
      .map { entries =>
        // One line per *configured* cluster and none for the bare one: the two branches are exclusive,
        // so a deployment that has any registry at all never prints the "none" sentence.
        assertEquals(entries.size, 1)
        val entry = entries.head
        assertEquals(entry.context.get("cluster.id"), Some("with-registry"))
        assertEquals(entry.context.get("schemaRegistry.urls"), Some("http://registry:8081"))
        assertEquals(entry.context.get("schemaRegistry.auth"), Some(oauthSettings.auth.describe))
        assert(!entry.message.contains("s3cr3t"), entry.message)
        assert(!entry.context.values.exists(_.contains("s3cr3t")), entry.context.toString)
      }
  }

  test("the service and its instrumentation are named the same thing everywhere") {
    // `service.name` on every log line, span and metric, and the scope the meter is registered under. A
    // dashboard that filters on one and a log search that filters on the other have to agree.
    assertEquals(SchemaWiring.Instrumentation, "kui.schema")
    assertEquals(kui.schema.api.SchemaApi.ServiceName, "kui-schema")
    assertEquals(kui.schema.api.SchemaApi.Id.value, "schema")
  }

  // -----------------------------------------------------------------------------------------------

  private val cluster: ClusterId = ClusterId.unsafe("with-registry")

  private val issuer: SafeUrl = SafeUrl.unsafe("http://issuer.internal:9000/oauth/token")

  private def oauthSettings: SchemaRegistrySettings =
    SchemaRegistrySettings(
      urls = NonEmptyList.of(
        SafeUrl.unsafe("http://registry:8081"),
        SafeUrl.unsafe("http://registry-2:8081")
      ),
      auth = RegistryAuthConfig.OAuth(issuer, "kui", Secret("s3cr3t"), None)
    )

  private def clusterConfig(id: String, registry: Option[SchemaRegistrySettings]): ClusterConfig =
    ClusterConfig(
      id = ClusterId.unsafe(id),
      name = id,
      bootstrapServers = BootstrapServers.unsafe("broker:9092"),
      security = ClusterSecurity.Plaintext,
      properties = ClientProperties.empty,
      readOnly = false,
      admin = AdminTuning.default,
      schemaRegistry = registry
    )

  private def bare: ClusterConfig = clusterConfig("bare", None)

  private def configured: ClusterConfig =
    clusterConfig(
      "with-registry",
      Some(
        SchemaRegistrySettings(
          urls = NonEmptyList.one(SafeUrl.unsafe("http://registry:8081")),
          auth = oauthSettings.auth
        )
      )
    )
}
