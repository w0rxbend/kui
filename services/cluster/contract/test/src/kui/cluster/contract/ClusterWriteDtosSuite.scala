package kui.cluster.contract

import io.circe.parser.parse
import io.circe.syntax.*
import munit.FunSuite

import kui.cluster.contract.dto.*
import kui.kernel.Secret

/** That a write carries what it must, prints what it must not, and never comes back.
  *
  * This is the only type in the cluster contract that holds a credential, so the two questions it has to
  * answer are: does a caller's password survive the round trip in, and does it stay out of everything that
  * is written down.
  */
final class ClusterWriteDtosSuite extends FunSuite {

  private val request = ClusterWriteRequest(
    name = "Production EU",
    readOnly = false,
    bootstrapServers = "broker-1.example.com:9093,broker-2.example.com:9093",
    security = ClusterSecurityWrite(
      protocol = "SASL_SSL",
      mechanism = Some("SCRAM-SHA-512"),
      username = Some("kui"),
      password = Some(Secret("hunter2")),
      truststore = Some(StoreMaterialWrite(Secret("MIIB..."), Some(Secret("truststore-secret")))),
      keystore = None,
      verifyHostname = true
    ),
    properties = Map("ssl.endpoint.identification.algorithm" -> "https"),
    admin = AdminTuningWrite(timeoutMs = 15000L, batchSize = 200, parallelism = 4)
  )

  test("the golden document is exactly what a caller sends") {
    assertNoDiff(
      request.asJson.spaces2,
      parse(GoldenDocuments.clusterWriteRequest).fold(failure => fail(failure.message), _.spaces2)
    )
  }

  test("a request round-trips, credentials included") {
    // The credentials have to survive the trip *in*: a write that dropped the password would register a
    // cluster KUI could not connect to, and the failure would surface half a minute later in a scrape.
    val decoded = parse(GoldenDocuments.clusterWriteRequest).flatMap(_.as[ClusterWriteRequest])

    assertEquals(decoded, Right(request))
    assertEquals(decoded.map(_.security.password.map(_.value)), Right(Some("hunter2")))
    assertEquals(decoded.map(_.security.truststore.map(_.base64.value)), Right(Some("MIIB...")))
  }

  test("a request with no properties decodes with an empty map") {
    // Absent and empty mean the same thing for an override layer, and a caller should not have to send
    // `"properties": {}` to say "no overrides".
    val minimal = parse(
      """{"name":"n","bootstrapServers":"h:9092",
         |"security":{"protocol":"PLAINTEXT","verifyHostname":true},
         |"admin":{"timeoutMs":1000,"batchSize":1,"parallelism":1}}""".stripMargin
    ).flatMap(_.as[ClusterWriteRequest])

    assertEquals(minimal.map(_.properties), Right(Map.empty[String, String]))
    assertEquals(minimal.map(_.readOnly), Right(false))
  }

  test("every credential redacts when printed") {
    // `toString` is what reaches a log line, an exception message and a debugger. This is the property
    // that makes "a request body never reaches a log line" true rather than hoped for.
    val printed = request.toString

    assert(!printed.contains("hunter2"), printed)
    assert(!printed.contains("truststore-secret"), printed)
    assert(!printed.contains("MIIB..."), printed)
    // The shape still prints, which is what makes a redacted line worth logging at all.
    assert(printed.contains("SASL_SSL"), printed)
  }

  test("the write reads back the internal profile, on the internal channel and nowhere else") {
    // In M1 this asserted that the response had no field a credential could travel back in. ADR-046
    // changed that: the write endpoint is on `/internal/v1`, it answers with the same credential-bearing
    // profile the read endpoint serves, and a read-back that dropped the credentials would be a
    // different document from the one a consumer fetches a moment later.
    //
    // What is asserted instead is the property that actually matters, and it is asserted on the *type*:
    // the field list is fixed here, so adding a field to the profile is a failing test in the file whose
    // subject is what may travel back.
    val profileFields = List(
      "id",
      "name",
      "version",
      "readOnly",
      "bootstrapServers",
      "security",
      "properties",
      "admin",
      "updatedAt"
    )

    val encoded = ClusterProfileDto(
      id = kui.kernel.ClusterId.unsafe("prod-eu"),
      name = "Production EU",
      version = 1L,
      readOnly = false,
      bootstrapServers = kui.kernel.cluster.BootstrapServers.unsafe("broker-1.example.com:9093"),
      security = kui.kernel.cluster.ClusterSecurity.Plaintext,
      properties = kui.kernel.cluster.ClientProperties.empty,
      admin = kui.kernel.cluster.AdminTuning.default,
      updatedAt = java.time.Instant.parse("2026-09-03T10:11:12Z")
    ).asJson

    assertEquals(encoded.asObject.map(_.keys.toList), Some(profileFields))

    // And the channel: `/internal/v1`, which is what makes carrying credentials on it legitimate.
    assert(
      ClusterWriteEndpoints.put.showPathTemplate().startsWith("/internal/v1/"),
      ClusterWriteEndpoints.put.showPathTemplate()
    )
  }
}
