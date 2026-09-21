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

/** How the message browser presents a bounded browse. Live mode always remains an infinite tail. */
enum MessageViewMode(val wire: String) {
  case Pages extends MessageViewMode("pages")
  case Infinite extends MessageViewMode("infinite")
}

object MessageViewMode {
  def fromWire(raw: String): Option[MessageViewMode] = values.find(_.wire == raw)
  given CanEqual[MessageViewMode, MessageViewMode] = CanEqual.derived
}

/** Defaults applied when a message URL does not explicitly choose a page size or presentation mode. */
final case class MessageBrowserSettings(pageSize: Int, mode: MessageViewMode)

object MessageBrowserSettings {
  val MinPageSize: Int = 1
  val MaxPageSize: Int = 500
  val Default: MessageBrowserSettings = MessageBrowserSettings(100, MessageViewMode.Pages)

  def validPageSize(value: Int): Boolean = value >= MinPageSize && value <= MaxPageSize

  given CanEqual[MessageBrowserSettings, MessageBrowserSettings] = CanEqual.derived
}

/** Persistence boundary for principal-scoped settings. */
trait UiSettingsStore[F[_]] {
  def get(cluster: ClusterId, principal: Principal): F[Either[KuiError, Option[UiAppearance]]]
  def put(
      cluster: ClusterId,
      principal: Principal,
      appearance: UiAppearance
  ): F[Either[KuiError, UiAppearance]]
  def getMessageBrowser(
      cluster: ClusterId,
      principal: Principal
  ): F[Either[KuiError, Option[MessageBrowserSettings]]]
  def putMessageBrowser(
      cluster: ClusterId,
      principal: Principal,
      settings: MessageBrowserSettings
  ): F[Either[KuiError, MessageBrowserSettings]]
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

  def getMessageBrowser(
      principal: Principal,
      cluster: ClusterId
  ): F[Either[KuiError, MessageBrowserSettings]] =
    registry.resolve(cluster).flatMap {
      case Left(error) => error.asLeft[MessageBrowserSettings].pure[F]
      case Right(_) =>
        store.getMessageBrowser(cluster, principal).map(_.map(_.getOrElse(MessageBrowserSettings.Default)))
    }

  def putMessageBrowser(
      principal: Principal,
      cluster: ClusterId,
      settings: MessageBrowserSettings
  ): F[Either[KuiError, MessageBrowserSettings]] =
    registry.resolve(cluster).flatMap {
      case Left(error) => error.asLeft[MessageBrowserSettings].pure[F]
      case Right(_) => store.putMessageBrowser(cluster, principal, settings)
    }
}
