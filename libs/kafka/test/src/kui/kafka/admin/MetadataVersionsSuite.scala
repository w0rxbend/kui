package kui.kafka.admin

import kui.testkit.KuiSuite

/** The hand-maintained level table.
  *
  * `tableIsMonotonic` is the one that earns its keep: it is the property a mistyped row breaks, and
  * a mistyped row would otherwise show an operator a Kafka version their cluster is not running.
  */
final class MetadataVersionsSuite extends KuiSuite {

  test("tableSpansTheKraftReleasesUpToThePinnedClient") {
    assertEquals(MetadataVersions.release(1), Some(KafkaVersion(3, 0, 0)))
    assertEquals(MetadataVersions.release(21), Some(KafkaVersion(3, 9, 0)))
    assertEquals(MetadataVersions.release(25), Some(KafkaVersion(4, 0, 0)))
  }

  test("tableIsMonotonic") {
    // A higher feature level never maps to a lower release. Kafka's own levels are assigned in
    // release order, so any violation here is a typing mistake rather than a fact about Kafka.
    val ordered = MetadataVersions.table.toList.sortBy(_._1)

    ordered.zip(ordered.drop(1)).foreach { case ((lowLevel, low), (highLevel, high)) =>
      assert(low <= high, s"level $lowLevel maps to $low but level $highLevel maps to $high")
    }
  }

  test("theTableStartsAtKafkaThreeZero") {
    // `metadata.version` arrived with KRaft's feature mechanism in 3.0. A 2.8 cluster — the ADR-030
    // minimum — is always detected through the `inter.broker.protocol.version` fallback, and
    // claiming a level-to-2.8 row here would be inventing one.
    assertEquals(MetadataVersions.table.values.min, KafkaVersion(3, 0, 0))
  }

  test("unknownLevelIsNoneNotAGuess") {
    assertEquals(MetadataVersions.release(0), None)
    assertEquals(MetadataVersions.release((MetadataVersions.highestKnownLevel + 1).toShort), None)
    assertEquals(MetadataVersions.release(-1), None)
  }

  test("highestKnownLevelMatchesTheTable") {
    assertEquals(MetadataVersions.highestKnownLevel, MetadataVersions.table.keys.max)
    assert(MetadataVersions.release(MetadataVersions.highestKnownLevel).isDefined)
  }

  test("everyLevelIsPositiveAndContiguous") {
    // A gap in the middle would mean a real cluster whose level this table cannot resolve, which
    // costs a fallback round trip on every version detection.
    val levels = MetadataVersions.table.keys.toList.sorted

    assertEquals(levels, (levels.min.toInt to levels.max.toInt).map(_.toShort).toList)
  }
}
