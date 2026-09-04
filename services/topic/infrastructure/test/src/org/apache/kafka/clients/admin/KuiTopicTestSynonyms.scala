package org.apache.kafka.clients.admin

/** A factory for `ConfigEntry.ConfigSynonym`, whose constructor Kafka keeps package-private.
  *
  * It lives in Kafka's own package for that one reason, and it is a second copy of
  * `libs/kafka`'s `KuiTestSynonyms` because a test module cannot see another module's test
  * sources. Both are four lines and both exist for the same reason: the synonyms are what say
  * *where* a setting came from, `TopicConfigEntry.defaultValue` is derived from them, and a
  * conversion that dropped them would make every setting on the Settings tab look overridden.
  * Asserting that without being able to build a synonym would mean asserting it only against a
  * live broker, where which synonyms are present depends on how the container is configured.
  *
  * Test sources only. Nothing shipped is in this package.
  */
object KuiTopicTestSynonyms {

  def synonym(name: String, value: String, source: ConfigEntry.ConfigSource): ConfigEntry.ConfigSynonym =
    new ConfigEntry.ConfigSynonym(name, value, source)
}
