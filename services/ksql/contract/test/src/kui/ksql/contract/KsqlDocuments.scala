package kui.ksql.contract

import java.time.Instant

import io.circe.Json
import io.circe.syntax.*

import kui.contracts.Section
import kui.contracts.capability.ReasonCode
import kui.contracts.sse.SseEventName
import kui.ksql.contract.dto.*

/** The instances every golden document in this module is rendered from.
  *
  * One set of values, encoded by the suite and decoded back by it, so that the golden files and the
  * round-trip assertions cannot describe two different documents. House rule 12: the case that binds the two
  * sides of a wire decodes the **encoder's own output** rather than a hand-written literal, which is exactly
  * what wave 5's metrics wire did not do — two packets, two hand-written shapes, both green, and the card
  * drawing nothing against a real broker.
  *
  * The files beside them are what a reviewer reads and what a browser fixture is cut from, so a field rename
  * is a diff in a file rather than an expectation quietly edited in passing. `services/ksql/contract/test/
  * resources/golden` is the path the browser's own suite reads off disk, and `GoldenFilesSuite` asserts the
  * directory rather than the classpath copy of it for exactly that reason.
  */
object KsqlDocuments {

  val fetchedAt: Instant = Instant.parse("2026-09-03T10:11:12Z")

  val expiresAt: Instant = Instant.parse("2026-09-03T10:16:12Z")

  /** Two streams, a table, a running query and a topic — the shape §4.15's voice line counts `4 objects`
    * over, and the shape §3.16's left pane draws with a glyph per kind.
    *
    * The order is the service's own: kind first, then name. `ORDERS` and `PAYMENTS` are the two streams,
    * `USERS` is the table, and the ordering is what stops a pane's rows moving between polls.
    */
  val orders: KsqlObjectDto = KsqlObjectDto(
    kind = "stream",
    name = "ORDERS",
    topic = Some("orders"),
    format = Some("JSON"),
    windowed = None,
    sinks = Nil,
    statement = None,
    partitions = None,
    replication = None
  )

  /** A stream whose value format the server did not report, which every ksqlDB before 0.10 does. `format` is
    * `null` rather than `"JSON"`: a defaulted format would tell an operator their Avro stream was JSON.
    */
  val payments: KsqlObjectDto = orders.copy(name = "PAYMENTS", topic = Some("payments"), format = None)

  val users: KsqlObjectDto = KsqlObjectDto(
    kind = "table",
    name = "USERS",
    topic = Some("users"),
    format = Some("AVRO"),
    windowed = Some(false),
    sinks = Nil,
    statement = None,
    partitions = None,
    replication = None
  )

  val query: KsqlObjectDto = KsqlObjectDto(
    kind = "query",
    name = "CSAS_ENRICHED_ORDERS_5",
    topic = None,
    format = None,
    windowed = None,
    sinks = List("ENRICHED_ORDERS"),
    statement = Some("CREATE STREAM ENRICHED_ORDERS AS SELECT * FROM ORDERS EMIT CHANGES;"),
    partitions = None,
    replication = None
  )

  val topic: KsqlObjectDto = KsqlObjectDto(
    kind = "topic",
    name = "orders",
    topic = None,
    format = None,
    windowed = None,
    sinks = Nil,
    statement = None,
    partitions = Some(3),
    replication = Some(3)
  )

  /** What a working ksqlDB answers. */
  val listing: KsqlObjectsResponse =
    KsqlObjectsResponse(
      Section.Ok(KsqlObjectsDto(List(orders, payments, users, query, topic), Nil, 0), fetchedAt)
    )

  /** A server that named a row KUI could not describe, and an answer cut to the bound.
    *
    * Both facts travel rather than being dropped: a row missing from a list is indistinguishable from a row
    * that is not there, and a list that quietly stops is how an operator concludes a stream does not exist.
    */
  val partial: KsqlObjectsResponse =
    KsqlObjectsResponse(
      Section.Ok(
        KsqlObjectsDto(List(orders), List("(a table the ksqlDB cluster did not name)"), 1811),
        fetchedAt
      )
    )

  /** A deployment that configured no ksqlDB at all. Answered with a 200: ADR-032's rule is that the browser
    * hides the row, and there is nothing wrong.
    */
  val notConfigured: KsqlObjectsResponse = KsqlObjectsResponse(Section.NotConfigured)

