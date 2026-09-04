package kui.serde.builtin

import cats.Applicative

import kui.serde.{Serde, SerdeName}

/** The serdes every KUI deployment has, with no configuration and no Schema Registry.
  *
  * They are the eight formats of SD-001 that need nothing external, and they are what makes
  * `libs/serde-confluent` genuinely optional: a deployment with no registry at all still browses topics of
  * JSON, text, integers and UUIDs.
  */
object BuiltinSerdes {

  /** In resolution order.
    *
    * The order is the tie-break for auto-detection and the order the picker lists them in, so it is a
    * decision and not an accident:
    *
    *   - the fixed-width numeric serdes come first, because they are the ones that can be certain: four bytes
    *     that are not text are an integer, and nothing else in this list can say that much about them;
    *   - `UUID` next, for the same reason — sixteen bytes with a valid version and variant are a UUID;
    *   - `Json` before `String`, because every JSON document is also valid text, and a user looking at a JSON
    *     topic wants the table view rather than a wall of one-line strings;
    *   - `String` as the last serde that competes at all;
    *   - `Base64` and `Hex` last and never auto-detected, because they can render any payload and would
    *     otherwise win every contest by being unable to fail.
    *
    * `Fallback` is deliberately absent. It is the terminal case of resolution, not a candidate in it.
    */
  def all[F[_]: Applicative]: List[Serde[F]] = List(
    NumberSerdes.int32[F],
    NumberSerdes.int64[F],
    NumberSerdes.uint32[F],
    NumberSerdes.uint64[F],
    UuidSerde[F],
    JsonSerde[F],
    StringSerde[F],
    Base64Serde[F],
    HexSerde[F]
  )

  /** The same list, by name, for configuration validation — which has to reject an unknown serde name before
    * any serde is instantiated.
    */
  val names: List[SerdeName] = List(
    SerdeName.Int32,
    SerdeName.Int64,
    SerdeName.UInt32,
    SerdeName.UInt64,
    SerdeName.Uuid,
    SerdeName.Json,
    SerdeName.String,
    SerdeName.Base64,
    SerdeName.Hex
  )
}
