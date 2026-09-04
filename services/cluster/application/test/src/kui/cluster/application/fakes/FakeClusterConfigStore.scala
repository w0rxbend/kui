package kui.cluster.application.fakes

import cats.effect.kernel.{Concurrent, Ref}
import cats.syntax.all.*

import kui.cluster.domain.*
import kui.kernel.ClusterId
import kui.kernel.error.{ApplicationError, ErrorCode, KuiError}

/** A metadata store a suite can drive.
  *
  * It lives here and not in `libs/testkit` because a fake of a domain port necessarily depends on the
  * service that owns the port, and the layering rules forbid a library depending on a service.
  *
  * `put` implements the real version check rather than always succeeding, so that a suite about concurrent
  * writers is asserting the rule and not the fake's opinion of it.
  */
final class FakeClusterConfigStore[F[_]: Concurrent] private (
    state: Ref[F, FakeClusterConfigStore.State[F]]
) extends ClusterConfigStore[F] {

  import FakeClusterConfigStore.State

  def list: F[Either[KuiError, List[ClusterProfile]]] =
    record("list") >> guarded(_.profiles.values.toList)

  def get(id: ClusterId): F[Either[KuiError, Option[ClusterProfile]]] =
    record(s"get:${id.value}") >> guarded(_.profiles.get(id))

  def put(profile: ClusterProfile, expected: ProfileVersion): F[Either[KuiError, ClusterProfile]] =
    record(s"put:${profile.id.value}@${expected.value}") >> state.modify { current =>
      current.failWith match {
        case Some(error) => (current, error.asLeft[ClusterProfile])
        case None =>
          val held = current.profiles.get(profile.id).map(_.version).getOrElse(ProfileVersion.Static)

          if held != expected then
            (
              current,
              ApplicationError
                .Remote(
                  ErrorCode.ConfigVersionConflict,
                  s"cluster '${profile.id.value}' was changed by someone else",
                  Nil
                )
                .asLeft[ClusterProfile]
            )
          else {
            val written = profile.at(expected.next, ProfileOrigin.Stored)
            (current.copy(profiles = current.profiles.updated(profile.id, written)), written.asRight)
          }
      }
    }

  def delete(id: ClusterId, expected: ProfileVersion): F[Either[KuiError, Unit]] =
    record(s"delete:${id.value}@${expected.value}") >> state.modify { current =>
      current.failWith match {
        case Some(error) => (current, error.asLeft[Unit])
        case None =>
          current.profiles.get(id) match {
            // Already gone. The port states deletion as idempotent, so the fake has to agree or a suite
            // about retries would be asserting the fake's opinion.
            case None => (current, ().asRight[KuiError])
            case Some(held) if held.version != expected =>
              (
                current,
                ApplicationError
                  .Remote(
                    ErrorCode.ConfigVersionConflict,
                    s"cluster '${id.value}' was changed by someone else",
                    Nil
                  )
                  .asLeft[Unit]
              )
            case Some(_) => (current.copy(profiles = current.profiles - id), ().asRight[KuiError])
          }
      }
    }

  def onChange(handler: List[ClusterProfile] => F[Unit]): F[F[Unit]] =
    state
      .modify { current =>
        val id = current.nextHandlerId
        (
          current.copy(handlers = current.handlers.updated(id, handler), nextHandlerId = id + 1),
          id
        )
      }
      .map(id => state.update(current => current.copy(handlers = current.handlers - id)))

  def health: F[StoreHealth] = state.get.map(_.health)

  /** Replaces the stored set and notifies every registered handler, which is what a store tail event does. */
  def setProfiles(profiles: List[ClusterProfile]): F[Unit] =
    for {
      handlers <- state.modify { current =>
        (
          current.copy(profiles = profiles.map(p => p.id -> p).toMap),
          current.handlers.values.toList
        )
      }
      _ <- handlers.traverse_(handler => handler(profiles))
    } yield ()

  def setHealth(health: StoreHealth): F[Unit] = state.update(_.copy(health = health))

  /** Every call from now on fails with `error`, including `list`. */
  def fail(error: KuiError): F[Unit] = state.update(_.copy(failWith = Some(error)))

  def recover: F[Unit] = state.update(_.copy(failWith = None))

  def calls: F[List[String]] = state.get.map(_.calls.reverse)

  private def record(call: String): F[Unit] =
    state.update(current => current.copy(calls = call :: current.calls))

  private def guarded[A](read: State[F] => A): F[Either[KuiError, A]] =
    state.get.map(current => current.failWith.toLeft(read(current)))
}

object FakeClusterConfigStore {

  final case class State[F[_]](
      profiles: Map[ClusterId, ClusterProfile],
      health: StoreHealth,
      /** When set, `list`, `get` and `put` all return it. */
      failWith: Option[KuiError],
      /** Every call, most recent first. */
      calls: List[String],
      handlers: Map[Long, List[ClusterProfile] => F[Unit]],
      nextHandlerId: Long
  )

  def make[F[_]: Concurrent](
      initial: List[ClusterProfile],
      health: StoreHealth = StoreHealth.Online
  ): F[FakeClusterConfigStore[F]] =
    Ref
      .of[F, State[F]](
        State(initial.map(p => p.id -> p).toMap, health, None, Nil, Map.empty, 0L)
      )
      .map(new FakeClusterConfigStore[F](_))
}
