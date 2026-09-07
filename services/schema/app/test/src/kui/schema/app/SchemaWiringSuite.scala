package kui.schema.app

import kui.testkit.KuiIOSuite

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

  test("a refused connection is retried, because everything this service sends is idempotent") {
    // Setting a level to BACKWARD twice leaves it BACKWARD, and registering the same schema under the
    // same subject twice is idempotent by the registry's own definition — the second call answers the id
    // and version the first produced. So a retry can never apply something twice, and dropping it to
    // zero turns one refused connection during a rolling restart into a failed screen.
    assertEquals(SchemaWiring.MaxRetries, 2)
  }

  test("the service and its instrumentation are named the same thing everywhere") {
    // `service.name` on every log line, span and metric, and the scope the meter is registered under. A
    // dashboard that filters on one and a log search that filters on the other have to agree.
    assertEquals(SchemaWiring.Instrumentation, "kui.schema")
    assertEquals(kui.schema.api.SchemaApi.ServiceName, "kui-schema")
    assertEquals(kui.schema.api.SchemaApi.Id.value, "schema")
  }
}
