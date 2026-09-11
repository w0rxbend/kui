package kui.ksql.infrastructure

import cats.effect.IO
import fs2.Stream
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.impl.cats.implicits.*
import sttp.client4.testing.{BackendStub, ResponseStub, StreamBackendStub, StubBody}
import sttp.client4.{Backend, StreamBackend}
import sttp.model.StatusCode

import kui.config.SafeUrl
import kui.kernel.error.ErrorCode
import kui.ksql.domain.*
import kui.testkit.KuiIOSuite

/** What the client does with each answer a ksqlDB server can give.
  *
  * A stub rather than a running ksqlDB: every promise here is a promise about a *response*, and a real
  * ksqlDB is the slowest possible way to produce one — and cannot be made to produce most of them at all.
  * The server KUI has to survive is the one that names a stream it will not describe, the one that answers a
  * 400 because the operator typed `SELCT`, and the one behind an ingress that answers 404 for everything.
  */
final class KsqlHttpSuite extends KuiIOSuite {

  private val base: SafeUrl = SafeUrl.unsafe("http://ksqldb:8088")

  private def server(
      respond: PartialFunction[String, (StatusCode, String)],
      streamBody: String = "",
      streamStatus: StatusCode = StatusCode.Ok,
      address: SafeUrl = base
  ): KsqlHttp[IO] = {
    val calls: Backend[IO] = BackendStub[IO](summon[sttp.monad.MonadError[IO]]).whenAnyRequest
      .thenRespondF { request =>
        val path = "/" + request.uri.path.mkString("/")
        val (status, body) = respond.applyOrElse(path, (_: String) => (StatusCode.NotFound, ""))
        IO.pure(ResponseStub.adjust(body, status): sttp.client4.Response[StubBody])
      }

    val streaming: StreamBackend[IO, Fs2Streams[IO]] =
      StreamBackendStub[IO, Fs2Streams[IO]](summon[sttp.monad.MonadError[IO]]).whenAnyRequest
        .thenRespondF(_ =>
          IO.pure(
            ResponseStub.adjust(
              Stream.emits(streamBody.getBytes("UTF-8").toList).covary[IO],
              streamStatus
            ): sttp.client4.Response[StubBody]
          )
        )

    new KsqlHttp[IO](
      calls,
      streaming,
      address,
      scala.concurrent.duration.Duration(30, "seconds"),
      KsqlCredentials.anonymous[IO]
    )
  }

  /** The four listings ksqlDB answers for `SHOW STREAMS; SHOW TABLES; SHOW QUERIES; SHOW TOPICS;`.
    *
    * ==Every order in it is deliberately wrong==
    *
    * ksqlDB answers in its metastore's iteration order, which is a hash order, so the ordering this client
    * imposes has to be visible in what it answers. The streams arrive `PAYMENTS, ORDERS` and the whole
    * document arrives tables-before-streams, so neither the server's order nor its reverse is the order the
    * assertion expects.
    *
    * `BROKEN` is a stream with **no `topic`**, which is what a `SHOW STREAMS` row from a server this build
    * has never seen looks like: it is `unreadable` rather than a row with an empty topic in it.
    */
  private val listings: String =
    """[
      |  { "@type": "tables",
      |    "tables": [
      |      { "name": "USERS", "topic": "users", "valueFormat": "AVRO", "isWindowed": false }
      |    ] },
      |  { "@type": "streams",
      |    "streams": [
      |      { "name": "PAYMENTS", "topic": "payments", "valueFormat": "JSON" },
      |      { "name": "ORDERS", "topic": "orders", "valueFormat": "JSON" },
      |      { "name": "BROKEN" }
      |    ] },
      |  { "@type": "queries",
      |    "queries": [
      |      { "id": "CSAS_ORDERS_5", "queryString": "CREATE STREAM X AS SELECT * FROM ORDERS;",
      |        "sinks": ["X"] }
      |    ] },
      |  { "@type": "kafka_topics",
      |    "topics": [ { "name": "orders", "replicaInfo": [3, 3] } ] }
      |]""".stripMargin

