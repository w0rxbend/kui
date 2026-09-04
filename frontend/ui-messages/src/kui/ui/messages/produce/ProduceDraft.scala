package kui.ui.messages.produce

import kui.contracts.message.{DecodedPayloadDto, HeaderDto}
import kui.kernel.{PartitionId, TopicName}
import kui.message.contract.{MessageDto, ProduceRequestDto}
import kui.ui.messages.SerdeChoices

/** What somebody has typed into the publish form, before it is a request.
  *
  * ## Why the form's state is a value and not the DOM
  *
  * Because two of the decisions it holds are invisible in a text box. A record with **no key** and a record
  * with an **empty key** are different records to Kafka, and a record with no value is a *tombstone* — the
  * record that tells a compacted topic to forget a key — while a record with an empty value is an ordinary
  * record that happens to hold nothing. A form that read its answer out of `input.value` at submit time
  * cannot tell those apart, because both are the empty string. So the absence is a checkbox with its own
  * field here, and the text box beside it is disabled while it is ticked.
  *
  * Getting that wrong is not cosmetic. A user who meant "publish an empty string" and got a tombstone has
  * deleted a key from a compacted topic, and nothing on the screen would have said so.
  *
  * ## Why the topic is in the draft
  *
  * So that republishing a record into a *different* topic is the same form as publishing a new one. That is
  * the whole of the republish feature: open a record, press Republish, and the form comes up holding what
  * that record contained, with every field still editable — including which topic it goes to.
  */
final case class ProduceDraft(
    topic: String,
    partition: String,
    key: String,
    hasKey: Boolean,
    value: String,
    isTombstone: Boolean,
    headers: List[(String, String)],
    /** How the key is turned into bytes, or [[kui.ui.messages.SerdeChoices.Automatic]] — the empty string —
      * to let the service resolve the same serde it would read the record back with.
      */
    keySerde: String,
    valueSerde: String,
    count: String
) {

  /** The draft as the document the service takes, or the reason it is not ready.
    *
    * Every check here is one the *server* also makes, and deliberately so: this one exists to put the message
    * next to the field while the user is still looking at it, and the server's exists because a browser is
    * not a security boundary. Where they disagree the server wins, and its answer is what the drawer shows.
    */
  def request: Either[String, (TopicName, ProduceRequestDto)] =
    for {
      destination <- TopicName.from(topic.trim).left.map(_ => ProduceDraft.TopicRequired)
      chosen <- ProduceDraft.partitionOf(partition)
      copies <- ProduceDraft.countOf(count)
      named <- ProduceDraft.namedHeaders(headers)
    } yield (
      destination,
      ProduceRequestDto(
        partition = chosen,
        // `None` is a record with no key at all, which Kafka partitions differently from a record whose
        // key happens to be empty. The checkbox is the only thing that can say which was meant.
        key = Option.when(hasKey)(key),
        // `None` is a tombstone. This single `Option` is the difference between "forget this key" and
        // "here is an empty value", and it is why the form has a checkbox rather than a text box that
        // somebody clears.
        value = Option.unless(isTombstone)(value),
        headers = named.map((name, text) => HeaderDto(name, Some(text))),
        // Empty means "let the service resolve it", which is the default and resolves the same serde the
        // browse screen would decode the record with. A name is sent only when somebody chose one, so the
        // one mistake this form can make that leaves a topic worse than it found it — writing with a serde
        // the reader cannot read back — stays something you have to ask for rather than something you can
        // do by leaving a control alone.
        keySerde = ProduceDraft.chosen(keySerde),
        valueSerde = ProduceDraft.chosen(valueSerde),
        count = copies
      )
    )
}

object ProduceDraft {

  val TopicRequired: String = "A topic name is needed."

  /** A chosen serde name, or `None` for "let the service decide".
    *
    * Blank is not a serde called "". An empty `Some("")` would reach the service as a name it cannot resolve
    * and would turn the default — the case that must always work — into a 400.
    */
  def chosen(raw: String): Option[String] = Option(raw.trim).filter(_.nonEmpty)

