package kui.identity.domain

import kui.kernel.{Secret, ValidationError}

/** A stored password, in the only form a password should ever be stored in.
  *
  * ==The encoding, and why it is written down here==
  *
  * {{{
  * pbkdf2-sha256$210000$<salt, base64url, no padding>$<derived key, base64url, no padding>
  * }}}
  *
  * Everything needed to check a password is in the string: the algorithm, the cost, the salt. That is the
  * property that matters, and it is why the format is self-describing rather than four configuration keys an
  * operator has to keep in step with the hashes they generated last year. Raising the iteration count later
  * does not invalidate an existing hash — it is verified with the count it was written with, and re-hashed
  * with the new one the next time that person changes their password.
  *
  * ==Why PBKDF2 and not bcrypt==
  *
  * ADR-015 named `at.favre.lib:bcrypt`. Adding a third-party dependency to KUI needs the PLAN §13 checklist
  * and an approval, and this is the one place where the JDK already ships an adequate answer:
  * `PBKDF2WithHmacSHA256` is in `javax.crypto`, is a NIST-recommended password-based KDF (SP 800-132), and at
  * 210,000 iterations is the parameter set OWASP publishes for exactly this construction. bcrypt would be a
  * better memory-hardness story and Argon2id better still; neither is worth a dependency this milestone, and
  * the encoding above names the algorithm in every hash precisely so that adding one later is a new prefix
  * rather than a migration. See ADR-015 Amendment 1.
  *
  * ==What this type deliberately cannot do==
  *
  * It does not hash and it does not verify. Both need `javax.crypto`, and a domain module that could reach
  * for a cipher is a domain module that will. `PasswordHasher` is the port; the adapter lives in
  * `infrastructure`.
  */
final case class PasswordHash(
    algorithm: PasswordAlgorithm,
    iterations: Int,
    saltBase64: String,
    hashBase64: String
) {

  /** The single string that goes into `kui.auth.users[].passwordHash`. */
  def encoded: String = s"${algorithm.wire}$$$iterations$$$saltBase64$$$hashBase64"
}

object PasswordHash {

  /** The separator. `$` because it cannot appear in base64url output, so a hash can never be mis-split.
    */
  private val Separator: Char = '$'

  private val Field: String = "passwordHash"

  /** Reads an encoded hash, or says why it is not one.
    *
    * A `Secret` in, because the caller holds the hash as one — it is not a password, but it is the input to
    * an offline cracking attempt and there is no reason for it to be printable. The failure names the shape
    * that was expected and never the value that was found, for the same reason.
    */
  def parse(encoded: Secret[String]): Either[ValidationError, PasswordHash] = {
    val parts = encoded.value.split(Separator).toList

    parts match {
      case algorithm :: iterations :: salt :: hash :: Nil =>
        for {
          named <- PasswordAlgorithm
            .fromWire(algorithm)
            .toRight(
              ValidationError.Format(
                Field,
                s"an encoded hash whose algorithm is one of ${PasswordAlgorithm.values.map(_.wire).mkString(", ")}",
                algorithm
              )
            )
          cost <- iterations.toIntOption
            .filter(_ > 0)
            .toRight(ValidationError.Format(Field, "an iteration count above zero", iterations))
          _ <- Either.cond(
            salt.nonEmpty && hash.nonEmpty,
            (),
            ValidationError.Format(Field, "a non-empty salt and derived key", "an empty segment")
          )
        } yield PasswordHash(named, cost, salt, hash)

      case _ =>
        Left(
          ValidationError.Format(
            Field,
            s"four ${Separator}-separated parts: algorithm, iterations, salt and derived key",
            s"${parts.size} part(s)"
          )
        )
    }
  }

  given CanEqual[PasswordHash, PasswordHash] = CanEqual.derived
}

/** Which key derivation function produced a hash.
  *
  * An enum with one case today, and that is the point: the algorithm travels *in* the hash, so the day a
  * second one is added, every existing hash keeps verifying under the first while new ones are written under
  * the second. A deployment is never asked to re-hash passwords it does not have.
  */
enum PasswordAlgorithm(val wire: String, val keyLengthBits: Int, val defaultIterations: Int) {

  /** PBKDF2 with HMAC-SHA-256. 210,000 iterations is OWASP's published parameter for this construction, and
    * 256 bits of derived key matches the PRF's output so that nothing is truncated.
    */
  case Pbkdf2HmacSha256 extends PasswordAlgorithm("pbkdf2-sha256", 256, 210_000)
}

object PasswordAlgorithm {

  /** What a new hash is written with today. */
  val Current: PasswordAlgorithm = Pbkdf2HmacSha256

  def fromWire(raw: String): Option[PasswordAlgorithm] = values.find(_.wire == raw.trim.toLowerCase)

  given CanEqual[PasswordAlgorithm, PasswordAlgorithm] = CanEqual.derived
}
