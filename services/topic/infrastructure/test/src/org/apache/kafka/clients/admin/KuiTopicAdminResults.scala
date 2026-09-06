package org.apache.kafka.clients.admin

import java.util.{Map as JMap}

import org.apache.kafka.common.KafkaFuture
import org.apache.kafka.common.config.ConfigResource
import org.apache.kafka.common.internals.KafkaFutureImpl

/** Factories for the admin result types a topic scrape reads, whose constructors Kafka keeps
  * package-private.
  *
  * It lives in Kafka's own package for that one reason, and it is the same device the
  * `KuiTopicTestSynonyms` beside it uses. Without it, the behaviour argued at length in
  * `KafkaTopicAdmin.cleanupPolicies`'s scaladoc — one batched `describeConfigs` per two hundred topics, a
  * per-topic failure costing that row's policy, a whole-batch failure costing that batch's policies and not
  * the page — could only be exercised against a broker that had been deliberately misconfigured, which is
  * why none of it was exercised at all.
  *
  * `ListOffsetsResult` is not here: its constructor is public.
  *
  * Test sources only. Nothing shipped is in this package.
  */
object KuiTopicAdminResults {

  def listTopics(listings: JMap[String, TopicListing]): ListTopicsResult =
    new ListTopicsResult(KafkaFuture.completedFuture(listings))

  def describeTopics(values: JMap[String, KafkaFuture[TopicDescription]]): DescribeTopicsResult =
    DescribeTopicsResult.ofTopicNames(values)

  def describeConfigs(values: JMap[ConfigResource, KafkaFuture[Config]]): DescribeConfigsResult =
    new DescribeConfigsResult(values)

  def describeLogDirs(
      values: JMap[Integer, KafkaFuture[JMap[String, LogDirDescription]]]
  ): DescribeLogDirsResult =
    new DescribeLogDirsResult(values)

  /** A future that has already failed — how Kafka reports a resource the caller may not describe. */
  def failed[A](failure: Throwable): KafkaFuture[A] = {
    val future = new KafkaFutureImpl[A]()
    val _ = future.completeExceptionally(failure)
    future
  }
}
