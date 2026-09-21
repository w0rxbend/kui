package kui.ksql.application

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import scala.util.control.NonFatal

import cats.effect.kernel.Sync

import kui.kernel.error.{ApplicationError, KuiError}
import kui.kernel.{ClusterId, Secret}

/** The token that says "run exactly the statement the operator was shown" (ADR-045).
  *
  * It authorises nothing an operator could not do anyway. What it prevents is running a *different* statement
  * from the one whose consequences were on screen — because the client edited the text after the warning was
  * shown, because a second tab planned something else five minutes ago, or because a script replayed a token
  * against new text.
  *
  * ==Why the statement travels with the token, where `TopicPlanToken`'s subject is a name==
  *
  * `services/topic` signs a cluster, a topic and an operation, and its apply endpoints then take **only** a
  * token: everything the operation will do was fixed when the plan was computed. A statement editor cannot
  * work that way — the statement *is* the request, and there is no second phase for the ordinary
  * `CREATE STREAM` — so this token signs the **canonical statement text itself** and [[verify]] is given the
  * text being applied. A token minted for `DROP STREAM orders DELETE TOPIC;` therefore cannot be spent on
  * `DROP STREAM payments DELETE TOPIC;`, which is the substitution the two-phase flow exists to make
  * impossible. ADR-055 §7 records the deviation and this reason.
  *
  * The statement is **hashed** into the payload rather than written into it, for two reasons that are both
  * about the token being a URL-safe string somebody pastes into a `curl`: a sixteen-kilobyte statement would
  * make a sixteen-kilobyte token, and a token that carried the text would put somebody's SQL — which may name
  * a topic they consider sensitive — into every log that records a request body.
  *
  * The wire form is `base64url(payload) "." base64url(HMAC-SHA256(payload))`, and the payload is a
  * hand-written delimited line rather than a derived encoding, for `TopicPlanToken`'s reason: it is a
  * compatibility surface, and a renamed field must not silently invalidate every token in flight.
  *
  * The signature is verified before the payload is parsed. A codec that parses first is one an attacker can
  * drive with a payload they never had to sign.
  *
  * ==The key==
  *
  * ADR-026's streaming cursor key, which is what `TopicPlanToken` and the consumer service's `PlanToken` both
  * take. One secret for a deployment to configure and one to rotate; the three uses are kept apart by the
  * operation name inside the payload, which is why [[Operation]] is a literal here and not a parameter.
  */
trait KsqlPlanToken[F[_]] {

  /** Signs one planned statement. */
  def mint(cluster: ClusterId, statement: String, expiresAt: Instant): F[String]

  /** `Left(KUI-VALIDATION)` for a bad signature, an expired token, or one minted for another cluster or
    * another statement.
    *
    * The failures are deliberately not distinguished in the message: telling a caller which part of a forged
    * token was wrong is an oracle. The log line distinguishes them; the response does not.
    */
  def verify(cluster: ClusterId, statement: String, token: String, now: Instant): F[Either[KuiError, Unit]]
}

object KsqlPlanToken {

  private val Algorithm: String = "HmacSHA256"
  private val Digest: String = "SHA-256"
  private val Version: String = "v1"
  private val Separator: Char = '.'
  private val Field: Char = '|'

  /** The operation name inside the payload, which is what keeps this token and `TopicPlanToken`'s apart while
    * they share one key. A topic-deletion token replayed here does not parse to this string and is refused.
    */
  val Operation: String = "ksql.statement"

  /** How long a plan may be confirmed for. ADR-045 fixes five minutes: long enough to read a warning and
    * think about it, short enough that the cluster has probably not moved.
    */
  val Ttl: java.time.Duration = java.time.Duration.ofMinutes(5)

  def make[F[_]: Sync](key: Secret[Array[Byte]]): KsqlPlanToken[F] = new Impl[F](key)

  final private class Impl[F[_]: Sync](key: Secret[Array[Byte]]) extends KsqlPlanToken[F] {

    def mint(cluster: ClusterId, statement: String, expiresAt: Instant): F[String] =
      Sync[F].delay {
        val payload = render(cluster, statement, expiresAt)
        s"${encode(payload.getBytes(StandardCharsets.UTF_8))}$Separator${encode(sign(payload))}"
      }

    def verify(
        cluster: ClusterId,
        statement: String,
        token: String,
        now: Instant
    ): F[Either[KuiError, Unit]] =
      Sync[F].delay {
        token.split(Separator) match {
          case Array(payloadPart, signaturePart) =>
            decode(payloadPart).zip(decode(signaturePart)) match {
              case Some((bytes, mac)) =>
                val payload = new String(bytes, StandardCharsets.UTF_8)

                // Constant-time, and before any parsing.
                if !MessageDigest.isEqual(sign(payload), mac) then Left(invalid)
                else
                  parse(payload) match {
                    case Some((subject, operation, fingerprint, expiresAt))
                        if subject == cluster.value &&
                          operation == Operation &&
                          // Constant-time again: the fingerprint is derived from the caller's own
                          // statement, so a comparison that stopped at the first differing character
                          // would leak how much of a signed statement a guess had matched.
                          MessageDigest.isEqual(
                            fingerprint.getBytes(StandardCharsets.UTF_8),
                            fingerprintOf(statement).getBytes(StandardCharsets.UTF_8)
                          ) &&
                          !now.isAfter(expiresAt) =>
                      Right(())
                    case _ => Left(invalid)
                  }
              case None => Left(invalid)
            }

          case _ => Left(invalid)
        }
      }

    private def sign(payload: String): Array[Byte] = {
      val mac = Mac.getInstance(Algorithm)
      mac.init(new SecretKeySpec(key.value, Algorithm))
      mac.doFinal(payload.getBytes(StandardCharsets.UTF_8))
    }
  }

  /** The refusal, which is one sentence for four different failures on purpose. */
  private val invalid: KuiError =
    ApplicationError.Invalid(
      "this confirmation is no longer valid for this statement; ask for the statement to be planned " +
        "again and confirm the plan that comes back",
      Nil
    )

  private def encode(bytes: Array[Byte]): String = Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

  private def decode(raw: String): Option[Array[Byte]] =
    try Some(Base64.getUrlDecoder.decode(raw))
    catch { case NonFatal(_) => None }

  /** SHA-256 of the canonical statement text, base64url.
    *
    * A hash rather than the text, for the reason this object's header gives. It is not a secret and it does
    * not need to be: the token's integrity comes from the HMAC over the whole payload, and the fingerprint's
    * only job is to bind the token to one statement.
    */
  def fingerprintOf(statement: String): String =
    encode(MessageDigest.getInstance(Digest).digest(statement.getBytes(StandardCharsets.UTF_8)))

  private def render(cluster: ClusterId, statement: String, expiresAt: Instant): String =
    List(Version, cluster.value, Operation, fingerprintOf(statement), expiresAt.toEpochMilli.toString)
      .mkString(Field.toString)

  /** `(cluster, operation, fingerprint, expiry)`.
    *
    * `split(String)` is a regex split and `|` is alternation, so the delimiter is quoted. Quoting rather than
    * using the `Char` overload keeps the `-1` limit, which is what preserves an empty field.
    */
  private def parse(payload: String): Option[(String, String, String, Instant)] =
    payload.split(java.util.regex.Pattern.quote(Field.toString), -1).toList match {
      case version :: cluster :: operation :: fingerprint :: expiry :: Nil if version == Version =>
        expiry.toLongOption.map(millis => (cluster, operation, fingerprint, Instant.ofEpochMilli(millis)))
      case _ => None
    }
}
