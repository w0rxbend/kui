package kui.connect.contract.dto

import java.time.Instant

import io.circe.syntax.*
import io.circe.{Codec, HCursor, Json}
import sttp.tapir.Schema as TapirSchema

import kui.contracts.Section

/** One task of a connector, on the wire.
  *
  * @param state
  *   the worker's own word, uppercase as it sent it. Not an enum: Connect has added states across releases —
  *   `STOPPED` arrived in Kafka 3.5 — and a browser that met an unknown one must draw the row in a neutral
  *   tone rather than fail to decode the document. `kui.connect.domain.ConnectorState` argues it at length.
  * @param reason
  *   the first line of [[trace]], which is the sentence `SCREENS-V4.md` §7.7 asks a failed card for. It
  *   travels rather than being sliced in the browser so that the card, the drawer row and a future
  *   notification cannot each take a different line of the same trace. `null` when the worker recorded no
  *   trace — **which is not the same as no problem**: a `FAILED` task with no reason must be drawn as failed
  *   with the reason missing.
  * @param trace
  *   the whole trace, verbatim. The panel shows [[reason]]; an operator who needs the caused-by chain has
  *   nowhere else to get it, and KUI must not be the reason they open a worker's log to read something it
  *   already sent.
  */
final case class ConnectorTaskDto(
    id: Int,
    state: String,
    workerId: Option[String],
    reason: Option[String],
    trace: Option[String]
)

object ConnectorTaskDto {

  given Codec[ConnectorTaskDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        id <- cursor.get[Int]("id")
        state <- cursor.get[String]("state")
        workerId <- cursor.getOrElse[Option[String]]("workerId")(None)
        reason <- cursor.getOrElse[Option[String]]("reason")(None)
        trace <- cursor.getOrElse[Option[String]]("trace")(None)
      } yield ConnectorTaskDto(id, state, workerId, reason, trace),
    (task: ConnectorTaskDto) =>
      Json.obj(
        "id" -> task.id.asJson,
        "state" -> task.state.asJson,
        "workerId" -> task.workerId.asJson,
        "reason" -> task.reason.asJson,
        "trace" -> task.trace.asJson
      )
  )

  given TapirSchema[ConnectorTaskDto] = TapirSchema
    .derived[ConnectorTaskDto]
    .description(
      "One task of a connector. `state` is the worker's own word; `reason` is the first line of the " +
        "worker's trace and is absent when it recorded none, which is not the same as no problem"
    )

  given CanEqual[ConnectorTaskDto, ConnectorTaskDto] = CanEqual.derived
}

/** One connector, with its tasks expanded.
  *
  * ==Three derived values travel, and none of them is a measurement==
  *
  * `runningTasks`, `taskCount` and `failed` are computed from `tasks` and sent anyway, for `AlertEventDto`'s
  * reason: the card's `3/3 tasks`, the drawer's `1 failed` row and the page's voice line are three places
  * that must show one number, and three browsers deriving it are three chances to derive it differently.
  * `RESTARTING` is not running, which is the entire content of `runningTasks` and exactly the state an
  * operator is watching that figure during.
  *
  * ==And one figure the design asks for is not here==
  *
  * §3.14 draws `3/3 tasks · 1,204 msg/s · orders.*`. The Connect REST API publishes **no throughput**: a
  * worker's status document carries states, worker ids and traces, and the per-connector record rate lives in
  * the workers' JMX beans, which this service does not read. So no rate is on this wire, the card must draw a
  * dash rather than a zero (§3.14's *Absent* paragraph), and ADR-054 §7 records what it would cost to change
  * that. A zero here would say the connector moved nothing, which nobody measured.
  *
  * @param kind
  *   `source`, `sink`, or whatever the worker called it — empty when it said nothing at all, which every
  *   Connect release before 2.0 does. The screen draws the icon's direction from this and must be able to
  *   draw a connector whose direction is unknown.
  * @param reason
  *   the connector's own failure line if it has one, otherwise the first failed task's. Absent on a healthy
  *   connector, and absent on a failed one whose worker recorded no trace.
  */
final case class ConnectorDto(
    connect: String,
    name: String,
    kind: String,
    state: String,
    workerId: Option[String],
    reason: Option[String],
    failed: Boolean,
    runningTasks: Int,
    taskCount: Int,
    tasks: List[ConnectorTaskDto]
)

