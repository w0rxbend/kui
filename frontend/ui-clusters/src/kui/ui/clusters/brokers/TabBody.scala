package kui.ui.clusters.brokers

import java.time.Instant

import com.raquo.laminar.api.L.*

import kui.contracts.Section
import kui.ui.clusters.ClustersCss
import kui.ui.kernel.component.{EmptyState, StaleDataOverlay, StaleReason}
import kui.ui.kernel.time.Timestamps

/** The four ways a tab's section can come back, drawn the same way in both tabs.
  *
  * ## Why the two tabs share this and not a page-level treatment
  *
  * The tabs read two different endpoints at two different moments, and one of them failing must not blank the
  * other: a broker whose settings cannot be read still has disks worth looking at. So the section handling is
  * *per tab*, and each tab shows its own `scrapedAt` — one timestamp covering both would be wrong for
  * whichever was fetched second.
  */
object TabBody {

  def apply[A](
      section: Signal[Option[Section[A]]],
      unavailableTestId: String,
      unavailableMessage: String => String,
      forbiddenMessage: String,
      emptyTitle: String,
      emptyDescription: String,
      isEmpty: Signal[Boolean],
      body: HtmlElement,
      overlayTestId: String,
      zone: Signal[String],
      now: () => Instant
  ): HtmlElement =
    div(
      cls := ClustersCss.TabBody,
      // An explanation, never an empty table. A table with no rows is a claim that there is nothing there.
      child.maybe <-- section.map(
        _.flatMap(notice(_, unavailableTestId, unavailableMessage, forbiddenMessage))
      ),
      child.maybe <-- section
        .combineWith(isEmpty)
        .map((current, empty) =>
          Option.when(current.exists(hasData) && empty)(
            EmptyState(emptyTitle, description = Some(emptyDescription))
          )
        ),
      child.maybe <-- section
        .combineWith(isEmpty)
        .map((current, empty) =>
          Option.when(current.exists(hasData) && !empty)(
            StaleDataOverlay(
              content = body,
              stale = section.map(_.flatMap(staleReason)),
              fetchedAt = section.map(_.flatMap(fetchedAt)),
              zone = zone,
              now = now,
              testId = Some(overlayTestId)
            )
          )
        ),
      div(
        cls := ClustersCss.ScrapedAt,
        dataAttr("testid") := s"$overlayTestId-scraped-at",
        text <-- section
          .combineWith(zone)
          .map((current, zoneId) => scrapedLine(current.flatMap(fetchedAt), zoneId, now()))
      )
    )

  private def hasData[A](section: Section[A]): Boolean = section.toOption.isDefined

  private def fetchedAt[A](section: Section[A]): Option[Instant] =
    section match {
      case Section.Ok(_, at) => Some(at)
      case Section.Stale(_, at, _) => Some(at)
      case _ => None
    }

  private def staleReason[A](section: Section[A]): Option[StaleReason] =
    section match {
      case Section.Stale(_, _, reason) => Some(StaleReason.degraded(reason.wire))
      case _ => None
    }

  private def notice[A](
      section: Section[A],
      testId: String,
      unavailableMessage: String => String,
      forbiddenMessage: String
  ): Option[HtmlElement] =
    section match {
      case Section.Unavailable(_, message, _) =>
        // The service's own words, unedited: it is the string an operator can search for.
        Some(explanation(testId, unavailableMessage(message)))
      case Section.Forbidden => Some(explanation(testId, forbiddenMessage))
      case _ => None
    }

  private def explanation(testId: String, text: String): HtmlElement =
    div(cls := ClustersCss.Error, dataAttr("testid") := testId, role := "alert", p(text))

  private def scrapedLine(at: Option[Instant], zone: String, now: Instant): String =
    at.fold(Timestamps.NeverRefreshed)(instant =>
      s"${Timestamps.lastUpdated(Some(instant), now)} (${Timestamps.absolute(instant, zone)})"
    )
}
