package kui.cluster.api

import cats.effect.Async
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.metrics.Counter
import sttp.tapir.server.ServerEndpoint

import kui.cluster.application.{
  AppearanceAccent,
  AppearanceDensity,
  AppearanceTheme,
  MessageBrowserSettings,
  MessageViewMode,
  UiAppearance,
  UiSettingsUseCase
}
import kui.cluster.contract.ClusterEndpoints
import kui.cluster.contract.dto.{MessageBrowserSettingsDto, UiAppearanceDto}
import kui.http.principal.{RbacGuard, SecuredRoutes}
import kui.kernel.error.{ApplicationError, FieldError, KuiError}
import kui.security.PrincipalCodec

/** Principal-scoped appearance settings, mapped at the API boundary. */
object UiSettingsRoutes {

  def apply[F[_]: Async](
      settings: UiSettingsUseCase[F],
      principals: PrincipalCodec[F],
      rejections: Counter[F, Long],
      logger: StructuredLogger[F],
      guard: RbacGuard[F]
  ): List[ServerEndpoint[Any, F]] = {
    val secured = ClusterApi.Securing[F](principals, rejections, logger, guard)

    List(
      secured(ClusterEndpoints.getUiSettings) { principal => cluster =>
        settings.get(principal, cluster).map(_.map(toWire))
      },
      secured.withBody(ClusterEndpoints.putUiSettings)(input => SecuredRoutes.bodyBytes(input._3)) {
        principal =>
          { case (_, cluster, request) =>
            fromWire(request) match {
              case Left(error) => error.asLeft[UiAppearanceDto].pure[F]
              case Right(appearance) =>
                settings.put(principal, cluster, appearance).map(_.map(toWire))
            }
          }
      },
      secured(ClusterEndpoints.getMessageBrowserSettings) { principal => cluster =>
        settings.getMessageBrowser(principal, cluster).map(_.map(toMessageBrowserWire))
      },
      secured.withBody(ClusterEndpoints.putMessageBrowserSettings)(input =>
        SecuredRoutes.bodyBytes(input._3)
      ) { principal =>
        { case (_, cluster, request) =>
          fromMessageBrowserWire(request) match {
            case Left(error) => error.asLeft[MessageBrowserSettingsDto].pure[F]
            case Right(browserSettings) =>
              settings
                .putMessageBrowser(principal, cluster, browserSettings)
                .map(_.map(toMessageBrowserWire))
          }
        }
      }
    )
  }

  private def toWire(appearance: UiAppearance): UiAppearanceDto =
    UiAppearanceDto(appearance.theme.wire, appearance.accent.wire, appearance.density.wire)

  private def fromWire(dto: UiAppearanceDto): Either[KuiError, UiAppearance] = {
    val theme = AppearanceTheme.fromWire(dto.theme)
    val accent = AppearanceAccent.fromWire(dto.accent)
    val density = AppearanceDensity.fromWire(dto.density)
    val problems = List(
      Option.when(theme.isEmpty)(FieldError.of("theme", "must be one of auto, light, dark")),
      Option.when(accent.isEmpty)(FieldError.of("accent", "must be one of blue, teal, green, amber")),
      Option.when(density.isEmpty)(FieldError.of("density", "must be one of comfortable, compact"))
    ).flatten

    if problems.nonEmpty then Left(ApplicationError.Invalid("appearance settings are not valid", problems))
    else Right(UiAppearance(theme.get, accent.get, density.get))
  }

  private def toMessageBrowserWire(settings: MessageBrowserSettings): MessageBrowserSettingsDto =
    MessageBrowserSettingsDto(settings.pageSize, settings.mode.wire)

  private def fromMessageBrowserWire(
      dto: MessageBrowserSettingsDto
  ): Either[KuiError, MessageBrowserSettings] = {
    val mode = MessageViewMode.fromWire(dto.mode)
    val problems = List(
      Option.when(!MessageBrowserSettings.validPageSize(dto.pageSize))(
        FieldError.of(
          "pageSize",
          s"must be between ${MessageBrowserSettings.MinPageSize} and ${MessageBrowserSettings.MaxPageSize}"
        )
      ),
      Option.when(mode.isEmpty)(FieldError.of("mode", "must be one of pages, infinite"))
    ).flatten

    if problems.nonEmpty then
      Left(ApplicationError.Invalid("message browser settings are not valid", problems))
    else Right(MessageBrowserSettings(dto.pageSize, mode.get))
  }
}
