package kui.cluster.contract.dto

import io.circe.{Codec, HCursor, Json}
import sttp.tapir.Schema

/** Principal-scoped appearance settings for one cluster. Values are deliberately closed by the server. */
final case class UiAppearanceDto(theme: String, accent: String, density: String)

object UiAppearanceDto {
  given Codec[UiAppearanceDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        theme <- cursor.get[String]("theme")
        accent <- cursor.get[String]("accent")
        density <- cursor.get[String]("density")
      } yield UiAppearanceDto(theme, accent, density),
    (appearance: UiAppearanceDto) =>
      Json.obj(
        "theme" -> Json.fromString(appearance.theme),
        "accent" -> Json.fromString(appearance.accent),
        "density" -> Json.fromString(appearance.density)
      )
  )

  given Schema[UiAppearanceDto] = Schema
    .derived[UiAppearanceDto]
    .description("The complete appearance snapshot for this principal and cluster")

  given CanEqual[UiAppearanceDto, UiAppearanceDto] = CanEqual.derived
}

/** Principal-scoped defaults for bounded message browsing on one cluster. */
final case class MessageBrowserSettingsDto(pageSize: Int, mode: String)

object MessageBrowserSettingsDto {
  given Codec[MessageBrowserSettingsDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        pageSize <- cursor.get[Int]("pageSize")
        mode <- cursor.get[String]("mode")
      } yield MessageBrowserSettingsDto(pageSize, mode),
    (settings: MessageBrowserSettingsDto) =>
      Json.obj(
        "pageSize" -> Json.fromInt(settings.pageSize),
        "mode" -> Json.fromString(settings.mode)
      )
  )

  given Schema[MessageBrowserSettingsDto] = Schema
    .derived[MessageBrowserSettingsDto]
    .description("The default page size and presentation mode for bounded message browsing")

  given CanEqual[MessageBrowserSettingsDto, MessageBrowserSettingsDto] = CanEqual.derived
}
