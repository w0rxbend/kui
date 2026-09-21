package kui.connect.domain

import kui.kernel.{ConnectName, ConnectorName, TaskId}

/** What a Kafka Connect worker calls the state of a connector or of one of its tasks.
  *
  * ==It is the worker's word, kept==
  *
  * This is a wrapper around a string rather than an enum, and that is the whole point. Connect's REST API
  * publishes `RUNNING`, `PAUSED`, `FAILED`, `UNASSIGNED` and `RESTARTING`, and it has published more of them
  * over time: `STOPPED` arrived in Kafka 3.5 (KIP-875) and `DESTROYED` appears on a connector mid-deletion. A
  * sealed enum would have to answer for a word it has never seen, and every available answer is a lie — an
  * `Unknown` case draws a grey pill over a connector the worker described perfectly well, and mapping the
  * nearest known state invents a fact.
  *
  * `SCREENS-V4.md` §7.7 is the same argument from the other end: a failed card must carry the *worker's* own
  * reason and never a status word KUI chose. So a state KUI has never heard of travels to the browser
  * unchanged, and the screen renders an unrecognised state in the neutral tone rather than refusing to draw
  * the row.
  *
  * The two constants below exist because two rules in this service *are* about specific states — a running
  * task is running and a failed one carries a trace — and comparing against a constant is what keeps those
  * spellings in one place. There was a third, `PAUSED`, justified by *"a paused connector's zero throughput
  * is a measured zero"*; there is no throughput anywhere on this wire (ADR-054 §7), so nothing in the product
  * ever asked the question and the constant and its `isPaused` were deleted rather than left as declarations
  * with no caller. A paused connector still reaches the browser: its state word travels unchanged, which is
  * this type's whole argument.
  */
final case class ConnectorState(wire: String) {

  /** Case-insensitively, because the API's own capitalisation is a documented `RUNNING` and at least one
    * Connect-compatible implementation answers `Running`. A comparison that cared would report a running
    * connector as unrecognised.
    */
  def is(other: ConnectorState): Boolean = wire.equalsIgnoreCase(other.wire)

  def isRunning: Boolean = is(ConnectorState.Running)

  def isFailed: Boolean = is(ConnectorState.Failed)
}

object ConnectorState {

  val Running: ConnectorState = ConnectorState("RUNNING")
  val Failed: ConnectorState = ConnectorState("FAILED")

  given CanEqual[ConnectorState, ConnectorState] = CanEqual.derived
}

/** Whether a connector reads from Kafka or writes into it, as the worker said.
  *
  * Kept as the worker's word for [[ConnectorState]]'s reason, and with one more of its own: `type` is absent
  * from the connector document of every Connect release before 2.0 and from some compatible implementations,
  * so "the worker did not say" is a state that genuinely occurs and is not the same as "neither source nor
  * sink". The screen draws the icon tile from this and must be able to draw a connector whose direction is
  * unknown; a defaulted `source` would point half of those arrows the wrong way.
  */
enum ConnectorKind(val wire: String) {
  case Source extends ConnectorKind("source")
  case Sink extends ConnectorKind("sink")

  /** The worker named a direction this build does not know, or named none at all. `word` is what it said,
    * empty when it said nothing.
    */
  case Other(word: String) extends ConnectorKind(word)
}

object ConnectorKind {

  /** What the worker's `type` field means, with its own spelling preserved when it is not one of the two. */
  def fromWorker(raw: Option[String]): ConnectorKind =
    raw.map(_.trim).filter(_.nonEmpty) match {
      case None => Other("")
      case Some(word) if word.equalsIgnoreCase(Source.wire) => Source
      case Some(word) if word.equalsIgnoreCase(Sink.wire) => Sink
      case Some(word) => Other(word)
    }

  given CanEqual[ConnectorKind, ConnectorKind] = CanEqual.derived
}

/** One task of one connector, as the worker's status document described it.
  *
  * @param trace
  *   the stack trace the worker recorded for a failed task, verbatim and complete. It is carried rather than
  *   summarised here because [[reason]] is a *rendering* of it and a reader who needs the rest — the
  *   caused-by chain, the class name — has nowhere else to get it. Absent for a task that has not failed,
  *   and, crucially, also absent for some tasks that *have*: a worker that lost the task to a rebalance
  *   mid-failure reports `FAILED` with no trace at all. See [[reason]].
  */
final case class ConnectorTask(
    id: TaskId,
    state: ConnectorState,
    workerId: Option[String],
    trace: Option[String]
) {

  /** The first line of the worker's trace, which is the sentence `SCREENS-V4.md` §7.7 asks the card for.
    *
    * `Task 0: connection refused to es-01:9200` is the design's own example and it is the first line of a
    * Java stack trace — the exception's class and message — with everything after it being frames. So the
    * reason is a *slice* of what the worker said and never a sentence KUI composed.
    *
    * `None` when there is no trace, and that is the case with a rule attached: **a task with no trace is not
    * a task with no problem**. A `FAILED` task whose reason is absent must be drawn as failed with the reason
    * missing — "the worker gave no reason" — and never as healthy and never with a reason invented from the
    * state word. ADR-054 §6.
    */
  def reason: Option[String] =
    trace.flatMap(_.linesIterator.map(_.trim).find(_.nonEmpty))
}

