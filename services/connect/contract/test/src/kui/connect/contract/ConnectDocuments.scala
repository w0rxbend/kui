package kui.connect.contract

import java.time.Instant

import kui.connect.contract.dto.*
import kui.contracts.Section
import kui.contracts.capability.ReasonCode

/** The instances every golden document in this module is rendered from.
  *
  * One set of values, encoded by the suite and decoded back by it, so that the golden files and the
  * round-trip assertions cannot describe two different documents. House rule 12: the case that binds the two
  * sides of a wire decodes the **encoder's own output** rather than a hand-written literal, which is exactly
  * what wave 5's metrics wire did not do — two packets, two hand-written shapes, both green, and the card
  * drawing nothing against a real broker.
  *
  * The files beside them are what a reviewer reads and what a browser fixture is cut from, so a field rename
  * is a diff in a file rather than an expectation quietly edited in passing.
  */
object ConnectDocuments {

  val fetchedAt: Instant = Instant.parse("2026-09-03T10:11:12Z")

  /** The trace a real Elasticsearch sink writes when its cluster is unreachable. `SCREENS-V4.md` §7.7 quotes
    * its first line as the sentence the failed card is missing, so it is the first line here too.
    */
  val elasticTrace: String =
    "org.apache.kafka.connect.errors.ConnectException: connection refused to es-01:9200\n" +
      "\tat org.apache.kafka.connect.runtime.WorkerSinkTask.deliverMessages(WorkerSinkTask.java:586)\n" +
      "\tat org.apache.kafka.connect.runtime.WorkerSinkTask.poll(WorkerSinkTask.java:329)"

  /** A healthy source: three tasks, all running, and no reason to show. */
  val orders: ConnectorDto = ConnectorDto(
    connect = "payments",
    name = "orders-source",
    kind = "source",
    state = "RUNNING",
    workerId = Some("10.0.0.1:8083"),
    reason = None,
    failed = false,
    runningTasks = 3,
    taskCount = 3,
    tasks = List(
      ConnectorTaskDto(0, "RUNNING", Some("10.0.0.1:8083"), None, None),
      ConnectorTaskDto(1, "RUNNING", Some("10.0.0.2:8083"), None, None),
      ConnectorTaskDto(2, "RUNNING", Some("10.0.0.2:8083"), None, None)
    )
  )

  /** The card §3.14 draws with a state, a task count and no reason at all. Here it has one, and the reason is
    * the worker's own first line: `failed` is true while the connector's own state is `RUNNING`, which is the
    * case an operator shown only the connector state is not told about.
    */
  val elastic: ConnectorDto = ConnectorDto(
    connect = "payments",
    name = "elastic-sink",
    kind = "sink",
    state = "RUNNING",
    workerId = Some("10.0.0.1:8083"),
    reason = Some("org.apache.kafka.connect.errors.ConnectException: connection refused to es-01:9200"),
    failed = true,
    runningTasks = 1,
    taskCount = 2,
    tasks = List(
      ConnectorTaskDto(0, "RUNNING", Some("10.0.0.1:8083"), None, None),
      ConnectorTaskDto(
        1,
        "FAILED",
        Some("10.0.0.2:8083"),
        Some("org.apache.kafka.connect.errors.ConnectException: connection refused to es-01:9200"),
        Some(elasticTrace)
      )
    )
  )

  /** A paused connector, whose single task is paused with it. §3.14's *Absent* paragraph is why there is no
    * rate anywhere on this wire: a literal `0 msg/s` on a paused connector would be a measured zero, and
    * nothing measured it.
    */
  val archive: ConnectorDto = ConnectorDto(
    connect = "payments",
    name = "archive-sink",
    kind = "sink",
    state = "PAUSED",
    workerId = Some("10.0.0.1:8083"),
    reason = None,
    failed = false,
    runningTasks = 0,
    taskCount = 1,
    tasks = List(ConnectorTaskDto(0, "PAUSED", Some("10.0.0.1:8083"), None, None))
  )

  /** A Connect cluster mid-rebalance, as this service reports it: `STARTING`, not `UPSTREAM_UNAVAILABLE`.
    *
    * The rule this packet owns, on the wire. `ConnectMapping.section` is where it is decided and
    * `ConnectMappingSuite` is where it is asserted against that function; this document is what a browser
    * fixture is cut from, so the two cannot drift.
    */
  val rebalancingSection: Section[ConnectorsDto] = Section.Unavailable(
    ReasonCode.Starting,
    "the Kafka Connect cluster 'analytics' is rebalancing and cannot answer yet: Cannot complete request " +
      "momentarily due to stale configuration (typically caused by a concurrent config change)",
    Some(fetchedAt)
  )

  /** Two Connect clusters, one answering and one rebalancing — the shape §4.14's voice line is drawn from and
    * the reason the section is per worker rather than per document.
    */
  val listing: ConnectorListResponse = ConnectorListResponse(
    Section.Ok(
      ConnectorListDto(
        List(
          ConnectWorkerDto(
            "payments",
            Section.Ok(ConnectorsDto(List(archive, elastic, orders), Nil), fetchedAt)
          ),
          ConnectWorkerDto("analytics", rebalancingSection)
        )
      ),
      fetchedAt
    )
  )

  /** A worker that named a connector and would not describe it. The name is in `unreadable` rather than
    * missing from `items`, because a connector missing from a list looks like a connector that was deleted.
    */
  val partial: ConnectorListResponse = ConnectorListResponse(
    Section.Ok(
      ConnectorListDto(
        List(
          ConnectWorkerDto(
            "payments",
            Section.Ok(ConnectorsDto(List(orders), List("elastic-sink")), fetchedAt)
          )
        )
      ),
      fetchedAt
    )
  )

  /** A deployment that configured no Kafka Connect at all. It is answered with a 200: ADR-032's rule is that
    * the browser hides the row, and there is nothing wrong.
    */
  val notConfigured: ConnectorListResponse = ConnectorListResponse(Section.NotConfigured)

  /** What a restart answers: what was accepted and when, and no connector state. */
  val restarted: ConnectorOperationDto = ConnectorOperationDto(
    connect = "payments",
    connector = "elastic-sink",
    operation = "connect.connector.restart",
    acceptedAt = fetchedAt
  )

  /** Every document this module commits, by file name. Read by the suite so that a document added here
    * without a file — or a file with no document — fails rather than being skipped.
    */
  val all: List[(String, io.circe.Json)] = {
    import io.circe.syntax.*

    List(
      "connectors-response.json" -> listing.asJson,
      "connectors-partial.json" -> partial.asJson,
      "connectors-not-configured.json" -> notConfigured.asJson,
      "connector-operation.json" -> restarted.asJson
    )
  }
}
