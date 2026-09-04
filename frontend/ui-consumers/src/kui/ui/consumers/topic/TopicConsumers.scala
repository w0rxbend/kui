package kui.ui.consumers.topic

import io.circe.Json

import kui.contracts.Section
import kui.contracts.consumer.TopicConsumerRowDto
import kui.gateway.contract.dto.TopicOverviewDto

/** What the topic page's Consumers tab has to draw, decided from the overview document alone.
  *
  * ## Why this is a separate value from the panel that renders it
  *
  * Because the interesting part is not the markup. The tab reads one section of a five-section aggregation,
  * and that section has five possible statuses, two of which carry data, two of which are not errors, and one
  * of which is. Getting those wrong produces screens that lie quietly: a `NotConfigured` section rendered as
  * an error puts a permanent red panel on every topic page of a deployment that has no consumer service, and
  * an `Unavailable` section rendered as an empty table tells an operator that nothing is reading their topic
  * at the exact moment KUI cannot tell.
  *
  * All of that is a function of one document, so it is written as one, and the suite drives it with documents
  * rather than with a DOM.
  */
enum TopicConsumersView {

  /** Rows to draw. Empty is a real answer — nothing consumes this topic — and is drawn as an empty state,
    * never as a failure.
    *
    * @param stale
    *   the gateway served the last snapshot it had rather than a fresh one. The rows are real and old, and
    *   the panel says so above them.
    */
  case Rows(rows: List[TopicConsumerRowDto], stale: Boolean)

  /** This deployment has no consumer service, so there is nothing to say and nothing is wrong.
    *
    * The tab is only on screen at all because this feature is loaded, which in a deployment without a
    * consumer service it would not be — but a per-cluster `NotConfigured` is possible even where the service
    * exists, so the case is handled rather than assumed away.
    */
  case Absent

  /** The consumer service exists and could not be asked, or the answer could not be read. A sentence and a
    * retry, never an empty table.
    */
  case Unreadable(message: String)
}

object TopicConsumersView {
  given CanEqual[TopicConsumersView, TopicConsumersView] = CanEqual.derived
}

object TopicConsumers {

  /** The tab, from the gateway's topic overview.
    *
    * The rows arrive as `Json` because their shape belongs to the consumer service and the gateway must not
    * declare it — that is what keeps a new section from being a change to a shared type. They are decoded
    * here with the consumer contract's own codec, so the browser reads exactly what the service wrote.
    *
    * A row that does not decode fails the whole tab rather than being skipped. Skipping it would show an
    * operator a shorter list of consumer groups than exists, with nothing saying a row was dropped, and "no
    * group is behind" read off a list that quietly lost the group that is behind is the worst answer this
    * panel can give.
    */
  def of(overview: TopicOverviewDto): TopicConsumersView =
    overview.consumerGroups match {
      case Section.NotConfigured => TopicConsumersView.Absent
      case Section.Forbidden => TopicConsumersView.Unreadable(Forbidden)
      case Section.Unavailable(reason, message, _) =>
        TopicConsumersView.Unreadable(unavailable(reason.wire, message))
      case Section.Ok(rows, _) => decoded(rows, stale = false)
      case Section.Stale(rows, _, _) => decoded(rows, stale = true)
    }

  private def decoded(rows: List[Json], stale: Boolean): TopicConsumersView =
    rows.foldLeft[Either[String, List[TopicConsumerRowDto]]](Right(Nil)) { (accumulated, row) =>
      for {
        sofar <- accumulated
        one <- row.as[TopicConsumerRowDto].left.map(_.getMessage)
      } yield sofar :+ one
    } match {
      case Right(decodedRows) => TopicConsumersView.Rows(decodedRows, stale)
      case Left(failure) => TopicConsumersView.Unreadable(undecodable(failure))
    }

  val Forbidden: String =
    "You do not have permission to see which consumer groups read this topic."

  def unavailable(reason: String, detail: String): String =
    s"The consumer groups for this topic could not be read ($reason). $detail"

  def undecodable(failure: String): String =
    "KUI could not read the consumer groups this cluster reported, so none are shown rather than some: " +
      s"$failure"
}
