package kui.alerts.contract.dto

import java.time.Instant

import io.circe.syntax.*
import io.circe.{Codec, HCursor, Json}
import sttp.tapir.Schema as TapirSchema

import kui.contracts.Section

/** How and when an event closed, on the wire.
  *
  * @param kind
  *   `cleared` or `acknowledged`, and the difference is not cosmetic: `cleared` is the cluster saying the
  *   condition stopped, `acknowledged` is a person saying they know about it. A feed that collapsed the two
  *   into a boolean would answer "is it fixed?" with "somebody looked at it".
  * @param by
  *   who acknowledged it, rendered exactly as the audit trail renders a principal. Absent for a `cleared`
  *   resolution, because nobody did it — a `system` placeholder there would give "who cleared this" a
  *   misleading answer rather than no answer.
  */
final case class AlertResolutionDto(at: Instant, kind: String, by: Option[String])

object AlertResolutionDto {

  given Codec[AlertResolutionDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        at <- cursor.get[Instant]("at")
        kind <- cursor.get[String]("kind")
        by <- cursor.getOrElse[Option[String]]("by")(None)
      } yield AlertResolutionDto(at, kind, by),
    (resolution: AlertResolutionDto) =>
      Json.obj(
        "at" -> resolution.at.asJson,
        "kind" -> resolution.kind.asJson,
        "by" -> resolution.by.asJson
      )
  )

  given TapirSchema[AlertResolutionDto] = TapirSchema
    .derived[AlertResolutionDto]
    .description(
      "How an event closed: `cleared` means the condition stopped, `acknowledged` means a person said " +
        "they know. `by` is set only for an acknowledgement"
    )

  given CanEqual[AlertResolutionDto, AlertResolutionDto] = CanEqual.derived
}

/** One row of the feed.
  *
  * ==Four fields where a browser might expect two==
  *
  * `severity` and `tone` are both here, and so are `category` and `glyph`, because `SCREENS-V4.md` §3.9 is a
  * correction of exactly that conflation: the notifications panel draws two `warning` items with different
  * glyphs, and the shipped component could not, because it picked the glyph from the severity. Severity
  * chooses the tone; category chooses the glyph. The derived halves travel rather than being re-derived in
  * the browser so that the bell, the card and the panel cannot each map them differently — and because the
  * resolved rendering is *not* a function of severity alone: a resolved row is drawn in the success tone
  * whatever it opened at, which is the fourth dot in §3.8 and is decided in
  * `kui.alerts.domain.AlertEvent.tone`.
  *
  * @param openedAt
  *   when KUI first saw the condition. It is KUI's own observation and not the cluster's: a broker publishes
  *   no "this partition went offline at", so an age on this screen is an age since KUI noticed, and after a
  *   restart it is an age since the restart.
  * @param lastSeenAt
  *   the most recent pass on which the rule was still firing. It is what separates "still happening" from
  *   "opened four hours ago and not checked since, because the facts behind it stopped being readable".
  */
final case class AlertEventDto(
    id: String,
    severity: String,
    tone: String,
    category: String,
    glyph: String,
    openedAt: Instant,
    lastSeenAt: Instant,
    title: String,
    detail: String,
    resolution: Option[AlertResolutionDto]
)

object AlertEventDto {

  given Codec[AlertEventDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        id <- cursor.get[String]("id")
        severity <- cursor.get[String]("severity")
        tone <- cursor.get[String]("tone")
        category <- cursor.get[String]("category")
        glyph <- cursor.get[String]("glyph")
        openedAt <- cursor.get[Instant]("openedAt")
        lastSeenAt <- cursor.get[Instant]("lastSeenAt")
        title <- cursor.get[String]("title")
        detail <- cursor.get[String]("detail")
        resolution <- cursor.getOrElse[Option[AlertResolutionDto]]("resolution")(None)
      } yield AlertEventDto(
        id,
        severity,
        tone,
        category,
        glyph,
        openedAt,
        lastSeenAt,
        title,
        detail,
        resolution
      ),
    (event: AlertEventDto) =>
      Json.obj(
        "id" -> event.id.asJson,
        "severity" -> event.severity.asJson,
        "tone" -> event.tone.asJson,
        "category" -> event.category.asJson,
        "glyph" -> event.glyph.asJson,
        "openedAt" -> event.openedAt.asJson,
        "lastSeenAt" -> event.lastSeenAt.asJson,
        "title" -> event.title.asJson,
        "detail" -> event.detail.asJson,
        "resolution" -> event.resolution.asJson
      )
  )

  given TapirSchema[AlertEventDto] = TapirSchema
    .derived[AlertEventDto]
    .description(
      "One row of the alerts feed. `severity` chooses the tone and `category` chooses the glyph; both " +
        "derived values travel so the bell, the card and the panel cannot map them differently. " +
        "`openedAt` is when KUI noticed, not when the cluster changed"
    )

  given CanEqual[AlertEventDto, AlertEventDto] = CanEqual.derived
}

