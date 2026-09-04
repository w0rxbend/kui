package kui.identity.infrastructure

import java.security.SecureRandom
import java.util.Base64

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

import cats.effect.kernel.Sync
import cats.syntax.all.*

import kui.identity.domain.{PasswordAlgorithm, PasswordHash, PasswordHasher}
import kui.kernel.Secret

/** Hashing and checking a password with PBKDF2-HMAC-SHA-256 from the JDK (ADR-015 Amendment 1).
  *
  * ==Why the JDK and not bcrypt==
  *
  * ADR-015 named `at.favre.lib:bcrypt`. A new third-party dependency in KUI needs the PLAN §13 checklist and
  * an approval, and this is one of the few places where the platform already ships something adequate:
  * `PBKDF2WithHmacSHA256` is a NIST-recommended password-based key derivation function (SP 800-132) and
  * 210,000 iterations is OWASP's published parameter for exactly this construction. bcrypt has the better
  * memory-hardness story and Argon2id better still; neither is worth a dependency this milestone, and
  * [[PasswordHash]] names its algorithm in every hash precisely so that adding one later is a new prefix
  * rather than a migration.
  *
  * ==The two properties that matter, and where each lives==
  *
  * **Cost.** The iteration count is what makes an offline attack on a stolen hash expensive. It is stored per
  * hash, so raising it later leaves every existing password verifiable, and each of them is re-hashed with
  * the new cost the next time its owner changes it.
  *
  * **Constant time.** [[verify]] compares the derived keys byte by byte without stopping early. `==` on two
  * arrays would return as soon as it found a difference, which turns "is this password right" into a
  * measurable side channel — the same reasoning `Secret` and `CsrfCheck` apply to their own comparisons.
  *
  * ==Why every call is `blocking`==
  *
  * Deriving a key deliberately takes on the order of a hundred milliseconds. Running that on a compute pool
  * thread would stall the runtime's fixed-size pool for that long, once per login attempt, which is a denial
  * of service anyone can trigger without an account.
  */
object Pbkdf2PasswordHasher {

  private val Algorithm: String = "PBKDF2WithHmacSHA256"

  private val SaltBytes: Int = 16

  private val encoder: Base64.Encoder = Base64.getUrlEncoder.withoutPadding
  private val decoder: Base64.Decoder = Base64.getUrlDecoder

  def make[F[_]: Sync]: F[PasswordHasher[F]] =
    Sync[F]
      .delay(new SecureRandom())
      .map(random =>
        new PasswordHasher[F] {

          def hash(password: Secret[String]): F[PasswordHash] =
            Sync[F].blocking {
              val algorithm = PasswordAlgorithm.Current
              val salt = new Array[Byte](SaltBytes)
              random.nextBytes(salt)
              val derived = derive(password.value, salt, algorithm.defaultIterations, algorithm.keyLengthBits)

              PasswordHash(
                algorithm,
                algorithm.defaultIterations,
                encoder.encodeToString(salt),
                encoder.encodeToString(derived)
              )
            }

          def verify(password: Secret[String], against: PasswordHash): F[Boolean] =
            Sync[F]
              .blocking {
                val salt = decoder.decode(against.saltBase64)
                val expected = decoder.decode(against.hashBase64)
                val derived = derive(password.value, salt, against.iterations, expected.length * 8)
                constantTimeEquals(derived, expected)
              }
              // A hash whose base64 will not decode is a corrupt configuration entry, not a wrong password,
              // and it must not crash a login attempt: the honest answer for "does this password match a
              // hash that is not a hash" is no. The load-time parse in `PasswordHash.parse` is what is
              // supposed to catch it, and this is the belt to that pair of braces.
              .handleError(_ => false)
        }
      )

  private def derive(
      password: String,
      salt: Array[Byte],
      iterations: Int,
      keyLengthBits: Int
  ): Array[Byte] = {
    val spec = new PBEKeySpec(password.toCharArray, salt, iterations, keyLengthBits)
    try SecretKeyFactory.getInstance(Algorithm).generateSecret(spec).getEncoded
    finally spec.clearPassword()
  }

  /** A comparison whose duration does not depend on where the first difference is. */
  private def constantTimeEquals(left: Array[Byte], right: Array[Byte]): Boolean = {
    val length = math.max(left.length, right.length)
    var difference = left.length ^ right.length
    var index = 0
    while index < length do {
      val leftByte = if index < left.length then left(index).toInt else 0
      val rightByte = if index < right.length then right(index).toInt else 0
      difference |= leftByte ^ rightByte
      index += 1
    }
    difference == 0
  }
}
