package kui.cluster.contract

import java.time.Instant

import io.circe.parser.parse
import io.circe.syntax.*
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import sttp.tapir.{EndpointIO, EndpointInput}

import kui.cluster.contract.dto.*
import kui.contracts.KuiEndpoint
import kui.contracts.cluster.ClusterSecurityDto
import kui.kernel.ClusterId

/** That the profile a service fetches carries no credential, and that its ETag is the store version.
  *
  * The redaction assertion here is the second of R-12's three: the contract suite (CLAPI-001) covers the
  * cluster DTOs, this one covers the profile a service-to-service caller receives, and the store's own suite
  * covers the raw topic record. Three layers, three separate assertions, because a secret that leaks does so
  * through whichever of them nobody wrote.
  */
final class ProfileEndpointsSuite extends ScalaCheckSuite {

  private val at = Instant.parse("2026-09-03T10:11:12Z")

  private val profile = ClusterProfileDto(
    id = ClusterId.unsafe("prod-eu"),
    name = "Production EU",
    version = 7L,
    readOnly = false,
    bootstrapServers = "broker-1.example.com:9093,broker-2.example.com:9093",
    security = ClusterSecurityDto("SASL_SSL", Some("SCRAM-SHA-512"), true, false),
    adminTimeoutMs = 15000L,
    adminBatchSize = 200,
    adminParallelism = 4,
    propertyKeys = List("sasl.jaas.config", "ssl.endpoint.identification.algorithm"),
    updatedAt = at
  )

  private val change = ClusterChangeDto(ClusterId.unsafe("prod-eu"), 8L, ClusterChangeDto.Updated, at)

  private def leaves(input: EndpointInput[?]): List[EndpointInput[?]] =
    input match {
      case EndpointInput.Pair(left, right, _, _) => leaves(left) ++ leaves(right)
      case EndpointIO.Pair(left, right, _, _) => leaves(left) ++ leaves(right)
      case EndpointInput.MappedPair(wrapped, _) => leaves(wrapped)
      case EndpointIO.MappedPair(wrapped, _) => leaves(wrapped)
      case leaf => List(leaf)
    }

  test("theProfileGoldenDocumentDecodes") {
    assertNoDiff(
      profile.asJson.spaces2,
      parse(GoldenDocuments.clusterProfile).fold(failure => fail(failure.message), _.spaces2)
    )
    assertEquals(parse(GoldenDocuments.clusterProfile).flatMap(_.as[ClusterProfileDto]), Right(profile))
    assertEquals(profile.asJson.as[ClusterProfileDto], Right(profile))
  }

  test("theChangeGoldenDocumentDecodes") {
    assertNoDiff(
      change.asJson.spaces2,
      parse(GoldenDocuments.clusterChange).fold(failure => fail(failure.message), _.spaces2)
    )
    assertEquals(parse(GoldenDocuments.clusterChange).flatMap(_.as[ClusterChangeDto]), Right(change))
    assertEquals(change.asJson.as[ClusterChangeDto], Right(change))
  }

  test("noSecretFieldExistsOnTheProfileDto") {
    // Built as if every credential a profile can hold were the same distinctive token. The DTO has nowhere
    // to put one, so the token can only reach the wire through a field that should not exist.
    val canary = "kui-secret-canary"
    val leaky = profile.copy(
      name = "Production EU",
      propertyKeys = List("sasl.jaas.config", "ssl.truststore.password")
    )
    val encoded = leaky.asJson.noSpaces

    assert(!encoded.contains(canary), encoded)
    // The keys survive, so an operator can see *that* a cluster overrides a property...
    assert(encoded.contains("sasl.jaas.config"), encoded)
    // ...and no *field* could carry what it was set to. The check is on field names, not on the whole
    // document, because "ssl.truststore.password" is a perfectly good thing for propertyKeys to contain -
    // the key is public, the value is not, and that distinction is the whole point of the field.
    val fieldNames = leaky.asJson.asObject.toList.flatMap(_.keys) ++
      leaky.security.asJson.asObject.toList.flatMap(_.keys)

    List("password", "keystore", "truststore", "username", "jaas", "secret", "credential").foreach(word =>
      assert(
        !fieldNames.exists(name => name.toLowerCase.contains(word) && !name.endsWith("Configured")),
        s"'$word' is a field name: $fieldNames"
      )
    )
  }

  test("theProfilePathIsUnderInternalV1AndCarriesTheSignedPrincipal") {
    val segments = leaves(ProfileEndpoints.profile.input).collect {
      case EndpointInput.FixedPath(segment, _, _) => segment
    }
    val headers = leaves(ProfileEndpoints.profile.securityInput).collect {
      case EndpointIO.Header(name, _, _) => name
    }

    assertEquals(segments, List("internal", "v1", "clusters", "profile"))
    assertEquals(headers, List(KuiEndpoint.PrincipalHeader))
    assertEquals(
      ProfileEndpoints.profile.showPathTemplate().takeWhile(_ != '?'),
      "/internal/v1/clusters/{clusterId}/profile"
    )
  }

  test("anUnknownChangeKindDecodes") {
    // A future "renamed" must reach a consumer as a word it can log and ignore, not as a decode failure
    // that costs it the frame - and with it, the version bump that would have made it re-fetch.
    val decoded = parse("""{"id":"prod-eu","version":9,"change":"renamed","at":"2026-09-03T10:11:12.000Z"}""")
      .flatMap(_.as[ClusterChangeDto])

    assertEquals(decoded.map(_.change), Right("renamed"))
  }

  property("theEtagIsTheVersion") {
    forAll(Gen.choose(0L, Long.MaxValue)) { version =>
      val versioned = profile.copy(version = version)

      assertEquals(ProfileResult.current(versioned).entityTag, s""""$version"""")
      assertEquals(ProfileResult.notModified(version).entityTag, s""""$version"""")
    }
  }

  property("a caller holding the current version is told so, whatever a proxy did to the quotes") {
    forAll(Gen.choose(0L, 1000000L)) { version =>
      val tag = ClusterProfileDto.etagOf(version)

      assert(ProfileResult.isCurrent(Some(tag), version))
      assert(ProfileResult.isCurrent(Some(version.toString), version))
      assert(ProfileResult.isCurrent(Some(s"W/$tag"), version))
      assert(!ProfileResult.isCurrent(Some(tag), version + 1))
      // A wildcard is a request for the profile regardless: on a read, "unconditional" is the only
      // reading of `*` that is useful, and answering 304 to it would leave a caller with nothing.
      assert(!ProfileResult.isCurrent(Some(ProfileEndpoints.AnyEtag), version))
      assert(!ProfileResult.isCurrent(None, version))
    }
  }

  test("the profile endpoint is not in the list the gateway turns into public routes") {
    // ADR-043: a service calls another service's contract directly, one hop. Publishing the profile at
    // /api/v1 as well would add a browser-reachable surface with no browser consumer.
    assert(!ClusterEndpoints.all.exists(_.info.name.contains("cluster.profile")))
    assertEquals(ProfileEndpoints.all.flatMap(_.info.name), List("cluster.profile"))
  }
}