/** What one rule found on the last pass.
  *
  * @param unmeasuredSubjects
  *   how many subjects the rule declined to judge because the number it compares was not measured — today,
  *   log directories whose broker reports no capacity. It is here so that a card can say "nothing above 80%,
  *   of the four directories whose capacity this cluster reports" instead of implying it looked at all of
  *   them. Zero for every rule but the disk one.
  */
final case class RuleEvaluationDto(openEvents: Int, unmeasuredSubjects: Int)

object RuleEvaluationDto {

  given Codec[RuleEvaluationDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        open <- cursor.getOrElse[Int]("openEvents")(0)
        unmeasured <- cursor.getOrElse[Int]("unmeasuredSubjects")(0)
      } yield RuleEvaluationDto(open, unmeasured),
    (evaluation: RuleEvaluationDto) =>
      Json.obj(
        "openEvents" -> evaluation.openEvents.asJson,
        "unmeasuredSubjects" -> evaluation.unmeasuredSubjects.asJson
      )
  )

  given TapirSchema[RuleEvaluationDto] = TapirSchema
    .derived[RuleEvaluationDto]
    .description("What one rule found: how many of its events are open, and how many subjects it skipped")

  given CanEqual[RuleEvaluationDto, RuleEvaluationDto] = CanEqual.derived
}

/** One rule's row, `Section`-wrapped.
  *
  * ==Why the `Section` is here and not only around the document==
  *
  * A rule reads a fact through an admin call, and the four rules read three different calls that fail
  * independently. A cluster whose `describeLogDirs` is refused by an ACL still has readable partition counts,
  * so wrapping only the document would mean one refused call took the whole feed down — and the feed is the
  * screen an operator is looking at precisely when calls are being refused. Wrapping per rule makes one dead
  * rule cost one row, which is the same argument `MetricsEndpoints` makes for five endpoints instead of one
  * document, applied one level in (ADR-053 §7).
  *
  * An `unavailable` section here is the whole point: it carries the reason and the message, so the row says
  * *"KUI could not count the partitions"* rather than answering zero. `ok` with `openEvents: 0` is a
  * measurement; `unavailable` is the absence of one, and the product's central promise is that a screen can
  * tell the reader which it is looking at.
  */
final case class AlertRuleReportDto(
    rule: String,
    category: String,
    evaluation: Section[RuleEvaluationDto]
)

object AlertRuleReportDto {

  given Codec[AlertRuleReportDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        rule <- cursor.get[String]("rule")
        category <- cursor.get[String]("category")
        evaluation <- cursor.get[Section[RuleEvaluationDto]]("evaluation")
      } yield AlertRuleReportDto(rule, category, evaluation),
    (report: AlertRuleReportDto) =>
      Json.obj(
        "rule" -> report.rule.asJson,
        "category" -> report.category.asJson,
        "evaluation" -> report.evaluation.asJson
      )
  )

  given TapirSchema[AlertRuleReportDto] = TapirSchema
    .derived[AlertRuleReportDto]
    .description(
      "One rule and what it could establish on the last pass. An unavailable evaluation means the fact " +
        "the rule reads could not be read, which is not the same as finding nothing"
    )

  given CanEqual[AlertRuleReportDto, AlertRuleReportDto] = CanEqual.derived
}

/** The feed itself.
  *
  * `openCount` and `unreadCount` are counted over the **whole** store and not over `items`, which is a page.
  * A browser that derived either from the rows it received would show `2 open` on a feed whose next page
  * holds a third — an alert count recomputed from a page is not the open count.
  *
  * @param evaluatedAt
  *   when the rules last ran. Absent before the first pass, and that absence is why the zeros beside it are
  *   readable: a feed with `openCount: 0` and no `evaluatedAt` has not established that the cluster is well,
  *   it has established that KUI has not looked yet.
  */
final case class AlertFeedDto(
    items: List[AlertEventDto],
    total: Int,
    openCount: Int,
    unreadCount: Int,
    lastReadAt: Option[Instant],
    evaluatedAt: Option[Instant],
    rules: List[AlertRuleReportDto]
)

object AlertFeedDto {

