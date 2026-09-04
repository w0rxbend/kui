package kui.cluster.application

import cats.effect.kernel.Concurrent
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger

import kui.cluster.domain.{ClusterConfigStore, ClusterProfile, ProfileOrigin, ProfileVersion}
import kui.kernel.ClusterId
import kui.kernel.error.{ApplicationError, KuiError}

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

  /** Removes the cluster, expecting the store to currently hold `expected`.
    *
    * The same three steps as [[put]], and one more that only removal needs. A cluster this deployment also
    * declares in its *static* configuration cannot be removed at all: the store record would go, the next
    * resolve would put the configured profile straight back, and the operator would watch a row they deleted
    * reappear. That is refused here, by name, with the file that has to change instead.
    */
  def delete(id: ClusterId, expected: ProfileVersion): F[Either[KuiError, Unit]]
}

object ClusterWriteUseCase {

  val Operation: String = "kui.cluster.write"

  /** The refusal a caller sees for a cluster this deployment declares in a file.
    *
    * `Conflict` rather than `Forbidden`: nothing is wrong with the caller's permissions, the request is
    * simply incompatible with the state of the deployment, and the message names the state.
    */
  def staticallyDefined(id: ClusterId): KuiError =
    ApplicationError.Conflict(
      s"cluster '${id.value}' is declared in this deployment's configuration file; remove it from " +
        "kui.clusters[] and restart, or it will be resolved again on the next reload"
    )

  def make[F[_]: Concurrent](
      registry: ClusterRegistry[F],
      store: ClusterConfigStore[F],
      logger: StructuredLogger[F]
  ): ClusterWriteUseCase[F] =
    new ClusterWriteUseCase[F] {

      private val context: Map[String, String] =
        Map("service.name" -> ClusterService.Id.value, "operation" -> Operation)

      def delete(id: ClusterId, expected: ProfileVersion): F[Either[KuiError, Unit]] =
        registry.resolve(id).flatMap {
          case Left(error) => error.asLeft[Unit].pure[F]

          // Static, or static-then-stored: the file this process was started with still names it. Removing
          // the store record would leave the row on the screen, which reads as a delete that silently
          // failed.
          case Right(profile) if profile.origin != ProfileOrigin.Stored =>
            (ClusterWriteUseCase.staticallyDefined(id): KuiError).asLeft[Unit].pure[F]

          case Right(_) =>
            store.delete(id, expected).flatMap {
              case Left(error) =>
                logger
                  .warn(context ++ Map("cluster.id" -> id.value))(
                    s"the cluster could not be removed: ${error.message}"
                  )
                  .as(error.asLeft[Unit])

              case Right(()) =>
                // Reload before answering, for the mirror image of `put`'s reason: a caller that removed a
                // cluster and could immediately still read it back would reasonably retry the delete.
                registry.reload *>
                  logger
                    .info(
                      context ++ Map("cluster.id" -> id.value, "version.from" -> expected.value.toString)
                    )("a cluster was removed")
                    .as(().asRight[KuiError])
            }
        }

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
