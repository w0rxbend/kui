package kui.config.store

import java.nio.charset.StandardCharsets
import java.util.Base64

import scala.util.control.NonFatal

import kui.kernel.Secret

/** One AES-256 key and the id it is known by.
  *
  * The id is short, operator-chosen text. It appears in every record the key encrypts, and it is how a reader
  * picks the right key after a rotation: a record written under `k1` stays readable while `k1` is still in
  * the keyring, however many newer keys have been added since.
  */
final case class EncryptionKey(id: String, material: Secret[Array[Byte]]) {

  /** Never the material. `Secret` already redacts, but a case class's generated `toString` would print
    * `Secret(***)` for the field and the array's identity hash for nothing, so it is replaced outright.
    */
  override def toString: String = s"EncryptionKey($id)"
}

object EncryptionKey {

  /** AES-256. What `openssl rand -base64 32` in `docs/operations/metadata-store.md` §4.2 produces. */
  val KeyLengthBytes: Int = 32

  val IdPattern: String = "^[a-z0-9][a-z0-9-]{0,31}$"

  private val idRegex = IdPattern.r

  /** Decodes base64 material, checks its length and checks the id.
    *
    * No failure here ever contains the material — not the decoded bytes, not the base64, not its length when
    * the length is the problem. An operator who pastes the wrong string needs to know which key is wrong and
    * what was expected, and nothing else.
    */
  def fromBase64(id: String, base64: String): Either[StoreError, EncryptionKey] =
    if !idRegex.matches(id) then
      Left(StoreError.InvalidKeyMaterial(id, s"the key id does not match $IdPattern"))
    else
      decode(base64) match {
        case None =>
          Left(StoreError.InvalidKeyMaterial(id, "the key material is not valid base64"))
        case Some(bytes) if bytes.length != KeyLengthBytes =>
          Left(
            StoreError.InvalidKeyMaterial(
              id,
              s"an AES-256 key is $KeyLengthBytes bytes; generate one with 'openssl rand -base64 32'"
            )
          )
        case Some(bytes) => Right(EncryptionKey(id, Secret(bytes)))
      }

  private def decode(base64: String): Option[Array[Byte]] =
    try Some(Base64.getDecoder.decode(base64.trim.getBytes(StandardCharsets.US_ASCII)))
    catch { case NonFatal(_) => None }
}

/** Every key KUI can decrypt with, and the one it encrypts with.
  *
  * Reads try the `keyId` written into the record and nothing else. Trying every key in turn would make a
  * wrong-key failure indistinguishable from a corrupt record and would turn a rotation mistake into a silent
  * success under the wrong key.
  */
final class EncryptionKeyring private (val active: EncryptionKey, val all: Map[String, EncryptionKey]) {

  def find(keyId: String): Option[EncryptionKey] = all.get(keyId)

  /** Ids yes, material never. */
  override def toString: String =
    s"EncryptionKeyring(active=${active.id}, ${all.size} keys: ${all.keys.toList.sorted.mkString(", ")})"
}

object EncryptionKeyring {

  /** Fails when the keyring is empty or when the active id is not among the keys, naming the ids present.
    *
    * An empty keyring is refused rather than represented, because a KUI that started with a Kafka store and
    * no key would work perfectly until the first secret and then fail at write time — the worst place to
    * discover a configuration mistake.
    */
  def of(keys: List[EncryptionKey], activeKeyId: String): Either[StoreError, EncryptionKeyring] =
    if keys.isEmpty then
      Left(
        StoreError.InvalidKeyMaterial(activeKeyId, "the keyring is empty; configure kui.store.encryptionKey")
      )
    else
      keys.find(_.id == activeKeyId) match {
        case Some(active) => Right(new EncryptionKeyring(active, keys.map(k => k.id -> k).toMap))
        case None =>
          Left(
            StoreError.InvalidKeyMaterial(
              activeKeyId,
              s"it is not among the configured keys (${keys.map(_.id).sorted.mkString(", ")})"
            )
          )
      }
}