  test("a server that names two streams and a table reaches the domain with each object's kind") {
    // The required case, at the adapter's own boundary. Two streams, one table, one query and one topic
    // out of one round trip, each with the kind the screen draws its glyph from.
    server { case "/ksql" => (StatusCode.Ok, listings) }.objects.map {
      case Right(objects) =>
        assertEquals(
          objects.items.map(item => s"${item.kind.wire}:${item.name}"),
          List("stream:ORDERS", "stream:PAYMENTS", "table:USERS", "query:CSAS_ORDERS_5", "topic:orders")
        )
        assertEquals(objects.of(KsqlObjectKind.Stream).size, 2)
        assertEquals(objects.of(KsqlObjectKind.Table).size, 1)
      case Left(error) => fail(s"the listing failed: ${error.message}")
    }
  }

  test("each kind keeps the facts that kind has") {
    server { case "/ksql" => (StatusCode.Ok, listings) }.objects.map {
      case Right(objects) =>
        assertEquals(
          objects.items.collectFirst { case stream: KsqlObject.Stream => stream: KsqlObject },
          Some(KsqlObject.Stream("ORDERS", "orders", Some("JSON")): KsqlObject)
        )
        assertEquals(
          objects.items.collectFirst { case table: KsqlObject.Table => table: KsqlObject },
          Some(KsqlObject.Table("USERS", "users", Some("AVRO"), windowed = false): KsqlObject)
        )
        assertEquals(
          objects.items.collectFirst { case query: KsqlObject.Query => query.sinks },
          Some(List("X"))
        )
        // `replicaInfo` is one entry per partition, each the replica count — so two partitions with three
        // replicas each, and not three partitions.
        assertEquals(
          objects.items.collectFirst { case topic: KsqlObject.Topic => (topic.partitions, topic.replication) },
          Some((2, 3))
        )
      case Left(error) => fail(s"the listing failed: ${error.message}")
    }
  }

  test("a row the server named and would not describe is unreadable rather than dropped") {
    server { case "/ksql" => (StatusCode.Ok, listings) }.objects.map {
      case Right(objects) =>
        assertEquals(objects.unreadable, List("BROKEN"))
        assert(!objects.items.exists(_.name == "BROKEN"))
        assert(objects.partial)
      case Left(error) => fail(s"the listing failed: ${error.message}")
    }
  }

  test("an entity kind this build has never seen is ignored rather than counted as unreadable") {
    // A future ksqlDB adding a listing to its answer must not put a permanent false row on the screen of
    // every deployment that upgraded.
    val extra = """[{ "@type": "connectors", "connectors": [ { "name": "x" } ] }]"""

    server { case "/ksql" => (StatusCode.Ok, extra) }.objects.map {
      case Right(objects) => assertEquals((objects.items, objects.unreadable), (Nil, Nil))
      case Left(error) => fail(s"the listing failed: ${error.message}")
    }
  }

  test("a 400 is the operator's own statement being wrong, and it carries ksqlDB's sentence") {
    // Reporting it as an upstream failure would tell them their ksqlDB is broken when what happened is
    // that they typed `SELCT`. It reaches the browser as KUI-VALIDATION with the server's own words.
    val refusal = """{"@type":"statement_error","error_code":40001,"message":"line 1:1: mismatched input"}"""

    server { case "/ksql" => (StatusCode.BadRequest, refusal) }.objects.map {
      case Left(error) =>
        assertEquals(error.code, ErrorCode.Validation)
        assert(clue(error.message).contains("mismatched input"))
      case Right(other) => fail(s"expected a refusal, got $other")
    }
  }

  test("a 404 is an address that is not a ksqlDB, and says so") {
    // `/ksql` exists on every ksqlDB server there has ever been, so a 404 is a proxy or an ingress pointed
    // at the wrong service — which is what an operator can act on.
    server(PartialFunction.empty).objects.map {
      case Left(error) =>
        assertEquals(error.code, ErrorCode.UpstreamUnavailable)
        assert(clue(error.message).contains("does not look like a ksqlDB server"))
      case Right(other) => fail(s"expected a failure, got $other")
    }
  }

  test("a 401 is KUI's credentials being rejected and not the server being down") {
    server { case "/ksql" => (StatusCode.Unauthorized, "") }.objects.map {
      case Left(error) => assertEquals(error.code, ErrorCode.UpstreamAuth)
      case Right(other) => fail(s"expected a failure, got $other")
    }
  }

  test("a 5xx with a message is KUI-UPSTREAM-KSQL, the code declared in wave 1 for exactly this") {
    val failure = """{"message":"Server is not ready"}"""

    server { case "/ksql" => (StatusCode.ServiceUnavailable, failure) }.objects.map {
      case Left(error) =>
        assertEquals(error.code, ErrorCode.UpstreamKsql)
        assert(clue(error.message).contains("Server is not ready"))
      case Right(other) => fail(s"expected a failure, got $other")
    }
  }

