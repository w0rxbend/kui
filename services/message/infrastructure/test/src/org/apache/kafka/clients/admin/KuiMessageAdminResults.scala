package org.apache.kafka.clients.admin

import java.util.Map as JMap

import org.apache.kafka.common.KafkaFuture
import org.apache.kafka.common.config.ConfigResource
import org.apache.kafka.common.internals.KafkaFutureImpl

/** The two admin results a record purge reads whose constructors Kafka keeps inside its own package.
  *
  * `DeleteRecordsResult`, `DeletedRecords` and `ListOffsetsResult` are all public, so this file is two
  * methods rather than the table its siblings in `libs/kafka` and `services/topic` are. It exists for the
  * same reason they do — a test module cannot see another module's test sources — and it is what makes the
  * `cleanup.policy` read on a purge plan assertable at all: `KafkaRecordDeleter.cleanupPolicy` promises that
  * a broker that will not be described costs the *warning* and never the plan, and that promise is about
  * what a `describeConfigs` answer, or its absence, does.
  *
  * Test sources only. Nothing shipped is in this package.
  */
object KuiMessageAdminResults {

  def describeConfigs(values: JMap[ConfigResource, KafkaFuture[Config]]): DescribeConfigsResult =
    new DescribeConfigsResult(values)

  /** `describeTopics` by name. `DescribeTopicsResult.ofTopicNames` is `private[admin]` in Kafka 4, so even
    * the published factory has to be reached from inside the package.
    */
  def describeTopics(values: JMap[String, KafkaFuture[TopicDescription]]): DescribeTopicsResult =
    DescribeTopicsResult.ofTopicNames(values)

  /** A future that has already failed — how Kafka reports one partition of a batch that was refused. */
  def failed[A](failure: Throwable): KafkaFuture[A] = {
    val future = new KafkaFutureImpl[A]()
    val _ = future.completeExceptionally(failure)
    future
  }
}
