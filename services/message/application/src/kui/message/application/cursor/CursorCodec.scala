package kui.message.application.cursor

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import scala.util.control.NonFatal

import cats.effect.Sync
import cats.syntax.all.*

import kui.kernel.browse.{Direction, IsolationLevel}
import kui.kernel.error.{ApplicationError, ErrorCode, KuiError}
import kui.kernel.serde.SerdeName
import kui.kernel.{ClusterId, Offset, PartitionId, Secret, TopicName}

/** Encodes and decodes the opaque `cursor` a browse hands back and takes again.
  *
  * The wire form is `base64url(payload) "." base64url(HMAC-SHA256(payload, cursorKey))`, and the payload is a
  * hand-written, explicitly versioned line of delimited fields. It is hand-written on purpose: a cursor is a
  * compatibility surface held by browsers that are already open, and a derived codec would let renaming a
  * field silently invalidate every one of them at the next deployment.
  *
  * The signature is verified **before** the payload is parsed. That order is not stylistic — a codec that
  * parses first is a codec an attacker can drive with a malformed payload it never had to sign.
  */
trait CursorCodec[F[_]] {

  /** `Left(KUI-CURSOR-TOO-LARGE)` above the configured size, with a hint to browse fewer partitions.
    *
    * The cursor grows about twenty bytes per partition, so a thousand-partition topic mints something no
    * query string should carry. Refusing is ADR-026's documented behaviour; truncating would produce a cursor
    * that decodes cleanly and resumes in the wrong place on the partitions it dropped.
    */
  def encode(cursor: BrowseCursor): F[Either[KuiError, String]]

  /** Verifies, then parses, then checks the version, the expiry and finally the binding.
    *
    * A cursor minted for another topic is `KUI-CURSOR-INVALID` — not a browse of that other topic. The cursor
    * names its own cluster and topic and the URL names them too, and quietly preferring the cursor's would
    * let a link browse something the path did not ask for.
    */
  def decode(raw: String, expected: (ClusterId, TopicName), now: Instant): F[Either[KuiError, BrowseCursor]]
}

object CursorCodec {

  /** About four hundred partitions' worth. Above this the caller is told to browse a subset. */
  val DefaultMaxBytes: Int = 8 * 1024

  private val Separator: Char = '.'
  private val Field: Char = '|'
  private val Pair: Char = ':'
  private val Entry: Char = ','
  private val Absent: String = ""
  private val Algorithm: String = "HmacSHA256"

  /** @param key
    *   the shared signing key, `kui.streaming.cursorKey`. Every replica of the service must have the same
    *   one, or a cursor minted by one is rejected by the next — which is precisely the failure this whole
    *   type exists to remove. It is a `Secret`, so it cannot reach a log or an error message through a
    *   `toString` nobody wrote on purpose
    */
  def hmacSha256[F[_]: Sync](key: Secret[Array[Byte]], maxBytes: Int = DefaultMaxBytes): CursorCodec[F] =
    new CursorCodec[F] {

      private val encoder = Base64.getUrlEncoder.withoutPadding
      private val decoder = Base64.getUrlDecoder

      def encode(cursor: BrowseCursor): F[Either[KuiError, String]] =
        Sync[F].delay {
          val payload = render(cursor).getBytes(StandardCharsets.UTF_8)
          val encoded =
            s"${encoder.encodeToString(payload)}$Separator${encoder.encodeToString(sign(payload))}"
          if encoded.length > maxBytes then
            Left(
              ApplicationError.Refused(
                ErrorCode.CursorTooLarge,
                s"this topic has too many partitions to page through in one cursor " +
                  s"(${cursor.perPartitionNext.size}); browse a subset of its partitions"
              )
            )
          else Right(encoded)
        }

      def decode(
          raw: String,
          expected: (ClusterId, TopicName),
          now: Instant
      ): F[Either[KuiError, BrowseCursor]] =
        Sync[F].delay {
          for {
            payload <- verified(raw)
            cursor <- parse(payload)
            _ <- notExpired(cursor, now)
            _ <- boundTo(cursor, expected)
          } yield cursor
        }

      /** Splits, decodes and checks the signature, returning the payload bytes only if they were signed. */
      private def verified(raw: String): Either[KuiError, String] = {
        val parts = raw.split(Separator).toList
        parts match {
          case encodedPayload :: encodedSignature :: Nil =>
            try {
              val payload = decoder.decode(encodedPayload)
              val signature = decoder.decode(encodedSignature)
              if MessageDigest.isEqual(sign(payload), signature) then
                Right(new String(payload, StandardCharsets.UTF_8))
              else Left(invalid("its signature does not match"))
            } catch {
              case NonFatal(_) => Left(invalid("it is not two base64url segments"))
            }
          case _ => Left(invalid("it is not two base64url segments separated by a dot"))
        }
      }

      private def sign(payload: Array[Byte]): Array[Byte] = {
        // A `Mac` is not thread-safe and this is the hottest path in the service, so one is created per
        // call rather than shared behind a lock that every browse would queue on.
        val mac = Mac.getInstance(Algorithm)
        mac.init(new SecretKeySpec(key.value, Algorithm))
        mac.doFinal(payload)
      }
    }

