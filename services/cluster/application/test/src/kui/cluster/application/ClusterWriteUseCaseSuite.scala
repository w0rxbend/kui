package kui.cluster.application

import java.time.Instant

import cats.effect.IO
import cats.effect.kernel.Resource

import kui.cluster.application.fakes.FakeClusterConfigStore
import kui.cluster.domain.*
import kui.kernel.ClusterId
import kui.kernel.cluster.ClusterSecurity
import kui.kernel.error.ErrorCode
import kui.testkit.KuiIOSuite
import kui.testkit.fakes.FakeStructuredLogger

/** Removing a cluster, and the one removal the deployment's own configuration file forbids.
  *
  * `ClusterWriteUseCase.delete`'s own comment states it — "a cluster this deployment also declares in its
  * *static* configuration cannot be removed at all: the store record would go, the next resolve would put
  * the configured profile straight back, and the operator would watch a row they deleted reappear" — and
  * nothing asserted it. Inverting the guard left `./mill services.cluster.__.test` at 509/509 green.
  *
  * The other half is asserted beside it, because a refusal-only case would pass just as well against a use
  * case that refused everything: a stored cluster is removed, and the registry no longer resolves it.
  */
final class ClusterWriteUseCaseSuite extends KuiIOSuite {

  private val Prod: ClusterId = ClusterId.unsafe("prod")

  private val at: Instant = Instant.parse("2026-09-06T10:00:00Z")

  private def profile(id: String, version: ProfileVersion, origin: ProfileOrigin): ClusterProfile =
    ClusterProfileFixtures.build(
      id = id,
      name = s"Cluster $id",
      security = ClusterSecurity.Plaintext,
      version = version,
      origin = origin
    )

  private val clock: ClockPort[IO] = new ClockPort[IO] { def now: IO[Instant] = IO.pure(at) }

  private def rig(
      static: List[ClusterProfile],
      stored: List[ClusterProfile]
  ): Resource[IO, (ClusterRegistry[IO], ClusterWriteUseCase[IO])] =
    for {
      logger <- Resource.eval(FakeStructuredLogger[IO])
      store <- Resource.eval(FakeClusterConfigStore.make[IO](stored))
      registry <- ClusterRegistry.make[IO](static, store, clock, logger)
    } yield (registry, ClusterWriteUseCase.make[IO](registry, store, logger))

  test("a cluster the configuration file declares is refused, and stays resolvable afterwards") {
    val declared = profile("prod", ProfileVersion.Static, ProfileOrigin.Static)

    rig(static = List(declared), stored = Nil).use { (registry, writes) =>
      for {
        outcome <- writes.delete(Prod, ProfileVersion.Static)
        still <- registry.resolve(Prod)
      } yield {
        assertEquals(outcome.left.map(_.code), Left(ErrorCode.InvalidState))
        // The message names the file the operator has to change, because a refusal that does not say
        // what would work is a refusal they will retry.
        assert(
          outcome.left.exists(_.message.contains("kui.clusters[]")),
          s"the refusal must name the configuration key: $outcome"
        )
        assert(still.isRight, "a refused delete must leave the cluster exactly where it was")
      }
    }
  }

  test("a cluster only the store declares is removed, and the registry stops resolving it") {
    val storedOnly = profile("prod", ProfileVersion.unsafe(1L), ProfileOrigin.Stored)

    rig(static = Nil, stored = List(storedOnly)).use { (registry, writes) =>
      for {
        before <- registry.resolve(Prod)
        outcome <- writes.delete(Prod, ProfileVersion.unsafe(1L))
        after <- registry.resolve(Prod)
      } yield {
        assert(before.isRight, "the fixture must start with the cluster present")
        assertEquals(outcome, Right(()))
        // Reloaded before answering: a caller that removed a cluster and could still read it back would
        // reasonably retry the delete.
        assertEquals(after.left.map(_.code), Left(ErrorCode.ClusterNotFound))
      }
    }
  }

  test("a cluster both sources declare is refused, because the file would put it straight back") {
    val overlaid = profile("prod", ProfileVersion.unsafe(2L), ProfileOrigin.StaticThenStored)

    rig(static = List(profile("prod", ProfileVersion.Static, ProfileOrigin.Static)), stored = List(overlaid))
      .use { (_, writes) =>
        writes
          .delete(Prod, ProfileVersion.unsafe(2L))
          .map(outcome => assertEquals(outcome.left.map(_.code), Left(ErrorCode.InvalidState)))
      }
  }

  test("a cluster nothing declares is a 404 and not a silent success") {
    rig(static = Nil, stored = Nil).use { (_, writes) =>
      writes
        .delete(Prod, ProfileVersion.Static)
        .map(outcome => assertEquals(outcome.left.map(_.code), Left(ErrorCode.ClusterNotFound)))
    }
  }
}
