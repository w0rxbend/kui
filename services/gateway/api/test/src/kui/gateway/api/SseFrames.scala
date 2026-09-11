package kui.gateway.api

import java.nio.charset.StandardCharsets

import io.circe.parser.decode

import kui.contracts.ErrorEnvelope

/** The frames of a relayed SSE body, read back the way a browser reads them.
  *
  * Every relay in this module promises ADR-035's terminal event, and the only honest way to assert that
  * promise is from the bytes that left the gateway: a relay is a byte mover, so a case that inspected a
  * `Stream[F, SseEvent]` would be asserting something the browser never sees. Shared between the alerts and
  * ksql relay suites so that the two read their wire the same way — the alternative is two parsers that
  * disagree about what a frame is, which is the drift ADR-035 exists to prevent.
  */
object SseFrames {

  /** One frame: its `event:` name and its joined `data:` payload. */
  final case class Frame(name: String, data: String)

  /** Every complete frame in a body. A frame is terminated by a blank line, so a trailing partial frame — a
    * body cut mid-write — is dropped rather than reported as a frame the browser would never have seen.
    */
  def parse(body: Array[Byte]): List[Frame] =
    new String(body, StandardCharsets.UTF_8)
      .split("\n\n")
      .toList
      .filter(_.nonEmpty)
      .flatMap { block =>
        val lines = block.linesIterator.map(_.stripSuffix("\r")).toList
        val name = lines.collectFirst { case line if line.startsWith("event:") => line.drop(6).trim }
        val data = lines.collect { case line if line.startsWith("data:") => line.drop(5).trim }

        name.map(Frame(_, data.mkString("\n")))
      }

  /** The envelope of the last frame, which must be an `error`.
    *
    * Fails with the frame names rather than with a decoding error when the body does not end that way: the
    * failure this is written for is a body that ends with no terminal frame at all, and "expected error, got
    * List(alerts)" is a sentence a maintainer can act on.
    */
  def terminalError(body: Array[Byte]): ErrorEnvelope = {
    val frames = parse(body)
    val last = frames.lastOption.getOrElse(
      throw new AssertionError("the relayed body carried no complete SSE frame at all")
    )

    if last.name != "error" then
      throw new AssertionError(
        s"the relayed body ends with an `${last.name}` frame and not with ADR-035's terminal `error`; " +
          s"frames were ${frames.map(_.name).mkString(", ")}"
      )

    decode[ErrorEnvelope](last.data).fold(
      failure =>
        throw new AssertionError(s"the terminal frame is not an error envelope: ${failure.getMessage}"),
      identity
    )
  }
}
