package kui.contracts.topic

import io.circe.syntax.*
import io.circe.{Codec, HCursor, Json}
import sttp.tapir.Schema

/** One key on a topic's settings tab.
  *
  * @param value
  *   what the key is set to, or `None` when the broker refused to disclose it. A **sensitive** entry always
  *   has `None` here: the value never reaches the wire at all, rather than reaching it masked, because a mask
  *   on the wire is a value a proxy has logged
  * @param defaultValue
  *   what the key would be without this topic's override, when the broker reports it
  * @param source
  *   where the value came from — `"dynamic_topic_config"`, `"default_config"`, `"static_broker_config"` and
  *   so on. A `String`, deliberately, and not an enum: a broker can name a source KUI's enum does not have,
  *   and a decode failure on an unknown config source would fail a whole settings page over a cosmetic field.
  *   The service maps its own enum to a stable lowercase spelling
  * @param readOnly
  *   whether the broker says this key cannot be changed. It drives whether M5 offers an edit control; in M2
  *   nothing can be edited at all, so it is displayed and no more
  * @param documentation
  *   the broker's own description of the key, when it supplies one
  */
final case class TopicConfigEntryDto(
    name: String,
    value: Option[String],
    defaultValue: Option[String],
    source: String,
    sensitive: Boolean,
    readOnly: Boolean,
    documentation: Option[String]
) {

  /** Whether this key has been overridden away from its default, which is what the settings tab emphasises.
    *
    * Derived rather than sent, for the same reason `PageInfo.pageCount` is: a producer's separately computed
    * "is overridden" flag can disagree with the two values it is supposed to summarise.
    */
  def overridden: Boolean = defaultValue.isEmpty || value != defaultValue
}

object TopicConfigEntryDto {

  given Codec[TopicConfigEntryDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        name <- cursor.get[String]("name")
        value <- cursor.get[Option[String]]("value")
        defaultValue <- cursor.get[Option[String]]("defaultValue")
        source <- cursor.get[String]("source")
        sensitive <- cursor.getOrElse[Boolean]("sensitive")(false)
        readOnly <- cursor.getOrElse[Boolean]("readOnly")(false)
        documentation <- cursor.get[Option[String]]("documentation")
      } yield TopicConfigEntryDto(name, value, defaultValue, source, sensitive, readOnly, documentation),
    (dto: TopicConfigEntryDto) =>
      Json.obj(
        "name" -> dto.name.asJson,
        // A sensitive entry's value never reaches the wire, whatever the producer put in the field.
        // Enforcing it in the encoder rather than at the call sites means one place to read and one
        // place to get right, and `TopicDtosSuite` asserts it on the JSON text.
        "value" -> (if dto.sensitive then Json.Null else dto.value.asJson),
        "defaultValue" -> dto.defaultValue.asJson,
        "source" -> dto.source.asJson,
        "sensitive" -> dto.sensitive.asJson,
        "readOnly" -> dto.readOnly.asJson,
        "documentation" -> dto.documentation.asJson
      )
  )

  given Schema[TopicConfigEntryDto] = Schema
    .derived[TopicConfigEntryDto]
    .description("One topic configuration key; a sensitive entry carries no value")

  given CanEqual[TopicConfigEntryDto, TopicConfigEntryDto] = CanEqual.derived
}
