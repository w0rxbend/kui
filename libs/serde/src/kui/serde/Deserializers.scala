package kui.serde

import cats.effect.Sync
import cats.syntax.all.*

/** The one place a `Deserializer[F]` is actually run.
  *
  * Nothing else in KUI calls `deserialize` directly, and that is a rule rather than a convention: this object
  * is what turns every way a decode can go wrong into a `DeserializeFailure` attached to one record, instead
  * of an exception that ends a page.
  *
  * A third-party serde that throws is not a hypothetical. It is the ordinary failure mode of a decoder handed
  * bytes of the wrong shape — Avro's own reader throws `ArrayIndexOutOfBoundsException` on a truncated
  * payload, not a checked error — and a stream that propagated it would stop on the first odd record in a
  * topic of ten thousand good ones.
  */
object Deserializers {

  /** The text a record with no payload decodes to.
    *
    * A tombstone is a record. It has a key, a timestamp, an offset and a meaning ("this key is deleted"), and
    * the browse screen has to show it as a row. Handing `null` to a deserializer to find that out would make
    * every serde responsible for the same null check, and the first one to forget it would throw.
    */
  val NullPayload: DeserializeResult = DeserializeResult.text("")

  /** Runs a deserializer and converts **both** a returned `Left` and a thrown exception into a
    * `DeserializeFailure`.
    *
    * The failure's `cause` is the exception's message, or its class name when the message is null — never its
    * stack trace, which would travel to the browser on the record and put a server-side file path on a user's
    * screen.
    */
  def attempt[F[_]: Sync](
      d: Deserializer[F],
      headers: List[RawHeader],
      bytes: Option[Array[Byte]]
  ): F[Either[DeserializeFailure, DeserializeResult]] =
    bytes match {
      case None => Sync[F].pure(Right(NullPayload))
      case Some(payload) =>
        Sync[F]
          .defer(d.deserialize(headers, payload))
          .handleError(t => Left(DeserializeFailure(d.serde, describe(t))))
    }

  /** [[attempt]], then the fallback when it failed.
    *
    * Returns the failure alongside the fallback's result rather than instead of it: the caller needs both,
    * because the record is rendered from the text and the row's marker is drawn from the failure
    * (`deserializeErrors[]`, ADR-035).
    *
    * This function has no failure path at all, and that is its entire contract. If `fallback` is
    * `FallbackSerde`'s — and the resolution order guarantees it is — then the second `attempt` cannot fail
    * either, and the `getOrElse` below is unreachable. It is written anyway, because "unreachable given a
    * precondition someone else maintains" is exactly the code that is reached eighteen months later.
    */
  def withFallback[F[_]: Sync](
      primary: Deserializer[F],
      fallback: Deserializer[F],
      headers: List[RawHeader],
      bytes: Option[Array[Byte]]
  ): F[(DeserializeResult, Option[DeserializeFailure])] =
    attempt(primary, headers, bytes).flatMap {
      case Right(result) => Sync[F].pure((result, None))
      case Left(failure) =>
        attempt(fallback, headers, bytes).map { fallen =>
          (fallen.getOrElse(NullPayload), Some(failure))
        }
    }

  /** An exception reduced to one safe line. */
  private def describe(t: Throwable): String =
    Option(t.getMessage).filter(_.nonEmpty).getOrElse(t.getClass.getSimpleName)
}
