package kui.ui.messages.table

/** How far the table view is willing to unfold a record before it stops.
  *
  * Every one of these numbers has the same justification: a Kafka record is arbitrary data written by
  * somebody else's producer, and a table view that trusts it will one day be handed a 40-level document with
  * ten thousand distinct keys and will freeze the browser tab. The caps make the worst case bounded and the
  * markers (see `JsonFlattener`) make the truncation visible, because a cap the user cannot see is a lie
  * about the data.
  *
  * They are constructor parameters rather than constants so that the property suite can drive the flattener
  * at small values — a depth cap of 3 is very hard to violate by accident in a generated document, and a
  * depth cap of 1 is not. DEVPLAN §10 D4 makes this the **only** place the numbers appear; a cap written
  * twice is a cap that drifts.
  *
  * @param maxDepth
  *   how many field or index steps below the `H` / `K` / `V` root are expanded into their own columns.
  *   Anything deeper becomes one cell holding the remaining subtree as compact JSON.
  * @param maxRows
  *   how many records the table holds at once.
  * @param maxColumns
  *   how many distinct paths become columns. The rest stay reachable through the column picker.
  * @param maxArrayElements
  *   how many elements of one array become their own columns before the remainder collapses into a single
  *   `+N more` cell.
  */
final case class FlattenLimits(maxDepth: Int, maxRows: Int, maxColumns: Int, maxArrayElements: Int)

object FlattenLimits {

  /** Kouncil's measured numbers (`research/kouncil/ui-analysis.md`, `json-grid.ts`), kept rather than
    * re-derived because they have been tuned against real topics by real users; `maxColumns` is KUI's
    * addition, since Kouncil has no column picker to fall back on.
    */
  val Default: FlattenLimits =
    FlattenLimits(maxDepth = 3, maxRows = 1000, maxColumns = 120, maxArrayElements = 10)

  given CanEqual[FlattenLimits, FlattenLimits] = CanEqual.derived
}