  test("an answer that is not the array of entities is a failure and not an empty listing") {
    // An empty listing would say "this ksqlDB has no streams", which is a claim about a server that in
    // fact said something KUI could not read.
    server { case "/ksql" => (StatusCode.Ok, """{"not":"an array"}""") }.objects.map {
      case Left(error) => assertEquals(error.code, ErrorCode.UpstreamKsql)
      case Right(other) => fail(s"expected a failure, got $other")
    }
  }

  test("a statement answers the server's own sentence and its command id") {
    val status =
      """[{"@type":"currentStatus","commandId":"stream/ORDERS/create",
        |  "commandStatus":{"status":"SUCCESS","message":"Stream created and running"}}]""".stripMargin

    val statement = KsqlStatement.parse("CREATE STREAM ORDERS (ID STRING);").toOption.get

    server { case "/ksql" => (StatusCode.Ok, status) }.execute(statement).map {
      case Right(StatementOutcome.Status(message, entity)) =>
        assertEquals(message, "Stream created and running")
        assertEquals(entity, Some("stream/ORDERS/create"))
      case other => fail(s"expected a status, got $other")
    }
  }

  test("a pull query answers columns and rows, and a SQL NULL stays a NULL") {
    val answer =
      """[{"header":{"queryId":"q","schema":"`ID` STRING, `TOTAL` DOUBLE, `NOTE` STRING"}},
        |{"row":{"columns":["17",42.5,null]}},
        |{"finalMessage":"Limit Reached"}]""".stripMargin

    val statement = KsqlStatement.parse("SELECT * FROM ORDERS WHERE ID = '17';").toOption.get

    server { case "/query" => (StatusCode.Ok, answer) }.execute(statement).map {
      case Right(StatementOutcome.Rows(columns, rows)) =>
        assertEquals(columns, List("ID", "TOTAL", "NOTE"))
        // A number renders as its compact JSON and a NULL stays `None`: a table that drew `null` as the
        // word "null" would say something about a value nobody has.
        assertEquals(rows, List(List(Some("17"), Some("42.5"), None)))
      case other => fail(s"expected rows, got $other")
    }
  }

  test("a push query that reached this adapter is a caller defect and says so rather than buffering") {
    val statement = KsqlStatement.parse("SELECT * FROM ORDERS EMIT CHANGES;").toOption.get

    server(PartialFunction.empty).execute(statement).map {
      case Left(error) => assertEquals(error.code, ErrorCode.InvalidState)
      case Right(other) => fail(s"expected a refusal, got $other")
    }
  }

  test("a push query's frames arrive one line at a time, header first") {
    // The shape of a chunked `/query` response: a JSON array written incrementally, one element per line
    // with the array's punctuation around it. Reading it as a document would need it to be complete, and
    // a push query's never is.
    val chunks =
      "[{\"header\":{\"queryId\":\"q\",\"schema\":\"`ID` STRING, `TOTAL` DOUBLE\"}},\n" +
        "{\"row\":{\"columns\":[\"17\",42.5]}},\n" +
        "{\"row\":{\"columns\":[\"18\",null]}},\n"

    val statement = KsqlStatement.parse("SELECT * FROM ORDERS EMIT CHANGES;").toOption.get

    server(PartialFunction.empty, streamBody = chunks)
      .rows(statement)
      .compile
      .toList
      .map(frames =>
        assertEquals(
          frames,
          List(
            Right(QueryFrame.Header(List("ID", "TOTAL"))),
            Right(QueryFrame.Row(QueryRow(List(Some("17"), Some("42.5"))))),
            Right(QueryFrame.Row(QueryRow(List(Some("18"), None))))
          )
        )
      )
  }

  test("a push query that the server finished ends `exhausted` rather than simply stopping") {
    val chunks =
      "[{\"header\":{\"queryId\":\"q\",\"schema\":\"`ID` STRING\"}},\n" +
        "{\"finalMessage\":\"Limit Reached\"}]"

    val statement = KsqlStatement.parse("SELECT * FROM ORDERS EMIT CHANGES LIMIT 1;").toOption.get

    server(PartialFunction.empty, streamBody = chunks)
      .rows(statement)
      .compile
      .toList
      .map(frames => assertEquals(frames.last, Right(QueryFrame.Ended(exhausted = true))))
  }

