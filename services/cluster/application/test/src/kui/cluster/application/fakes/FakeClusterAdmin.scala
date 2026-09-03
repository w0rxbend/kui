package kui.cluster.application.fakes

import scala.concurrent.duration.{Duration, FiniteDuration}

import cats.data.NonEmptyList
import cats.effect.kernel.{Ref, Temporal}
import cats.syntax.all.*

import kui.cluster.domain.*
import kui.kernel.error.KuiError
import kui.kernel.{BrokerId, ClusterId}

/** A cluster admin port a suite can drive, and which remembers what it was asked.
  *
  * Two of its controls carry most of the suites above it. `delay` makes every method sleep before answering,
  * which under virtual time is how "the dashboard never waits on a broker" becomes an assertion rather than
  * a hope. `calls` records every invocation with the cluster it was for, which is how "the quorum was never
  * asked for" and "the deleted cluster's loop stopped" are assertions about behaviour rather than about
  * timing.
  */
final class FakeClusterAdmin[F[_]: Temporal] private (state: Ref[F, FakeClusterAdmin.State])
    extends ClusterAdmin[F] {

  def describeCluster(profile: ClusterProfile): F[Either[KuiError, ClusterDescription]] =
    answer(profile, "describeCluster")(_.description)

  def detectVersion(profile: ClusterProfile): F[Either[KuiError, Option[KafkaVersion]]] =
    answer(profile, "detectVersion")(_.version)

  def describeQuorum(profile: ClusterProfile): F[Either[KuiError, Option[QuorumInfo]]] =
    answer(profile, "describeQuorum")(_.quorum)

  def brokerConfigs(
      profile: ClusterProfile,
      broker: BrokerId,
      docs: Boolean
  ): F[Either[KuiError, List[ConfigEntry]]] =
    record(profile, "brokerConfigs", docs) >>
      pause >>
      state.get.map(_.configs.getOrElse(broker, Right(Nil)))

  def describeLogDirs(
      profile: ClusterProfile,
      brokers: NonEmptyList[BrokerId]
  ): F[Either[KuiError, PartialResult[BrokerId, List[LogDir]]]] =
    answer(profile, "describeLogDirs")(_.logDirs)

  def capabilities(profile: ClusterProfile): F[ClusterFeatures] =
    record(profile, "capabilities", false) >> pause >> state.get.map(_.features)

  /** Every call, in order, as `(cluster, method)`. */
  def calls: F[List[(ClusterId, String)]] = state.get.map(_.calls.reverse)

  def callsFor(cluster: ClusterId): F[List[String]] =
    calls.map(_.collect { case (id, method) if id == cluster => method })

  /** The profiles the fake was actually called with, most recent first. It is what makes "a rotated password
    * reached the loop" an assertion.
    */
  def seenProfiles: F[List[ClusterProfile]] = state.get.map(_.seen)

  /** The `docs` flag of the last `brokerConfigs` call. */
  def lastDocsFlag: F[Option[Boolean]] = state.get.map(_.lastDocs)

  def set(update: FakeClusterAdmin.State => FakeClusterAdmin.State): F[Unit] = state.update(update)

  def reset: F[Unit] = state.update(_.copy(calls = Nil, seen = Nil))

  private def answer[A](profile: ClusterProfile, method: String)(
      read: FakeClusterAdmin.State => A
  ): F[A] =
    record(profile, method, false) >> pause >> state.get.map(read)

  private def record(profile: ClusterProfile, method: String, docs: Boolean): F[Unit] =
    state.update { current =>
      current.copy(
        calls = (profile.id, method) :: current.calls,
        seen = profile :: current.seen,
        lastDocs = if method == "brokerConfigs" then Some(docs) else current.lastDocs
      )
    }

  private def pause: F[Unit] =
    state.get.flatMap { current =>
      if current.delay > Duration.Zero then Temporal[F].sleep(current.delay)
      else Temporal[F].unit
    }
}

object FakeClusterAdmin {

  final case class State(
      description: Either[KuiError, ClusterDescription],
      version: Either[KuiError, Option[KafkaVersion]],
      quorum: Either[KuiError, Option[QuorumInfo]],
      configs: Map[BrokerId, Either[KuiError, List[ConfigEntry]]],
      logDirs: Either[KuiError, PartialResult[BrokerId, List[LogDir]]],
      features: ClusterFeatures,
      /** How long every method sleeps before answering. */
      delay: FiniteDuration,
      calls: List[(ClusterId, String)],
      seen: List[ClusterProfile],
      lastDocs: Option[Boolean]
  )

  def make[F[_]: Temporal](
      healthy: ClusterDescription,
      features: ClusterFeatures = TopologyFixtures.allFeatures,
      delay: FiniteDuration = Duration.Zero
  ): F[FakeClusterAdmin[F]] =
    Ref
      .of[F, State](
        State(
          description = Right(healthy),
          version = Right(TopologyFixtures.defaultVersion),
          quorum = Right(None),
          configs = Map.empty,
          logDirs = Right(PartialResult.empty[BrokerId, List[LogDir]]),
          features = features,
          delay = delay,
          calls = Nil,
          seen = Nil,
          lastDocs = None
        )
      )
      .map(new FakeClusterAdmin[F](_))
}
