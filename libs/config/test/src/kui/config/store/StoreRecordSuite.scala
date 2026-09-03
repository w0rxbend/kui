package kui.config.store

import java.time.Instant

import io.circe.Json
import io.circe.syntax.*

import kui.kernel.Secret
import kui.testkit.KuiSuite

/** That the envelope on the wire is the one committed under `test/resources/store`, and that the two
  * compatibility rules behave differently on purpose.
  *
  * The golden files are the format's contract. A change to them is a change an operator's tooling and every
  * previously written record will see, so it has to be visible in a diff.
  */
final class StoreRecordSuite extends KuiSuite {

  private val clusterGolden: Json = StoreFixtures.golden("record-cluster-v1.json")
  private val tombstoneGolden: Json = StoreFixtures.golden("record-tombstone-v1.json")

  private def decoded(json: Json): StoreRecord =
    StoreRecord.fromJson(json).fold(e => fail(s"the golden file did not decode: ${e.message}"), identity)

  test("decodesTheCommittedGoldenFile") {
    val record = decoded(clusterGolden)
    assertEquals(record.envelopeVersion, 1)
    assertEquals(record.key, StoreKey(StoreSection.Cluster, "prod-eu"))
    assertEquals(record.version, 3L)
    assertEquals(record.updatedAt, Instant.parse("2026-09-03T10:15:30Z"))
    assertEquals(record.updatedBy, "kui-cluster/7f3a")
    assertEquals(record.deleted, false)
  }

  test("encodesToTheCommittedGoldenFile") {
    assertEquals(decoded(clusterGolden).asJson, clusterGolden)
    assertEquals(decoded(tombstoneGolden).asJson, tombstoneGolden)
  }

  test("tombstoneGoldenFileHasDeletedTrueAndAnEmptyPayload") {
    val record = decoded(tombstoneGolden)
    assertEquals(record.deleted, true)
    assertEquals(record.payload, Json.obj())
    assertEquals(StoreRecord.tombstone(record.key, record.version, record.updatedBy, record.updatedAt), record)
  }

  test("unknownEnvelopeVersionIsANamedError") {
    val bumped = clusterGolden.mapObject(_.add("envelopeVersion", Json.fromInt(99)))
    StoreRecord.fromJson(bumped) match {
      case Left(error @ StoreError.UnsupportedEnvelope(found, supported)) =>
        assertEquals(found, 99)
        assertEquals(supported, Set(1))
        assert(error.message.contains("99"), error.message)
        assert(error.message.contains("1"), error.message)
      case other => fail(s"expected UnsupportedEnvelope, got $other")
    }
  }

  test("unknownEnvelopeFieldIsIgnored") {
    val extended = clusterGolden.mapObject(_.add("futureField", Json.fromInt(1)))
    assertEquals(StoreRecord.fromJson(extended), StoreRecord.fromJson(clusterGolden))
  }

  test("aKeyDisagreeingWithTheRecordKeyIsMalformed") {
    assert(StoreRecord.fromJsonWithKey("cluster/prod-eu", clusterGolden).isRight)
    StoreRecord.fromJsonWithKey("cluster/somewhere-else", clusterGolden) match {
      case Left(StoreError.MalformedRecord(key, _)) => assertEquals(key, "cluster/somewhere-else")
      case other => fail(s"expected MalformedRecord, got $other")
    }
  }

  test("updatedAtIsSecondPrecision") {
    val record = StoreRecord.create(StoreKey.SettingsGlobal, Json.obj(), "kui-test/1", Instant.parse("2026-09-03T10:15:30.123456789Z"))
    assertEquals(record.updatedAt, Instant.parse("2026-09-03T10:15:30Z"))
    assertEquals(record.asJson.hcursor.get[String]("updatedAt"), Right("2026-09-03T10:15:30Z"))
  }

  test("payloadWithACipherNodeRoundTripsUntouched") {
    val record = decoded(clusterGolden)
    val password = record.payload.hcursor.downField("security").downField("password").focus
    assertEquals(password.flatMap(_.asObject).map(_.keys.toList), Some(List(SecretJson.CipherField)))
    assertEquals(StoreRecord.fromJson(record.asJson).map(_.payload), Right(record.payload))
  }

  test("secretMarkerRedactsInToString") {
    val marker = Json.obj(SecretJson.PlaintextField -> Json.fromString("hunter2"))
    val secret = SecretJson.decoder.decodeJson(marker).fold(f => fail(f.message), identity)
    assertEquals(secret.toString, Secret.Redacted)
    assertEquals(secret.value, "hunter2")
    assertEquals(SecretJson.encoder(secret), marker)
  }

  test("plaintextPathsFindsEveryMarker") {
    val marker = (value: String) => Json.obj(SecretJson.PlaintextField -> Json.fromString(value))
    val payload = Json.obj(
      "security" -> Json.obj("username" -> Json.fromString("kui"), "password" -> marker("p1")),
      "keystore" -> marker("p2"),
      "listeners" -> Json.arr(Json.obj("token" -> marker("p3")))
    )
    assertEquals(SecretJson.plaintextPaths(payload), List("security.password", "keystore", "listeners[0].token"))
    assertEquals(SecretJson.isFullyEncrypted(payload), false)
  }

  test("isFullyEncryptedIsFalseWhenOneMarkerRemains") {
    val encrypted = decoded(clusterGolden).payload
    assert(SecretJson.isFullyEncrypted(encrypted))
    val leaked = encrypted.mapObject(_.add("token", Json.obj(SecretJson.PlaintextField -> Json.fromString("x"))))
    assertEquals(SecretJson.isFullyEncrypted(leaked), false)
    assertEquals(SecretJson.plaintextPaths(leaked), List("token"))
  }
}
