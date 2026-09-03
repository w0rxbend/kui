package kui.ui.shell.feature

import com.raquo.laminar.api.L.*

import kui.ui.kernel.component.Icon
import kui.ui.kernel.sse.SseConnection
import kui.ui.shell.{Messages, ShellCss}

/** The strip above the page that says the picture may be out of date, or that something is limping.
  *
  * ## The rule this component exists to enforce
  *
  * When the capability stream drops, KUI stops being told what changed. The tempting reaction is to mark
  * every feature unavailable, and it is exactly wrong: nothing has been observed to break, and taking a
  * working product off the air because one connection failed is a far worse outcome than showing slightly old
  * information. So the capability store keeps every feature's last known state, and this banner is what makes
  * that honest — the user is told, in one line at the top of the content, that what they are looking at is no
  * longer live.
  *
  * The complementary rule matters just as much: an unknown state is never rendered as `Ready`. That is
  * enforced upstream, in `FeatureState.derive`, which turns "not reported" into `Degraded(Starting)`.
  *
  * `Reconnecting` deliberately does *not* raise the banner. A stream that is between attempts is a normal
  * event — a proxy recycling a connection, a laptop's wifi blinking — and a banner that appears for a second
  * every few minutes is one people learn to ignore.
  */
object CapabilityBanner {

  def apply(
      connection: Signal[SseConnection],
      degradedFeatures: Signal[List[String]]
  ): HtmlElement = {
    val message: Signal[Option[String]] =
      connection
        .combineWith(degradedFeatures)
        .map { (current, degraded) =>
          if isStale(current) then Some(Messages.StaleBanner)
          else if degraded.nonEmpty then Some(Messages.degradedBanner(degraded))
          else None
        }

    div(
      cls := ShellCss.CapabilityBanner,
      dataAttr("testid") := "capability-banner",
      // `role="status"` rather than `alert`: this is information the user should be told about
      // without having whatever they were doing interrupted. `alert` is for something that needs an
      // answer now, and using it here would make a screen reader talk over the user's own typing.
      role := "status",
      aria.live := "polite",
      hidden <-- message.map(_.isEmpty),
      child.maybe <-- message.map(_.map(text => banner(text)))
    )
  }

  private def banner(message: String): HtmlElement =
    div(
      cls := ShellCss.CapabilityBannerBody,
      span(cls := ShellCss.CapabilityBannerIcon, aria.hidden := true, Icon.warning),
      span(cls := ShellCss.CapabilityBannerText, message)
    )

  /** Whether the browser's picture has stopped being updated.
    *
    * `Closed` is the only state that means it: the stream has finished and is not coming back without
    * somebody asking. The capability store falls back to polling at that point, so the picture is not frozen
    * — it is up to thirty seconds behind, which is exactly what the banner says.
    */
  private def isStale(connection: SseConnection): Boolean =
    connection match {
      case SseConnection.Closed(_) => true
      case SseConnection.Connecting | SseConnection.Open | SseConnection.Reconnecting(_) => false
    }
}
