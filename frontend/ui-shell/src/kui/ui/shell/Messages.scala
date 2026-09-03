package kui.ui.shell

import kui.contracts.capability.ReasonCode

/** Every sentence the shell shows about a feature's health, in one place (ADR-024).
  *
  * ## Why the strings are centralised and why there is no translation layer
  *
  * KUI ships in English in M0 and has no i18n runtime, so "centralised" here means one object per module
  * rather than a message catalogue with lookups. The gain is not translation, it is *consistency and review*:
  * a reason code is rendered identically wherever it appears — sidebar tooltip, fallback panel, toast — and
  * the wording can be read and corrected as prose in one file instead of being hunted for across screens.
  *
  * Each sentence is written for an operator who is trying to decide what to do next, not for a developer
  * reading a log. `UpstreamAuth` says the credentials are wrong rather than naming an HTTP status, because
  * "401" is not something the reader can act on and "the service refused KUI's credentials" is.
  */
object Messages {

  /** The reason code, as one sentence. */
  def reason(code: ReasonCode): String =
    code match {
      case ReasonCode.UpstreamUnavailable => "The service is not responding."
      case ReasonCode.UpstreamTimeout => "The service is taking too long to answer."
      case ReasonCode.CircuitOpen =>
        "KUI has stopped calling this service for a moment after repeated failures, and will try again by " +
          "itself."
      case ReasonCode.UpstreamAuth => "The service refused KUI's credentials."
      case ReasonCode.NotConfigured => "This is not configured in this deployment."
      case ReasonCode.Forbidden => "You do not have permission to use this."
      case ReasonCode.Starting => "KUI has not checked this service yet."
      case ReasonCode.Unknown => "Something is wrong with this service, and KUI cannot say what."
    }

  /** What "following the system" means, on the settings page. */
  val themeHelp: String =
    "\"Following the system\" changes with your operating system's own light and dark setting, " +
      "including while KUI is open."

  /** Why density is a switch and not a size scale. */
  val densityHelp: String =
    "Compact narrows the rows in every table so more of them fit on screen. It changes nothing " +
      "else - not the type size, not the controls."

  /** What the timezone preference applies to, and where it is kept. */
  val timezoneHelp: String =
    "Every time KUI shows - when a cluster was last read, when a service went down - is shown in " +
      "this zone. It is remembered in this browser only."

  /** The refresh-rate setting, and the promise it makes about broker load. */
  val refreshRateHelp: String =
    "How often a screen re-reads what KUI already knows. It never asks a cluster for anything: " +
      "KUI reads each cluster on its own schedule, and the refresh button on a page is what asks " +
      "for that to happen now. Off by default."

  /** The sidebar tooltip on a `Forbidden` entry. */
  def notPermitted(featureLabel: String): String = s"You do not have permission to view $featureLabel"

  /** The heading of a feature's fallback panel. */
  def unavailableTitle(featureLabel: String): String = s"$featureLabel is unavailable"

  /** The spinner's label while a feature's module is downloading.
    *
    * Named rather than a bare spinner, because a page that says only "loading" gives a user staring at a slow
    * connection no way to tell whether the thing they clicked is the thing that is loading.
    */
  def loading(featureLabel: String): String = s"Loading $featureLabel…"

  /** What a failed dynamic import says. It is a network failure, not a missing feature, and the difference
    * decides whether retrying is worth the user's time.
    */
  def moduleFailed(featureLabel: String): String =
    s"$featureLabel could not be downloaded. This is usually a network problem rather than a fault in the " +
      "service itself."

  val StaleBanner: String =
    "KUI has lost its live connection to the gateway, so what follows may be out of date. It is still " +
      "trying to reconnect."

  val NothingElseWorks: String = "Nothing else is available right now either."

  val WhatStillWorks: String = "What still works"

  val RetryNow: String = "Retry now"

  val Retrying: String = "Checking…"

  /** What a failed probe says, inline on the panel.
    *
    * Inline and not a toast: the user pressed a button on this panel and the answer belongs next to the
    * button. A toast for every press would also stack up on a service that stays down, which is how a
    * notification area becomes something people close without reading.
    */
  def retryFailed(detail: String): String = s"KUI could not re-check the service: $detail"

  def degradedBanner(featureLabels: List[String]): String =
    featureLabels match {
      case Nil => ""
      case single :: Nil => s"$single is working, but not well."
      case several => s"${several.mkString(", ")} are working, but not well."
    }
}
