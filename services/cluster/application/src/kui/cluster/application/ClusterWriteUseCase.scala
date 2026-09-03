package kui.cluster.application

import cats.effect.kernel.Concurrent
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger

import kui.cluster.domain.{ClusterConfigStore, ClusterProfile, ProfileVersion}
import kui.kernel.error.KuiError

/** Registering or replacing one cluster at runtime.
  *
  * Three lines of orchestration, and each one is there for a reason a caller cannot supply:
  *
  *   1. the write goes through the store's optimistic version check, so two replicas writing the same key
  *      produce one winner and one conflict rather than a lost update;
  *   2. the store's own contract is that `put` returns only once the record is readable back, so a success
  *      here means the change is already visible to every replica that has caught up;
  *   3. the registry is reloaded before the answer is given, so the very next read from *this* replica sees
  *      the cluster that was just written. Without it, a caller could write a cluster and immediately fail to
  *      read it back from the same process, which reads as data loss and is not.
  *
  * The cluster service is the single writer of `cluster/<id>` (ADR-036). Nothing else in KUI may write that
  * key, which is what makes the version check sufficient as a serialisation point.
  */
trait ClusterWriteUseCase[F[_]] {

  /** Writes the profile, expecting the store to currently hold `expected`.
    *
    * `ProfileVersion.Static` — zero — means "create; fail if it exists", which is the same check with the
    * same code path rather than a second endpoint.
    */
  def put(profile: ClusterProfile, expected: ProfileVersion): F[Either[KuiError, ClusterProfile]]
}

object ClusterWriteUseCase {

  val Operation: String = "kui.cluster.write"

  def make[F[_]: Concurrent](
      registry: ClusterRegistry[F],
      store: ClusterConfigStore[F],
      logger: StructuredLogger[F]
  ): ClusterWriteUseCase[F] =
    new ClusterWriteUseCase[F] {

      private val context: Map[String, String] =
        Map("service.name" -> ClusterService.Id.value, "operation" -> Operation)

      def put(profile: ClusterProfile, expected: ProfileVersion): F[Either[KuiError, ClusterProfile]] =
        store.put(profile, expected).flatMap {
          case Left(error) =>
            logger
              .warn(context ++ Map("cluster.id" -> profile.id.value))(
                s"the cluster could not be written: ${error.message}"
              )
              .as(error.asLeft[ClusterProfile])

          case Right(written) =>
            // Reload before answering, never after. A caller that wrote a cluster and could not
            // immediately read it back from the same process would reasonably conclude the write was
            // lost - and the log line below is what says otherwise when someone asks later.
            registry.reload *>
              logger
                .info(
                  context ++ Map(
                    "cluster.id" -> written.id.value,
                    "version.from" -> expected.value.toString,
                    "version.to" -> written.version.value.toString
                  )
                )("a cluster was written")
                .as(written.asRight[KuiError])
        }
    }
}
