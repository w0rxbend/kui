package kui.ui.messages

/** Every sentence this feature shows, in one place (ADR-024).
  *
  * One object per module rather than a message catalogue with lookups: KUI ships in English and has no i18n
  * runtime. The gain is consistency and review — the wording can be read as prose in one file instead of
  * being hunted for across screens.
  */
object Messages {

  val Title: String = "Messages"

  /** The feature's own half of the shell's fallback panel.
    *
    * Only the feature can write this sentence: the reason, the "since", the retry and the list of other
    * working features are the shell's, and it draws them around this. What belongs here is what a user can
    * still do while the message service is down (ADR-032).
    */
  val UnavailableView: String =
    "Reading messages is unavailable while the message service is down. The dashboard, the brokers page, " +
      "the topic explorer and consumer groups all still work, and nothing on your Kafka clusters is " +
      "affected — no records are lost while KUI cannot read them."

  // --- The controls ----------------------------------------------------------------------------

  val SeekLabel: String = "Start from"
  val SeekBeginning: String = "The beginning"
  val SeekLatest: String = "The end"
  val SeekOffset: String = "An offset"
  val SeekTimestamp: String = "A time"

  val OffsetPlaceholder: String = "Offset, e.g. 41284"
  val TimestampPlaceholder: String = "2026-09-04T09:15 or epoch milliseconds"

  val PartitionsLabel: String = "Partitions"
  val PartitionsPlaceholder: String = "All partitions"
  val PartitionsHint: String = "Partition numbers, separated by commas. Empty means every partition."

  val FilterLabel: String = "Contains"
  val FilterPlaceholder: String = "Text the record must contain"

  val LimitLabel: String = "Records"

  val LiveLabel: String = "Follow live"
  val LiveHint: String =
    "Start at the end and keep the stream open, so new records appear as they are produced."

  val Read: String = "Read"
  val Stop: String = "Stop"
  val TryAgain: String = "Try again"

  // --- The status line -------------------------------------------------------------------------

  val Connecting: String = "Connecting"
  val Streaming: String = "Reading"
  val Finished: String = "Finished"

  /** The count is the one number that says whether a filter matched nothing or the topic is empty. */
  def delivered(records: Int): String = if records == 1 then "1 record" else s"$records records"

  def scanned(records: Long): String =
    if records == 1L then "1 record read from Kafka" else s"$records records read from Kafka"

  val EmptyTitle: String = "No records yet"
  val EmptyDescription: String =
    "Choose where to start and press Read. Nothing is fetched until you ask, because a topic can hold " +
      "a great deal more than a screen."

  val NothingMatchedTitle: String = "Nothing matched"
  val NothingMatchedDescription: String =
    "Records were read but none of them contained that text. Widening the range or clearing the filter " +
      "is usually the next step."

  val ExhaustedTitle: String = "No records"
  val ExhaustedDescription: String = "There are no records in the range you asked for."

  // --- The table -------------------------------------------------------------------------------

  val ColumnOffset: String = "Offset"
  val ColumnPartition: String = "Partition"
  val ColumnKey: String = "Key"
  val ColumnTimestamp: String = "Timestamp"
  val ColumnValue: String = "Value"

  /** A record with no key, and a record with no value, are different facts and neither is an empty string. */
  val NoKey: String = "no key"
  val Tombstone: String = "tombstone"

  val Expand: String = "Show this record"
  val Collapse: String = "Hide this record"

  val KeyHeading: String = "Key"
  val ValueHeading: String = "Value"
  val HeadersHeading: String = "Headers"
  val NoHeaders: String = "This record carries no headers"

  /** Which clock stamped the record. Without it, a timestamp search that returns nothing is inexplicable: the
    * topic may be configured so the broker overwrites every producer's timestamp on append.
    */
  def timestampType(kind: String): String =
    if kind == "LogAppendTime" then "stamped by the broker on append" else "stamped by the producer"

  def decodeFailed(target: String, serde: String, cause: String): String =
    s"The $target could not be read with $serde: $cause"

  /** The sentence under a record delivered through the fallback serde. A record that could not be decoded is
    * still delivered, because the bytes are what the user came to see.
    */
  val FallbackNote: String =
    "Shown as raw bytes, because no configured serde could read it."

  // What is deliberately not here: any wording for publishing or resending a record. Those are mutations,
  // governed by ADR-045's plan-token confirmation and ADR-047's read-only refusal and audit, and the message
  // service does not serve them yet. A control that cannot be honoured end to end is a promise with a date
  // on it (DEVPLAN §10 D8).
}
