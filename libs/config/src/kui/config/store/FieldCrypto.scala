package kui.config.store

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}

import scala.util.control.NonFatal

import cats.effect.Sync
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}

/** The contents of an `$enc` node.
  *
  * `iv` and `ct` are base64 with no line wrapping, so that a record survives `kafka-console-consumer` and a
  * TSV export. `ct` includes the 16-byte GCM authentication tag, because that is what `Cipher.doFinal`
  * returns and splitting it would be a second thing to get wrong.
  */
final case class CipherBlob(alg: String, keyId: String, iv: String, ct: String)

object CipherBlob {

  val Algorithm: String = "AES-256-GCM"

  given Encoder[CipherBlob] =
    Encoder.instance { blob =>
      Json.obj(
        "alg" -> Json.fromString(blob.alg),
        "keyId" -> Json.fromString(blob.keyId),
        "iv" -> Json.fromString(blob.iv),
        "ct" -> Json.fromString(blob.ct)
      )
    }

  given Decoder[CipherBlob] =
    Decoder.instance { cursor =>
      for {
        alg <- cursor.get[String]("alg")
        keyId <- cursor.get[String]("keyId")
        iv <- cursor.get[String]("iv")
        ct <- cursor.get[String]("ct")
      } yield CipherBlob(alg, keyId, iv, ct)
    }

  given CanEqual[CipherBlob, CipherBlob] = CanEqual.derived
}

/** Encrypts and decrypts the secret-marked fields of a store payload.
  *
  * `F` is here rather than a pure API because the JCE calls really are effects: they draw from a
  * `SecureRandom`, they throw, and a `Cipher` is not thread-safe, so every call gets its own instance inside
  * `Sync[F].blocking`. A pure signature would either be a lie or force the caller to hold a mutable cipher.
  */
trait FieldCrypto[F[_]] {

  /** Replaces every `{"$secret": s}` node with `{"$enc": {...}}`. A payload with no marker is returned
    * unchanged and costs no cipher.
    *
    * **The ciphertext is bound to the record key and to the field's JSON path** (see `Aad`). The consequence
    * is worth stating plainly: moving a secret field to a different path in a section's payload makes every
    * existing record undecryptable at that field. That is a migration, not a refactor (ADR-044).
    */
  def encryptPayload(key: StoreKey, payload: Json): F[Json]

  /** The inverse. A payload with no `$enc` node is returned unchanged.
    *
    * Fails as a value with `StoreError.UnknownKeyId` or `StoreError.DecryptionFailed`, and never returns a
    * partially decrypted payload: a caller handed one would have no way to tell which fields it could trust.
    */
  def decryptPayload(key: StoreKey, payload: Json): F[Either[StoreError, Json]]

  def encryptBytes(aad: Array[Byte], plaintext: Array[Byte]): F[CipherBlob]

  def decryptBytes(aad: Array[Byte], blob: CipherBlob): F[Either[StoreError, Array[Byte]]]
}

object FieldCrypto {

  /** `AES/GCM/NoPadding`: GCM authenticates, so a tampered record fails rather than decrypting to garbage
    * that some later parser has to notice.
    */
  private val Transformation: String = "AES/GCM/NoPadding"

  /** 96 bits, GCM's native IV size. */
  private val IvLengthBytes: Int = 12

  /** 128 bits, the JCE maximum and its default. */
  private val TagLengthBits: Int = 128

  /** What binds a ciphertext to where it lives.
    *
    * The additional authenticated data is `"<record key>|<field path>"`. A password copied out of
    * `cluster/a`'s `password` field into `cluster/b`'s, or into `cluster/a`'s `truststorePassword`, fails
    * authentication instead of decrypting silently. It is the cheap defence against somebody with write
    * access to the topic rearranging records.
    */
  def aad(key: StoreKey, path: String): Array[Byte] =
    s"${key.render}|$path".getBytes(StandardCharsets.UTF_8)

  def apply[F[_]: Sync](keyring: EncryptionKeyring): FieldCrypto[F] = new Impl[F](keyring)

  final private class Impl[F[_]: Sync](keyring: EncryptionKeyring) extends FieldCrypto[F] {

    // One process-wide source of randomness. `SecureRandom` is thread-safe, and seeding a fresh one per
    // call is both slow and, on some platforms, a way to get correlated output.
    private val random: SecureRandom = new SecureRandom()

    def encryptBytes(aad: Array[Byte], plaintext: Array[Byte]): F[CipherBlob] =
      Sync[F].blocking {
        val key = keyring.active
        // A fresh IV per encryption, generated here so that no caller can supply one. Reusing an IV
        // under one key does not weaken GCM, it destroys it: two ciphertexts under the same IV leak the
        // authentication subkey and the XOR of the plaintexts.
        val iv = new Array[Byte](IvLengthBytes)
        random.nextBytes(iv)
        val cipher = Cipher.getInstance(Transformation)
        cipher.init(
          Cipher.ENCRYPT_MODE,
          new SecretKeySpec(key.material.value, "AES"),
          new GCMParameterSpec(TagLengthBits, iv)
        )
        cipher.updateAAD(aad)
        val ct = cipher.doFinal(plaintext)
        CipherBlob(CipherBlob.Algorithm, key.id, encode(iv), encode(ct))
      }

