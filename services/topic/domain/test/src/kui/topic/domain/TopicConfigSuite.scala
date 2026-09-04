package kui.topic.domain

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import kui.kernel.TopicName
import kui.testkit.KuiSuite

/** The configuration entry, whose two hard rules are both about not inventing information. */
final class TopicConfigSuite extends KuiSuite {

  private def entry(
      name: String,
      value: Option[String],
      source: ConfigSource = ConfigSource.DynamicTopic,
      sensitive: Boolean = false,
      synonyms: List[ConfigSynonym] = Nil
  ): TopicConfigEntry =
    TopicConfigEntry(name, value, source, sensitive, isReadOnly = false, documentation = None, synonyms = synonyms)

  private val default: ConfigSynonym = ConfigSynonym("retention.ms", Some("604800000"), ConfigSource.Default)

  test("defaultComesFromTheDefaultSourceSynonym") {
    val overridden = entry("retention.ms", Some("86400000"), synonyms = List(default))

    assertEquals(overridden.defaultValue, Some("604800000"))
    assert(overridden.isOverridden)
  }

  test("noSynonymsMeansNoDefault") {
    assertEquals(entry("retention.ms", Some("86400000")).defaultValue, None)
  }

  test("aSensitiveEntryHasNeitherAValueNorADefault") {
    val secret = entry(
      "ssl.keystore.password",
      value = None,
      sensitive = true,
      synonyms = List(ConfigSynonym("ssl.keystore.password", Some("hunter2"), ConfigSource.Default))
    )

    assertEquals(secret.value, None)
    assertEquals(secret.defaultValue, None)
    assert(!secret.isOverridden, "'overridden' is not knowable without the value, and a bold row would be a guess")
  }

  test("aValueEqualToItsDefaultIsNotOverridden") {
    assert(!entry("retention.ms", Some("604800000"), synonyms = List(default)).isOverridden)
  }

  property("entriesAreSortedByName") {
    forAll(Gen.listOf(Gen.identifier)) { names =>
      val config = TopicConfig.of(TopicName.unsafe("orders"), names.distinct.map(n => entry(n, Some("x"))))

      assertEquals(config.entries.map(_.name), names.distinct.sorted)
    }
  }
}
