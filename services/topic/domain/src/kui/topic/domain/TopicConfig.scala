package kui.topic.domain

import kui.kernel.TopicName

/** Where a topic configuration value came from.
  *
  * Kafka's own `ConfigSource` enum, narrowed to the sources a *topic* resource can actually have and named
  * the way the glossary names it, so that the screen can say "default" rather than "DEFAULT_CONFIG".
  *
  * It is declared here rather than reused from `services/cluster/domain`: rule A11 forbids one service from
  * seeing another's domain at all, and a shared copy in `libs/kernel` would be a wire vocabulary the two
  * services could not evolve separately. The duplication is deliberate and is the same trade `KafkaToDomain`
  * records one service over.
  */
enum ConfigSource {

  /** Set on this topic, by an operator, with `alterConfigs`. This is what "overridden" means. */
  case DynamicTopic

  /** Set as a cluster-wide default for topics, dynamically. */
  case DynamicDefaultBroker

  /** Inherited from a broker's static configuration file. */
  case StaticBroker

  /** Kafka's own built-in default. */
  case Default

  /** A source this version of KUI does not know. Rendered as "unknown" rather than dropped, because a config
    * entry that vanishes from the tab is worse than one whose provenance is unlabelled.
    */
  case Unknown

  def token: String = this match {
    case DynamicTopic => "dynamic-topic"
    case DynamicDefaultBroker => "dynamic-default-broker"
    case StaticBroker => "static-broker"
    case Default => "default"
    case Unknown => "unknown"
  }
}

object ConfigSource {
  given CanEqual[ConfigSource, ConfigSource] = CanEqual.derived
}

/** One of the values a configuration entry would have taken had the winning one not been set.
  *
  * The synonyms are what make "this was overridden" knowable at all: Kafka does not report a default beside a
  * value, it reports the whole chain, and the default is the link in that chain whose source is
  * [[ConfigSource.Default]].
  */
final case class ConfigSynonym(name: String, value: Option[String], source: ConfigSource)

object ConfigSynonym {
  given CanEqual[ConfigSynonym, ConfigSynonym] = CanEqual.derived
}

/** One entry of a topic's configuration, as `describeConfigs` reports it.
  *
  * A sensitive entry has no value, ever. Kafka returns `null` for one and KUI never invents a replacement:
  * the screen shows that the key has a value without showing the value.
  */
final case class TopicConfigEntry(
    name: String,
    value: Option[String],
    source: ConfigSource,
    isSensitive: Boolean,
    isReadOnly: Boolean,
    documentation: Option[String],
    /** The rest of the chain. Empty is normal — plenty of clusters report no synonyms at all — and it means
      * [[defaultValue]] is unknown rather than equal to the value.
      */
    synonyms: List[ConfigSynonym]
) {

  /** Kafka's own default for this key, derived from the synonym whose source is [[ConfigSource.Default]].
    *
    * Derived, not stored, and derived from the broker rather than from a table KUI carries: a table of Kafka
    * defaults maintained here would be a table that is wrong on the next broker release, and the broker
    * already knows the answer.
    *
    * `None` for a sensitive entry. The default of a sensitive key is not itself a secret, but showing a
    * default beside a masked value invites the reader to conclude the value equals it, and absence is the
    * honest rendering.
    */
  def defaultValue: Option[String] =
    if isSensitive then None
    else synonyms.find(_.source == ConfigSource.Default).flatMap(_.value)

  /** What the screen bolds: a value that is not the default.
    *
    * Always false for a sensitive entry, because "overridden" is not knowable without the value and a bolded
    * row would be a guess presented as a fact.
    */
  def isOverridden: Boolean =
    if isSensitive then false
    else
      defaultValue match {
        case Some(default) => !value.contains(default)
        case None => source == ConfigSource.DynamicTopic
      }
}

object TopicConfigEntry {
  given Ordering[TopicConfigEntry] = Ordering.by((entry: TopicConfigEntry) => entry.name)
  given CanEqual[TopicConfigEntry, TopicConfigEntry] = CanEqual.derived
}

/** The whole configuration of one topic, in the order the Settings tab renders it.
  *
  * Sorted by name, always. The tab is a reference list somebody scans alphabetically, and a broker-dependent
  * order would make two clusters look different for no reason at all.
  */
final case class TopicConfig private (topic: TopicName, entries: List[TopicConfigEntry]) {
  def get(name: String): Option[TopicConfigEntry] = entries.find(_.name == name)
  def overridden: List[TopicConfigEntry] = entries.filter(_.isOverridden)
  def isEmpty: Boolean = entries.isEmpty
}

object TopicConfig {

  def of(topic: TopicName, entries: List[TopicConfigEntry]): TopicConfig =
    new TopicConfig(topic, entries.sorted)

  given CanEqual[TopicConfig, TopicConfig] = CanEqual.derived
}
