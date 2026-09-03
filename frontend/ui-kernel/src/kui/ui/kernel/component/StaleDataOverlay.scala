package kui.ui.kernel.component

import java.time.Instant

import com.raquo.laminar.api.L.*
import org.scalajs.dom

import kui.ui.kernel.css.KernelCss
import kui.ui.kernel.time.Timestamps

/** Why the content under the overlay is not being refreshed.
  *
  * The `state` word is the one the capability registry uses — `Unavailable`, `Degraded` — and the `detail` is
  * the registry's own reason string, which ADR-032 requires to be rendered verbatim rather than translated
  * into something friendlier. A reason a user can paste into a message to whoever runs the cluster is worth
  * more than a reassuring sentence.
  */
final case class StaleReason(state: String, detail: Option[String]) {

  /** The one line the badge shows after the timestamp. */
  def summary: String = detail.fold(state)(reason => s"$state: $reason")
}

object StaleReason {

  /** "Unavailable" plus the registry's reason string. */
  def unavailable(detail: String): StaleReason = StaleReason("Unavailable", Some(detail))

  /** "Degraded" plus the registry's reason string. */
  def degraded(detail: String): StaleReason = StaleReason("Degraded", Some(detail))

  /** The last request failed but the feature is not reported down: there is no state word to add. */
  def lastRequestFailed(detail: String): StaleReason = StaleReason("Not refreshed", Some(detail))
}

/** Keeps what the user was already looking at on the screen when the service behind it goes away.
  *
  * ## The rule
  *
  * ADR-032's stale-data rule (DC-H3): data already fetched in this session stays visible, dimmed, with the
  * time it was fetched and the reason it is not being refreshed, and every action that would change something
  * is disabled. It is not replaced by a spinner, an empty table or an error page.
  *
  * That is the behaviour that makes "a cluster being down never takes the page down" true in practice. An
  * error page is technically honest and practically useless: the operator looking at the screen when the
  * service died is the person who most needs the last numbers it produced.
  *
  * ## What it is not
  *
  * It does not fetch, retry or poll. It is handed a state and it draws it. Retrying is an explicit user
  * action (ADR-032), and browser-side polling of cluster data is forbidden outright (M1 DEVPLAN §10 D10) —
  * the server refreshes its snapshot and the page shows `scrapedAt`.
  *
  * ## When to use it, and when not to
  *
  * Overlay when there is previously fetched data in this session to keep showing. Fallback panel when there
  * is not: "we have nothing and cannot get any" is a different message from "this is old", and dimming an
  * empty table communicates neither.
  */
object StaleDataOverlay {

  /** Marks a control this overlay disabled, so that restoring it later cannot accidentally enable one that
    * was already disabled for a reason of its own.
    */
  private val OwnedMarker = "data-kui-stale-disabled"

  private val Controls = "button, a, input, select, textarea, [tabindex]"

  /** Wraps `content`, dimming it and disabling interaction whenever `stale` holds a reason.
    *
    * @param content
    *   rendered once and never unmounted. The whole point is that what the user was looking at is still
    *   there, the same nodes, in the same scroll position.
    * @param stale
    *   `None` means fresh: no badge, no dimming, no `aria-busy`.
    * @param fetchedAt
    *   when the content was last successfully fetched. `None` renders "never refreshed" rather than a
    *   fabricated time.
    * @param zone
    *   the IANA zone id the badge's absolute time is formatted in, supplied by the caller. This component
    *   reads no preference of its own.
    * @param now
    *   the clock, so that the relative form can be asserted without waiting.
    */
  def apply(
      content: HtmlElement,
      stale: Signal[Option[StaleReason]],
      fetchedAt: Signal[Option[Instant]],
      zone: Signal[String],
      now: () => Instant = () => Instant.now(),
      testId: Option[String] = None
  ): HtmlElement = {

    val body = div(cls := KernelCss.StaleContent, content)

    div(
      cls := KernelCss.Stale,
      cls(KernelCss.StaleActive) <-- stale.map(_.isDefined),
      // `aria-busy` is the standard way of telling assistive technology that a region's content is
      // not currently authoritative. It is set on the wrapper rather than on the table so that one
      // announcement covers the whole region.
      aria.busy <-- stale.map(_.isDefined),
      Components.testIdAttr(testId),
      child.maybe <-- stale.combineWith(fetchedAt, zone).map { (reason, at, zoneId) =>
        reason.map(badge(_, at, zoneId, now(), testId))
      },
      body,
      // The dimming is CSS; the *disabling* cannot be, because `pointer-events: none` still leaves
      // every control in the tab order and operable from a keyboard. So the controls beneath are
      // marked disabled for real, and un-marked again when the data goes fresh.
      stale.map(_.isDefined) --> Observer[Boolean](isStale => setInteractive(body.ref, !isStale))
    )
  }

  private def badge(
      reason: StaleReason,
      fetchedAt: Option[Instant],
      zone: String,
      now: Instant,
      testId: Option[String]
  ): HtmlElement =
    div(
      cls := KernelCss.StaleBadge,
      // Announced once, politely: the user is told the data stopped refreshing without the whole
      // table being read back to them.
      role := "status",
      aria.live := "polite",
      Components.testIdAttr(testId.map(id => s"$id-stale-badge")),
      fetchedAt.map(at => title := Timestamps.absolute(at, zone)),
      span(cls := KernelCss.StaleBadgeTime, Timestamps.lastUpdated(fetchedAt, now)),
      span(cls := KernelCss.StaleBadgeReason, reason.summary)
    )

  /** Disables, or restores, every control under `root`.
    *
    * Only controls this overlay disabled are restored, which is what the marker attribute is for: a button
    * that a screen disabled for its own reasons — no permission, nothing selected — must stay disabled when
    * the data goes fresh again.
    */
  private def setInteractive(root: dom.Element, interactive: Boolean): Unit = {
    val controls = root.querySelectorAll(Controls)
    (0 until controls.length).foreach { index =>
      val element = controls(index)
      if interactive then {
        if element.hasAttribute(OwnedMarker) then {
          element.removeAttribute(OwnedMarker)
          element.removeAttribute("disabled")
          element.removeAttribute("aria-disabled")
          element.removeAttribute("tabindex")
        }
      } else if !element.hasAttribute(OwnedMarker) && element.getAttribute("aria-disabled") != "true" then {
        element.setAttribute(OwnedMarker, "")
        element.setAttribute("disabled", "")
        element.setAttribute("aria-disabled", "true")
        // Removed from the tab order too. A disabled-looking control that a keyboard user can still
        // reach and activate is worse than one that was never dimmed.
        element.setAttribute("tabindex", "-1")
      }
    }
  }
}
