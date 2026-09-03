package kui.config.store

import org.scalacheck.{Gen, Prop}

import kui.config.StoreConfig
import kui.kernel.PositiveInt
import kui.testkit.KuiSuite

/** That the topics KUI creates are the ones the operator page documents, and that the required settings
  * are exactly the ones somebody decided were required.
  *
  * The golden list of required settings is the load-bearing assertion. Adding a setting to
  * `docs/operations/metadata-store.md` without deciding whether KUI refuses to differ on it is the mistake
  * this suite exists to catch, because the consequence of getting it wrong — refusing to start on somebody's
  * reasonable retention choice, or starting happily with compaction turned off — is invisible until it is
  * expensive.
  */
final class StoreTopicsSuite extends KuiSuite {

  private def config(prefix: String = "__kui_", isr: Int = 2, maxFile: Long = 4194304L): StoreConfig =
    StoreConfig.Default.copy(
      topicPrefix = prefix,
      minInSyncReplicas = PositiveInt.unsafe(isr),
      maxFileBytes = maxFile
    )

  test("namesDeriveFromThePrefix") {
    val topics = StoreTopics.of(config(prefix = "acme_"))
    assertEquals(topics.config.name, "acme_config")
    assertEquals(topics.files.name, "acme_files")
    assertEquals(topics.audit.name, "acme_audit")
  }

  test("managedNowExcludesAudit") {
    // DEVPLAN §10 D7: M1 creates and validates __kui_config and __kui_files only. Creating a
    // retention-based topic that nothing produces to would leave an operator wondering why it is empty,
    // and would fix its retention settings before the feature that needs them exists. M5 flips the
    // audit topic's `createdBy` and this list grows by one, with no new mechanism.
    val topics = StoreTopics.of(config())
    assertEquals(topics.managedNow.map(_.name), List("__kui_config", "__kui_files"))
    assertEquals(topics.audit.createdBy, CreatedBy.M5)
  }

  test("configAndFilesAreSinglePartition") {
    // More than one partition destroys the total order the whole concurrency design rests on: two
    // records for one key could land in different partitions and be replayed in either order.
    StoreTopics.of(config()).managedNow.foreach(topic => assertEquals(topic.partitions, 1, clue = topic.name))
  }

  test("requiredSettingsAreExactlyTheDocumentedSet") {
    val topics = StoreTopics.of(config())
    assertEquals(topics.config.required.keySet, Set("cleanup.policy", "min.insync.replicas"))
    assertEquals(topics.files.required.keySet, Set("cleanup.policy", "min.insync.replicas", "max.message.bytes"))
    assertEquals(topics.config.required("cleanup.policy"), "compact")
    assertEquals(
      topics.config.advisory.keySet,
      Set("retention.ms", "delete.retention.ms", "min.compaction.lag.ms", "segment.ms", "min.cleanable.dirty.ratio")
    )
  }

  property("filesMaxMessageBytesExceedsMaxFileBytes") {
    Prop.forAll(Gen.choose(StoreConfig.MinFileBytes, StoreConfig.MaxFileBytes)) { maxFile =>
      val limit = StoreTopics.of(config(maxFile = maxFile)).files.required("max.message.bytes").toLong
      limit >= maxFile + StoreTopics.FileOverheadBytes
    }
  }

  test("aLargerBrokerLimitSatisfiesMaxMessageBytes") {
    // Validated as "at least", not "equal": an operator whose broker allows larger messages than KUI
    // needs has a topic that works, and refusing to start would be pedantry with a cost.
    assert(StoreTopics.satisfies("max.message.bytes", "5242880", "10485760"))
    assert(!StoreTopics.satisfies("max.message.bytes", "5242880", "1048576"))
    assert(!StoreTopics.satisfies("cleanup.policy", "compact", "delete"))
    assert(StoreTopics.satisfies("cleanup.policy", "compact", "compact"))
  }

  test("minInsyncReplicasFollowsTheConfiguredValue") {
    val topics = StoreTopics.of(config(isr = 5))
    topics.managedNow.foreach(topic => assertEquals(topic.required("min.insync.replicas"), "5", clue = topic.name))
  }

  test("creationConfigCarriesRequiredAndAdvisoryTogether") {
    val files = StoreTopics.of(config()).files
    assertEquals(files.creationConfig.keySet, files.required.keySet ++ files.advisory.keySet)
  }

  test("incompatibilityMessageNamesTopicSettingExpectedAndFound") {
    val error = StoreError.TopicIncompatible("__kui_config", "cleanup.policy", "compact", "delete")
    assertEquals(
      error.message,
      "topic __kui_config has cleanup.policy=delete, expected compact. KUI will not change an existing " +
        "topic's configuration. Fix the topic or point kui.store.topicPrefix at a different prefix."
    )
    assertEquals(error.code.wire, "KUI-STORE-TOPIC-INCOMPATIBLE")
    List("__kui_config", "cleanup.policy", "compact", "delete").foreach(part =>
      assert(error.message.contains(part), s"the message should name '$part'")
    )
  }

  test("aPartitionCountDifferenceIsReportedUnderTheNamePartitions") {
    val error = StoreError.TopicIncompatible("__kui_config", StoreTopics.PartitionsSetting, "1", "3")
    assert(error.message.contains("partitions=3"), error.message)
  }
}
