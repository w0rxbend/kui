package kui.cluster.infrastructure

import cats.effect.{IO, Ref}
import org.apache.kafka.clients.admin.Admin

import kui.kafka.AdminClientPool
import kui.kernel.ClusterId
import kui.kernel.cluster.ClusterConnection

/** An `AdminClientPool` that opens nothing and remembers what it was asked to do.
  *
  * The pool's real behaviour — one client per cluster, a per-cluster creation gate, generation-guarded
  * invalidation, closing everything on release — is `libs/kafka`'s and is tested there against its own
  * factory. What this module has to prove is narrower: that the *adapter* asks for an invalidation when, and
  * only when, the connection is what broke, and that a profile whose version moved evicts the client built
  * from the old one.
  */
final class RecordingAdminPool(val events: Ref[IO, List[String]]) extends AdminClientPool[IO] {

  def run[A](connection: ClusterConnection, operation: String)(call: Admin => IO[A]): IO[A] =
    IO.raiseError(new UnsupportedOperationException("this pool never opens a client"))

  def invalidate(id: ClusterId): IO[Unit] = events.update(s"invalidate:${id.value}" :: _)

  def evict(id: ClusterId): IO[Unit] = events.update(s"evict:${id.value}" :: _)
}

object RecordingAdminPool {
  def apply(): IO[RecordingAdminPool] = Ref.of[IO, List[String]](Nil).map(new RecordingAdminPool(_))
}