    def decryptBytes(aad: Array[Byte], blob: CipherBlob): F[Either[StoreError, Array[Byte]]] =
      Sync[F].blocking {
        keyring.find(blob.keyId) match {
          case None => Left(StoreError.UnknownKeyId(blob.keyId, keyring.all.keySet))
          case Some(key) =>
            try {
              val cipher = Cipher.getInstance(Transformation)
              cipher.init(
                Cipher.DECRYPT_MODE,
                new SecretKeySpec(key.material.value, "AES"),
                new GCMParameterSpec(TagLengthBits, decode(blob.iv))
              )
              cipher.updateAAD(aad)
              Right(cipher.doFinal(decode(blob.ct)))
            } catch {
              // Every JCE failure collapses to one named error on purpose. A bad tag, a truncated
              // ciphertext and a malformed IV are the same fact to a caller — this record cannot be
              // read with this key — and the exception messages that distinguish them are not worth
              // the chance of one of them quoting the input.
              case NonFatal(_) => Left(StoreError.DecryptionFailed(blob.keyId, ""))
            }
        }
      }

    def encryptPayload(key: StoreKey, payload: Json): F[Json] =
      SecretJson.plaintextPaths(payload) match {
        case Nil => Sync[F].pure(payload)
        case _ =>
          for {
            encrypted <- rewrite(payload, "", SecretJson.PlaintextField) { (path, marker) =>
              marker.hcursor.get[String](SecretJson.PlaintextField) match {
                case Left(_) =>
                  Sync[F].pure(
                    Left(
                      StoreError.MalformedRecord(key.render, s"the secret marker at '$path' is not a string")
                    )
                  )
                case Right(plaintext) =>
                  encryptBytes(aad(key, path), plaintext.getBytes(StandardCharsets.UTF_8))
                    .map(blob => Right(Json.obj(SecretJson.CipherField -> blob.asJson)))
              }
            }
            // Costs one tree walk on a write that happens a few times a day, and it is the whole
            // difference between "KUI encrypts secrets" and "KUI encrypts the secrets it remembered".
            checked <- Sync[F].raiseWhen(!SecretJson.isFullyEncrypted(encrypted))(
              new StoreFailure(
                StoreError.MalformedRecord(
                  key.render,
                  s"a plaintext secret survived encryption at ${SecretJson.plaintextPaths(encrypted).mkString(", ")}"
                )
              )
            ) *> Sync[F].pure(encrypted)
          } yield checked
      }

    def decryptPayload(key: StoreKey, payload: Json): F[Either[StoreError, Json]] =
      rewrite(payload, "", SecretJson.CipherField) { (path, node) =>
        node.hcursor.get[CipherBlob](SecretJson.CipherField) match {
          case Left(failure) =>
            Sync[F].pure(
              Left(
                StoreError.MalformedRecord(
                  key.render,
                  s"the '$path' cipher node is unreadable: ${failure.message}"
                )
              )
            )
          case Right(blob) =>
            decryptBytes(aad(key, path), blob).map {
              case Left(StoreError.DecryptionFailed(keyId, _)) =>
                Left(StoreError.DecryptionFailed(keyId, path))
              case Left(other) => Left(other)
              case Right(bytes) =>
                Right(
                  Json.obj(
                    SecretJson.PlaintextField -> Json.fromString(new String(bytes, StandardCharsets.UTF_8))
                  )
                )
            }
        }
      }.map(json => Right(json).withLeft[StoreError]).recoverWith { case failure: StoreFailure =>
        // `rewrite` aborts the walk by raising, so that a caller never sees a half-decrypted payload.
        // Anything else that failed is not a store error and keeps propagating.
        Sync[F].pure(Left(failure.error))
      }

    /** Walks the tree once, replacing every node that is exactly a `field` marker with what `f` returns.
      *
      * The traversal is the same one `SecretJson.plaintextPaths` performs, and the paths it builds are the
      * same strings, which is what makes the AAD agree between encryption and decryption. A `Left` from `f`
      * aborts the whole walk through `StoreFailure`, so a caller never sees a half-rewritten payload.
      */
    private def rewrite(json: Json, path: String, field: String)(
        f: (String, Json) => F[Either[StoreError, Json]]
    ): F[Json] =
      if SecretJson.isMarker(json, field) then
        f(path, json).flatMap {
          case Right(replacement) => Sync[F].pure(replacement)
          case Left(error) => Sync[F].raiseError(new StoreFailure(error))
        }
      else
        json.asObject match {
          case Some(obj) =>
            obj.toList
              .traverse((name, value) => rewrite(value, join(path, name), field)(f).map(name -> _))
              .map(Json.fromFields)
          case None =>
            json.asArray match {
              case Some(items) =>
                items.zipWithIndex
                  .traverse((item, index) => rewrite(item, s"$path[$index]", field)(f))
                  .map(Json.fromValues)
              case None => Sync[F].pure(json)
            }
        }

    private def join(prefix: String, name: String): String =
      if prefix.isEmpty then name else s"$prefix.$name"

    private def encode(bytes: Array[Byte]): String = Base64.getEncoder.encodeToString(bytes)

    private def decode(text: String): Array[Byte] = Base64.getDecoder.decode(text)
  }
}
