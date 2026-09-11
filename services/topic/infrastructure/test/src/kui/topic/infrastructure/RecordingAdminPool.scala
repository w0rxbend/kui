package kui.topic.infrastructure

import cats.effect.{IO, Ref}
import org.apache.kafka.clients.admin.Admin

import kui.kafka.AdminClientPool
import kui.kernel.ClusterId
import kui.kernel.cluster.ClusterConnection

/** An `AdminClientPool` that hands every call the same client and counts the round trips.
  *
  * The pool's real behaviour is `libs/kafka`'s and is tested there. What this module needs from it is the
  * count: `KafkaTopicAdmin`'s scaladoc argues that the list's cleanup column is affordable because it costs
  * one `describeConfigs` per two hundred topics rather than one per row — "on ten thousand topics that is ten
  * thousand calls for one string each. Batched at `ConfigBatch` = 200, it is fifty." Nothing checked the
  * fifty, and a `grouped` that lost its argument would still return the right policies.
  *
  * It is a second copy of the cluster service's, for the reason `KuiTopicTestSynonyms` records: a test module
  * cannot see another module's test sources.
  */
final class RecordingAdminPool(val runs: Ref[IO, List[String]], client: Admin) extends AdminClientPool[IO] {

  /** `IO.defer`, because a Kafka call can throw before it returns a future — an admin client whose connection
    * has gone throws from `describeConfigs` itself — and the real pool invokes `call` inside a `flatMap`, so
    * that throw arrives as a failed effect. A fixture that evaluated it eagerly would let the exception past
    * the very `handleErrorWith` the suite is here to assert.
    */
  def run[A](connection: ClusterConnection, operation: String)(call: Admin => IO[A]): IO[A] =
    runs.update(_ :+ operation) *> IO.defer(call(client))

  def invalidate(id: ClusterId): IO[Unit] = IO.unit

  def evict(id: ClusterId): IO[Unit] = IO.unit

  /** How many round trips were made under one operation label. */
  def runsOf(operation: String): IO[Int] = runs.get.map(_.count(_ == operation))
}

object RecordingAdminPool {
  def apply(client: Admin): IO[RecordingAdminPool] =
    Ref.of[IO, List[String]](Nil).map(new RecordingAdminPool(_, client))
}
