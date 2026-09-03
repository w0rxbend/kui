package kui.config.store

import java.nio.charset.StandardCharsets
import java.util.Base64

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import org.scalacheck.{Arbitrary, Gen, Prop}

import kui.testkit.KuiSuite

/** That every secret in a store payload is encrypted before it can reach a topic, that it can be read
  * back, and that each of the ways this goes wrong in practice fails by name instead of quietly.
  *
  * The milestone's security exit criterion — a console-consumer dump of `__kui_config` containing no
  * plaintext password — is this suite plus STORE-009's dump against a real broker.
  */
final class FieldCryptoSuite extends KuiSuite {

  private val k1Material = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
  private val k2Material = "//79/Pv6+fj39vX08/Lx8O/u7ezr6uno5+bl5OPi4eA="
  private val impostorMaterial = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="

  private def key(id: String, material: String): EncryptionKey =
    EncryptionKey.fromBase64(id, material).fold(e => fail(e.message), identity)

  private def keyring(active: String, keys: (String, String)*): EncryptionKeyring =
    EncryptionKeyring
      .of(keys.toList.map((id, material) => key(id, material)), active)
      .fold(e => fail(e.message), identity)

  private val k1Ring = keyring("k1", "k1" -> k1Material)
  private val crypto = FieldCrypto[IO](k1Ring)

  private val recordKey = StoreKey.cluster("prod-eu").fold(e => fail(e.message), identity)
  private val otherKey = StoreKey.cluster("staging-us").fold(e => fail(e.message), identity)

  private def marker(value: String): Json =
    Json.obj(SecretJson.PlaintextField -> Json.fromString(value))

  private def payloadWith(password: String, truststore: String): Json =
    Json.obj(
      "displayName" -> Json.fromString("Production EU"),
      "security" -> Json.obj(
        "username" -> Json.fromString("kui"),
        "password" -> marker(password),
        "truststorePassword" -> marker(truststore)
      )
    )

  /** The alphabet that breaks naive implementations: quotes, backslashes, line breaks, `=`, and
    * characters outside the basic multilingual plane. It is the same alphabet KAFKA-002's JAAS property
    * test uses, because a password that breaks one usually breaks the other.
    */
  private val nastyStrings: Gen[String] = {
    val chars = Gen.oneOf(Gen.alphaNumChar, Gen.oneOf('"', '\\', '\n', '\r', '=', ' ', '\t', 'é', '☃'))
    Gen.oneOf(
      Gen.const(""),
      Gen.listOf(chars).map(_.mkString),
      Gen.const("😀 emoji beyond the BMP"),
      Gen.const("x" * 4096)
    )
  }

  private val arbitraryBytes: Gen[Array[Byte]] =
    Gen.choose(0, 8192).flatMap(n => Gen.listOfN(n, Arbitrary.arbitrary[Byte]).map(_.toArray))

  property("roundTripsArbitraryBytes") {
    val aad = FieldCrypto.aad(recordKey, "security.password")
    Prop.forAll(arbitraryBytes) { bytes =>
      val blob = crypto.encryptBytes(aad, bytes).unsafeRunSync()
      crypto.decryptBytes(aad, blob).unsafeRunSync().map(_.toList) == Right(bytes.toList)
    }
  }

  property("roundTripsArbitraryStrings") {
    Prop.forAll(nastyStrings) { plaintext =>
      val payload = Json.obj("password" -> marker(plaintext))
      val encrypted = crypto.encryptPayload(recordKey, payload).unsafeRunSync()
      crypto.decryptPayload(recordKey, encrypted).unsafeRunSync() == Right(payload)
    }
  }

  test("everyEncryptionUsesAFreshIv") {
    // The single worst mistake an AES-GCM implementation can make: reusing an IV under one key does not
    // weaken GCM, it destroys it. One hundred encryptions of one plaintext must give one hundred IVs.
    val aad = FieldCrypto.aad(recordKey, "security.password")
    val ivs =
      List.fill(100)(crypto.encryptBytes(aad, "hunter2".getBytes(StandardCharsets.UTF_8)).unsafeRunSync().iv)
    assertEquals(ivs.distinct.size, 100)
  }

  test("payloadWithNoMarkerIsUnchanged") {
    val payload = Json.obj("displayName" -> Json.fromString("Production EU"), "brokers" -> Json.arr(Json.fromInt(1)))
    assertEquals(crypto.encryptPayload(recordKey, payload).unsafeRunSync(), payload)
    assertEquals(crypto.decryptPayload(recordKey, payload).unsafeRunSync(), Right(payload))
  }

  property("encryptPayloadLeavesNoPlaintextMarker") {
    Prop.forAll(nastyStrings, nastyStrings) { (password, truststore) =>
      val encrypted = crypto.encryptPayload(recordKey, payloadWith(password, truststore)).unsafeRunSync()
      SecretJson.isFullyEncrypted(encrypted) && !encrypted.noSpaces.contains(SecretJson.PlaintextField)
    }
  }

  property("decryptPayloadIsTheInverse") {
    Prop.forAll(nastyStrings, nastyStrings) { (password, truststore) =>
      val payload = payloadWith(password, truststore)
      val encrypted = crypto.encryptPayload(recordKey, payload).unsafeRunSync()
      crypto.decryptPayload(recordKey, encrypted).unsafeRunSync() == Right(payload)
    }
  }

