package kui.ui.consumers.reset

import java.time.Instant

import scala.util.control.NonFatal

import kui.consumer.contract.dto.ResetPlanRequest
import kui.kernel.TopicName
import kui.kernel.group.ResetTarget
import kui.ui.consumers.Messages

/** What the wizard's first step holds, before it is a request.
  *
  * Every field is the string the user typed, because that is what an input holds and because a field that
  * stored a parsed value would have to decide what to do while somebody is halfway through typing "-" or
  * "1e". Turning it into a request is [[ResetForm.requestOf]], which either succeeds completely or names one
  * thing that is wrong.
  *
  * @param offset
  *   one offset for every partition in scope. The contract's `OFFSET` mode takes a map keyed by partition,
  *   and this screen only offers the same offset everywhere: a control for sixty different offsets is a table
  *   of sixty inputs, and the one case anybody actually wants — "put all of them at 0" — is expressible here.
  *   A per-partition reset remains available through the API, which is the honest place for it
  */
final case class ResetForm(
    topic: String,
    target: ResetTarget,
    offset: String,
    timestamp: String,
    shiftBy: String,
    durationMinutes: String
)

object ResetForm {

  val Empty: ResetForm =
    ResetForm(
      topic = "",
      target = ResetTarget.Earliest,
      offset = "",
      timestamp = "",
      shiftBy = "",
      durationMinutes = ""
    )

  given CanEqual[ResetForm, ResetForm] = CanEqual.derived

  /** Which extra field this target needs, or none.
    *
    * The same question the contract's decoder asks, asked here so that the wizard can hide the fields that do
    * not apply. Two implementations of one rule is how they stop agreeing, so this is only about *which field
    * to show*; whether it is filled in is decided by [[requestOf]] and, again and authoritatively, by the
    * server's own decoder — a request missing its parameter is refused there rather than defaulted, because
    * defaulting a missing timestamp to "now" resets a consumer group to a point in time nobody asked for.
    */
  def parameterOf(target: ResetTarget): Option[String] = target match {
    case ResetTarget.Earliest | ResetTarget.Latest => None
    case ResetTarget.Offset => Some("offset")
    case ResetTarget.Timestamp => Some("timestamp")
    case ResetTarget.ShiftBy => Some("shiftBy")
    case ResetTarget.Duration => Some("durationMinutes")
  }

  /** The request, or the one sentence to put under the field that is wrong.
    *
    * @param partitions
    *   the partitions this group holds on the chosen topic. They are named explicitly rather than left empty
    *   — which the contract reads as "every partition" — so that the plan covers exactly what the screen
    *   showed, and so that a partition added to the topic between the page loading and the plan being asked
    *   for is not silently swept in.
    */
  def requestOf(form: ResetForm, partitions: List[Int]): Either[String, ResetPlanRequest] =
    for {
      topic <- TopicName.from(form.topic.trim).left.map(_ => Messages.NoTopic)
      request <- form.target match {
        case ResetTarget.Earliest | ResetTarget.Latest =>
          Right(base(topic, partitions, form.target))

        case ResetTarget.Offset =>
          positive(form.offset, Messages.BadOffset).map(value =>
            base(topic, partitions, form.target)
              .copy(offsets = Some(partitions.map(partition => partition.toString -> value).toMap))
          )

        case ResetTarget.Timestamp =>
          instantOf(form.timestamp).map(at => base(topic, partitions, form.target).copy(timestamp = Some(at)))

        case ResetTarget.ShiftBy =>
          // Deliberately allowed to be negative: shifting back is the point of it.
          form.shiftBy.trim.toLongOption
            .toRight(Messages.BadShift)
            .map(by => base(topic, partitions, form.target).copy(shiftBy = Some(by)))

        case ResetTarget.Duration =>
          positive(form.durationMinutes, Messages.BadDuration)
            .filterOrElse(_ > 0L, Messages.BadDuration)
            .map(minutes => base(topic, partitions, form.target).copy(durationMs = Some(minutes * 60000L)))
      }
      checked <- Either.cond(partitions.nonEmpty, request, Messages.NoPartitions)
    } yield checked

  private def base(topic: TopicName, partitions: List[Int], target: ResetTarget): ResetPlanRequest =
    ResetPlanRequest(
      topic = topic,
      partitions = partitions.sorted,
      target = target,
      timestamp = None,
      offsets = None,
      shiftBy = None,
      durationMs = None
    )

  private def positive(raw: String, whenWrong: String): Either[String, Long] =
    raw.trim.toLongOption.filter(_ >= 0L).toRight(whenWrong)

  /** A `datetime-local` value — `2026-09-04T09:00` — read in the browser's own zone.
    *
    * The input has no zone in it, which is exactly what an operator means by "09:00": the time on the clock
    * they were looking at when the incident happened. `js.Date` applies the browser's zone, and the request
    * carries the resulting instant, so what reaches the server is unambiguous even though what was typed was
    * not.
    */
  def instantOf(raw: String): Either[String, Instant] = {
    val trimmed = raw.trim
    if trimmed.isEmpty then Left(Messages.BadTimestamp)
    else
      try {
        val millis = new scala.scalajs.js.Date(trimmed).getTime()
        if millis.isNaN then Left(Messages.BadTimestamp)
        else Right(Instant.ofEpochMilli(millis.toLong))
      } catch { case NonFatal(_) => Left(Messages.BadTimestamp) }
  }
}
