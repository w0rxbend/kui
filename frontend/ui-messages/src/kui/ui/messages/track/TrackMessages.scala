package kui.ui.messages.track

/** Every word the track screen says, in one place, for the same reason `Messages` exists next door: a string
  * typed at the point of use is a string nobody can find when it needs changing, and one that is wrong in two
  * places is wrong differently in each.
  */
object TrackMessages {

  val Title: String = "Track an event"

  val Lead: String =
    "Follow one value — an order id, a correlation id — across several topics inside a time window. " +
      "Each topic is read forwards from the start of the window; nothing is indexed, so a narrow window " +
      "and a short list of topics is a fast answer and a wide one is a long scan."

  val TopicsLabel: String = "Topics"
  val TopicsPlaceholder: String = "orders.v1, shipments.v1"
  val TopicsHint: String = "Separated by commas or spaces. Every topic named is read."

  val SourceLabel: String = "Look in"
  val SourceValue: String = "Value"
  val SourceKey: String = "Key"
  val SourceHeader: String = "Header"

  val HeaderLabel: String = "Header name"
  val HeaderPlaceholder: String = "order-id"

  val OperatorLabel: String = "Compare"
  val OperatorContains: String = "contains"
  val OperatorEquals: String = "equals"
  val OperatorMatches: String = "matches (regex)"

  val ValueLabel: String = "Value"
  val ValuePlaceholder: String = "order-4711"

  val FromLabel: String = "From"
  val ToLabel: String = "To"
  val WindowHint: String = "UTC. A window with no zone on it is read as UTC, like every other time in KUI."

  val Search: String = "Search"
  val Searching: String = "Searching…"

  // --- What can be wrong with the form ------------------------------------------------------------

  val NoTopics: String = "Name at least one topic to search."
  val NoValue: String = "Type the value to look for."
  val NoHeader: String = "A header search needs the header's name."
  val BadFrom: String = "The start of the window is not a time KUI can read."
  val BadTo: String = "The end of the window is not a time KUI can read."
  val BackwardsWindow: String = "The end of the window is before its start."

  def badTopic(name: String): String = s"'$name' is not a name Kafka would accept for a topic."

  // --- What came back -----------------------------------------------------------------------------

  val EmptyTitle: String = "Nothing to search yet"
  val EmptyDescription: String =
    "Name the topics, say what to look for, and press Search. Nothing is read until you ask, because a " +
      "track reads every record in the window."

  val NoHitsTitle: String = "No hits"

  /** The sentence that makes "no hits" interpretable, and the reason `scanned` is on the answer at all: a
    * scan that read nothing and a scan that read a million records and rejected them are the same screen
    * without it, and they mean opposite things.
    */
  def noHits(scanned: Long): String =
    if scanned == 0L then
      "Nothing was read at all, so the window is probably empty or the topics were written to at another " +
        "time. Widen the window before concluding the value is not there."
    else s"$scanned records were read and none of them matched."

  def found(hits: Int, scanned: Long): String =
    s"${if hits == 1 then "1 hit" else s"$hits hits"} in $scanned records read."

  val Truncated: String =
    "The scan stopped at its limit, so this is not all of them. Narrow the window or the topic list."

  val ColumnTopic: String = "Topic"
  val ColumnPartition: String = "Partition"
  val ColumnOffset: String = "Offset"
  val ColumnTimestamp: String = "Timestamp"
  val ColumnKey: String = "Key"
  val ColumnValue: String = "Value"

  /** How much of a payload one row shows. The same number the record table uses, because the two tables sit
    * one click apart and rows of different heights read as two different products.
    */
  val PreviewLength: Int = 160
}
