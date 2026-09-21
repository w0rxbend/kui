package kui.cluster.application

import cats.Monad
import cats.syntax.all.*

import kui.kernel.ClusterId
import kui.kernel.error.KuiError
import kui.security.Principal

enum AppearanceTheme(val wire: String) {
  case Auto extends AppearanceTheme("auto")
  case Light extends AppearanceTheme("light")
  case Dark extends AppearanceTheme("dark")
}

object AppearanceTheme {
  def fromWire(raw: String): Option[AppearanceTheme] = values.find(_.wire == raw)
  given CanEqual[AppearanceTheme, AppearanceTheme] = CanEqual.derived
}

enum AppearanceAccent(val wire: String) {
  case Blue extends AppearanceAccent("blue")
  case Teal extends AppearanceAccent("teal")
  case Green extends AppearanceAccent("green")
  case Amber extends AppearanceAccent("amber")
}

object AppearanceAccent {
  def fromWire(raw: String): Option[AppearanceAccent] = values.find(_.wire == raw)
  given CanEqual[AppearanceAccent, AppearanceAccent] = CanEqual.derived
}

enum AppearanceDensity(val wire: String) {
  case Comfortable extends AppearanceDensity("comfortable")
  case Compact extends AppearanceDensity("compact")
}

object AppearanceDensity {
  def fromWire(raw: String): Option[AppearanceDensity] = values.find(_.wire == raw)
  given CanEqual[AppearanceDensity, AppearanceDensity] = CanEqual.derived
}

/** The complete appearance snapshot; writes replace it atomically for one principal and cluster. */
final case class UiAppearance(
    theme: AppearanceTheme,
    accent: AppearanceAccent,
    density: AppearanceDensity
)

object UiAppearance {
  val Default: UiAppearance =
    UiAppearance(AppearanceTheme.Auto, AppearanceAccent.Blue, AppearanceDensity.Comfortable)

  given CanEqual[UiAppearance, UiAppearance] = CanEqual.derived
}

/** Persistence boundary for principal-scoped settings. */
trait UiSettingsStore[F[_]] {
  def get(cluster: ClusterId, principal: Principal): F[Either[KuiError, Option[UiAppearance]]]
  def put(
      cluster: ClusterId,
      principal: Principal,
      appearance: UiAppearance
  ): F[Either[KuiError, UiAppearance]]
}

/** Resolves the cluster before touching metadata, keeping unknown cluster ids a truthful 404. */
final class UiSettingsUseCase[F[_]: Monad](
    registry: ClusterRegistry[F],
    store: UiSettingsStore[F]
) {
  def get(principal: Principal, cluster: ClusterId): F[Either[KuiError, UiAppearance]] =
    registry.resolve(cluster).flatMap {
      case Left(error) => error.asLeft[UiAppearance].pure[F]
      case Right(_) => store.get(cluster, principal).map(_.map(_.getOrElse(UiAppearance.Default)))
    }

  def put(
      principal: Principal,
      cluster: ClusterId,
      appearance: UiAppearance
  ): F[Either[KuiError, UiAppearance]] =
    registry.resolve(cluster).flatMap {
      case Left(error) => error.asLeft[UiAppearance].pure[F]
      case Right(_) => store.put(cluster, principal, appearance)
    }
}
