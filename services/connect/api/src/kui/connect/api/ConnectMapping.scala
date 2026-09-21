package kui.connect.api

import java.time.Instant

import kui.connect.application.{AcceptedOperation, ConnectListing, WorkerReport}
import kui.connect.contract.ConnectEndpoints
import kui.connect.contract.dto.*
import kui.connect.domain.*
import kui.contracts.Section
import kui.contracts.capability.ReasonCode
import kui.kernel.error.{DomainError, ErrorCode, KuiError}
import kui.kernel.{ConnectName, ConnectorName, ValidationError}

/** Application types to wire types, and the one place the two vocabularies are allowed to meet (ADR-033).
  *
  * Rule A3 keeps `Section` out of the application layer, so every decision about *which* section a worker's
  * row carries is made here — and every one of them is a decision about honesty rather than about shape:
  *
  *   - a worker that answered carries `ok` with what it said, timestamped with the read;
  *   - a worker that is **rebalancing** carries `unavailable` with `STARTING`, whose own sentence is "KUI has
  *     not finished reading this cluster yet", and with the worker's own rebalance message. It is not
  *     `UPSTREAM_UNAVAILABLE`: see [[section]];
  *   - anything else goes through `Section.fromEither`, so a Connect cluster that is down, slow, refusing
  *     KUI's credentials or behind an open breaker is described by the same fold every other section in the
  *     product uses.
  */
object ConnectMapping {

  /** The whole answer, with one row per configured Connect cluster.
    *
    * A cluster that configured none is `not_configured` — with a 200, because a deployment that never
    * intended to run Kafka Connect is not a broken one and ADR-032's rule is that the browser hides the row.
    */
  def response(listing: ConnectListing, at: Instant): ConnectorListResponse =
    ConnectorListResponse(
      listing match {
        case ConnectListing.NotConfigured => Section.NotConfigured
        case ConnectListing.Workers(reports) =>
          Section.Ok(ConnectorListDto(reports.map(worker(_, at))), at)
      }
    )

  /** One configured Connect cluster's row. */
  def worker(report: WorkerReport, at: Instant): ConnectWorkerDto =
    ConnectWorkerDto(
      connect = report.connect.value,
      connectors = report.facts match {
        case Right(facts) =>
          Section.Ok(ConnectorsDto(facts.connectors.map(connector), facts.unreadable), at)
        case Left(error) => section(error, at)
      }
    )

  /** Which section a worker's failure carries, and this is the rule this packet owns.
    *
    * A Connect cluster answers `409` while its workers are agreeing on an assignment. That is
    * `ErrorCode.ConnectRebalancing`, and it is a **transient state, not a failure**: it lasts seconds, it
    * clears itself, and the connectors are all still there. So it is reported with `ReasonCode.Starting` —
    * "KUI has not finished reading this cluster yet" — and never with `UPSTREAM_UNAVAILABLE`, which is the
    * reason code whose own sentence is "the cluster is not answering" and which sends an operator to look at
    * a Connect cluster that is working correctly.
    *
    * The rule has three halves and they must agree, because a browser that read one of them would draw a
    * screen the other two contradict: this mapping, `ConnectHttp.errorFrom`'s classification of the 409 as an
    * `ApplicationError` (so ADR-039 §6 keeps it from dimming anything), and `ConnectCapabilities.probe`,
    * which leaves the capability `available` for exactly this error. `ConnectMappingSuite` and
    * `ConnectCapabilitiesSuite` assert the halves separately, against these functions rather than against a
    * re-implementation of them.
    *
    * The section keeps the error's own message, which carries the worker's rebalance sentence, so the screen
    * says which Connect cluster is rebalancing rather than "something is starting".
    */
  def section(error: KuiError, at: Instant): Section[ConnectorsDto] =
    if error.code == ErrorCode.ConnectRebalancing then
      Section.Unavailable(ReasonCode.Starting, error.message, Some(at))
    else Section.fromEither(Left(error), at)

  /** One connector, with the three derived values computed once here.
    *
    * `failed`, `runningTasks` and `taskCount` all come off the domain type rather than being recomputed from
    * `tasks` in this file, so that the browser, this mapping and the domain cannot hold three opinions about
    * what a running task is — `Connector.runningTasks` is the one that says `RESTARTING` is not running.
    */
  def connector(connector: Connector): ConnectorDto =
    ConnectorDto(
      connect = connector.connect.value,
      name = connector.name.value,
      kind = connector.kind.wire,
      state = connector.state.wire,
      workerId = connector.workerId,
      reason = connector.reason,
      failed = connector.isFailed,
      runningTasks = connector.runningTasks,
      taskCount = connector.taskCount,
      tasks = connector.tasks.map(task)
    )

  /** One task. `reason` is the first line of the worker's own trace and never a sentence KUI composed
    * (`SCREENS-V4.md` §7.7), and it is absent — rather than filled in from the state word — when the worker
    * recorded no trace.
    */
  def task(task: ConnectorTask): ConnectorTaskDto =
    ConnectorTaskDto(
      id = task.id.value,
      state = task.state.wire,
      workerId = task.workerId,
      reason = task.reason,
      trace = task.trace
    )

  def accepted(accepted: AcceptedOperation): ConnectorOperationDto =
    ConnectorOperationDto(
      connect = accepted.connect.value,
      connector = accepted.connector.value,
      operation = accepted.operation.operation,
      acceptedAt = accepted.acceptedAt
    )

  /** The Connect cluster's name off the URL, or the refusal.
    *
    * A name that is not the shape `ConnectName` accepts is a `KUI-VALIDATION` naming the path parameter, and
    * not a 501 from the use case: the two are different situations — a malformed request against a request
    * for something this deployment does not have — and the caller of the first has a typo while the caller of
    * the second is looking at a stale screen.
    */
  def connectName(raw: String): Either[KuiError, ConnectName] =
    ConnectName
      .from(raw)
      .left
      .map(_ =>
        DomainError.fromValidation(
          ValidationError.Format(
            ConnectEndpoints.ConnectNameParam,
            "a Kafka Connect cluster name, as kui.clusters.<n>.connect[].name gives it",
            raw
          )
        )
      )

  def connectorName(raw: String): Either[KuiError, ConnectorName] =
    ConnectorName
      .from(raw)
      .left
      .map(_ =>
        DomainError.fromValidation(
          ValidationError.Format(
            ConnectEndpoints.ConnectorNameParam,
            "a connector name, as the Connect cluster reported it",
            raw
          )
        )
      )
}
