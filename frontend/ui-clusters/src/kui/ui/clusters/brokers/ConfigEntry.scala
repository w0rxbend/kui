package kui.ui.clusters.brokers

import kui.contracts.cluster.BrokerConfigEntryDto
import kui.ui.clusters.component.{Bytes, Durations}

/** Where a broker setting's value came from.
  *
  * The order is the point of the type. An operator opening this tab is almost always asking "what did
  * somebody change", so what somebody changed sorts to the top and the defaults sort to the bottom.
  *
  * `Unknown` exists because Kafka adds configuration sources between versions. A source string this build has
  * never heard of has to render as "we have no name for this", with the raw string kept, rather than as a
  * blank cell or a failure to decode the response.
  */
enum ConfigSource(val label: String, val order: Int) {
  case DynamicBroker extends ConfigSource("Dynamic broker config", 1)
  case DynamicDefaultBroker extends ConfigSource("Dynamic default broker config", 2)
  case DynamicBrokerLogger extends ConfigSource("Dynamic broker logger config", 3)
  case StaticBroker extends ConfigSource("Static broker config", 4)
  case Default extends ConfigSource("Default config", 5)
  case Unknown(raw: String) extends ConfigSource("Unknown", 6)
}

object ConfigSource {

  given CanEqual[ConfigSource, ConfigSource] = CanEqual.derived

  /** Total over any string Kafka might send.
    *
    * Matched case-insensitively and without punctuation, because the same source has been spelled
    * `DYNAMIC_BROKER_CONFIG` and `dynamic broker config` by different producers of this field, and a screen
    * that showed "Unknown" for one of them would be reporting a version difference as a problem.
    */
  def fromWire(raw: String): ConfigSource =
    raw.toLowerCase.replace('-', '_').replace(' ', '_') match {
      case "dynamic_broker_config" => DynamicBroker
      case "dynamic_default_broker_config" => DynamicDefaultBroker
      case "dynamic_broker_logger_config" => DynamicBrokerLogger
      case "static_broker_config" => StaticBroker
      case "default_config" => Default
      case _ => Unknown(raw)
    }
}

/** How one setting's value is to be drawn.
  *
  * Decided once, as data, so that the precedence between the rules is a test rather than the order of a chain
  * of `if`s inside a rendering function.
  */
enum ConfigValue {

  /** The server refused to send it. KUI never receives the value at all. */
  case Redacted

  case Bytes(raw: String, formatted: String)
  case Duration(raw: String, formatted: String)

  /** Set, deliberately, to the empty string — which is not the same as unset and must not look the same. */
  case Empty

  case Plain(text: String)
}

object ConfigValue {
  given CanEqual[ConfigValue, ConfigValue] = CanEqual.derived
}

/** One configuration entry, reduced to what the table draws. */
final case class ConfigEntry(
    name: String,
    value: ConfigValue,
    source: ConfigSource,
    readOnly: Boolean,
    documentation: Option[String]
)

object ConfigEntry {

  given CanEqual[ConfigEntry, ConfigEntry] = CanEqual.derived

  /** Every entry, ordered by source and then by key. */
  def of(entries: List[BrokerConfigEntryDto]): List[ConfigEntry] =
    entries
      .map(dto =>
        ConfigEntry(
          name = dto.name,
          value = valueOf(dto.name, dto.value, dto.isSensitive),
          source = ConfigSource.fromWire(dto.source),
          readOnly = dto.isReadOnly,
          documentation = dto.documentation
        )
      )
      .sortBy(entry => (entry.source.order, entry.name))

  /** The rendering rule, in precedence order.
    *
    * Sensitivity comes first and is the reason the order is written down: a sensitive key that happens to end
    * in `.ms` must render as redacted, not as a duration formatted from a value that is not there.
    */
  def valueOf(name: String, raw: Option[String], sensitive: Boolean): ConfigValue =
    if sensitive then ConfigValue.Redacted
    else
      raw match {
        case None => ConfigValue.Empty
        case Some(text) if text.isEmpty => ConfigValue.Empty
        case Some(text) if name.endsWith(".bytes") =>
          text.trim.toLongOption
            .map(bytes => ConfigValue.Bytes(text, Bytes.format(Some(bytes))))
            .getOrElse(ConfigValue.Plain(text))
        case Some(text) if name.endsWith(".ms") =>
          Durations.fromMillis(text).map(ConfigValue.Duration(text, _)).getOrElse(ConfigValue.Plain(text))
        case Some(text) => ConfigValue.Plain(text)
      }

  /** Case-insensitive match on key or value.
    *
    * A redacted entry matches on its key alone. Matching it on its displayed value would mean typing the mask
    * characters found it, and matching it on the real value is impossible — the browser does not have one,
    * which is the whole point.
    */
  def matches(entry: ConfigEntry, term: String): Boolean = {
    val needle = term.trim.toLowerCase
    needle.isEmpty || entry.name.toLowerCase.contains(needle) || searchableValue(entry).contains(needle)
  }

  private def searchableValue(entry: ConfigEntry): String =
    entry.value match {
      case ConfigValue.Redacted => ""
      case ConfigValue.Empty => ""
      case ConfigValue.Bytes(raw, _) => raw.toLowerCase
      case ConfigValue.Duration(raw, _) => raw.toLowerCase
      case ConfigValue.Plain(text) => text.toLowerCase
    }
}