  /** A ksqlDB that did not answer. `UPSTREAM_UNAVAILABLE` and not `STARTING`: KUI cannot tell a server that
    * is still building its metastore from one that is broken, and claiming it could would send an operator to
    * wait for something that is not coming.
    */
  val unavailable: KsqlObjectsResponse =
    KsqlObjectsResponse(
      Section.Unavailable(
        ReasonCode.UpstreamUnavailable,
        "ksqldb could not be reached",
        Some(fetchedAt)
      )
    )

  /** What a `CREATE STREAM` answers: the server's own sentence, and no rows. */
  val created: StatementResultDto = StatementResultDto(
    statement = "CREATE STREAM ENRICHED_ORDERS AS SELECT * FROM ORDERS EMIT CHANGES;",
    shape = "statement",
    outcome = "status",
    columns = Nil,
    rows = Nil,
    message = Some("Stream created and running"),
    entity = Some("stream/ENRICHED_ORDERS/create"),
    executedAt = fetchedAt
  )

  /** What a pull query answers: columns, and rows under them.
    *
    * The second row's last cell is `null`, which is a **SQL NULL** and not the four characters `null`. That
    * distinction is the reason the cell type is nullable on this wire at all, and this document is where a
    * browser fixture is cut from so that the two ends cannot disagree about it.
    */
  val rows: StatementResultDto = StatementResultDto(
    statement = "SELECT ID, TOTAL, NOTE FROM ORDERS WHERE ID = '17';",
    shape = "pull_query",
    outcome = "rows",
    columns = List("ID", "TOTAL", "NOTE"),
    rows = List(
      List(Some("17"), Some("42.50"), Some("gift wrap")),
      List(Some("18"), Some("11.00"), None)
    ),
    message = None,
    entity = None,
    executedAt = fetchedAt
  )

  /** The plan for the one statement in ksqlDB's language that destroys records.
    *
    * The warning names the Kafka topic, because that is the whole content of the confirmation: "this deletes
    * something" is not a thing anybody can weigh, and `orders` is. The plan looked it up in the cluster's own
    * object listing; a plan that could not would say so in the warning rather than name a topic nobody
    * measured.
    */
  val destructivePlan: StatementPlanDto = StatementPlanDto(
    statement = "DROP STREAM ORDERS DELETE TOPIC;",
    shape = "statement",
    destructive = true,
    deletesTopic = true,
    warnings = List(
      "This deletes the Kafka topic 'orders' and every record in it. Nothing in KUI can undo it."
    ),
    token = Some("eyJ2IjoxfQ.c2ln"),
    expiresAt = Some(expiresAt),
    computedAt = fetchedAt
  )

  /** The plan for a statement that needs no confirmation: no token, no warnings, and nothing to click
    * through. A screen that asked for a confirmation here would teach an operator to click past them.
    */
  val harmlessPlan: StatementPlanDto = StatementPlanDto(
    statement = "CREATE STREAM ENRICHED_ORDERS AS SELECT * FROM ORDERS EMIT CHANGES;",
    shape = "statement",
    destructive = false,
    deletesTopic = false,
    warnings = Nil,
    token = None,
    expiresAt = None,
    computedAt = fetchedAt
  )

  /** The push query's first frame: the columns, once, as soon as the server accepts the query. */
  val streamHeader: Json =
    Json.obj(
      "event" -> Json.fromString(SseEventName.Phase),
      "data" -> QueryHeaderDto(List("ID", "TOTAL", "NOTE")).asJson
    )

  /** One row frame.
    *
    * The `event` field is the one part of this wire that is not a DTO and therefore the one nothing could
    * check. The browser's own constant is asserted against this file's `event`; `GoldenFilesSuite` is the
    * other end of it, and it compares the field to a **literal** rather than to `SseEventName.Row` — because
    * comparing the constant with itself is the assertion that proves nothing.
    */
  val streamFrame: Json =
    Json.obj(
      "event" -> Json.fromString(SseEventName.Row),
      "data" -> QueryRowDto(List(Some("17"), Some("42.50"), None)).asJson
    )

  /** Every document this module commits, by file name. Read by the suite so that a document added here
    * without a file — or a file with no document — fails rather than being skipped.
    */
  val all: List[(String, Json)] =
    List(
      "objects-response.json" -> listing.asJson,
      "objects-partial.json" -> partial.asJson,
      "objects-not-configured.json" -> notConfigured.asJson,
      "objects-unavailable.json" -> unavailable.asJson,
      "statement-status.json" -> created.asJson,
      "statement-rows.json" -> rows.asJson,
      "statement-plan.json" -> destructivePlan.asJson,
      "statement-plan-harmless.json" -> harmlessPlan.asJson,
      "ksql-stream-header.json" -> streamHeader,
      "ksql-stream-frame.json" -> streamFrame
    )
}