object ConnectorDto {

  given Codec[ConnectorDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        connect <- cursor.get[String]("connect")
        name <- cursor.get[String]("name")
        kind <- cursor.get[String]("kind")
        state <- cursor.get[String]("state")
        workerId <- cursor.getOrElse[Option[String]]("workerId")(None)
        reason <- cursor.getOrElse[Option[String]]("reason")(None)
        failed <- cursor.get[Boolean]("failed")
        runningTasks <- cursor.get[Int]("runningTasks")
        taskCount <- cursor.get[Int]("taskCount")
        tasks <- cursor.getOrElse[List[ConnectorTaskDto]]("tasks")(Nil)
      } yield ConnectorDto(
        connect,
        name,
        kind,
        state,
        workerId,
        reason,
        failed,
        runningTasks,
        taskCount,
        tasks
      ),
    (connector: ConnectorDto) =>
      Json.obj(
        "connect" -> connector.connect.asJson,
        "name" -> connector.name.asJson,
        "kind" -> connector.kind.asJson,
        "state" -> connector.state.asJson,
        "workerId" -> connector.workerId.asJson,
        "reason" -> connector.reason.asJson,
        "failed" -> connector.failed.asJson,
        "runningTasks" -> connector.runningTasks.asJson,
        "taskCount" -> connector.taskCount.asJson,
        "tasks" -> connector.tasks.asJson
      )
  )

  given TapirSchema[ConnectorDto] = TapirSchema
    .derived[ConnectorDto]
    .description(
      "One connector on one Connect cluster. `failed` is true when the connector or any of its tasks " +
        "failed; `runningTasks` counts only RUNNING tasks, so a restart in flight lowers it. No " +
        "throughput is published: the Connect REST API measures none"
    )

  given CanEqual[ConnectorDto, ConnectorDto] = CanEqual.derived
}

/** What one Connect cluster is running, when it answered.
  *
  * @param unreadable
  *   the connectors the worker named and then would not describe, in the order it named them. They are on the
  *   wire rather than dropped because a connector missing from a list is indistinguishable from a connector
  *   that was deleted, and this is a live case: `GET /connectors?expand=status` is answered per connector by
  *   the worker that owns it, so one wedged worker in a Connect cluster loses the status of its share while
  *   the rest answer normally.
  */
final case class ConnectorsDto(items: List[ConnectorDto], unreadable: List[String])

object ConnectorsDto {

  given Codec[ConnectorsDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        items <- cursor.getOrElse[List[ConnectorDto]]("items")(Nil)
        unreadable <- cursor.getOrElse[List[String]]("unreadable")(Nil)
      } yield ConnectorsDto(items, unreadable),
    (connectors: ConnectorsDto) =>
      Json.obj("items" -> connectors.items.asJson, "unreadable" -> connectors.unreadable.asJson)
  )

  given TapirSchema[ConnectorsDto] = TapirSchema
    .derived[ConnectorsDto]
    .description(
      "The connectors of one Connect cluster. `unreadable` names the ones the worker listed and " +
        "refused to describe, which is a different fact from a connector that is not there"
    )

  given CanEqual[ConnectorsDto, ConnectorsDto] = CanEqual.derived
}

/** One configured Connect cluster's row, with its own section.
  *
  * The section is **per worker** and not per document, and that is the whole shape of this response. A Kafka
  * cluster may configure two Connect clusters; one of them being down must cost one row rather than the
  * screen, exactly as one dead alert rule costs one row rather than the feed (ADR-053 §7). A worker that is
  * rebalancing is a section of its own kind again: transient, retryable, and not a failure — see
  * `ConnectMapping.section`.
  */
final case class ConnectWorkerDto(connect: String, connectors: Section[ConnectorsDto])

object ConnectWorkerDto {

  given Codec[ConnectWorkerDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        connect <- cursor.get[String]("connect")
        connectors <- cursor.get[Section[ConnectorsDto]]("connectors")
      } yield ConnectWorkerDto(connect, connectors),
    (worker: ConnectWorkerDto) =>
      Json.obj("connect" -> worker.connect.asJson, "connectors" -> worker.connectors.asJson)
  )

  given TapirSchema[ConnectWorkerDto] = TapirSchema
    .derived[ConnectWorkerDto]
    .description("One configured Connect cluster and what it answered, or why it did not")

  given CanEqual[ConnectWorkerDto, ConnectWorkerDto] = CanEqual.derived
}