  given Codec[AlertFeedDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        items <- cursor.getOrElse[List[AlertEventDto]]("items")(Nil)
        total <- cursor.getOrElse[Int]("total")(0)
        openCount <- cursor.getOrElse[Int]("openCount")(0)
        unreadCount <- cursor.getOrElse[Int]("unreadCount")(0)
        lastReadAt <- cursor.getOrElse[Option[Instant]]("lastReadAt")(None)
        evaluatedAt <- cursor.getOrElse[Option[Instant]]("evaluatedAt")(None)
        rules <- cursor.getOrElse[List[AlertRuleReportDto]]("rules")(Nil)
      } yield AlertFeedDto(items, total, openCount, unreadCount, lastReadAt, evaluatedAt, rules),
    (feed: AlertFeedDto) =>
      Json.obj(
        "items" -> feed.items.asJson,
        "total" -> feed.total.asJson,
        "openCount" -> feed.openCount.asJson,
        "unreadCount" -> feed.unreadCount.asJson,
        "lastReadAt" -> feed.lastReadAt.asJson,
        "evaluatedAt" -> feed.evaluatedAt.asJson,
        "rules" -> feed.rules.asJson
      )
  )

  given TapirSchema[AlertFeedDto] = TapirSchema
    .derived[AlertFeedDto]
    .description(
      "One cluster's alerts. `openCount` and `unreadCount` are counted over the whole store, not over " +
        "`items`, which is one page. `evaluatedAt` absent means the rules have never run here"
    )

  given CanEqual[AlertFeedDto, AlertFeedDto] = CanEqual.derived
}

/** The feed endpoint's whole answer. */
final case class AlertFeedResponse(events: Section[AlertFeedDto])

object AlertFeedResponse {

  given Codec[AlertFeedResponse] = Codec.from(
    (cursor: HCursor) => cursor.get[Section[AlertFeedDto]]("events").map(AlertFeedResponse(_)),
    (response: AlertFeedResponse) => Json.obj("events" -> response.events.asJson)
  )

  given TapirSchema[AlertFeedResponse] = TapirSchema
    .derived[AlertFeedResponse]
    .description("What KUI has noticed about this cluster, or the reason it cannot say")

  given CanEqual[AlertFeedResponse, AlertFeedResponse] = CanEqual.derived
}

/** What an acknowledgement answers.
  *
  * The new `openCount` travels with the closed event so that the danger pill and the bell move on the
  * response the caller already has. A browser that removed one row from the page it was holding and
  * decremented its own number would be counting a page.
  */
final case class AcknowledgementDto(event: AlertEventDto, openCount: Int)

object AcknowledgementDto {

  given Codec[AcknowledgementDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        event <- cursor.get[AlertEventDto]("event")
        openCount <- cursor.getOrElse[Int]("openCount")(0)
      } yield AcknowledgementDto(event, openCount),
    (acknowledgement: AcknowledgementDto) =>
      Json.obj(
        "event" -> acknowledgement.event.asJson,
        "openCount" -> acknowledgement.openCount.asJson
      )
  )

  given TapirSchema[AcknowledgementDto] = TapirSchema
    .derived[AcknowledgementDto]
    .description("The event as it now stands, and the cluster's open count after the acknowledgement")

  given CanEqual[AcknowledgementDto, AcknowledgementDto] = CanEqual.derived
}

/** One frame of the change stream.
  *
  * It carries the open count and not the events, for `ClusterChangeDto`'s reason one service over: a
  * subscriber that sees a number it does not hold re-reads the feed it is already authorized for, which keeps
  * one principal's unread count off every other subscriber's socket and makes a dropped frame cost a fetch
  * rather than a stale screen.
  *
  * The open count *is* on the frame, though, and that is the difference from the cluster stream: it is the
  * number the danger pill and the bell both draw, it is the same for every principal, and putting it here is
  * what stops the two of them holding different values between one poll and the next.
  */
final case class AlertChangeDto(cluster: String, openCount: Int, at: Instant)

object AlertChangeDto {

  /** The SSE event name a consumer registers a listener for. */
  val EventName: String = "alerts"

  given Codec[AlertChangeDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        cluster <- cursor.get[String]("cluster")
        openCount <- cursor.getOrElse[Int]("openCount")(0)
        at <- cursor.get[Instant]("at")
      } yield AlertChangeDto(cluster, openCount, at),
    (change: AlertChangeDto) =>
      Json.obj(
        "cluster" -> change.cluster.asJson,
        "openCount" -> change.openCount.asJson,
        "at" -> change.at.asJson
      )
  )

  given TapirSchema[AlertChangeDto] = TapirSchema
    .derived[AlertChangeDto]
    .description("A cluster whose feed changed, and the open count it changed to")

  given CanEqual[AlertChangeDto, AlertChangeDto] = CanEqual.derived
}
