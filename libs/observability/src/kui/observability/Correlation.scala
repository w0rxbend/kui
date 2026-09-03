package kui.observability

import java.security.SecureRandom

import cats.effect.kernel.Sync
import cats.syntax.all.*
import org.typelevel.otel4s.trace.Tracer

import kui.kernel.CorrelationId

/** The id that ties one HTTP response to the log lines and the trace it produced.
  *
  * A user reports "it failed at about ten past three". Without a correlation id, finding that request in a
  * log system means guessing from timestamps across several services. With one, the id is in the error the
  * user is looking at, in every log line the request produced and in the span, so all three are one search
  * away from each other.
  */
object Correlation {

  /** The request and response header the id travels in. */
  val HeaderName: String = "X-Kui-Correlation-Id"

  private val Length: Int = 16

  private val random: SecureRandom = new SecureRandom()

  /** The current span's id when there is a span, and a fresh random one otherwise.
    *
    * Deriving it from the span id rather than always generating one means the correlation id in an error body
    * and the span id in the trace backend are the same 16 characters, so an operator can paste one into the
    * other. Outside a span — a scheduler, a startup path — there is nothing to derive from, so a random id is
    * generated; it is still unique, it just does not point at a trace.
    */
  def fromSpanOrRandom[F[_]: {Sync, Tracer}]: F[CorrelationId] =
    Tracer[F].currentSpanOrNoop.flatMap { span =>
      val context = span.context
      if context.isValid then Sync[F].pure(CorrelationId.unsafe(context.spanIdHex))
      else newRandom[F]
    }

  /** A fresh id, with no span involved. */
  def newRandom[F[_]: Sync]: F[CorrelationId] =
    Sync[F].delay {
      val bytes = new Array[Byte](Length / 2)
      random.nextBytes(bytes)
      CorrelationId.unsafe(bytes.map(byte => f"${byte & 0xff}%02x").mkString)
    }

  /** Accepts a caller-supplied id, or `None` when it is not one KUI can use.
    *
    * An id from outside is echoed rather than replaced, so that a caller who logs "I sent request X" can find
    * X on this side too. It is validated first — `CorrelationId` allows letters, digits and dashes up to 64
    * characters — because the value ends up in a response header and in log output, and an unchecked header
    * is how a newline gets into a log file.
    */
  def accept(raw: String): Option[CorrelationId] = CorrelationId.from(raw).toOption
}
