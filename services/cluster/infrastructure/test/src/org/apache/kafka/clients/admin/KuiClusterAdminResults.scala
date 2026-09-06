package org.apache.kafka.clients.admin

import java.util.{Map as JMap}

import org.apache.kafka.common.KafkaFuture
import org.apache.kafka.common.internals.KafkaFutureImpl

/** Factories for the two admin result types a partition sweep reads, whose constructors Kafka keeps
  * package-private.
  *
  * It lives in Kafka's own package for that one reason, and it is the same device
  * `KuiTopicTestSynonyms` uses one service over. Without it the only way to exercise
  * `KafkaPartitionSweeper.sweep` — the batching, the per-topic failure isolation, and the
  * `unreadable = batch.skipped.keySet` line that withholds every partition figure at once — would be
  * against a live broker, where whether a topic is describable depends on how the container was
  * configured and the interesting case cannot be provoked at all.
  *
  * Test sources only. Nothing shipped is in this package.
  */
object KuiClusterAdminResults {

  def listTopics(listings: JMap[String, TopicListing]): ListTopicsResult =
    new ListTopicsResult(KafkaFuture.completedFuture(listings))

  def listTopicsFailure(failure: Throwable): ListTopicsResult =
    new ListTopicsResult(failed[JMap[String, TopicListing]](failure))

  def describeTopics(values: JMap[String, KafkaFuture[TopicDescription]]): DescribeTopicsResult =
    DescribeTopicsResult.ofTopicNames(values)

  /** A future that has already failed — how Kafka reports a topic the caller may not describe. */
  def failed[A](failure: Throwable): KafkaFuture[A] = {
    val future = new KafkaFutureImpl[A]()
    val _ = future.completeExceptionally(failure)
    future
  }
}
