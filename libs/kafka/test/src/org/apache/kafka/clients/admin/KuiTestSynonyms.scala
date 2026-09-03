package org.apache.kafka.clients.admin

/** A factory for `ConfigEntry.ConfigSynonym`, whose constructor Kafka keeps package-private.
  *
  * It lives in Kafka's own package for that one reason. Synonyms are what tell an operator *where*
  * a broker setting came from — a static file, a dynamic override, a default — and
  * `AdminConversions` has to preserve them in order; asserting that without being able to build one
  * would mean asserting it only against a live broker, where the synonyms present depend on how the
  * container happens to be configured.
  *
  * Test sources only. Nothing shipped is in this package.
  */
object KuiTestSynonyms {

  def synonym(name: String, value: String, source: ConfigEntry.ConfigSource): ConfigEntry.ConfigSynonym =
    new ConfigEntry.ConfigSynonym(name, value, source)
}
