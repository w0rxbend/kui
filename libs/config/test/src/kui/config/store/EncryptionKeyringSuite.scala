package kui.config.store

import kui.testkit.KuiSuite

/** That bad key material is refused with a message an operator can act on, and that no such message ever
  * quotes the material it is complaining about.
  *
  * The second half matters more than it looks. A "expected 32 bytes, got 'AAEC…'" message is how a key
  * ends up in a log aggregator that a hundred people can read.
  */
final class EncryptionKeyringSuite extends KuiSuite {

  private val validBase64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="

  private def why(result: Either[StoreError, ?]): String =
    result match {
      case Left(error) => error.message
      case Right(value) => fail(s"expected a rejection, got $value")
    }

  test("rejectsWrongKeyLength") {
    val sixteen = "AAECAwQFBgcICQoLDA0ODw=="
    val thirtyOne = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHg=="
    List(sixteen, thirtyOne).foreach { material =>
      val message = why(EncryptionKey.fromBase64("k1", material))
      assert(message.contains("32 bytes"), message)
      assert(!message.contains(material), message)
    }
  }

  test("rejectsBadBase64") {
    val message = why(EncryptionKey.fromBase64("k1", "not-base-64-!!!"))
    assert(message.contains("not valid base64"), message)
    assert(!message.contains("not-base-64-!!!"), message)
  }

  test("rejectsBadKeyId") {
    List("K1", "", "a" * 40, "k 1").foreach { id =>
      assert(EncryptionKey.fromBase64(id, validBase64).isLeft, s"expected the id '$id' to be rejected")
    }
  }

  test("rejectsActiveKeyIdNotPresent") {
    val k1 = EncryptionKey.fromBase64("k1", validBase64).fold(e => fail(e.message), identity)
    val message = why(EncryptionKeyring.of(List(k1), "k2"))
    assert(message.contains("k1"), message)
    assert(message.contains("k2"), message)
  }

  test("rejectsEmptyKeyring") {
    val message = why(EncryptionKeyring.of(Nil, "k1"))
    assert(message.contains("kui.store.encryptionKey"), message)
  }

  test("nothingLeaksThroughToString") {
    val k1 = EncryptionKey.fromBase64("k1", validBase64).fold(e => fail(e.message), identity)
    val keyring = EncryptionKeyring.of(List(k1), "k1").fold(e => fail(e.message), identity)
    assertEquals(k1.toString, "EncryptionKey(k1)")
    assertEquals(keyring.toString, "EncryptionKeyring(active=k1, 1 keys: k1)")
    assert(!keyring.toString.contains(validBase64))
  }
}
