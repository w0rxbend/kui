package kui.ui.messages.track

import java.time.Instant

import kui.kernel.TopicName
import kui.message.contract.{TrackMatchDto, TrackQueryDto}
import kui.ui.messages.MessagesPage

/** What the track form holds, and what it takes to turn that into a request (ET-003).
  *
  * ## Why the form is a value and the parsing is a function
  *
  * Because every rule worth getting right here is about text a person typed — a topic list with a stray
  * comma, a window whose end is before its start, a header search with no header name — and each of those has
  * a sentence that has to be shown next to the control that caused it. Kept as a pure function over a case
  * class, all of it is testable without a browser, and the page is left with drawing.
  *
  * ## Why the failures are refusals and not repairs
  *
  * A header search with no header name has an obvious-looking repair: search the value instead. That repair
  * is the reference product's defect — the search runs, finds things, and finds the wrong things — so it is
  * refused here as it is refused by the contract's own codec and by the domain. Three refusals of one rule is
  * not duplication: it is the same rule stated where each layer can act on it, and the first one to see the
  * mistake is this one, which is the only one that can point at the box.
  */
final case class TrackForm(
    topics: String,
    source: String,
    header: String,
    operator: String,
    value: String,
    from: String,
    to: String
)

object TrackForm {

  /** How far back the window starts when the screen opens.
    *
    * An hour, because the overwhelmingly common track is about something that has just gone wrong. A default
    * of "everything" would be a scan of the whole retention the first time anybody pressed the button.
    */
  val DefaultWindowSeconds: Long = 3600L

  def initial(now: Instant): TrackForm =
    TrackForm(
      topics = "",
      source = TrackMatchDto.Source.Value,
      header = "",
      operator = TrackMatchDto.Operator.Contains,
      value = "",
      from = render(now.minusSeconds(DefaultWindowSeconds)),
      to = render(now)
    )

  /** The instant, as the `2026-09-04T09:15` a browser's own datetime field shows. Seconds and below are
    * dropped because the control cannot show them and a value it cannot show is a value it would silently
    * discard on the next edit.
    */
  def render(at: Instant): String = at.toString.take(16)

  /** The topics the user listed: commas or whitespace, in either combination, because a person pasting a list
    * out of a terminal has one and a person typing has the other.
    */
  def topicsOf(raw: String): List[String] =
    raw.split("[,\\s]+").toList.map(_.trim).filter(_.nonEmpty).distinct

  /** The request this form describes, or the first thing wrong with it.
    *
    * First and not all: the form has five controls, the sentences appear one at a time under the button, and
    * a list of five complaints is one nobody reads. The order is the order a person fills the form in.
    */
  def query(form: TrackForm): Either[String, TrackQueryDto] =
    for {
      topics <- validTopics(form)
      _ <- validHeader(form)
      _ <- validValue(form)
      window <- validWindow(form)
    } yield TrackQueryDto(
      topics = topics,
      `match` = TrackMatchDto(
        source = form.source,
        // Sent only for a header search, and rejected by the service when it is sent with any other source
        // — a request that names both a header and a value search is one whose author believed something
        // untrue about it.
        header = Option.when(form.source == TrackMatchDto.Source.Header)(form.header.trim),
        operator = form.operator,
        value = form.value
      ),
      from = window._1,
      to = window._2,
      limit = None
    )

  private def validTopics(form: TrackForm): Either[String, List[TopicName]] =
    topicsOf(form.topics) match {
      case Nil => Left(TrackMessages.NoTopics)
      case names =>
        // The first bad name, not all of them: the sentence appears under one button and a list of five
        // complaints is a list nobody reads.
        names.foldRight(Right(Nil): Either[String, List[TopicName]]) { (name, rest) =>
          for {
            parsed <- TopicName.from(name).left.map(_ => TrackMessages.badTopic(name))
            tail <- rest
          } yield parsed :: tail
        }
    }

  private def validHeader(form: TrackForm): Either[String, Unit] =
    if form.source == TrackMatchDto.Source.Header && form.header.trim.isEmpty then
      Left(TrackMessages.NoHeader)
    else Right(())

  private def validValue(form: TrackForm): Either[String, Unit] =
    if form.value.isEmpty then Left(TrackMessages.NoValue) else Right(())

  private def validWindow(form: TrackForm): Either[String, (Instant, Instant)] =
    (MessagesPage.parseTimestamp(form.from), MessagesPage.parseTimestamp(form.to)) match {
      case (None, _) => Left(TrackMessages.BadFrom)
      case (_, None) => Left(TrackMessages.BadTo)
      case (Some(from), Some(to)) if to <= from => Left(TrackMessages.BackwardsWindow)
      case (Some(from), Some(to)) => Right((Instant.ofEpochMilli(from), Instant.ofEpochMilli(to)))
    }
}