  test("wrongKeyProducesANamedErrorAndNoPlaintext") {
    val encrypted = crypto.encryptPayload(recordKey, payloadWith("hunter2", "trustme")).unsafeRunSync()
    // The same key *id*, different material: exactly what happens when two deployments both call their
    // key "k1" and point at one topic.
    val impostor = FieldCrypto[IO](keyring("k1", "k1" -> impostorMaterial))
    impostor.decryptPayload(recordKey, encrypted).unsafeRunSync() match {
      case Left(StoreError.DecryptionFailed(keyId, where)) =>
        assertEquals(keyId, "k1")
        assertEquals(where, "security.password")
      case other => fail(s"expected DecryptionFailed, got $other")
    }
  }

  test("unknownKeyIdIsANamedError") {
    val encrypted = crypto.encryptPayload(recordKey, payloadWith("hunter2", "trustme")).unsafeRunSync()
    val renamed = encrypted.hcursor
      .downField("security")
      .downField("password")
      .downField(SecretJson.CipherField)
      .downField("keyId")
      .set(Json.fromString("gone"))
      .top
      .getOrElse(fail("could not rewrite the keyId"))
    assertEquals(
      crypto.decryptPayload(recordKey, renamed).unsafeRunSync(),
      Left(StoreError.UnknownKeyId("gone", Set("k1")))
    )
  }

  test("tamperedCiphertextFailsAuthentication") {
    val blob = crypto
      .encryptBytes(FieldCrypto.aad(recordKey, "security.password"), "hunter2".getBytes(StandardCharsets.UTF_8))
      .unsafeRunSync()
    val bytes = Base64.getDecoder.decode(blob.ct)
    bytes(0) = (bytes(0) ^ 0x01).toByte
    val tampered = blob.copy(ct = Base64.getEncoder.encodeToString(bytes))
    assertEquals(
      crypto.decryptBytes(FieldCrypto.aad(recordKey, "security.password"), tampered).unsafeRunSync(),
      Left(StoreError.DecryptionFailed("k1", ""))
    )
  }

  test("aadBindsTheFieldPath") {
    val encrypted = crypto.encryptPayload(recordKey, payloadWith("hunter2", "trustme")).unsafeRunSync()
    val password = encrypted.hcursor.downField("security").downField("password").focus.getOrElse(Json.Null)
    val spliced = encrypted.hcursor
      .downField("security")
      .downField("truststorePassword")
      .set(password)
      .top
      .getOrElse(fail("could not splice the field"))
    assertEquals(
      crypto.decryptPayload(recordKey, spliced).unsafeRunSync(),
      Left(StoreError.DecryptionFailed("k1", "security.truststorePassword"))
    )
  }

  test("aadBindsTheRecordKey") {
    val encrypted = crypto.encryptPayload(recordKey, payloadWith("hunter2", "trustme")).unsafeRunSync()
    assertEquals(
      crypto.decryptPayload(otherKey, encrypted).unsafeRunSync(),
      Left(StoreError.DecryptionFailed("k1", "security.password"))
    )
  }

  test("rotationReadsOldRecords") {
    // `docs/operations/metadata-store.md` §4.2's rotation procedure, as a test.
    val encrypted = crypto.encryptPayload(recordKey, payloadWith("hunter2", "trustme")).unsafeRunSync()
    val rotated = FieldCrypto[IO](keyring("k2", "k1" -> k1Material, "k2" -> k2Material))
    assertEquals(
      rotated.decryptPayload(recordKey, encrypted).unsafeRunSync(),
      Right(payloadWith("hunter2", "trustme"))
    )
    // Rewritten under k2, and then k1 is dropped: still readable.
    val reencrypted = rotated.encryptPayload(recordKey, payloadWith("hunter2", "trustme")).unsafeRunSync()
    val k2Only = FieldCrypto[IO](keyring("k2", "k2" -> k2Material))
    assertEquals(k2Only.decryptPayload(recordKey, reencrypted).unsafeRunSync(), Right(payloadWith("hunter2", "trustme")))
    // But a record still written under k1 is now unreadable, by name rather than by silence.
    assertEquals(
      k2Only.decryptPayload(recordKey, encrypted).unsafeRunSync(),
      Left(StoreError.UnknownKeyId("k1", Set("k2")))
    )
  }

  test("theCommittedEncryptedGoldenFileHoldsNoPlaintextAndStillDecrypts") {
    val golden = StoreFixtures.golden("record-cluster-encrypted-k1.json")
    assert(!golden.noSpaces.contains(SecretJson.PlaintextField), "the golden file leaks a plaintext marker")
    assert(!golden.noSpaces.contains("canary"), "the golden file leaks a plaintext secret")
    val record = StoreRecord.fromJson(golden).fold(e => fail(e.message), identity)
    val decrypted = crypto.decryptPayload(record.key, record.payload).unsafeRunSync()
    assertEquals(
      decrypted.map(json => SecretJson.plaintextPaths(json)),
      Right(List("security.password", "security.truststorePassword"))
    )
  }

  property("nothingLeaksThroughToString") {
    Prop.forAll(nastyStrings) { plaintext =>
      val payload = Json.obj("password" -> marker(plaintext))
      val encrypted = crypto.encryptPayload(recordKey, payload).unsafeRunSync()
      val blob = encrypted.hcursor
        .downField("password")
        .get[CipherBlob](SecretJson.CipherField)
        .fold(f => fail(f.message), identity)
      val rendered = List(k1Ring.toString, k1Ring.active.toString, blob.toString).mkString(" ")
      // Short plaintexts are excluded: a two-character string turns up inside base64 by coincidence, and
      // a test that fails on coincidence teaches people to rerun it rather than to read it.
      !rendered.contains(k1Material) && (plaintext.length < 8 || !rendered.contains(plaintext))
    }
  }
}