/** Every Connect cluster configured for one Kafka cluster, in configuration order.
  *
  * Configuration order rather than name order, because the operator wrote the list and a screen that reorders
  * it makes the second entry hard to find in a file where it is second.
  */
final case class ConnectorListDto(workers: List[ConnectWorkerDto])

object ConnectorListDto {

  given Codec[ConnectorListDto] = Codec.from(
    (cursor: HCursor) => cursor.getOrElse[List[ConnectWorkerDto]]("workers")(Nil).map(ConnectorListDto.apply),
    (list: ConnectorListDto) => Json.obj("workers" -> list.workers.asJson)
  )

  given TapirSchema[ConnectorListDto] = TapirSchema
    .derived[ConnectorListDto]
    .description("Every Connect cluster configured for this Kafka cluster, in configuration order")

  given CanEqual[ConnectorListDto, ConnectorListDto] = CanEqual.derived
}

/** The connector list endpoint's whole answer.
  *
  * The outer section is `not_configured` for a cluster with no `kui.clusters.<n>.connect[]` block at all —
  * answered with a **200**, because a deployment that never intended to run Kafka Connect is not a broken
  * one, and ADR-032's rule is that the browser hides the row rather than drawing a red panel nobody can
  * clear. It is `ok` whenever at least one Connect cluster is configured, whatever the workers said: what
  * each of them said is the per-worker section inside.
  */
final case class ConnectorListResponse(connectors: Section[ConnectorListDto])

object ConnectorListResponse {

  given Codec[ConnectorListResponse] = Codec.from(
    (cursor: HCursor) => cursor.get[Section[ConnectorListDto]]("connectors").map(ConnectorListResponse.apply),
    (response: ConnectorListResponse) => Json.obj("connectors" -> response.connectors.asJson)
  )

  given TapirSchema[ConnectorListResponse] = TapirSchema
    .derived[ConnectorListResponse]
    .description(
      "The connectors of every Connect cluster configured for this Kafka cluster. `not_configured` " +
        "with a 200 when this cluster configures none"
    )

  given CanEqual[ConnectorListResponse, ConnectorListResponse] = CanEqual.derived
}

/** What a pause, a resume or a restart answers.
  *
  * ==No state==
  *
  * The Connect REST API answers all three with `202 Accepted` and an empty body: the request has been
  * recorded and the cluster applies it when its workers have agreed. So this document says what was accepted
  * and when, and says nothing about what the connector now is — a state here could only be the state *before*
  * the operation, dressed up as its result, which is the class of invented figure this project has shipped
  * once per wave.
  *
  * What an operator sees while it takes effect is the next read of the list. ADR-054 §5 states that, and
  * states its cost: there is no push, because Connect publishes no change feed to relay.
  *
  * @param acceptedAt
  *   KUI's instant, not the worker's — the worker publishes none. It is what lets a screen say "asked 3
  *   seconds ago" rather than leaving a button that appears to have done nothing.
  */
final case class ConnectorOperationDto(
    connect: String,
    connector: String,
    operation: String,
    acceptedAt: Instant
)

object ConnectorOperationDto {

  given Codec[ConnectorOperationDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        connect <- cursor.get[String]("connect")
        connector <- cursor.get[String]("connector")
        operation <- cursor.get[String]("operation")
        acceptedAt <- cursor.get[Instant]("acceptedAt")
      } yield ConnectorOperationDto(connect, connector, operation, acceptedAt),
    (accepted: ConnectorOperationDto) =>
      Json.obj(
        "connect" -> accepted.connect.asJson,
        "connector" -> accepted.connector.asJson,
        "operation" -> accepted.operation.asJson,
        "acceptedAt" -> accepted.acceptedAt.asJson
      )
  )

  given TapirSchema[ConnectorOperationDto] = TapirSchema
    .derived[ConnectorOperationDto]
    .description(
      "The worker accepted the operation. It carries no connector state: Connect applies these " +
        "asynchronously, and the next read of the connector list is where the change appears"
    )

  given CanEqual[ConnectorOperationDto, ConnectorOperationDto] = CanEqual.derived
}