  /** The payload, one line, version first.
    *
    * Every value in it is drawn from an alphabet that excludes the three delimiters: a cluster id is a slug,
    * a Kafka topic name is letters, digits, `.`, `_` and `-`, a serde name is letters, digits, `_`, `.` and
    * `-`, and a filter id is hexadecimal. So no field needs escaping, and the parser can be a `split`.
    */
  private[cursor] def render(cursor: BrowseCursor): String = {
    val offsets = cursor.perPartitionNext.toList
      .sortBy((partition, _) => partition.value)
      .map((partition, offset) => s"${partition.value}$Pair${offset.value}")
      .mkString(Entry.toString)

    List(
      cursor.v.toString,
      cursor.cluster.value,
      cursor.topic.value,
      cursor.direction.wire,
      offsets,
      cursor.filterId.getOrElse(Absent),
      cursor.keySerde.fold(Absent)(_.value),
      cursor.valueSerde.fold(Absent)(_.value),
      cursor.limit.toString,
      cursor.isolation.wire,
      cursor.expiresAt.getEpochSecond.toString
    ).mkString(Field.toString)
  }

  private[cursor] def parse(payload: String): Either[KuiError, BrowseCursor] = {
    // `Pattern.quote`, because `String.split` takes a *regular expression* and a bare "|" is the
    // alternation operator: it matches the empty string between every character and shreds the payload.
    val fields = payload.split(java.util.regex.Pattern.quote(Field.toString), -1).toList
    fields match {
      case List(
            version,
            cluster,
            topic,
            direction,
            offsets,
            filterId,
            keySerde,
            valueSerde,
            limit,
            isolation,
            expiry
          ) =>
        for {
          _ <- knownVersion(version)
          parsedDirection <- Direction
            .from(direction)
            .leftMap(_ => invalid("its direction is not one KUI mints"))
          parsedIsolation <- IsolationLevel
            .from(isolation)
            .leftMap(_ => invalid("its isolation level is not one KUI mints"))
          parsedOffsets <- offsetsOf(offsets)
          parsedLimit <- numeric(limit, "limit").map(_.toInt)
          parsedExpiry <- numeric(expiry, "expiry")
        } yield BrowseCursor(
          v = BrowseCursor.Version,
          cluster = ClusterId.unsafe(cluster),
          topic = TopicName.unsafe(topic),
          direction = parsedDirection,
          perPartitionNext = parsedOffsets,
          filterId = Option.when(filterId.nonEmpty)(filterId),
          keySerde = Option.when(keySerde.nonEmpty)(SerdeName.unsafe(keySerde)),
          valueSerde = Option.when(valueSerde.nonEmpty)(SerdeName.unsafe(valueSerde)),
          limit = parsedLimit,
          isolation = parsedIsolation,
          expiresAt = Instant.ofEpochSecond(parsedExpiry)
        )
      case other => Left(invalid(s"it has ${other.size} fields rather than 11"))
    }
  }

  private def knownVersion(version: String): Either[KuiError, Unit] =
    if version == BrowseCursor.Version.toString then Right(())
    else
      // Not ignored. A cursor from a later release carries a field this build does not know about, and
      // reading it as though the field were absent starts the browse somewhere the user did not ask for.
      Left(invalid(s"it is version '$version' and this build mints version ${BrowseCursor.Version}"))

  private def offsetsOf(raw: String): Either[KuiError, Map[PartitionId, Offset]] =
    if raw.isEmpty then Right(Map.empty)
    else
      raw
        .split(Entry)
        .toList
        .traverse { pair =>
          pair.split(Pair).toList match {
            case partition :: offset :: Nil =>
              for {
                p <- numeric(partition, "partition").map(_.toInt)
                o <- numeric(offset, "offset")
                _ <- Either.cond(p >= 0 && o >= 0L, (), invalid("it carries a negative partition or offset"))
              } yield PartitionId.unsafe(p) -> Offset.unsafe(o)
            case _ => Left(invalid("its offset map is malformed"))
          }
        }
        .map(_.toMap)

  private def numeric(raw: String, what: String): Either[KuiError, Long] =
    raw.toLongOption.toRight(invalid(s"its $what is not a number"))

  private def notExpired(cursor: BrowseCursor, now: Instant): Either[KuiError, Unit] =
    if cursor.expiresAt.isAfter(now) then Right(())
    else
      // Its own code, not `INVALID`: the browser's response to an expired cursor is to re-issue the browse
      // from the filter bar and show the user a refreshed page, which is what they wanted from "next". Its
      // response to an invalid one is to report a problem.
      Left(
        ApplicationError.Refused(
          ErrorCode.CursorExpired,
          "this page link has expired; the browse has been restarted from the current position"
        )
      )

  private def boundTo(cursor: BrowseCursor, expected: (ClusterId, TopicName)): Either[KuiError, Unit] = {
    val (cluster, topic) = expected
    if cursor.cluster == cluster && cursor.topic == topic then Right(())
    else Left(invalid("it was issued for a different cluster or topic"))
  }

  private def invalid(why: String): KuiError =
    ApplicationError.Refused(ErrorCode.CursorInvalid, s"this page link cannot be used: $why")
}
