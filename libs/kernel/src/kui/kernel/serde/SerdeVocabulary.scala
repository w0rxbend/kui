/** The vocabulary a serde is talked about in: its name, which half of a record it is reading, which direction
  * a user is asking about, and what came out.
  *
  * These four types live in `libs/kernel` rather than beside the serdes themselves because four layers name
  * them and only one of those four may see `libs/serde`. The message service's `domain` states its ports in
  * terms of a serde name and may see nothing but this module (rule A1); the wire contract puts a serde name
  * on every decoded payload; the browser draws a marker on a row whose decode fell back and builds the serde
  * picker; and `libs/serde` itself implements them. Declared four times they would drift, and the drift would
  * show up as a picker offering a serde the service does not have.
  *
  * `libs/serde` re-exports every one of them, so code there refers to `kui.serde.SerdeName` exactly as it did
  * before this move (M3, MSG-017; the same decision M1 took for the cluster vocabulary).
  */
package kui.kernel.serde

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
