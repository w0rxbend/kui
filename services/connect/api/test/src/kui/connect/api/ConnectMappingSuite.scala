package kui.connect.api

import java.time.Instant

import munit.FunSuite

import kui.connect.application.{ConnectListing, WorkerReport}
import kui.connect.contract.ConnectEndpoints
import kui.connect.domain.*
import kui.contracts.Section
import kui.contracts.capability.ReasonCode
import kui.kernel.error.{ApplicationError, ErrorCode, InfrastructureError, KuiError}
import kui.kernel.{ConnectName, ConnectorName, TaskId}

/** Which section each answer carries, and what a connector looks like once it is on the wire.
  *
  * Every assertion here is against `ConnectMapping`'s own functions — the ones the routes call — rather than
  * against a re-implementation of them in the fixture, which is the difference between gating the product and
  * gating the test.
  */
final class ConnectMappingSuite extends FunSuite {

  private val at: Instant = Instant.parse("2026-09-03T10:11:12Z")
  private val payments: ConnectName = ConnectName.unsafe("payments")

  private val rebalancing: KuiError = ApplicationError.Refused(
    ErrorCode.ConnectRebalancing,
    "the Kafka Connect cluster 'payments' is rebalancing and cannot answer yet: Cannot complete request " +
      "momentarily due to stale configuration"
  )

  private def connector(state: String, tasks: List[ConnectorTask], trace: Option[String] = None): Connector =
    Connector(payments, ConnectorName.unsafe("elastic-sink"), ConnectorKind.Sink, ConnectorState(state),
      Some("10.0.0.1:8083"), trace, tasks)

  private def task(id: Int, state: String, trace: Option[String] = None): ConnectorTask =
    ConnectorTask(TaskId.unsafe(id), ConnectorState(state), Some("10.0.0.1:8083"), trace)

  test("a rebalancing worker is reported as rebalancing and not as an upstream that is down") {
    // **The rule this packet owns**, at the point the section is chosen. `STARTING` is the one reason code
    // in the vocabulary whose sentence is about KUI not having finished rather than about the cluster being
    // broken, and a rebalance is exactly that: transient, self-clearing, and not something to send an
    // operator to look at a Connect cluster for. The mutation that reverses it — `ReasonCode.Starting` →
    // `ReasonCode.UpstreamUnavailable` — reddens this case and `ConnectCapabilitiesSuite`'s twin.
    ConnectMapping.section(rebalancing, at) match {
      case Section.Unavailable(reason, message, since) =>
        assertEquals(reason, ReasonCode.Starting)
        assertNotEquals(reason, ReasonCode.UpstreamUnavailable)
        assert(clue(message).contains("rebalancing"))
        assertEquals(since, Some(at))
      case other => fail(s"expected an unavailable section, got $other")
    }
  }

  test("every other failure goes through the fold every other section in the product uses") {
    // A second opinion here about what `KUI-UPSTREAM-UNAVAILABLE` means would be a second sentence for one
    // outage, on a screen beside cards that use the first.
    assertEquals(
      ConnectMapping.section(InfrastructureError.Unreachable("kafka-connect.payments", "refused"), at),
      Section.fromEither(
        Left(InfrastructureError.Unreachable("kafka-connect.payments", "refused")),
        at
      )
    )
    assertEquals(ConnectMapping.section(ApplicationError.Forbidden("no"), at).status, "forbidden")
  }

  test("a cluster with no Kafka Connect maps to not_configured and carries no worker list") {
    val response = ConnectMapping.response(ConnectListing.NotConfigured, at)

    assertEquals(response.connectors.status, "not_configured")
    assertEquals(response.connectors.toOption, None)
  }

  test("each configured Connect cluster keeps its own row, in the order it was configured") {
    val response = ConnectMapping.response(
      ConnectListing.Workers(
        List(
          WorkerReport(payments, Right(ConnectorFacts.complete(List(connector("RUNNING", Nil))))),
          WorkerReport(ConnectName.unsafe("analytics"), Left(rebalancing))
        )
      ),
      at
    )

    val workers = response.connectors.toOption.map(_.workers).getOrElse(Nil)

    assertEquals(workers.map(_.connect), List("payments", "analytics"))
    assertEquals(workers.head.connectors.status, "ok")
    assertEquals(workers(1).connectors.status, "unavailable")
  }

  test("the three derived figures come off the domain type rather than being recomputed here") {
    // `runningTasks` is decided in one place — `Connector.runningTasks`, which is the one that says
    // RESTARTING is not running — and this mapping reads it. Two implementations would be two answers.
    val during = connector("RUNNING", List(task(0, "RUNNING"), task(1, "RESTARTING"), task(2, "FAILED")))
    val wire = ConnectMapping.connector(during)

    assertEquals(wire.runningTasks, during.runningTasks)
    assertEquals(wire.taskCount, during.taskCount)
    assertEquals(wire.failed, during.isFailed)
    assertEquals(wire.runningTasks, 1)
  }

  test("a failed task's reason is the worker's own first line and its trace travels whole") {
    val trace = "org.apache.kafka.connect.errors.ConnectException: connection refused\n\tat Worker.poll"
    val wire = ConnectMapping.task(task(3, "FAILED", Some(trace)))

    assertEquals(wire.reason, Some("org.apache.kafka.connect.errors.ConnectException: connection refused"))
    assertEquals(wire.trace, Some(trace))
    assertEquals(wire.state, "FAILED")
    assertEquals(wire.id, 3)
  }

  test("a state word KUI has never heard of reaches the wire unchanged") {
    assertEquals(ConnectMapping.connector(connector("DESTROYED", Nil)).state, "DESTROYED")
  }

  test("a malformed Connect cluster name is a validation failure naming the path parameter") {
    ConnectMapping.connectName("c" * 300) match {
      case Left(error) =>
        assertEquals(error.code, ErrorCode.Validation)
        assertEquals(error.details.flatMap(_.field), List(ConnectEndpoints.ConnectNameParam))
      case Right(name) => fail(s"expected a refusal, got $name")
    }
  }

  test("a malformed connector name is a validation failure naming its own path parameter") {
    ConnectMapping.connectorName("c" * 300) match {
      case Left(error) =>
        assertEquals(error.details.flatMap(_.field), List(ConnectEndpoints.ConnectorNameParam))
      case Right(name) => fail(s"expected a refusal, got $name")
    }
  }

  test("a well-formed name is accepted as it was written") {
    assertEquals(ConnectMapping.connectName("payments").map(_.value), Right("payments"))
    assertEquals(ConnectMapping.connectorName("elastic-sink").map(_.value), Right("elastic-sink"))
  }
}