  val PartitionNotANumber: String = "The partition has to be a whole number, or empty to let Kafka choose."

  val CountNotANumber: String = "The number of copies has to be a whole number of at least one."

  val HeaderNeedsAName: String = "Every header needs a name."

  /** An empty form for a topic: one record, no key, no headers. */
  def empty(topic: TopicName): ProduceDraft =
    ProduceDraft(
      topic = topic.value,
      partition = "",
      key = "",
      hasKey = false,
      value = "",
      isTombstone = false,
      headers = Nil,
      keySerde = SerdeChoices.Automatic,
      valueSerde = SerdeChoices.Automatic,
      count = "1"
    )

  /** A form holding what a record on the screen contains, ready to be edited and sent again.
    *
    * This is the republish path, and what it carries is the *decoded* text — what the reader can see and
    * change — rather than the original bytes. That distinction is the whole difference between the two
    * actions this feature offers: republishing re-serialises what you have edited, so it is a new record you
    * are responsible for; resending copies the bytes untouched, so it is the same record moved.
    *
    * A tombstone and a keyless record come back as exactly that, because the payload document says which it
    * was — an absent payload rather than empty text — and collapsing the two here would turn "republish this
    * tombstone" into "publish an empty value" without anybody seeing it happen.
    */
  def of(topic: TopicName, record: MessageDto): ProduceDraft =
    ProduceDraft(
      topic = topic.value,
      partition = "",
      key = textOf(record.key),
      hasKey = isPresent(record.key),
      value = textOf(record.value),
      isTombstone = !isPresent(record.value),
      headers = record.headers.toList.sortBy(_._1),
      // The serde the record was *read* with, so republishing writes it back the way the reader will read
      // it. That is the whole difference between this and picking a serde by hand: the form starts on the
      // answer that round-trips, and changing it is a deliberate act.
      keySerde = SerdeChoices.offered(record.key.serde),
      valueSerde = SerdeChoices.offered(record.value.serde),
      count = "1"
    )

  private def isPresent(payload: DecodedPayloadDto): Boolean =
    payload.kind != DecodedPayloadDto.Kind.Absent

  private def textOf(payload: DecodedPayloadDto): String =
    if isPresent(payload) then payload.text else ""

  /** A partition number, or "let Kafka choose".
    *
    * Blank is not zero. A form that read an empty box as partition 0 would quietly pin every record somebody
    * published to one partition, which on a keyed topic breaks the ordering the keys were chosen for.
    */
  def partitionOf(raw: String): Either[String, Option[PartitionId]] =
    raw.trim match {
      case "" => Right(None)
      case text =>
        text.toIntOption.flatMap(number => PartitionId.from(number).toOption) match {
          case Some(partition) => Right(Some(partition))
          case None => Left(PartitionNotANumber)
        }
    }

  /** How many copies. Blank means one, because that is what a form with an untouched field means. */
  def countOf(raw: String): Either[String, Int] =
    raw.trim match {
      case "" => Right(1)
      case text => text.toIntOption.filter(_ >= 1).toRight(CountNotANumber)
    }

  /** Headers, with the blank rows the form leaves behind dropped and a value without a name refused.
    *
    * A row where both boxes are empty is somebody who pressed "add header" and changed their mind, and
    * silently ignoring it is right. A row with a value and no name is a mistake worth naming: the header
    * would be dropped, and the record would be published looking as though it had been sent.
    */
  def namedHeaders(rows: List[(String, String)]): Either[String, List[(String, String)]] = {
    val kept = rows.filterNot((name, value) => name.trim.isEmpty && value.isEmpty)
    if kept.exists((name, _) => name.trim.isEmpty) then Left(HeaderNeedsAName)
    else Right(kept.map((name, value) => name.trim -> value))
  }

  given CanEqual[ProduceDraft, ProduceDraft] = CanEqual.derived
}
