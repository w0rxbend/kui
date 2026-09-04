package kui.serde

/** One knob a serde exposes on the produce form.
  *
  * Kafbat's produce dialog renders these; KUI's does the same (MSG-038). `required` is separate from
  * `default` because "no default and not required" is a real combination — an optional subject override, for
  * instance — and collapsing the two would make it unexpressible.
  */
final case class SerdeParameter(
    name: String,
    description: String,
    required: Boolean,
    default: Option[String]
)

object SerdeParameter {
  given CanEqual[SerdeParameter, SerdeParameter] = CanEqual.derived
}

/** The schema a serde would use for one topic and target, in the two forms the product needs.
  *
  * @param schemaType
  *   `AVRO`, `PROTOBUF`, `JSON` — the registry's own spelling, kept so that an operator comparing KUI's
  *   screen with their registry's sees the same word.
  * @param source
  *   the schema exactly as the registry holds it, for the "view schema" panel.
  * @param jsonSchema
  *   the JSON Schema the produce form validates against (SD-004), which for an Avro or Protobuf schema is a
  *   projection and not the schema itself. `None` when no projection could be made; the form then falls back
  *   to a free editor and the serializer still validates against `source`.
  */
final case class SchemaDescription(
    schemaType: String,
    source: String,
    jsonSchema: Option[String]
)

object SchemaDescription {
  given CanEqual[SchemaDescription, SchemaDescription] = CanEqual.derived
}

/** What a serde tells the user about itself.
  *
  * `docs/operations/serdes.md` is generated from these (MSG-048) rather than written by hand, so the
  * paragraph an operator reads and the paragraph the picker shows cannot drift apart. That is why
  * `description` is a sentence and not a label.
  *
  * @param coveredByIntegrationTest
  *   whether this serde is exercised against a real broker or registry rather than only in unit tests. It is
  *   published because an operator choosing between two serdes for a production topic deserves to know which
  *   of them KUI has actually run end to end.
  */
final case class SerdeDescription(
    name: SerdeName,
    description: String,
    coveredByIntegrationTest: Boolean
)

object SerdeDescription {
  given CanEqual[SerdeDescription, SerdeDescription] = CanEqual.derived
}

/** One row of the serde picker (MS-009).
  *
  * A serde that cannot work right now — a Schema-Registry serde with an unreachable registry — appears here
  * with `available = false` and a reason, and is rendered disabled. It is not omitted: a user who configured
  * Avro and finds Avro simply missing from the list has no way to tell a configuration mistake from an outage
  * (ADR-032).
  */
final case class SerdeSuggestion(
    name: SerdeName,
    description: String,
    preferred: Boolean,
    schema: Option[SchemaDescription],
    parameters: List[SerdeParameter],
    available: Boolean,
    unavailableReason: Option[String]
)

object SerdeSuggestion {
  given CanEqual[SerdeSuggestion, SerdeSuggestion] = CanEqual.derived
}
