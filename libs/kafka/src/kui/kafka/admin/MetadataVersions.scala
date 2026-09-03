package kui.kafka.admin

/** `metadata.version` feature levels, mapped to release numbers.
  *
  * There is no Kafka API that returns "the Kafka version". On a KRaft cluster the closest thing is the
  * finalized `metadata.version` feature level, which is an integer, and turning it into a release number
  * needs a table. The reference implementation carries the same hand-maintained table
  * (`research/kafka/admin-capabilities.md` §0, "Version detection"), for the same reason.
  *
  * A level this table does not know answers `None` rather than a guess. That case is not a dead end —
  * `KafkaClusterAdmin.version` falls through to the `inter.broker.protocol.version` broker config, which a
  * broker newer than this table still answers correctly — so KUI being older than a cluster costs a log line
  * rather than a version cell.
  *
  * `metadata.version` itself only exists from Kafka 3.0: KRaft's feature mechanism arrived with it. A 2.8
  * cluster (the ADR-030 minimum) is always detected through the config fallback.
  */
object MetadataVersions {

  val table: Map[Short, KafkaVersion] = Map(
    1.toShort -> KafkaVersion(3, 0, 0), // IBP_3_0_IV1
    2.toShort -> KafkaVersion(3, 1, 0), // IBP_3_1_IV0
    3.toShort -> KafkaVersion(3, 2, 0), // IBP_3_2_IV0
    4.toShort -> KafkaVersion(3, 3, 0), // IBP_3_3_IV0
    5.toShort -> KafkaVersion(3, 3, 0), // IBP_3_3_IV1
    6.toShort -> KafkaVersion(3, 3, 0), // IBP_3_3_IV2
    7.toShort -> KafkaVersion(3, 3, 0), // IBP_3_3_IV3
    8.toShort -> KafkaVersion(3, 4, 0), // IBP_3_4_IV0
    9.toShort -> KafkaVersion(3, 5, 0), // IBP_3_5_IV0
    10.toShort -> KafkaVersion(3, 5, 0), // IBP_3_5_IV1
    11.toShort -> KafkaVersion(3, 5, 0), // IBP_3_5_IV2
    12.toShort -> KafkaVersion(3, 6, 0), // IBP_3_6_IV0
    13.toShort -> KafkaVersion(3, 6, 0), // IBP_3_6_IV1
    14.toShort -> KafkaVersion(3, 6, 0), // IBP_3_6_IV2
    15.toShort -> KafkaVersion(3, 7, 0), // IBP_3_7_IV0
    16.toShort -> KafkaVersion(3, 7, 0), // IBP_3_7_IV1
    17.toShort -> KafkaVersion(3, 7, 0), // IBP_3_7_IV2
    18.toShort -> KafkaVersion(3, 7, 0), // IBP_3_7_IV3
    19.toShort -> KafkaVersion(3, 7, 0), // IBP_3_7_IV4
    20.toShort -> KafkaVersion(3, 8, 0), // IBP_3_8_IV0
    21.toShort -> KafkaVersion(3, 9, 0), // IBP_3_9_IV0
    22.toShort -> KafkaVersion(4, 0, 0), // IBP_4_0_IV0
    23.toShort -> KafkaVersion(4, 0, 0), // IBP_4_0_IV1
    24.toShort -> KafkaVersion(4, 0, 0), // IBP_4_0_IV2
    25.toShort -> KafkaVersion(4, 0, 0) // IBP_4_0_IV3
  )

  /** The highest level the table knows, so a log line can say "level 31 is newer than this build of KUI knows
    * about" rather than reporting an old version.
    */
  val highestKnownLevel: Short = table.keys.max

  def release(featureLevel: Short): Option[KafkaVersion] = table.get(featureLevel)
}
