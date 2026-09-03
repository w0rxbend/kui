package kui.cluster.domain

/** Where a broker configuration value came from.
  *
  * Kafka's own `ConfigSource` enum, named the way the glossary names it so that the UI can say "default"
  * rather than "DEFAULT_CONFIG".
  */
enum ConfigSource {
  case DynamicBroker, DynamicDefaultBroker, StaticBroker, DynamicTopic, Default, Unknown

  /** The stable wire token, and the label the UI shows. */
  def token: String = this match {
    case DynamicBroker => "dynamic-broker"
    case DynamicDefaultBroker => "dynamic-default-broker"
    case StaticBroker => "static-broker"
    case DynamicTopic => "dynamic-topic"
    case Default => "default"
    case Unknown => "unknown"
  }

  /** True for the two sources an operator set at runtime, which is what the broker page groups first. */
  def isDynamic: Boolean = this match {
    case DynamicBroker | DynamicDefaultBroker | DynamicTopic => true
    case StaticBroker | Default | Unknown => false
  }
}

object ConfigSource {
  given CanEqual[ConfigSource, ConfigSource] = CanEqual.derived
}

/** One of the values a configuration entry would have taken had the winning one not been set. */
final case class ConfigSynonym(name: String, value: Option[String], source: ConfigSource)

object ConfigSynonym {
  given CanEqual[ConfigSynonym, ConfigSynonym] = CanEqual.derived
}

/** One entry of a broker's configuration, as `describeConfigs` reports it.
  *
  * `value` is `Option` and **is `None` for a sensitive entry**: Kafka returns `null` for a sensitive value
  * and KUI never invents one. `documentation` is `Option` because asking for it needs a 2.6 broker, and a
  * cluster that cannot answer must render an entry without documentation rather than no entry at all.
  */
final case class ConfigEntry(
    name: String,
    value: Option[String],
    source: ConfigSource,
    isSensitive: Boolean,
    isReadOnly: Boolean,
    isDefault: Boolean,
    documentation: Option[String],
    synonyms: List[ConfigSynonym]
)

object ConfigEntry {
  given Ordering[ConfigEntry] = Ordering.by(_.name)
  given CanEqual[ConfigEntry, ConfigEntry] = CanEqual.derived
}
