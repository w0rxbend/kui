package kui.consumer.infrastructure

import cats.effect.kernel.{Async, Resource}
import cats.effect.std.Queue
import cats.syntax.all.*
import fs2.Stream

import kui.cluster.client.{ClusterProfile, ClusterProfiles, ProfileChange}
import kui.consumer.application.ClusterProfileSource
import kui.consumer.domain.ClusterProfileView
import kui.kernel.ClusterId
import kui.kernel.cluster.ClusterConnection
import kui.kernel.error.{ApplicationError, ErrorCode, KuiError}

/** The consumer service's view of `services/cluster/client` (ADR-046).
  *
  * The client is the one implementation of the credential-bearing profile hop — conditional fetch, change
  * subscription, fallback poll, last-known cache — and this adapter is the thin thing that turns it into the
  * two questions this service asks: "what may I do to this cluster?" and "tell me when that changes".
  *
  * The connection, credentials included, never leaves this module: `ClusterProfileView` carries the id, the
  * display name and `readOnly`, and the layers above make their decisions from those. That is what keeps a
  * password out of a use case, an audit record and a log line by construction rather than by review.
  */
object ClusterProfileSourceAdapter {

  /** Wraps a `ClusterProfiles` client.
    *
    * The change stream is fed by a bounded queue registered with the client's callback interface, and the
    * registration is undone when the resource is released — so a released adapter leaves no handler behind
    * holding a reference to a queue nobody reads. A full queue drops the oldest notification rather than
    * blocking the client: the consequence of a dropped notification is one refresh interval of staleness, and
    * the consequence of blocking the profile client is every service in the process stalling behind it.
    */
  def resource[F[_]: Async](profiles: ClusterProfiles[F]): Resource[F, ClusterProfileSource[F]] =
    for {
      queue <- Resource.eval(Queue.circularBuffer[F, ClusterId](capacity = 64))
      deregister <- Resource.make(
        profiles.onChange {
          case ProfileChange.Updated(id, _, _) => queue.offer(id)
          case ProfileChange.Removed(id) => queue.offer(id)
        }
      )(identity)
      _ = deregister
    } yield new Impl[F](profiles, queue)

  /** The connection for one cluster, for the module that builds Kafka clients.
    *
    * Deliberately not on `ClusterProfileSource`: that interface is what the application layer sees, and it
    * must not be able to reach a credential at all.
    */
  def connectionOf[F[_]: Async](
      profiles: ClusterProfiles[F],
      cluster: ClusterId
  ): F[Either[KuiError, ClusterConnection]] =
    profiles.get(cluster).map {
      case Some(profile) => profile.connection.asRight[KuiError]
      case None =>
        ApplicationError
          .NotFound("cluster", cluster.value, ErrorCode.ClusterNotFound)
          .asLeft[ClusterConnection]
    }

  final private class Impl[F[_]: Async](profiles: ClusterProfiles[F], queue: Queue[F, ClusterId])
      extends ClusterProfileSource[F] {

    def profileOf(cluster: ClusterId): F[Either[KuiError, ClusterProfileView]] =
      profiles.get(cluster).map {
        case Some(profile) => viewOf(profile).asRight[KuiError]
        // "I have never seen this cluster" and "the cluster service is down" are different, and the
        // client already keeps the last known set for the second one: reaching here means the
        // cluster genuinely is not in the last successful listing.
        case None =>
          ApplicationError
            .NotFound("cluster", cluster.value, ErrorCode.ClusterNotFound)
            .asLeft[ClusterProfileView]
      }

    def all: F[List[ClusterProfileView]] =
      profiles.all.map(_.values.toList.map(viewOf).sortBy(_.cluster.value))

    def changes: Stream[F, ClusterId] = Stream.fromQueueUnterminated(queue)

    private def viewOf(profile: ClusterProfile): ClusterProfileView =
      ClusterProfileView(profile.id, profile.name, profile.readOnly)
  }
}
