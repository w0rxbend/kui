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

  /** Where the last finished browse stopped, as the server signed it.
    *
    * Held here and nowhere else. The browser cannot compute the next offsets — forward and backward
    * boundaries are different numbers for the same place — so "load more" is entirely a matter of handing
    * this back, and a screen that lost it has no second page.
    */
  private val cursorVar: Var[Option[String]] = Var(None)

  /** What the last browse was, so that "load more" reads the same range in the same direction with the same
    * decoding. Continuing with the parameters the *controls* currently hold would silently change what the
    * next page is a continuation of, whenever somebody edited a control without pressing Read.
    */
  private var lastQuery: Option[BrowseQuery] = None

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

  /** Whether there is a next page to ask for.
    *
    * True only when a browse has finished *and* the server chose to send a cursor with it. The server omits
    * one whenever asking again would be pointless, so this is the server's own answer to "is there more"
    * rather than the browser guessing from a full page — which is the guess that puts a Load more button
    * under the last page of every topic.
    */
  val canLoadMore: Signal[Boolean] =
    cursorVar.signal.combineWith(running).map((cursor, isRunning) => cursor.isDefined && !isRunning)

  /** Starts a browse, discarding whatever the previous one delivered.
    *
    * Discarding rather than appending: the new parameters describe a different range, and mixing two ranges
    * in one table produces a list whose offsets jump about with nothing on screen to say why.
    */
  def start(query: BrowseQuery): EventStream[Unit] = run(query, keepRows = false)

  /** Reads the next page and **appends** it.
    *
    * Appending is the whole difference from [[start]], and it is safe here in a way it would not be for a
    * changed query: the cursor names the exact continuation of the range already on screen, in the same
    * direction, so the rows join onto the ones below them rather than being a second range mixed into the
    * first.
    *
    * With no cursor it does nothing rather than starting a fresh browse. A "load more" that quietly re-read
    * the first page would look like a button that scrolled the user back to where they began.
    */
  def loadMore(): EventStream[Unit] =
    (lastQuery, cursorVar.now()) match {
      case (Some(query), Some(cursor)) => run(query.copy(cursor = Some(cursor)), keepRows = true)
      case _ => EventStream.empty
    }

  private def run(query: BrowseQuery, keepRows: Boolean): EventStream[Unit] = {
    stop()
    if !keepRows then rowsVar.set(Nil)
    lastQuery = Some(query.copy(cursor = None))
    // The cursor from the *previous* page is spent the moment this one starts. Leaving it in place would
    // leave Load more offering the page that is already being read.
    cursorVar.set(None)
    progressVar.set(
      // The record count restarts with each page, because it is what the status line reports about the
      // request in flight; the table's own length is what says how much is on screen.
      BrowseSession.Idle.copy(connection = SseConnection.Connecting)
    )

    val handle = open(apiRoot, cluster, topic, query)
    handleVar.set(Some(handle))

    // The connection state is the stream's own; it is mirrored into the progress record so that the status
    // line reads one value rather than combining two signals that can disagree for a tick.
    //
    // A `Closed` state also releases the handle. `running` is "is there a handle", and the button reads
    // Stop while it is true — so without this a browse that ended by itself, which is what every bounded
    // browse does the moment it has read its limit, left the button saying Stop for ever. The status line
    // said "Finished" beside a button offering to stop the thing that had finished, and there was no way
    // back to Read short of reloading the page. The handle is already closed by then; dropping the
    // reference is all that is left to do, and `stop()` on an already-closed handle is a no-op anyway.
    val connections = handle.connection.changes.map { state =>
      progressVar.update(_.copy(connection = state))
      state match {
        case SseConnection.Closed(_) =>
          // The server's continuation, if it sent one. It arrives on the terminal `done` event and is
          // recorded before the connection state changes, so it is already there to be read here.
          cursorVar.set(handle.endMarker())
          handleVar.update(_.filterNot(_ eq handle))
        case SseConnection.Open | SseConnection.Connecting | SseConnection.Reconnecting(_) => ()
      }
    }

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

  /** Forgets the continuation, for a screen that is about to browse something else. */
  def forgetCursor(): Unit = cursorVar.set(None)
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
