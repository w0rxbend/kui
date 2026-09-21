package kui.cluster.application

import java.time.Instant

import cats.effect.IO
import cats.effect.kernel.{Ref, Resource}

import kui.cluster.application.fakes.FakeClusterConfigStore
import kui.cluster.domain.*
import kui.kernel.error.KuiError
import kui.kernel.{ClusterId, RoleName, UserName}
import kui.security.{Principal, PrincipalKind}
import kui.testkit.KuiIOSuite
import kui.testkit.fakes.FakeStructuredLogger

/** Principal and cluster isolation for the settings application boundary. */
final class UiSettingsUseCaseSuite extends KuiIOSuite {

  private val Prod = ClusterId.unsafe("prod")
  private val Staging = ClusterId.unsafe("staging")
  private val Alice = Principal(UserName.unsafe("alice"), Set(RoleName.unsafe("ops")), PrincipalKind.Session)
  private val Bob = Principal(UserName.unsafe("bob"), Set.empty, PrincipalKind.Session)

  private val dark = UiAppearance(AppearanceTheme.Dark, AppearanceAccent.Teal, AppearanceDensity.Compact)

  final private class MemoryStore(
      state: Ref[IO, Map[(ClusterId, Principal), UiAppearance]]
  ) extends UiSettingsStore[IO] {
    def get(cluster: ClusterId, principal: Principal): IO[Either[KuiError, Option[UiAppearance]]] =
      state.get.map(values => Right(values.get(cluster -> principal)))

    def put(
        cluster: ClusterId,
        principal: Principal,
        appearance: UiAppearance
    ): IO[Either[KuiError, UiAppearance]] =
      state.update(_.updated(cluster -> principal, appearance)).as(Right(appearance))
  }

  private val clock = new ClockPort[IO] {
    def now: IO[Instant] = IO.pure(Instant.parse("2026-09-21T12:00:00Z"))
  }

  private def rig: Resource[IO, UiSettingsUseCase[IO]] =
    for {
      logger <- Resource.eval(FakeStructuredLogger[IO])
      config <- Resource.eval(FakeClusterConfigStore.make[IO](Nil))
      registry <- ClusterRegistry.make[IO](
        List(
          ClusterProfileFixtures.plaintext("prod", "Production"),
          ClusterProfileFixtures.plaintext("staging", "Staging")
        ),
        config,
        clock,
        logger
      )
      state <- Resource.eval(Ref.of[IO, Map[(ClusterId, Principal), UiAppearance]](Map.empty))
    } yield new UiSettingsUseCase[IO](registry, new MemoryStore(state))

  test("missing settings return product defaults") {
    rig.use(_.get(Alice, Prod)).map(result => assertEquals(result, Right(UiAppearance.Default)))
  }

  test("settings are isolated by principal and cluster") {
    rig.use { settings =>
      for {
        saved <- settings.put(Alice, Prod, dark)
        same <- settings.get(Alice, Prod)
        otherCluster <- settings.get(Alice, Staging)
        otherPrincipal <- settings.get(Bob, Prod)
      } yield {
        assertEquals(saved, Right(dark))
        assertEquals(same, Right(dark))
        assertEquals(otherCluster, Right(UiAppearance.Default))
        assertEquals(otherPrincipal, Right(UiAppearance.Default))
      }
    }
  }

  test("unknown clusters are rejected before the store is touched") {
    rig
      .use(_.put(Alice, ClusterId.unsafe("unknown"), dark))
      .map(result => assertEquals(result.left.map(_.code), Left(kui.kernel.error.ErrorCode.ClusterNotFound)))
  }
}
