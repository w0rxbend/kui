package kui.message.domain.ports

import kui.kernel.error.KuiError
import kui.kernel.serde.{SerdeName, SerdeUse, Target}
import kui.kernel.{ClusterId, TopicName}
import kui.message.domain.Decoded

/** One row of the serde picker, and the answer to "which serde will be used if I say nothing?".
  *
  * An unavailable serde — a Schema-Registry one whose registry is unreachable — appears here with
  * `available = false` and a reason, and the UI renders it disabled. It is never omitted: a user who
  * configured Avro and finds Avro simply missing from the list cannot tell a configuration mistake from an
  * outage (ADR-032).
  */
final case class SerdeChoice(
    name: SerdeName,
    description: String,
    preferred: Boolean,
    available: Boolean,
    unavailableReason: Option[String]
)

object SerdeChoice {
  given CanEqual[SerdeChoice, SerdeChoice] = CanEqual.derived
}

/** Turning a record's bytes into something a person can read, and back.
  *
  * The domain states this as a port rather than naming `libs/serde` because rule A1 confines this module to
  * `libs/kernel`, and because the milestone's central decoding rule is a *domain* rule, not a library detail:
  * **decoding never fails a browse**. [[decode]] therefore returns a [[Decoded]] and a reason, not an error —
  * a record no configured serde can read is still shown, through the fallback, with the failure attached.
  *
  * [[serialize]] is the opposite and says so in its type: producing bytes KUI could not encode would put
  * records in a topic that outlive the mistake, so a serialisation failure is terminal for its request.
  */
trait SerdeSource[F[_]] {

  /** Decodes one payload. `bytes` is `None` for a record with no key, or for a tombstone's absent value.
    *
    * `requested` is what the user picked in the picker; `None` means "resolve it the usual way" — topic
    * pattern, then cluster default, then auto-detection, then the fallback (ADR-028). The [[Decoded]] that
    * comes back always names the serde that actually produced it, which is what the browser draws the
    * fallback marker from.
    */
  def decode(
      cluster: ClusterId,
      topic: TopicName,
      target: Target,
      requested: Option[SerdeName],
      bytes: Option[Array[Byte]]
  ): F[(Decoded, Option[String])]

  /** Turns what a user typed into bytes. `Left` is a validation failure naming the field, not a 500. */
  def serialize(
      cluster: ClusterId,
      topic: TopicName,
      target: Target,
      requested: Option[SerdeName],
      properties: Map[String, String],
      text: Option[String]
  ): F[Either[KuiError, Option[Array[Byte]]]]

  /** The picker's list, with exactly one entry marked `preferred`.
    *
    * The preferred entry is derived from the same resolution [[decode]] uses rather than computed separately,
    * because two resolutions would let a user produce with a serde they cannot read back.
    */
  def choices(
      cluster: ClusterId,
      topic: TopicName,
      target: Target,
      use: SerdeUse
  ): F[Either[KuiError, List[SerdeChoice]]]
}
