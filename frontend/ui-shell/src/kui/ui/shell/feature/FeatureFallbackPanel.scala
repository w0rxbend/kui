package kui.ui.shell.feature

import java.time.Instant

import scala.scalajs.js

import com.raquo.laminar.api.L.*

import kui.contracts.capability.ReasonCode
import kui.ui.kernel.component.{Button, ButtonVariant, Icon}
import kui.ui.shell.{Messages, ShellCss}

/** What a route renders instead of a feature that cannot be used (ADR-032).
  *
  * ## The four things on it, and why all four
  *
  *   - **The reason, as a sentence.** A reason *code* is for a log; an operator needs to know whether to wait
  *     or to go and fix something, and "the service refused KUI's credentials" and "the service is not
  *     responding" lead to completely different next actions.
  *   - **When it started, twice.** "Down for two minutes" and "down since Tuesday" call for very different
  *     reactions, and neither format alone gives both: a relative time answers "is this new?" at a glance,
  *     and an absolute one is what gets pasted into a ticket or matched against a deploy log.
  *   - **A retry that actually retries.** It asks the gateway to probe the service again. Never a page
  *     reload: a reload throws away every other feature's loaded state and the user's place in the
  *     application, in order to re-ask a question one HTTP request can answer.
  *   - **What still works.** The single most useful sentence on the page, and the one only the shell can
  *     write, because it is about the *other* features. A user who came to look at topics and finds the
  *     cluster service down needs to know whether the trip was wasted.
  *
  * @param retryInFlight
  *   whether a probe is outstanding. The button shows it rather than the panel: a spinner over the whole
  *   panel would hide the reason the user is in the middle of reading.
  * @param retryError
  *   what the last probe failed with, if it did. Shown inline, next to the button that caused it, and
  *   deliberately not as a toast — a user pressing "retry" on a service that stays down would otherwise
  *   produce a stack of identical notifications, which is how a notification area becomes something people
  *   dismiss without reading.
  */
object FeatureFallbackPanel {

  def apply(
      featureLabel: String,
      reason: ReasonCode,
      message: String,
      since: Option[Instant],
      retry: Observer[Unit],
      whatStillWorks: Signal[List[String]],
      featureContent: Option[HtmlElement] = None,
      retryInFlight: Signal[Boolean] = Val(false),
      retryError: Signal[Option[String]] = Val(None),
      now: () => Instant = () => Instant.ofEpochMilli(js.Date.now().toLong)
  ): HtmlElement =
    sectionTag(
      cls := ShellCss.Fallback,
      dataAttr("testid") := "feature-fallback",
      // A landmark with a name, so a screen reader user who lands here by following a dimmed link is
      // told what this region is rather than being dropped into unlabelled prose.
      aria.label := Messages.unavailableTitle(featureLabel),
      h1(cls := ShellCss.FallbackTitle, Messages.unavailableTitle(featureLabel)),
      p(
        cls := ShellCss.FallbackReason,
        dataAttr("testid") := "fallback-reason",
        span(cls := ShellCss.FallbackReasonIcon, aria.hidden := true, Icon.warning),
        // The gateway's own message when it sent one, and the reason code's sentence when it did not.
        // Preferring the gateway's is deliberate: it is the more specific of the two, and it is the
        // one that mentions the actual upstream.
        if message.isEmpty then Messages.reason(reason) else message
      ),
      since.map(instant => sinceLine(instant, now())),
      // The feature's own words, when its module happens to already be in the browser. Only the
      // feature can write "you can still browse messages; only the schema names are missing", and
      // only the shell can write the rest of this panel — so each writes its own half.
      featureContent.map(content => div(cls := ShellCss.FallbackFeatureContent, content)),
      div(
        cls := ShellCss.FallbackActions,
        Button(
          label = retryInFlight.map(inFlight => if inFlight then Messages.Retrying else Messages.RetryNow),
          onClick = retry,
          variant = ButtonVariant.Primary,
          loading = retryInFlight,
          icon = Some(() => Icon.refresh),
          testId = Some("fallback-retry")
        ),
        child.maybe <-- retryError.map(
          _.map(detail =>
            p(
              cls := ShellCss.FallbackError,
              dataAttr("testid") := "fallback-retry-error",
              role := "alert",
              Messages.retryFailed(detail)
            )
          )
        )
      ),
      div(
        cls := ShellCss.FallbackStillWorks,
        dataAttr("testid") := "fallback-still-works",
        h2(cls := ShellCss.FallbackStillWorksTitle, Messages.WhatStillWorks),
        child <-- whatStillWorks.map {
          case Nil => p(cls := ShellCss.FallbackStillWorksEmpty, Messages.NothingElseWorks)
          case labels => ul(labels.map(label => li(label)))
        }
      )
    )

  /** The "since" line: relative first, because that is the question people ask first. */
  private def sinceLine(since: Instant, at: Instant): HtmlElement =
    p(
      cls := ShellCss.FallbackSince,
      dataAttr("testid") := "fallback-since",
      s"Since ${relative(since, at)}",
      " (",
      // A `<time>` element with a machine-readable attribute, so the absolute value can be read by a
      // tool as well as by a person.
      timeTag(dataAttr("datetime") := since.toString, since.toString),
      ")"
    )

  /** How long ago, in words.
    *
    * Coarse on purpose. The panel is answering "is this new, or has it been like this all morning?", and a
    * count of seconds would change while the user reads it, which makes the whole line look unstable.
    */
  def relative(since: Instant, at: Instant): String = {
    val seconds = math.max(0L, at.getEpochSecond - since.getEpochSecond)
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24

    if seconds < 60 then "less than a minute ago"
    else if minutes < 60 then plural(minutes, "minute")
    else if hours < 24 then plural(hours, "hour")
    else plural(days, "day")
  }

  private def plural(count: Long, unit: String): String =
    if count == 1L then s"1 $unit ago" else s"$count ${unit}s ago"
}
