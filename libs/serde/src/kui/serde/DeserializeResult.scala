package kui.serde

import io.circe.Json

/** One header exactly as Kafka carries it: a name and bytes that may be absent.
  *
  * `value` is an `Option` because a Kafka header value is genuinely nullable, and a serde that has to
  * distinguish "the header was set to nothing" from "the header was not there" is a serde that would
  * otherwise have to invent a sentinel.
  *
  * **Seam note.** `libs/kafka` declares a structurally identical `kui.kafka.consume.RawHeader` for the
  * records it polls. The two cannot be one type today: `libs/serde` must not depend on `libs/kafka` (a serde
  * knows nothing about a consumer), `libs/kafka` must not depend on `libs/serde` (decoding happens above the
  * port), and `libs/kernel` deliberately holds no record shapes so that the browser build does not carry
  * them. The one module that sees both — a service's `infrastructure` layer — converts, and that conversion
  * is two lines. If a third consumer appears, the right fix is a single home in `libs/kernel`, not a third
  * copy.
  */
final case class RawHeader(key: String, value: Option[Array[Byte]])

object RawHeader {

  /** Structural equality on the bytes, which the generated `equals` of a case class holding an `Array` does
    * not give: `Array` compares by reference. Tests compare headers constantly, and a reference comparison
    * would make them pass or fail by accident.
    */
  given CanEqual[RawHeader, RawHeader] = CanEqual.derived

  extension (header: RawHeader) {
    def sameAs(other: RawHeader): Boolean =
      header.key == other.key && header.value.map(_.toSeq) == other.value.map(_.toSeq)
  }
}

/** A payload, decoded.
  *
  * @param text
  *   what the user reads. Always a `String`: every rendering in KUI, including the table view's, starts from
  *   text, and a decoded value that were sometimes a number and sometimes a string would need a second
  *   representation on the wire for no gain.
  * @param kind
  *   whether `text` is JSON. Decided by the serde that produced it, not guessed downstream.
  * @param properties
  *   whatever else the serde knows and the browser can use: for the registry serdes, `{type, id, subjects}`,
  *   which is what lets a record link to its subject. Empty for every built-in.
  */
final case class DeserializeResult(text: String, kind: PayloadKind, properties: Map[String, Json])

object DeserializeResult {

  /** The overwhelmingly common shape: some text, nothing else known about it. */
  def text(value: String): DeserializeResult = DeserializeResult(value, PayloadKind.Text, Map.empty)

  def json(value: String): DeserializeResult = DeserializeResult(value, PayloadKind.Json, Map.empty)

  given CanEqual[DeserializeResult, DeserializeResult] = CanEqual.derived
}

/** Why one record's intended decoder did not work.
  *
  * It is a value and not an exception because it is not a failure of the browse: the record is still shown,
  * through the fallback, with this attached (ADR-035). A stream that ended here would hide the one record the
  * user opened the screen to find.
  *
  * `cause` is display text and follows `KuiError`'s rule: no stack trace, no upstream response body, no
  * credential. A decoder handed a registry URL with a password in it must not put it here.
  */
final case class DeserializeFailure(serde: SerdeName, cause: String)

object DeserializeFailure {
  given CanEqual[DeserializeFailure, DeserializeFailure] = CanEqual.derived
}

/** Why what the user typed could not become bytes.
  *
  * Unlike a decode failure this one *is* terminal for its request: producing a record KUI could not encode
  * would put bytes in a topic that outlive the mistake. `cause` names the offending field where the serde can
  * tell (ADR-034), because "invalid payload" sends a user back to guess.
  */
final case class SerializeFailure(serde: SerdeName, cause: String)

object SerializeFailure {
  given CanEqual[SerializeFailure, SerializeFailure] = CanEqual.derived
}
