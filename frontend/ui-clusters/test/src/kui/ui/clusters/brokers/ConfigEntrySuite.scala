package kui.ui.clusters.brokers

import munit.FunSuite

import kui.contracts.cluster.BrokerConfigEntryDto

class ConfigEntrySuite extends FunSuite {

  private def dto(
      name: String,
      value: Option[String] = Some("x"),
      source: String = "STATIC_BROKER_CONFIG",
      sensitive: Boolean = false,
      readOnly: Boolean = false
  ): BrokerConfigEntryDto =
    BrokerConfigEntryDto(name, value, source, sensitive, readOnly, documentation = None, synonyms = Nil)

  test("sensitiveBeatsEveryOtherRule") {
    // The precedence most likely to be implemented in the wrong order: a sensitive key that happens to end
    // in `.ms` must not be formatted as a duration from a value that is not there.
    assertEquals(ConfigEntry.valueOf("ssl.key.password.ms", None, sensitive = true), ConfigValue.Redacted)
    assertEquals(ConfigEntry.valueOf("sasl.jaas.config", Some("hidden"), sensitive = true), ConfigValue.Redacted)
  }

  test("bytesAndMillisecondsAreFormattedFromTheKeySuffix") {
    assertEquals(
      ConfigEntry.valueOf("log.segment.bytes", Some("1073741824"), sensitive = false),
      ConfigValue.Bytes("1073741824", "1.0 GiB")
    )
    assertEquals(
      ConfigEntry.valueOf("log.retention.ms", Some("604800000"), sensitive = false),
      ConfigValue.Duration("604800000", "7 d")
    )
  }

  test("aSuffixOnANonNumericValueFallsThroughToVerbatim") {
    assertEquals(ConfigEntry.valueOf("log.retention.ms", Some("none"), sensitive = false), ConfigValue.Plain("none"))
  }

  test("anEmptyValueIsNotTheSameAsAMissingOne") {
    // Both render the missing marker, and only one of them says "set to the empty string" — but they are
    // the same case as far as the browser can tell, so the rule is written down here rather than guessed at.
    assertEquals(ConfigEntry.valueOf("advertised.listeners", Some(""), sensitive = false), ConfigValue.Empty)
    assertEquals(ConfigEntry.valueOf("advertised.listeners", None, sensitive = false), ConfigValue.Empty)
  }

  test("unknownSourcesOrderLastAndKeepTheirRawName") {
    // Kafka adds configuration sources between versions; a new one must degrade to "we have no name for
    // this" rather than to a blank cell or a decode failure.
    val entries = ConfigEntry.of(List(dto("a", source = "SOMETHING_NEW"), dto("b", source = "DEFAULT_CONFIG")))
    assertEquals(entries.map(_.name), List("b", "a"))
    assertEquals(entries.last.source, ConfigSource.Unknown("SOMETHING_NEW"))
    assertEquals(entries.last.source.label, "Unknown")
  }

  test("sourceNamesAreMatchedWhateverTheirPunctuation") {
    assertEquals(ConfigSource.fromWire("DYNAMIC_BROKER_CONFIG"), ConfigSource.DynamicBroker)
    assertEquals(ConfigSource.fromWire("dynamic broker config"), ConfigSource.DynamicBroker)
    assertEquals(ConfigSource.fromWire("dynamic-broker-config"), ConfigSource.DynamicBroker)
  }

  test("entriesAreOrderedBySourceThenKey") {
    val entries = ConfigEntry.of(
      List(
        dto("z.default", source = "DEFAULT_CONFIG"),
        dto("b.static", source = "STATIC_BROKER_CONFIG"),
        dto("a.default", source = "DEFAULT_CONFIG"),
        dto("y.dynamic", source = "DYNAMIC_BROKER_CONFIG")
      )
    )
    // What somebody changed is at the top, which is what the operator opening this tab came to see.
    assertEquals(entries.map(_.name), List("y.dynamic", "b.static", "a.default", "z.default"))
  }

  test("searchMatchesKeyAndValueCaseInsensitively") {
    val entry = ConfigEntry.of(List(dto("log.retention.hours", Some("168")))).head
    assert(ConfigEntry.matches(entry, "RETENTION"))
    assert(ConfigEntry.matches(entry, "168"))
    assert(ConfigEntry.matches(entry, ""))
    assert(!ConfigEntry.matches(entry, "compression"))
  }

  test("searchNeverMatchesARedactedValue") {
    // Matching on the mask characters would be a lie, and matching on the real value is impossible — the
    // browser does not have one, which is the entire point of the redaction.
    val entry = ConfigEntry.of(List(dto("ssl.key.password", Some("hunter2"), sensitive = true))).head
    assert(!ConfigEntry.matches(entry, "hunter2"))
    assert(!ConfigEntry.matches(entry, "•"))
    assert(ConfigEntry.matches(entry, "ssl.key"))
  }
}
