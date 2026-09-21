package kui.cluster.app

import cats.Monad
import cats.syntax.all.*

import kui.cluster.application.{
  AppearanceAccent,
  AppearanceDensity,
  AppearanceTheme,
  MessageBrowserSettings as ApplicationMessageBrowser,
  MessageViewMode as ApplicationMessageViewMode,
  UiAppearance as ApplicationAppearance,
  UiSettingsStore
}
import kui.config.store.{
  ConfigStore,
  MessageBrowserSettings as StoredMessageBrowser,
  MessageViewMode as StoredMessageViewMode,
  UiAccent,
  UiAppearance as StoredAppearance,
  UiDensity,
  UiTheme,
  UserStateStore
}
import kui.kernel.ClusterId
import kui.kernel.error.KuiError
import kui.security.Principal

/** Maps the cluster application's vocabulary onto the shared principal record in `__kui_config`. */
final class StoredUiSettings[F[_]: Monad](metadata: ConfigStore[F]) extends UiSettingsStore[F] {
  private val users = UserStateStore[F](metadata)

  def get(
      cluster: ClusterId,
      principal: Principal
  ): F[Either[KuiError, Option[ApplicationAppearance]]] =
    users.get(cluster, principal).map(_.map(_.appearance.map(toApplication)))

  def put(
      cluster: ClusterId,
      principal: Principal,
      appearance: ApplicationAppearance
  ): F[Either[KuiError, ApplicationAppearance]] =
    users
      .putAppearance(cluster, principal, toStored(appearance))
      .map(_.map(toApplication))

  def getMessageBrowser(
      cluster: ClusterId,
      principal: Principal
  ): F[Either[KuiError, Option[ApplicationMessageBrowser]]] =
    users.get(cluster, principal).map(_.map(_.messageBrowser.map(toApplicationMessageBrowser)))

  def putMessageBrowser(
      cluster: ClusterId,
      principal: Principal,
      settings: ApplicationMessageBrowser
  ): F[Either[KuiError, ApplicationMessageBrowser]] =
    users
      .putMessageBrowser(cluster, principal, toStoredMessageBrowser(settings))
      .map(_.map(toApplicationMessageBrowser))

  private def toApplication(appearance: StoredAppearance): ApplicationAppearance =
    ApplicationAppearance(
      theme = appearance.theme match {
        case UiTheme.Auto => AppearanceTheme.Auto
        case UiTheme.Light => AppearanceTheme.Light
        case UiTheme.Dark => AppearanceTheme.Dark
      },
      accent = appearance.accent match {
        case UiAccent.Blue => AppearanceAccent.Blue
        case UiAccent.Teal => AppearanceAccent.Teal
        case UiAccent.Green => AppearanceAccent.Green
        case UiAccent.Amber => AppearanceAccent.Amber
      },
      density = appearance.density match {
        case UiDensity.Comfortable => AppearanceDensity.Comfortable
        case UiDensity.Compact => AppearanceDensity.Compact
      }
    )

  private def toStored(appearance: ApplicationAppearance): StoredAppearance =
    StoredAppearance(
      theme = appearance.theme match {
        case AppearanceTheme.Auto => UiTheme.Auto
        case AppearanceTheme.Light => UiTheme.Light
        case AppearanceTheme.Dark => UiTheme.Dark
      },
      accent = appearance.accent match {
        case AppearanceAccent.Blue => UiAccent.Blue
        case AppearanceAccent.Teal => UiAccent.Teal
        case AppearanceAccent.Green => UiAccent.Green
        case AppearanceAccent.Amber => UiAccent.Amber
      },
      density = appearance.density match {
        case AppearanceDensity.Comfortable => UiDensity.Comfortable
        case AppearanceDensity.Compact => UiDensity.Compact
      }
    )

  private def toApplicationMessageBrowser(settings: StoredMessageBrowser): ApplicationMessageBrowser =
    ApplicationMessageBrowser(
      settings.pageSize,
      settings.mode match {
        case StoredMessageViewMode.Pages => ApplicationMessageViewMode.Pages
        case StoredMessageViewMode.Infinite => ApplicationMessageViewMode.Infinite
      }
    )

  private def toStoredMessageBrowser(settings: ApplicationMessageBrowser): StoredMessageBrowser =
    StoredMessageBrowser(
      settings.pageSize,
      settings.mode match {
        case ApplicationMessageViewMode.Pages => StoredMessageViewMode.Pages
        case ApplicationMessageViewMode.Infinite => StoredMessageViewMode.Infinite
      }
    )
}
