package kui.alerts.api

import java.time.Instant

import kui.alerts.application.{AcknowledgedAlert, AlertFeed}
import kui.alerts.contract.AlertsEndpoints
import kui.alerts.contract.dto.*
import kui.alerts.domain.*
import kui.contracts.Section
import kui.contracts.capability.ReasonCode
import kui.kernel.ValidationError
import kui.kernel.error.{DomainError, KuiError}

/** Application types to wire types, and the one place the two vocabularies are allowed to meet (ADR-033).
  *
  * Rule A3 keeps `Section` out of the application layer, so every decision about *which* section a rule's row
  * carries is made here — and every one of them is a decision about honesty rather than about shape:
  *
  *   - a rule that ran carries `ok` with what it found, timestamped with the pass;
  *   - a rule whose fact could not be read carries `unavailable` with the failure's own reason and message,
  *     so the row says what happened instead of answering a zero;
  *   - a cluster the rules have **never** run for carries `unavailable` with `STARTING`, whose own sentence
  *     is "KUI has not finished reading this cluster yet". Not `ok` with zero: a feed that has never been
  *     evaluated has established nothing, and a clean bill of health is the most expensive wrong answer an
  *     alerts screen can give.
  */
object AlertsMapping {

  /** One event. Every derived value is computed once, here, so the bell, the card and the notifications panel
    * cannot each derive it differently — see `AlertEventDto`'s own note on why `tone` and `glyph` travel
    * rather than being re-derived in the browser.
    */
  def event(event: AlertEvent): AlertEventDto =
    AlertEventDto(
      id = event.id.value,
      severity = event.severity.wire,
      tone = event.tone,
      category = event.category.wire,
      glyph = event.category.glyph,
      openedAt = event.openedAt,
      lastSeenAt = event.lastSeenAt,
      title = event.title,
      detail = event.detail,
      resolution = event.resolution.map(resolution =>
        AlertResolutionDto(resolution.at, resolution.kind.wire, resolution.by)
      )
    )

  /** The whole feed, with one row per rule whether or not that rule has anything to say.
    *
    * Every rule is in `rules`, always, in `AlertRule.All`'s order. A rule that is simply absent from the list
    * would be indistinguishable on the screen from a rule that found nothing, and the whole reason the rows
    * exist is to tell those apart.
    */
  def feed(feed: AlertFeed, at: Instant): AlertFeedDto =
    AlertFeedDto(
      items = feed.events.map(event),
      total = feed.total,
      openCount = feed.openCount,
      unreadCount = feed.unreadCount,
      lastReadAt = feed.lastReadAt,
      evaluatedAt = feed.evaluatedAt,
      rules = AlertRule.All.map(rule => report(rule, feed, at))
    )

  /** One rule's row. */
  def report(rule: AlertRule, feed: AlertFeed, at: Instant): AlertRuleReportDto =
    AlertRuleReportDto(
      rule = rule.wire,
      category = rule.category.wire,
      evaluation = evaluation(rule, feed, at)
    )

  /** Which section a rule's row carries. The three cases are the three in this object's header. */
  private def evaluation(rule: AlertRule, feed: AlertFeed, at: Instant): Section[RuleEvaluationDto] =
    feed.reports.find(_.rule == rule) match {
      case None =>
        Section.Unavailable(ReasonCode.Starting, ReasonCode.Starting.sentence, feed.evaluatedAt)

      case Some(RuleReport(_, RuleOutcome.NotEvaluated(reason))) =>
        // Through `Section.fromEither` rather than hand-mapped, so this row's reason code is chosen by the
        // same fold every other section in the product uses. A second mapping here is a second opinion
        // about what `KUI-UPSTREAM-UNAVAILABLE` means.
        Section.fromEither(Left(reason), feed.evaluatedAt.getOrElse(at))

      case Some(RuleReport(_, RuleOutcome.Evaluated(unmeasured))) =>
        Section.Ok(
          RuleEvaluationDto(feed.openByRule.getOrElse(rule, 0), unmeasured),
          feed.evaluatedAt.getOrElse(at)
        )
    }

  def acknowledged(acknowledged: AcknowledgedAlert): AcknowledgementDto =
    AcknowledgementDto(event(acknowledged.event), acknowledged.openCount)

  /** The event id off the URL, or the refusal.
    *
    * An id that is not the shape this service issues is a `KUI-VALIDATION` naming the path parameter, and not
    * a 409 from the store: the two are different situations — a malformed request against a request the store
    * cannot satisfy — and the caller of the first has a typo while the caller of the second has a stale
    * screen.
    */
  def eventId(raw: String): Either[KuiError, AlertEventId] =
    AlertEventId
      .from(raw)
      .toRight(
        // Through `DomainError.fromValidation`, so a malformed id reaches the wire by the same path a
        // malformed cluster id does and lands in `details[0].field` where a caller already looks for it.
        DomainError.fromValidation(
          ValidationError.Format(
            AlertsEndpoints.EventIdParam,
            s"an alert event id: up to ${AlertEventId.MaxLength} characters of letters, digits, '.', " +
              "'_' and '-'",
            raw
          )
        )
      )
}
