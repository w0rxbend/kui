package kui.ui.consumers.list

import kui.consumer.contract.dto.{LagDeltaDto, LagUpdateDto}
import kui.contracts.consumer.GroupSummaryDto
import kui.kernel.GroupId

/** What the poller has learned since the page was drawn: which groups moved, and which stopped existing.
  *
  * Held apart from the page's rows on purpose. The rows are a *page* of a server-sorted, server-filtered
  * list, and the poller must not be allowed to change which rows those are — a lag figure arriving must never
  * reorder the table under somebody's cursor. So the rows stay exactly as the list endpoint sent them and
  * this is laid over the top, cell by cell.
  *
  * @param gone
  *   groups the server says no longer exist. They are removed rather than left showing their last lag: a
  *   deleted group is not a group that has caught up, and a row that stays on screen forever is what the
  *   delta protocol's `full` flag exists to prevent.
  */
final case class LagView(changed: Map[GroupId, LagUpdateDto], gone: Set[GroupId])

object LagView {
  val Empty: LagView = LagView(Map.empty, Set.empty)
  given CanEqual[LagView, LagView] = CanEqual.derived
}

/** Folding lag deltas into a view, and laying that view over a row.
  *
  * A plain function of two values, with no timer and no request in it, because the arithmetic is where the
  * mistakes are and a test of it should not have to wait for a clock.
  */
object LagFeed {

  /** The new view after one answer.
    *
    * `full` decides between merging and replacing, and getting it wrong is invisible. A **full** payload is
    * the server saying "start again" — it is what an unrecognised, expired or restarted-service token is
    * answered with — and merging one leaves every group that has since been deleted on screen for ever,
    * showing the lag it had when it died. So a full answer replaces the view outright.
    *
    * A **delta** carries only what moved, so it merges: a group absent from `changed` has not changed, and
    * dropping it would blank a cell that was correct.
    */
  def merge(current: LagView, delta: LagDeltaDto): LagView = {
    val arrived = delta.changed.map(update => update.groupId -> update).toMap
    val departed = delta.gone.toSet

    if delta.full then LagView(arrived, departed)
    else
      LagView(
        // A group that has come back is no longer gone; a group that has gone carries no update. Both
        // directions are applied so the two sets cannot drift into disagreeing about one group.
        changed = (current.changed ++ arrived).filterNot((id, _) => departed.contains(id)),
        gone = (current.gone -- arrived.keySet) ++ departed
      )
  }

  /** The rows as they should now be drawn: gone groups removed, moved groups repainted.
    *
    * Only the four fields the delta carries are replaced. Everything else on the row — the topic count, the
    * partition count, the coordinator, the completeness note — comes from the list endpoint and is not part
    * of the poll, because sending a whole group summary every few seconds for a page of twenty-five would
    * make this the most expensive request in the product.
    */
  def applyTo(rows: List[GroupSummaryDto], view: LagView): List[GroupSummaryDto] =
    rows.filterNot(row => view.gone.contains(row.groupId)).map { row =>
      view.changed.get(row.groupId) match {
        case None => row
        case Some(update) =>
          row.copy(
            state = update.state,
            members = update.members,
            totalLag = update.totalLag,
            pace = update.pace
          )
      }
    }
}
