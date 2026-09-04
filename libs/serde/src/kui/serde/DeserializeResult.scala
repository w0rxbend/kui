package kui.serde

import io.circe.Json

/** The name a serde is known by: in configuration, in the picker, in the `serde` attribute of a metric, and
  * in the marker the browser draws on a row whose decode fell back.
  *
  * An `opaque type` over `String` so that a serde name and a topic name cannot be passed to each other's
  * parameter, and so that the spelling is validated once, here, rather than at every call site.
  *
  * The spellings themselves are Kafbat's (ADR-028). A user migrating from Kafbat has `String`, `Int64` and
  * `Fallback` written in their configuration file already, and a KUI that renamed them would fail to load a
  * file that is otherwise entirely valid.
  */
opaque type SerdeName = String

object SerdeName {

  private val Pattern: String = "^[A-Za-z][A-Za-z0-9_.-]{0,63}$"

  /** The terminal case of resolution: the serde that cannot fail. Named as Kafbat names it. */
  val Fallback: SerdeName = "Fallback"

  val String: SerdeName = "String"
  val Int32: SerdeName = "Int32"
  val Int64: SerdeName = "Int64"
  val UInt32: SerdeName = "UInt32"
  val UInt64: SerdeName = "UInt64"
  val Uuid: SerdeName = "UUID"
  val Base64: SerdeName = "Base64"
  val Hex: SerdeName = "Hex"
  val Json: SerdeName = "Json"

  /** The Schema-Registry-backed serde of `libs/serde-confluent`.
    *
    * The constant lives here, in the module that has no Confluent dependency, because configuration
    * validation and the resolution table both need to talk about the serde by name in a deployment where the
    * Confluent module is not on the classpath at all (ADR-014).
    */
  val SchemaRegistry: SerdeName = "SchemaRegistry"

  def fromString(raw: Predef.String): Either[Predef.String, SerdeName] =
    if raw.matches(Pattern) then Right(raw)
    else
      Left(
        s"'$raw' is not a serde name: 1 to 64 characters, starting with a letter, then letters, " +
          "digits, '_', '.' or '-'"
      )

  /** Wraps a name that is already known to be well formed — a literal in this file or in a test. */
  def unsafe(raw: Predef.String): SerdeName = raw

  extension (name: SerdeName) def value: Predef.String = name

  given Ordering[SerdeName] = Ordering.String
  given CanEqual[SerdeName, SerdeName] = CanEqual.derived
}

/** Which half of a record a serde is being asked about. */
enum Target {
  case Key, Value

  /** The lowercase spelling used in metric attributes and in the Schema Registry subject suffix. */
  def label: String = this match {
    case Key => "key"
    case Value => "value"
  }
}

object Target {
  given CanEqual[Target, Target] = CanEqual.derived
}

/** Which direction a user is asking about: reading records, or writing one.
  *
  * The picker asks for one or the other (`GET .../serdes?use=DESERIALIZE`), because the sets differ: a
  * Schema-Registry serde can read a topic whose subject exists and cannot write to a topic whose subject does
  * not.
  */
enum SerdeUse {
  case Deserialize, Serialize
}

object SerdeUse {
  given CanEqual[SerdeUse, SerdeUse] = CanEqual.derived
}

/** What the browser needs to know to render a payload.
  *
  * `Json` is what makes the table view's flattener applicable; `Text` is everything else. It travels on the
  * wire (ADR-035) precisely so that the browser does not have to re-detect it by trying to parse every cell.
  */
enum PayloadKind {
  case Text, Json
}

object PayloadKind {
  given CanEqual[PayloadKind, PayloadKind] = CanEqual.derived
}

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