  test("a push query refused before it started arrives as one terminal failure, not an empty stream") {
    // An empty stream would reach the browser as "this query matched nothing", which is the most
    // misleading thing the screen could say about a query that was refused.
    val refusal = """{"@type":"statement_error","message":"Cannot execute push query"}"""
    val statement = KsqlStatement.parse("SELECT * FROM ORDERS EMIT CHANGES;").toOption.get

    server(PartialFunction.empty, streamBody = refusal, streamStatus = StatusCode.BadRequest)
      .rows(statement)
      .compile
      .toList
      .map { frames =>
        assertEquals(frames.size, 1)
        assertEquals(frames.head.left.toOption.map(_.code), Some(ErrorCode.Validation))
      }
  }

  test("an error frame mid-stream is a failure rather than a row nobody can read") {
    val chunks =
      "[{\"header\":{\"queryId\":\"q\",\"schema\":\"`ID` STRING\"}},\n" +
        "{\"errorMessage\":{\"message\":\"the query was terminated\"}},\n"

    val statement = KsqlStatement.parse("SELECT * FROM ORDERS EMIT CHANGES;").toOption.get

    server(PartialFunction.empty, streamBody = chunks)
      .rows(statement)
      .compile
      .toList
      .map(frames => assertEquals(frames.last.left.toOption.map(_.code), Some(ErrorCode.UpstreamKsql)))
  }

  test("the schema's column names survive a nested type, which a naive comma split does not") {
    // `` `V` ARRAY<STRUCT<`A` INT, `B` INT>> `` is one column, and splitting on every comma would make
    // four columns out of two — and then every row would be drawn under the wrong headings.
    assertEquals(
      KsqlHttp.columnsOf("`K` STRING KEY, `V` ARRAY<STRUCT<`A` INT, `B` INT>>, `W` DECIMAL(4, 2)"),
      List("K", "V", "W")
    )
    assertEquals(KsqlHttp.columnsOf("`ID` STRING"), List("ID"))
    assertEquals(KsqlHttp.columnsOf(""), Nil)
  }

  test("a cell renders as text, and only a JSON null becomes a NULL") {
    assertEquals(KsqlHttp.cellOf(io.circe.Json.Null), None)
    assertEquals(KsqlHttp.cellOf(io.circe.Json.fromString("x")), Some("x"))
    assertEquals(KsqlHttp.cellOf(io.circe.Json.fromInt(3)), Some("3"))
    assertEquals(KsqlHttp.cellOf(io.circe.Json.True), Some("true"))
    // The literal string "null" is a value somebody stored, and it is not a NULL.
    assertEquals(KsqlHttp.cellOf(io.circe.Json.fromString("null")), Some("null"))
  }

  test("a configured path is applied once, not twice") {
    // `RegistryHttp`'s defect, which cost that service a milestone: the resilient backend prefixes the
    // base URL's own path, so a request built against the full configured URL has it applied twice — and
    // a ksqlDB behind an ingress at `/ksql` answers 404 for `/ksql/ksql/ksql`.
    val seen = scala.collection.mutable.ListBuffer.empty[String]

    val calls: Backend[IO] = BackendStub[IO](summon[sttp.monad.MonadError[IO]]).whenAnyRequest
      .thenRespondF { request =>
        seen += "/" + request.uri.path.mkString("/")
        IO.pure(ResponseStub.adjust("[]", StatusCode.Ok): sttp.client4.Response[StubBody])
      }

    val streaming: StreamBackend[IO, Fs2Streams[IO]] =
      StreamBackendStub[IO, Fs2Streams[IO]](summon[sttp.monad.MonadError[IO]]).whenAnyRequest
        .thenRespondF(_ =>
          IO.pure(
            ResponseStub.adjust(Stream.empty.covaryAll[IO, Byte], StatusCode.Ok): sttp.client4.Response[
              StubBody
            ]
          )
        )

    val client = new KsqlHttp[IO](
      calls,
      streaming,
      SafeUrl.unsafe("http://ingress/ksql"),
      scala.concurrent.duration.Duration(30, "seconds"),
      KsqlCredentials.anonymous[IO]
    )

    client.objects.map(_ => assertEquals(seen.toList, List("/ksql")))
  }
}
