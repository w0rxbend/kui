package kui.http.upstream

import java.io.{ByteArrayOutputStream, InputStream}
import java.nio.charset.StandardCharsets

import scala.annotation.tailrec
import scala.util.control.NoStackTrace

import sttp.client4.*

/** Response handlers that keep a strict byte ceiling while reading an upstream body.
  *
  * The body is consumed incrementally and at most one byte beyond `maxBytes` is requested. A declared
  * `Content-Length` above the ceiling lets the client fail before reading the body, but is never trusted as
  * proof that the body fits: absent, invalid, or understated lengths still go through the same incremental
  * reader.
  */
object BoundedResponse {

  /** A UTF-8 string response whose encoded body may occupy no more than `maxBytes`. */
  def asString(maxBytes: Long): ResponseAs[String] = {
    require(maxBytes >= 0L, "a response byte limit must not be negative")

    fromMetadata(
      asInputStreamAlways(readUtf8(_, maxBytes, None)),
      ConditionalResponseAs(
        _.contentLength.exists(_ > maxBytes),
        asInputStreamAlways(_ => fail(maxBytes))
      )
    )
  }

  /** Read one body under the cap. `declaredContentLength` is an early rejection hint only. */
  private[upstream] def readUtf8(
      input: InputStream,
      maxBytes: Long,
      declaredContentLength: Option[Long]
  ): String = {
    require(maxBytes >= 0L, "a response byte limit must not be negative")

    if declaredContentLength.exists(_ > maxBytes) then fail(maxBytes)

    val buffer = new Array[Byte](BufferSize)
    val output = new ByteArrayOutputStream(math.min(maxBytes, BufferSize.toLong).toInt)

    @tailrec
    def read(total: Long): Unit = {
      val remaining = maxBytes - total
      val requested = if remaining >= BufferSize then BufferSize else (remaining + 1L).toInt
      val count = input.read(buffer, 0, requested)

      if count == -1 then ()
      else if count > 0 then {
        val nextTotal = total + count.toLong
        if nextTotal > maxBytes then fail(maxBytes)
        output.write(buffer, 0, count)
        read(nextTotal)
      } else read(total)
    }

    read(0L)

    output.toString(StandardCharsets.UTF_8)
  }

  final case class LimitExceeded(limitBytes: Long)
      extends RuntimeException(s"response exceeded $limitBytes bytes")
      with NoStackTrace

  /** sttp's synchronous `ResponseAs` callback communicates decoder failure by throwing. The exception is
    * caught by the effectful backend and contains only the configured numeric limit.
    */
  private def fail(limitBytes: Long): Nothing =
    // scalafix:off DisableSyntax.throw
    throw LimitExceeded(limitBytes)
    // scalafix:on DisableSyntax.throw

  private val BufferSize = 8192
}