object ConnectorTask {
  given CanEqual[ConnectorTask, ConnectorTask] = CanEqual.derived
}

/** One connector on one Connect cluster, with its tasks expanded.
  *
  * The Connect REST API can answer the connector list and the per-connector status separately, and this type
  * is the joined result — `GET /connectors?expand=status` in one call where the worker supports it. The join
  * is a fact about what was read, not about what exists: a connector that answered a list and refused a
  * status is named in [[ConnectorFacts.unreadable]] rather than drawn as a row with zeros in it.
  *
  * @param trace
  *   the connector-level trace, which the worker sets when the connector itself failed rather than one of its
  *   tasks — a bad configuration, most often. It is separate from the tasks' traces because the two answer
  *   different questions: this one says the connector will not start, a task's says one worker thread
  *   stopped.
  */
final case class Connector(
    connect: ConnectName,
    name: ConnectorName,
    kind: ConnectorKind,
    state: ConnectorState,
    workerId: Option[String],
    trace: Option[String],
    tasks: List[ConnectorTask]
) {

  /** How many of this connector's tasks the worker says are running.
    *
    * `RESTARTING` does **not** count and neither does `UNASSIGNED`, which is the whole content of this
    * method: `3/3 tasks` on the card means three tasks are moving data, and a restart in flight is exactly
    * the moment an operator is watching that number. Counting anything that is not `RUNNING` as running would
    * make the figure agree with `tasks.size` permanently and say nothing.
    */
  def runningTasks: Int = tasks.count(_.state.isRunning)

  def taskCount: Int = tasks.size

  /** Whether anything about this connector is failed — the connector itself, or any of its tasks.
    *
    * The drawer's `Kafka Connect  1 failed` row and the card's red pill are both drawn from this, and a
    * connector whose own state is `RUNNING` over a `FAILED` task is precisely the case the design's §7.7
    * finding is about: the worker reports the connector as running and its sink task as dead, and an operator
    * who was shown only the connector state would be told everything is fine.
    */
  def isFailed: Boolean = state.isFailed || tasks.exists(_.state.isFailed)

  /** The sentence the card shows under a failed connector, or nothing.
    *
    * The connector's own trace wins, because a connector that failed to start has no working task whose trace
    * would be more specific. Otherwise the first failed task's reason, in task-id order, so that two screens
    * reading one document cannot pick different tasks.
    *
    * `None` is a real answer and the browser must render it as one. See [[ConnectorTask.reason]].
    */
  def reason: Option[String] =
    trace
      .flatMap(_.linesIterator.map(_.trim).find(_.nonEmpty))
      .orElse(tasks.sortBy(_.id.value).filter(_.state.isFailed).flatMap(_.reason).headOption)
}

object Connector {
  given CanEqual[Connector, Connector] = CanEqual.derived
}

/** What one Connect cluster answered, as a whole.
  *
  * A list of connectors is not enough on its own, because "the worker named no connectors" and "the worker
  * would not say" have to reach the screen as different sentences, and so does the third case this type
  * exists for: a worker that answered the connector list and refused the status of some of them. §3.14's
  * *Absent* paragraph is the standing rule — an unmeasured figure stays a dash — and a connector with no task
  * list would otherwise be drawn as a connector with zero tasks.
  *
  * @param unreadable
  *   the connectors the worker named and would not describe, in the order it named them, spelled the way the
  *   worker spelled them. They are reported rather than dropped: a connector missing from a list looks like a
  *   connector that was deleted. It is a `String` and not a [[kui.kernel.ConnectorName]] on purpose — one of
  *   the ways a connector becomes unreadable is a name KUI's own validation refuses, and a list that could
  *   only hold valid names would have to drop exactly those.
  */
final case class ConnectorFacts(connectors: List[Connector], unreadable: List[String])

object ConnectorFacts {

  def complete(connectors: List[Connector]): ConnectorFacts = ConnectorFacts(connectors, Nil)

  given CanEqual[ConnectorFacts, ConnectorFacts] = CanEqual.derived
}

/** The three things KUI can ask a worker to do to a connector.
  *
  * A closed enum here where [[ConnectorState]] is an open string, and the asymmetry is deliberate: a state is
  * something the worker tells KUI and may extend at any time, while an operation is something KUI offers, and
  * every one of them needs a button, a permission and an audit spelling. A fourth one is a decision, not a
  * surprise.
  *
  * @param operation
  *   the audit and endpoint name, spelled the way a `kui.security.audit.MutationKind.operation` is spelled —
  *   dotted, lower case, most general segment first — so that the day `MutationKind` grows these cases,
  *   nothing that reads the audit trail has to change. `ConnectorOperationRecord` explains why it has not.
  */
enum ConnectorOperation(val wire: String, val operation: String) {
  case Pause extends ConnectorOperation("pause", "connect.connector.pause")
  case Resume extends ConnectorOperation("resume", "connect.connector.resume")
  case Restart extends ConnectorOperation("restart", "connect.connector.restart")
}

object ConnectorOperation {
  given CanEqual[ConnectorOperation, ConnectorOperation] = CanEqual.derived
}
