package kui.connect.infrastructure

import cats.effect.{IO, Ref}
import sttp.client4.impl.cats.implicits.*
import sttp.client4.testing.{BackendStub, ResponseStub, StubBody}
import sttp.client4.Backend
import sttp.model.{Method, StatusCode}

import kui.config.SafeUrl
import kui.connect.domain.*
import kui.kernel.error.ErrorCode
import kui.kernel.{ConnectName, ConnectorName}
import kui.testkit.KuiIOSuite

/** What the client does with each answer a Kafka Connect cluster can give.
  *
  * A stub rather than a running worker: every promise here is a promise about a *response*, and a real
  * Connect cluster is the slowest possible way to produce one — and cannot be made to produce most of them at
  * all. The worker KUI has to survive is the one mid-rebalance, the one that lists a connector and refuses
  * its status, and the one from 2018 that has never heard of `?expand=`.
  */
final class ConnectHttpSuite extends KuiIOSuite {

  private val base: SafeUrl = SafeUrl.unsafe("http://kafka-connect:8083")
  private val payments: ConnectName = ConnectName.unsafe("payments")
  private val elastic: ConnectorName = ConnectorName.unsafe("elastic-sink")

  private def worker(
      respond: PartialFunction[String, (StatusCode, String)],
      address: SafeUrl = base
  ): ConnectHttp[IO] = {
    val backend: Backend[IO] = BackendStub[IO](summon[sttp.monad.MonadError[IO]]).whenAnyRequest
      .thenRespondF { request =>
        val path = "/" + request.uri.path.mkString("/")
        val (status, body) = respond.applyOrElse(path, (_: String) => (StatusCode.NotFound, ""))
        IO.pure(ResponseStub.adjust(body, status): sttp.client4.Response[StubBody])
      }
    new ConnectHttp[IO](backend, address, payments, ConnectCredentials.anonymous[IO])
  }

  /** The trace an Elasticsearch sink writes when its cluster is unreachable, as JSON escapes it.
    *
    * Held apart from the document below only so that neither line runs past this codebase's column limit:
    * `\\n` and `\\t` here are the two characters JSON wants, not a real newline, so the fixture is a
    * document a worker could actually have sent.
    */
  private val elasticTrace: String =
    "org.apache.kafka.connect.errors.ConnectException: connection refused to es-01:9200" +
      "\\n\\tat org.apache.kafka.connect.runtime.WorkerSinkTask.poll(WorkerSinkTask.java:329)"

  /** The expanded document Kafka 2.3 and later answer: one key per connector, each with its status. */
  private val expanded: String =
    s"""{
      |  "orders-source": {
      |    "info": { "name": "orders-source", "type": "source" },
      |    "status": {
      |      "name": "orders-source",
      |      "connector": { "state": "RUNNING", "worker_id": "10.0.0.1:8083" },
      |      "tasks": [
      |        { "id": 1, "state": "RUNNING", "worker_id": "10.0.0.2:8083", "trace": "" },
      |        { "id": 0, "state": "RUNNING", "worker_id": "10.0.0.1:8083" }
      |      ],
      |      "type": "source"
      |    }
      |  },
      |  "elastic-sink": {
      |    "info": { "name": "elastic-sink", "type": "sink" },
      |    "status": {
      |      "name": "elastic-sink",
      |      "connector": { "state": "RUNNING", "worker_id": "10.0.0.1:8083" },
      |      "tasks": [
      |        { "id": 0, "state": "FAILED", "worker_id": "10.0.0.2:8083", "trace": "$elasticTrace" }
      |      ],
      |      "type": "sink"
      |    }
      |  },
      |  "archive-sink": {
      |    "status": {
      |      "name": "archive-sink",
      |      "connector": { "state": "PAUSED", "worker_id": "10.0.0.1:8083" },
      |      "tasks": []
      |    }
      |  }
      |}""".stripMargin

  private val rebalanceBody: String =
    """{"error_code":409,"message":"Cannot complete request momentarily due to stale configuration """ +
      """(typically caused by a concurrent config change)"}"""

