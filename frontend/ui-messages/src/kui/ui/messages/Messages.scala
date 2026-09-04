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

  /** The two serde overrides. "Automatic" is the default and is a claim about behaviour, not an absence: the
    * service picks per topic and says which it used on every record.
    */
  /** The button under a finished browse. "More" and not "Next page": the records join onto the ones already
    * on screen rather than replacing them, and "next page" promises a replacement.
    */
  val LoadMore: String = "Load more"

  val KeySerdeLabel: String = "Key as"
  val ValueSerdeLabel: String = "Value as"
  val SerdeAutomatic: String = "Automatic"

  val LiveLabel: String = "Follow live"
  val LiveHint: String =
    "Start at the end and keep the stream open, so new records appear as they are produced."

  val Read: String = "Read"
  val Stop: String = "Stop"
  val Pause: String = "Pause"
  val Resume: String = "Resume"
  val PauseHint: String =
    "Hold new records back without closing the stream. Nothing is lost: what arrives while paused is " +
      "shown the moment you resume."
  val TryAgain: String = "Try again"

  // --- The smart filter (MS-007) -----------------------------------------------------------------

  val SmartFilterLabel: String = "Smart filter"
  val SmartFilterActive: String = "Smart filter (on)"
  val SmartFilterApply: String = "Apply filter"
  val SmartFilterClear: String = "Clear filter"
  val SmartFilterPlaceholder: String = "record.value.status == 'FAILED'"

  /** What a person can write, in one line. The variables are the ones `CelEnvironment` exposes; a hint that
    * named one it does not would be a documented feature that fails to compile the first time it is pasted.
    */
  val SmartFilterHint: String =
    "A CEL expression evaluated on the service, once per record, over record.value, record.key, " +
      "record.valueAsText, record.keyAsText, record.headers, record.partition, record.offset and " +
      "record.timestampMs. Only records it returns true for are delivered."

  // --- The export (MS-011) -----------------------------------------------------------------------

  val ExportCsv: String = "Export CSV"
  val ExportHint: String =
    "Downloads exactly what is on screen: the same records, in the same order, with the columns this " +
      "view shows. Nothing is read from Kafka again."

  val CsvMediaType: String = "text/csv;charset=utf-8"

  // --- The status line -------------------------------------------------------------------------

  val Connecting: String = "Connecting"
  val Streaming: String = "Reading"
  val Finished: String = "Finished"

  /** The count is the one number that says whether a filter matched nothing or the topic is empty. */
  def delivered(records: Int): String = if records == 1 then "1 record" else s"$records records"

  /** What a paused tail says about the records waiting behind the pause.
    *
    * It is on the status line so that a paused screen reads as paused rather than as a stream that stopped
    * for a reason nobody can see.
    */
  def waiting(records: Int): String = if records == 1 then "1 record waiting" else s"$records records waiting"

  def scanned(records: Long): String =
    if records == 1L then "1 record read from Kafka" else s"$records records read from Kafka"

  // --- The table view (MS-004) -----------------------------------------------------------------

  /** The query parameter that remembers which view is on screen.
    *
    * In the URL, like every other choice this screen makes, so that "look at this topic as a table" is a link
    * somebody can be sent. It is not a browse parameter — the server neither sees nor needs it — so it is
    * named here rather than in the contract.
    */
  val ViewParam: String = "view"

  val ViewList: String = "list"
  val ViewTable: String = "table"

  val ViewListLabel: String = "List"
  val ViewTableLabel: String = "Table"
  val ViewHint: String =
    "The table view spreads each record's JSON across columns, so a field can be scanned down the page " +
      "instead of read out of every record in turn."

  def columnCount(shown: Int, all: Int): String =
    if shown == all then s"Columns ($all)" else s"Columns ($shown of $all)"

  def rowCap(cap: Int, held: Int): String =
    s"Showing the newest $cap of $held records. The table view is capped so that a wide topic cannot " +
      "freeze the tab; the list view shows them all."

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

  // --- Publishing ------------------------------------------------------------------------------

  val Publish: String = "Publish"

  val ProduceTopicLabel: String = "Topic"
  val ProducePartitionLabel: String = "Partition"
  val ProducePartitionPlaceholder: String = "Let Kafka choose"

  val ProduceKeyLabel: String = "Key"

  /** Not "empty key". A record with no key at all is partitioned round-robin and never compacted against
    * another record; a record whose key is the empty string is neither of those things. The label says which
    * one the switch means, because the text box cannot.
    */
  val ProduceNoKeyLabel: String = "No key"

  val ProduceValueLabel: String = "Value"

  /** The word matters more than any other on this screen. Publishing a record with no value to a compacted
    * topic *deletes* the key, and a switch labelled "empty" would hide that behind a word people read as
    * harmless.
    */
  val ProduceTombstoneLabel: String = "Tombstone (deletes this key on a compacted topic)"

  val ProduceHeadersLabel: String = "Headers"
  val ProduceHeaderNamePlaceholder: String = "Name"
  val ProduceHeaderValuePlaceholder: String = "Value"
  val ProduceAddHeader: String = "Add a header"
  val ProduceRemoveHeader: String = "Remove"

  /** The two publish-form serde choices.
    *
    * "as" rather than "serde", and phrased the same way as the browse bar's "Key as" / "Value as", because
    * the two controls are the same choice pointed in opposite directions and reading them the same way is
    * what makes that obvious.
    */
  val ProduceKeySerdeLabel: String = "Write key as"
  val ProduceValueSerdeLabel: String = "Write value as"

  val ProduceCountLabel: String = "Copies"
  val ProduceCountHint: String =
    "How many identical records to publish. One is the ordinary case; more is for filling a topic while " +
      "testing a consumer."

  /** The offsets are the point of this sentence. Somebody who has just published needs to be able to go and
    * find the record, and "published successfully" leaves them searching their own topic for it.
    */
  def published(records: Int): String =
    if records == 1 then "Published 1 record." else s"Published $records records."

  def landedAt(partition: Int, offset: Long): String = s"partition $partition, offset $offset"

  /** Opening the publish form with this record's contents already in it. It is a *new* record when it is
    * sent, which is why the verb is not "resend".
    */
  val Republish: String = "Republish"
  val RepublishHint: String =
    "Open the publish form holding this record's key, value and headers, ready to edit and send again."

  // --- Resending -------------------------------------------------------------------------------

  val Resend: String = "Copy to another topic"
  val ResendHint: String =
    "Copy this record into another topic exactly as it is — the same bytes, the same headers, nothing " +
      "decoded on the way."

  val ResendDestinationLabel: String = "Destination topic"
  val ResendDestinationPlaceholder: String = "The topic to copy into"

  val ResendExplanation: String =
    "The record is copied byte for byte, headers included, and is never decoded — so this works even on a " +
      "topic KUI cannot read. Kafka chooses the partition in the destination."

  val ResendDestinationRequired: String = "A destination topic is needed."

  val ResendSameTopic: String =
    "The destination is the topic being read. Copying a topic into itself would read back what it just " +
      "wrote; choose another topic."

  val ResendRangeInvalid: String = "This record's partition and offset cannot be read."

  def resendSource(topic: String, partition: Int, from: Long, until: Long): String = {
    val count = until - from
    val what = if count == 1L then s"offset $from" else s"offsets $from to ${until - 1}"
    s"Copying $what of $topic, partition $partition."
  }

  /** Both numbers, always. They differ whenever retention removed part of the source under the copy, and a
    * screen that showed only the second could not tell "there was nothing left to copy" from "the copy did
    * nothing".
    */
  def resent(read: Long, written: Long, destination: String): String =
    s"Read $read and wrote $written into $destination."
}
