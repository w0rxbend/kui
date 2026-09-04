package kui.ui.messages.browse

import com.raquo.laminar.api.L.*

import kui.kernel.{ClusterId, TopicName}
import kui.message.contract.{ConsumedDto, MessageDto}
import kui.ui.kernel.sse.{SseConnection, SseError, SseHandle}

/** What one browse looks like while it is running: the records so far, what the stream is doing, and how much
  * Kafka has been read.
  *
  * `scanned` is separate from the record count on purpose, and it is the number that makes a filtered browse
  * interpretable: a scan over a large topic routinely reads a million records and matches none of them, and
  * without this the screen is identical to a topic that is empty.
  */
final case class BrowseProgress(
    records: Int,
    consumed: Option[ConsumedDto],
    phase: Option[String],
    connection: SseConnection,
    failure: Option[SseError]
)

/** One running browse, owned by the screen.
  *
  * ## Why the rows are a `Var` and not a stream the table folds
  *
  * Because a browse is stopped, restarted with different parameters, and left running while the user reads.
  * Holding the accumulated rows in one place makes "clear and start again" a single assignment, and makes the
  * table's input an ordinary `Signal[List[…]]` — the same input every other table in KUI takes.
  *
  * ## The cap, and why there is one
  *
  * A live browse on a busy topic delivers faster than a person reads, forever. Without a bound the row list
  * grows until the tab dies, which is a failure the user cannot diagnose and cannot undo. So the newest
  * [[BrowseSession.MaxRows]] are kept and older ones are dropped, which is what "follow live" means anyway:
  * the interesting end of a tail is the new end. A bounded browse — one with a limit — never reaches the cap,
  * because the service stops first.
  *
  * ## Cancellation
  *
  * `stop()` aborts the request, and that propagates to a closed Kafka consumer on the service (ADR-035). The
  * screen binds it to the element's unmount as well as to the Stop button, so navigating away cannot leave a
  * consumer running — the thing the whole abortable-fetch transport exists for.
  */
final class BrowseSession(
    apiRoot: String,
    cluster: ClusterId,
    topic: TopicName,
    open: (String, ClusterId, TopicName, BrowseQuery) => SseHandle[BrowseEvent] = BrowseStream.open
) {

  private val rowsVar: Var[List[MessageDto]] = Var(Nil)
  private val progressVar: Var[BrowseProgress] = Var(BrowseSession.Idle)
  private val handleVar: Var[Option[SseHandle[BrowseEvent]]] = Var(None)

  /** The records so far, newest first.
    *
    * Newest first regardless of the direction the service read in, because the top of a table is where the
    * eye starts and the newest record is what a person opening a topic wants to see. A backwards browse
    * delivers in that order already; a forwards one does not, and reversing it here rather than in the table
    * keeps the table an ordinary table.
    */
  val rows: Signal[List[MessageDto]] = rowsVar.signal

  val progress: Signal[BrowseProgress] = progressVar.signal

  val running: Signal[Boolean] = handleVar.signal.map(_.isDefined)

  /** Starts a browse, discarding whatever the previous one delivered.
    *
    * Discarding rather than appending: the new parameters describe a different range, and mixing two ranges
    * in one table produces a list whose offsets jump about with nothing on screen to say why.
    */
  def start(query: BrowseQuery): EventStream[Unit] = {
    stop()
    rowsVar.set(Nil)
    progressVar.set(BrowseSession.Idle.copy(connection = SseConnection.Connecting))

    val handle = open(apiRoot, cluster, topic, query)
    handleVar.set(Some(handle))

    // The connection state is the stream's own; it is mirrored into the progress record so that the status
    // line reads one value rather than combining two signals that can disagree for a tick.
    val connections = handle.connection.changes.map(state => progressVar.update(_.copy(connection = state)))

    val events = handle.events.map {
      case Right(BrowseEvent.Record(message)) =>
        rowsVar.update(current => (message :: current).take(BrowseSession.MaxRows))
        progressVar.update(progress => progress.copy(records = progress.records + 1, phase = None))
      case Right(BrowseEvent.Phase(phase)) => progressVar.update(_.copy(phase = Some(phase.name)))
      case Right(BrowseEvent.Consumed(consumed)) => progressVar.update(_.copy(consumed = Some(consumed)))
      // A failure is held beside the rows rather than replacing them: the records that did arrive are still
      // what the user asked for, and throwing them away to show an error would lose the evidence.
      case Left(error) => progressVar.update(_.copy(failure = Some(error)))
    }

    EventStream.merge(events, connections)
  }

  /** Stops the browse and releases the server's consumer. Idempotent, because unmount and the Stop button can
    * both reach it.
    */
  def stop(): Unit = {
    handleVar.now().foreach(_.close())
    handleVar.set(None)
  }
}

object BrowseSession {

  /** How many records one browse keeps on screen.
    *
    * Five hundred is a long way past what anybody scrolls and a long way short of what makes a tab
    * unresponsive. It bounds a live tail, which is otherwise unbounded by definition.
    */
  val MaxRows: Int = 500

  val Idle: BrowseProgress =
    BrowseProgress(
      records = 0,
      consumed = None,
      phase = None,
      connection = SseConnection.Closed("not started"),
      failure = None
    )
}