  test("a worker that names three connectors reaches the domain with each task's own state") {
    // The required case, at the boundary the states actually cross. Tasks arrive in id order however the
    // worker listed them, because two screens reading one document must not order them differently.
    worker { case "/connectors" => (StatusCode.Ok, expanded) }.connectors.map {
      case Right(facts) =>
        assertEquals(
          facts.connectors.map(_.name.value),
          List("archive-sink", "elastic-sink", "orders-source")
        )
        assertEquals(
          facts.connectors.map(_.tasks.map(task => s"${task.id.value}:${task.state.wire}")),
          List(Nil, List("0:FAILED"), List("0:RUNNING", "1:RUNNING"))
        )
        assertEquals(facts.connectors.map(_.runningTasks), List(0, 0, 2))
        assertEquals(facts.unreadable, Nil)
      case Left(error) => fail(s"expected connectors, got $error")
    }
  }

  test("a task that failed carries the worker's own trace, and the reason is its first line") {
    // Required case. Never a word KUI chose: the sentence is a slice of the worker's text, and the whole
    // trace travels beside it so that nobody has to open a worker's log to read what KUI already has.
    worker { case "/connectors" => (StatusCode.Ok, expanded) }.connectors.map {
      case Right(facts) =>
        val failed = facts.connectors
          .find(_.name.value == "elastic-sink")
          .flatMap(_.tasks.headOption)
          .getOrElse(fail("the fixture has no failed task"))

        assertEquals(
          failed.reason,
          Some("org.apache.kafka.connect.errors.ConnectException: connection refused to es-01:9200")
        )
        assert(clue(failed.trace.getOrElse("")).contains("WorkerSinkTask.poll"))
      case Left(error) => fail(s"expected connectors, got $error")
    }
  }

  test("an empty trace on a healthy task is no trace at all") {
    // Connect writes `"trace": ""` on a task that has not failed. An empty reason would put an empty
    // sentence under a healthy connector.
    worker { case "/connectors" => (StatusCode.Ok, expanded) }.connectors.map {
      case Right(facts) =>
        val healthy = facts.connectors.find(_.name.value == "orders-source").toList.flatMap(_.tasks)

        assertEquals(healthy.flatMap(_.trace), Nil)
        assertEquals(healthy.flatMap(_.reason), Nil)
      case Left(error) => fail(s"expected connectors, got $error")
    }
  }

  test("a worker that named no type leaves the direction unknown rather than guessing") {
    worker { case "/connectors" => (StatusCode.Ok, expanded) }.connectors.map {
      case Right(facts) =>
        assertEquals(
          facts.connectors.map(connector => connector.name.value -> connector.kind.wire).toMap,
          Map("orders-source" -> "source", "elastic-sink" -> "sink", "archive-sink" -> "")
        )
      case Left(error) => fail(s"expected connectors, got $error")
    }
  }

  test("a rebalancing worker is KUI-CONNECT-REBALANCING and an application error, not an outage") {
    // The rule this packet owns, at the point the 409 is classified. `ApplicationError` is what keeps it
    // out of the capability registry entirely (ADR-039 §6): a business refusal must never dim a feature.
    worker { case "/connectors" => (StatusCode.Conflict, rebalanceBody) }.connectors.map {
      case Left(error) =>
        assertEquals(error.code, ErrorCode.ConnectRebalancing)
        assertNotEquals(error.code, ErrorCode.UpstreamUnavailable)
        assert(error.isInstanceOf[kui.kernel.error.ApplicationError])
        assert(clue(error.message).contains("rebalancing"))
        assert(clue(error.message).contains("stale configuration"))
      case Right(facts) => fail(s"expected a rebalance refusal, got $facts")
    }
  }

