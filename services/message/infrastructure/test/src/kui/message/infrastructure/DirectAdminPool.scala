package kui.message.infrastructure

import cats.effect.IO
import org.apache.kafka.clients.admin.Admin

import kui.kafka.AdminClientPool
import kui.kernel.ClusterId
import kui.kernel.cluster.ClusterConnection

/** An `AdminClientPool` that hands every call the same client.
  *
  * The pool's real behaviour — the client lifetime, the timeouts, the metrics, the reconnect — is
  * `libs/kafka`'s and is tested there. What this module needs from it is a way to put a stub `Admin` in front
  * of `KafkaRecordDeleter`, which takes a pool and not a client.
  *
  * `IO.defer`, because a Kafka call can throw before it returns a future — an admin client whose connection
  * has gone throws from `describeConfigs` itself — and the real pool invokes `call` inside a `flatMap`, so
  * that throw arrives as a failed effect. A fixture that evaluated it eagerly would let the exception past
  * the very `handleErrorWith` a purge plan's compaction warning depends on.
  */
final class DirectAdminPool(client: Admin) extends AdminClientPool[IO] {

  def run[A](connection: ClusterConnection, operation: String)(call: Admin => IO[A]): IO[A] =
    IO.defer(call(client))

  def invalidate(id: ClusterId): IO[Unit] = IO.unit

  def evict(id: ClusterId): IO[Unit] = IO.unit
}
