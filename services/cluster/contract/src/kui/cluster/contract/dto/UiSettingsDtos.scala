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