  test("a pre-2.3 worker that answers a bare list is read one status at a time") {
    worker {
      case "/connectors" => (StatusCode.Ok, """["orders-source","elastic-sink"]""")
      case "/connectors/orders-source/status" =>
        (
          StatusCode.Ok,
          """{"name":"orders-source","connector":{"state":"RUNNING"},"tasks":[{"id":0,"state":"RUNNING"}]}"""
        )
      case "/connectors/elastic-sink/status" =>
        (StatusCode.Ok, """{"name":"elastic-sink","connector":{"state":"PAUSED"},"tasks":[]}""")
    }.connectors.map {
      case Right(facts) =>
        assertEquals(facts.connectors.map(_.name.value), List("elastic-sink", "orders-source"))
        assertEquals(facts.unreadable, Nil)
      case Left(error) => fail(s"expected connectors, got $error")
    }
  }

  test("a connector the worker will not describe is named rather than dropped") {
    // A connector missing from a list looks like a connector that was deleted, and one worker of a Connect
    // cluster being wedged loses the status of its own share while the rest answer normally.
    worker {
      case "/connectors" => (StatusCode.Ok, """["orders-source","elastic-sink"]""")
      case "/connectors/orders-source/status" =>
        (StatusCode.Ok, """{"name":"orders-source","connector":{"state":"RUNNING"},"tasks":[]}""")
    }.connectors.map {
      case Right(facts) =>
        assertEquals(facts.connectors.map(_.name.value), List("orders-source"))
        assertEquals(facts.unreadable, List("elastic-sink"))
        assert(facts.partial)
      case Left(error) => fail(s"expected connectors, got $error")
    }
  }

  test("a connector whose expanded entry has no status is unreadable rather than absent") {
    worker {
      case "/connectors" => (StatusCode.Ok, """{"elastic-sink":{"info":{"name":"elastic-sink"}}}""")
    }.connectors.map {
      case Right(facts) =>
        assertEquals(facts.connectors, Nil)
        assertEquals(facts.unreadable, List("elastic-sink"))
      case Left(error) => fail(s"expected connectors, got $error")
    }
  }

  test("a task list KUI cannot read makes the connector unreadable rather than a connector with 0 tasks") {
    // All or nothing per connector: a connector drawn with three of its four tasks reports `3/3 tasks`
    // over a cluster with four, and the task quietly dropped is the one that was failing.
    worker {
      case "/connectors" =>
        (
          StatusCode.Ok,
          """{"elastic-sink":{"status":{"connector":{"state":"RUNNING"},""" +
            """"tasks":[{"id":-1,"state":"RUNNING"}]}}}"""
        )
    }.connectors.map {
      case Right(facts) =>
        assertEquals(facts.connectors, Nil)
        assertEquals(facts.unreadable, List("elastic-sink"))
      case Left(error) => fail(s"expected connectors, got $error")
    }
  }

  test("a 404 on the connector list is an address that is not a Connect worker") {
    // `/connectors` exists on every Connect worker there has ever been, so this is a proxy or an ingress
    // pointed at the wrong service — which is a sentence an operator can act on.
    worker { case "/never" => (StatusCode.Ok, "") }.connectors.map {
      case Left(error) =>
        assertEquals(error.code, ErrorCode.UpstreamUnavailable)
        assert(clue(error.message).contains("does not look like a Kafka Connect worker"))
      case Right(facts) => fail(s"expected a failure, got $facts")
    }
  }

  test("credentials that are rejected are KUI's problem and say so") {
    worker { case "/connectors" => (StatusCode.Unauthorized, "") }.connectors.map(answer =>
      assertEquals(answer.left.map(_.code), Left(ErrorCode.UpstreamAuth))
    )
  }

  test("requests are built relative to the root, because failover puts the base path back on") {
    // `RegistryHttp`'s defect, which cost the schema service a milestone: `Failover.rebase` replaces the
    // scheme and authority and *prefixes the base URL's own path*, so a client that also built its
    // requests against the full configured URL had that path applied twice. A Connect cluster behind an
    // ingress at `/connect` would have produced `/connect/connect/connectors` and an honest 404.
    Ref.of[IO, String]("").flatMap { asked =>
      val backend: Backend[IO] = BackendStub[IO](summon[sttp.monad.MonadError[IO]]).whenAnyRequest
        .thenRespondF { request =>
          asked
            .set("/" + request.uri.path.mkString("/"))
            .as(ResponseStub.adjust("{}", StatusCode.Ok): sttp.client4.Response[StubBody])
        }

      new ConnectHttp[IO](
        backend,
        SafeUrl.unsafe("http://gateway.internal/connect"),
        payments,
        ConnectCredentials.anonymous[IO]
      ).connectors >> asked.get.assertEquals("/connectors")
    }
  }

