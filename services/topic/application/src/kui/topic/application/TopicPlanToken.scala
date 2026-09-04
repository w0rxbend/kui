package kui.topic.application

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import scala.util.control.NonFatal

import cats.effect.kernel.Sync

import kui.kernel.error.{ApplicationError, KuiError}
import kui.kernel.{ClusterId, Secret, TopicName}
import kui.topic.domain.TopicMutation

/** The token that says "apply exactly the change the operator was shown" (ADR-045).
  *
  * It authorises nothing an operator could not do anyway. What it prevents is applying a *different* change
  * from the one on screen — because the client edited the numbers, because the cluster moved underneath the
  * plan, or because a second tab planned something else five minutes ago. The apply endpoints take only a
  * token and never a raw specification, so there is no path by which an unshown change can be made.
  *
  * The wire form is `base64url(payload) "." base64url(HMAC-SHA256(payload))`, and the payload is a
  * hand-written delimited line rather than a derived encoding, for the same reason the browse cursor's and
  * the offset-reset plan's are: it is a compatibility surface, and a renamed field must not silently
  * invalidate every token in flight.
  *
  * The signature is verified before the payload is parsed. A codec that parses first is one an attacker can
  * drive with a payload they never had to sign.
  *
  * ==Why this is not the consumer service's `PlanToken`==
  *
  * It signs a different subject — a cluster and a *topic*, plus an operation name — and rule A11 forbids this
  * service from seeing the consumer service's application layer, so there is no shared value available to
  * reuse. What is shared is the thing that must not drift: the key. Both take ADR-026's streaming cursor key,
  * so a deployment configures one secret and rotates one secret, and the two uses are kept apart by the
  * operation name inside the payload.
  */
trait TopicPlanToken[F[_]] {

  /** Signs one planned change.
    *
    * @param detail
    *   the resolved values the plan showed, canonically rendered by the caller. It is what makes the token a
    *   statement about *this* change and not merely about the operation: a partition plan's detail is the
    *   target count, so a token minted for "twelve" cannot be applied as "twenty".
    */
  def mint(
      cluster: ClusterId,
      topic: TopicName,
      operation: TopicMutation,
      detail: String,
      expiresAt: Instant
  ): F[String]

  /** `Left(KUI-VALIDATION)` for a bad signature, an expired token, or one minted for another cluster, topic
    * or operation. `Right` carries back the `detail` that was signed.
    *
    * The failures are deliberately not distinguished in the message: telling a caller which part of a forged
    * token was wrong is an oracle. The log line distinguishes them; the response does not.
    */
  def verify(
      cluster: ClusterId,
      topic: TopicName,
      operation: TopicMutation,
      token: String,
      now: Instant
  ): F[Either[KuiError, String]]
}

object TopicPlanToken {

  private val Algorithm: String = "HmacSHA256"
  private val Version: String = "v1"
  private val Separator: Char = '.'
  private val Field: Char = '|'

  /** How long a plan may be confirmed for. ADR-045 fixes five minutes: long enough to read a warning and
    * think about it, short enough that the cluster has probably not moved.
    */
  val Ttl: java.time.Duration = java.time.Duration.ofMinutes(5)

  /** Uses the existing cursor key (ADR-026): no new secret, and no new configuration for an operator to get
    * wrong.
    */
  def make[F[_]: Sync](key: Secret[Array[Byte]]): TopicPlanToken[F] = new Impl[F](key)

  final private class Impl[F[_]: Sync](key: Secret[Array[Byte]]) extends TopicPlanToken[F] {

    def mint(
        cluster: ClusterId,
        topic: TopicName,
        operation: TopicMutation,
        detail: String,
        expiresAt: Instant
    ): F[String] =
      Sync[F].delay {
        val payload = render(cluster, topic, operation, detail, expiresAt)
        s"${encode(payload.getBytes(StandardCharsets.UTF_8))}$Separator${encode(sign(payload))}"
      }

    def verify(
        cluster: ClusterId,
        topic: TopicName,
        operation: TopicMutation,
        token: String,
        now: Instant
    ): F[Either[KuiError, String]] =
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
                    case Some((subject, signedOperation, detail, expiresAt))
                        if subject == subjectOf(cluster, topic) &&
                          signedOperation == operation.operation &&
                          !now.isAfter(expiresAt) =>
                      Right(detail)
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

  private val invalid: KuiError =
    ApplicationError.Invalid(
      "this confirmation is no longer valid; ask for the change to be planned again and confirm the plan " +
        "that comes back",
      Nil
    )

  private def encode(bytes: Array[Byte]): String = Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

  private def decode(raw: String): Option[Array[Byte]] =
    try Some(Base64.getUrlDecoder.decode(raw))
    catch { case NonFatal(_) => None }

  /** The cluster and topic a token is bound to, joined, so that a plan for `orders.v1` on staging cannot be
    * applied to `orders.v1` on production.
    *
    * A topic name cannot contain `/` — `TopicName`'s own pattern allows only letters, digits, `.`, `_` and
    * `-` — so the two halves cannot be confused by a name chosen to look like a pair.
    */
  private def subjectOf(cluster: ClusterId, topic: TopicName): String = s"${cluster.value}/${topic.value}"

  private def render(
      cluster: ClusterId,
      topic: TopicName,
      operation: TopicMutation,
      detail: String,
      expiresAt: Instant
  ): String =
    List(Version, subjectOf(cluster, topic), operation.operation, detail, expiresAt.toEpochMilli.toString)
      .mkString(Field.toString)

  /** `(subject, operation, detail, expiry)`.
    *
    * `split(String)` is a regex split and `|` is alternation, so the delimiter is quoted. Quoting rather than
    * using the `Char` overload keeps the `-1` limit, which is what preserves an empty detail field.
    */
  private def parse(payload: String): Option[(String, String, String, Instant)] =
    payload.split(java.util.regex.Pattern.quote(Field.toString), -1).toList match {
      case version :: subject :: operation :: detail :: expiry :: Nil if version == Version =>
        expiry.toLongOption.map(millis => (subject, operation, detail, Instant.ofEpochMilli(millis)))
      case _ => None
    }
}
