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
  * only when, the connection is what broke, that a profile whose version moved evicts the client built from
  * the old one, and — for a caller that hands one in — how many round trips the adapter makes to a client.
  *
  * The two records are kept apart on purpose. `events` is the lifecycle log three suites assert *exactly*, so
  * a round trip must not appear in it; `runs` is the call log, one entry per `run`, which is what an
  * assertion like "fifty calls and not ten thousand" is made against.
  *
  * @param client
  *   the `Admin` every `run` is handed. `None` is the original behaviour and the one the lifecycle suites
  *   want: they assert what the adapter did to the pool, and a call that opened a client would be a call this
  *   fixture had to answer.
  */
final class RecordingAdminPool(
    val events: Ref[IO, List[String]],
    val runs: Ref[IO, List[String]],
    client: Option[Admin]
) extends AdminClientPool[IO] {

  /** `IO.defer`, because a Kafka call can throw before it returns a future — an admin client whose connection
    * has gone throws from the call itself — and the real pool invokes `call` inside a `flatMap`, so that
    * throw arrives as a failed effect rather than escaping the caller's error handling.
    */
  def run[A](connection: ClusterConnection, operation: String)(call: Admin => IO[A]): IO[A] =
    runs.update(_ :+ operation) *> IO.defer {
      client match {
        case Some(admin) => call(admin)
        case None => IO.raiseError(new UnsupportedOperationException("this pool never opens a client"))
      }
    }

  def invalidate(id: ClusterId): IO[Unit] = events.update(s"invalidate:${id.value}" :: _)

  def evict(id: ClusterId): IO[Unit] = events.update(s"evict:${id.value}" :: _)

  /** How many round trips were made under one operation label. */
  def runsOf(operation: String): IO[Int] = runs.get.map(_.count(_ == operation))
}

object RecordingAdminPool {
  def apply(client: Option[Admin] = None): IO[RecordingAdminPool] =
    for {
      events <- Ref.of[IO, List[String]](Nil)
      runs <- Ref.of[IO, List[String]](Nil)
    } yield new RecordingAdminPool(events, runs, client)
}
