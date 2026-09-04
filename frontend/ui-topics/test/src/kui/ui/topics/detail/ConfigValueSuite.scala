package kui.ui.topics.detail

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** The unit hints, which are the part of the settings tab that can be confidently wrong.
  *
  * A hint is an aid to recognising a number at a glance. A wrong one is worse than none, because it is read
  * *instead of* the number rather than alongside it, so every property here is about the hint refusing rather
  * than about it appearing.
  */
final class ConfigValueSuite extends ScalaCheckSuite {

  test("msKeysGetADurationHint") {
    assertEquals(ConfigValue.hint("retention.ms", "604800000"), Some("7 days"))
    assertEquals(ConfigValue.hint("segment.ms", "3600000"), Some("1 hour"))
    assertEquals(ConfigValue.hint("flush.ms", "1000"), Some("1 second"))
    assertEquals(ConfigValue.hint("linger.ms", "250"), Some("250 milliseconds"))
  }

  test("secondsKeysAreScaledBeforeBeingRead") {
    assertEquals(ConfigValue.hint("retention.seconds", "604800"), Some("7 days"))
  }

  test("bytesKeysGetASizeHint") {
    // Binary units, because every Kafka size setting is a power of two and an operator comparing this
    // against a number they typed needs the two to agree. 1 GB and 1 GiB differ by seven percent.
    assertEquals(ConfigValue.hint("segment.bytes", "1073741824"), Some("1.0 GiB"))
    assertEquals(ConfigValue.hint("max.message.bytes", "1048576"), Some("1.0 MiB"))
  }

  test("negativeAndZeroValuesGetNoHint") {
    // `-1` in Kafka means "no limit". "-1 milliseconds" looks like an interval and reads as nonsense.
    assertEquals(ConfigValue.hint("retention.ms", "-1"), None)
    assertEquals(ConfigValue.hint("retention.bytes", "-1"), None)
    assertEquals(ConfigValue.hint("retention.ms", "0"), None)
  }

  test("anUnknownKeyGetsNoHint") {
    assertEquals(ConfigValue.hint("cleanup.policy", "delete"), None)
    assertEquals(ConfigValue.hint("min.insync.replicas", "2"), None)
    // A suffix that only looks like one of ours.
    assertEquals(ConfigValue.hint("compression.type", "1000"), None)
  }

  test("aValueThatIsNotANumberGetsNoHint") {
    // A broker may name a policy this version of KUI has never heard of, and a cosmetic field must not be
    // able to fail a page.
    assertEquals(ConfigValue.hint("retention.ms", "forever"), None)
    assertEquals(ConfigValue.hint("segment.bytes", ""), None)
    assertEquals(ConfigValue.hint("segment.bytes", "9999999999999999999999"), None)
  }

  test("theMaskIsFixedWidthAndSaysWhatItIs") {
    // One bullet per character would tell a reader how long the secret is — and the server did not send the
    // value at all, so its length is not even known here.
    assertEquals(ConfigValue.masked.length, 6)
    assert(ConfigValue.maskedLabel.contains("sensitive"), ConfigValue.maskedLabel)
  }

  private val suffixes = Gen.oneOf(".ms", ".seconds", ".bytes")

  property("aHintNeverAppearsForANonPositiveNumber") {
    forAll(Gen.alphaLowerStr.suchThat(_.nonEmpty), suffixes, Gen.choose(-1_000_000L, 0L)) {
      (name, suffix, value) =>
        ConfigValue.hint(s"$name$suffix", value.toString).isEmpty
    }
  }

  property("aHintAlwaysAppearsForAPositiveNumberOnAKnownSuffix") {
    forAll(Gen.alphaLowerStr.suchThat(_.nonEmpty), suffixes, Gen.choose(1L, 1_000_000_000L)) {
      (name, suffix, value) =>
        ConfigValue.hint(s"$name$suffix", value.toString).isDefined
    }
  }

  property("aHintIsOneUnitAndNotAnAccumulation") {
    // "7 days", not "6 days 23 hours 60 minutes". A hint that has to be read carefully has failed at the
    // one thing it is for.
    forAll(Gen.choose(1L, 1_000_000_000_000L)) { millis =>
      ConfigValue.hint("retention.ms", millis.toString).forall(_.count(_ == ' ') == 1)
    }
  }
}
