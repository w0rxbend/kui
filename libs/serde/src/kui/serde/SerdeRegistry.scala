package kui.serde

import cats.effect.{Resource, Sync}
import cats.syntax.all.*
import org.typelevel.log4cats.Logger

/** How a Kafka-facing service gets the serdes for a cluster.
  *
  * `forCluster` returns a `Resource` because a Schema-Registry serde owns an HTTP client and two caches, and
  * because a profile change must not leave the old one running. The lifetime is the caller's: it acquires one
  * when it starts serving a cluster and releases it when that cluster's profile version changes.
  *
  * The ordering rule ADR-016 asks for — **build the new one before closing the old** — is the caller's too,
  * and it is not decoration: an in-flight browse holding the previous `ClusterSerdes` must keep decoding
  * until it finishes, or a configuration edit would truncate every stream that happened to be running.
  * `Resource` gives that for free as long as the acquire of the new one is sequenced before the release of
  * the old, which is how the message service's profile subscription is written (MSG-026).
  */
trait SerdeRegistry[F[_]] {
  def forCluster(profile: SerdeProfile): Resource[F, ClusterSerdes[F]]
}

object SerdeRegistry {

  /** A registry over a fixed set of factories.
    *
    * The factories are a constructor parameter rather than something discovered at run time, and that is
    * ADR-014 made concrete: a deployment that does not ship `libs/serde-confluent` passes an empty list, and
    * everything still works with the built-ins. There is no service loader and nothing reflective, so a
    * missing module is a missing list entry rather than a `ClassNotFoundException` on the first Avro topic.
    *
    * @param log
    *   one INFO line per registry build, listing the serde names in resolution order. Short, once per profile
    *   version, and it is the line an operator needs the moment a topic decodes unexpectedly — "which serdes
    *   did this cluster actually have?" is otherwise unanswerable after the fact.
    */
  def apply[F[_]: Sync](
      factories: List[SerdeFactory[F]],
      metrics: SerdeRegistryMetrics[F],
      log: Option[Logger[F]] = None
  ): SerdeRegistry[F] = new SerdeRegistry[F] {

    def forCluster(profile: SerdeProfile): Resource[F, ClusterSerdes[F]] =
      for {
        serdes <- ClusterSerdes.resource[F](profile, factories)
        _ <- Resource.eval(metrics.registryBuilt(profile))
        _ <- Resource.eval(
          log.traverse_(
            _.info(
              s"serdes for cluster '${profile.cluster.value}' version ${profile.version}, in resolution " +
                s"order: ${serdes.all.map(_.name.value).mkString(", ")}"
            )
          )
        )
      } yield serdes
  }
}

/** The one thing the registry itself reports. Separate from `SerdeMetrics` because that one is per record and
  * this one is per profile version, and mixing a per-record interface with a per-lifetime one invites a
  * caller to update the wrong instrument in a loop.
  */
trait SerdeRegistryMetrics[F[_]] {
  def registryBuilt(profile: SerdeProfile): F[Unit]
}

object SerdeRegistryMetrics {

  def noop[F[_]](using F: cats.Applicative[F]): SerdeRegistryMetrics[F] = new SerdeRegistryMetrics[F] {
    def registryBuilt(profile: SerdeProfile): F[Unit] = F.unit
  }
}