  test("a restart asks for the tasks too, and asks for all of them") {
    // Both parameters are the opposite of the API's defaults, and both are the point: `includeTasks=false`
    // restarts the connector and leaves its dead tasks dead, which is the state §7.7's operator is
    // pressing the button to get out of.
    Ref.of[IO, String]("").flatMap { asked =>
      val backend: Backend[IO] = BackendStub[IO](summon[sttp.monad.MonadError[IO]]).whenAnyRequest
        .thenRespondF { request =>
          asked
            .set(s"${request.method.method} ${request.uri.toString}")
            .as(ResponseStub.adjust("", StatusCode.Accepted): sttp.client4.Response[StubBody])
        }

      new ConnectHttp[IO](backend, base, payments, ConnectCredentials.anonymous[IO])
        .operate(elastic, ConnectorOperation.Restart) >>
        asked.get.map { line =>
          assert(clue(line).startsWith(Method.POST.method))
          assert(clue(line).contains("/connectors/elastic-sink/restart"))
          assert(clue(line).contains("includeTasks=true"))
          assert(clue(line).contains("onlyFailed=false"))
        }
    }
  }

  test("pause and resume are the worker's own two verbs and nothing else") {
    Ref.of[IO, List[String]](Nil).flatMap { asked =>
      val backend: Backend[IO] = BackendStub[IO](summon[sttp.monad.MonadError[IO]]).whenAnyRequest
        .thenRespondF { request =>
          asked
            .update(_ :+ s"${request.method.method} /${request.uri.path.mkString("/")}")
            .as(ResponseStub.adjust("", StatusCode.Accepted): sttp.client4.Response[StubBody])
        }

      val client = new ConnectHttp[IO](backend, base, payments, ConnectCredentials.anonymous[IO])

      client.operate(elastic, ConnectorOperation.Pause) >>
        client.operate(elastic, ConnectorOperation.Resume) >>
        asked.get.assertEquals(
          List("PUT /connectors/elastic-sink/pause", "PUT /connectors/elastic-sink/resume")
        )
    }
  }

  test("an operation on a connector the worker does not have names the connector it could not find") {
    // There is no KUI-CONNECTOR-NOT-FOUND in the shipped vocabulary and house rule 3 forbids adding one.
    // A 501 would say this deployment cannot do it at all, which is false; a 409 naming the connector is
    // the answer the alerts service already gives for an event id that names nothing.
    worker { case "/never" => (StatusCode.Ok, "") }
      .operate(elastic, ConnectorOperation.Pause)
      .map {
        case Left(error) =>
          assertEquals(error.code, ErrorCode.InvalidState)
          assert(clue(error.message).contains("elastic-sink"))
          assert(clue(error.message).contains("payments"))
        case Right(_) => fail("expected a refusal")
      }
  }

  test("an operation refused mid-rebalance is the rebalance refusal, not a failed restart") {
    worker { case "/connectors/elastic-sink/restart" => (StatusCode.Conflict, rebalanceBody) }
      .operate(elastic, ConnectorOperation.Restart)
      .map(answer => assertEquals(answer.left.map(_.code), Left(ErrorCode.ConnectRebalancing)))
  }

  test("an answer that is neither a list nor an expanded document is reported as not understood") {
    worker { case "/connectors" => (StatusCode.Ok, "\"yes\"") }.connectors.map {
      case Left(error) => assert(clue(error.message).contains("could not understand"))
      case Right(facts) => fail(s"expected a failure, got $facts")
    }
  }

  test("the upstream is named per Connect cluster, so a dashboard says which one is failing") {
    assertEquals(ConnectHttp.upstreamName(payments), "kafka-connect.payments")
  }
}
