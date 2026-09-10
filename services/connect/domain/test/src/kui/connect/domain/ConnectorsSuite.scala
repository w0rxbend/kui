package kui.connect.domain

import munit.FunSuite

import kui.kernel.{ConnectName, ConnectorName, TaskId}

/** What a connector and its tasks *are*, before anything has been mapped or serialised.
  *
  * Every rule here is one a screen draws directly: the pill's word, the `3/3 tasks` figure, the drawer's
  * failed count, and the sentence under a failed card.
  */
final class ConnectorsSuite extends FunSuite {

  private val payments = ConnectName.unsafe("payments")

  private def task(id: Int, state: String, trace: Option[String] = None): ConnectorTask =
    ConnectorTask(TaskId.unsafe(id), ConnectorState(state), Some("10.0.0.1:8083"), trace)

  private def connector(
      state: String,
      tasks: List[ConnectorTask],
      trace: Option[String] = None
  ): Connector =
    Connector(
      connect = payments,
      name = ConnectorName.unsafe("orders-sink"),
      kind = ConnectorKind.Sink,
      state = ConnectorState(state),
      workerId = Some("10.0.0.1:8083"),
      trace = trace,
      tasks = tasks
    )

  test("a state word KUI has never heard of survives unchanged") {
    // The rule `ConnectorState` exists for. `STOPPED` arrived in Kafka 3.5 and `DESTROYED` appears
    // mid-deletion; an enum would have to answer for both, and every available answer — an `Unknown`
    // case, or the nearest known state — either greys out a connector the worker described perfectly or
    // invents a fact. §7.7's rule is the same one from the other end: never a status word KUI chose.
    val state = ConnectorState("DESTROYED")

    assertEquals(state.wire, "DESTROYED")
    assert(!state.isRunning)
    assert(!state.isFailed)
    assert(!state.isPaused)
  }

  test("a state is compared case-insensitively, because not every implementation shouts") {
    assert(ConnectorState("Running").isRunning)
    assert(ConnectorState("running").is(ConnectorState.Running))
  }

  test("a worker that named no type leaves the direction unknown rather than guessing source") {
    // Every Connect release before 2.0 sends no `type` at all. A defaulted `source` would point half the
    // icons on the screen the wrong way, which is a claim about somebody's data flow.
    assertEquals(ConnectorKind.fromWorker(None), ConnectorKind.Other(""))
    assertEquals(ConnectorKind.fromWorker(Some("  ")), ConnectorKind.Other(""))
    assertEquals(ConnectorKind.fromWorker(Some("SOURCE")), ConnectorKind.Source)
    assertEquals(ConnectorKind.fromWorker(Some("sink")), ConnectorKind.Sink)
    assertEquals(ConnectorKind.fromWorker(Some("bridge")).wire, "bridge")
  }

  test("a task's reason is the first line of the worker's own trace and nothing else") {
    val trace =
      "org.apache.kafka.connect.errors.ConnectException: connection refused to es-01:9200\n" +
        "\tat org.apache.kafka.connect.runtime.WorkerSinkTask.deliverMessages(WorkerSinkTask.java:586)\n" +
        "\tat org.apache.kafka.connect.runtime.WorkerSinkTask.poll(WorkerSinkTask.java:329)"

    assertEquals(
      task(0, "FAILED", Some(trace)).reason,
      Some("org.apache.kafka.connect.errors.ConnectException: connection refused to es-01:9200")
    )
  }

  test("a failed task with no trace has no reason, which is not the same as no problem") {
    // A worker that lost the task to a rebalance mid-failure reports FAILED with no trace. The row must
    // still be failed, and the reason must still be absent: a sentence composed from the state word
    // would be KUI inventing the thing §7.7 says it must never invent.
    val failed = task(1, "FAILED")

    assert(failed.state.isFailed)
    assertEquals(failed.reason, None)
    assertEquals(connector("RUNNING", List(failed)).reason, None)
  }

  test("running counts only RUNNING, so a restart in flight lowers the figure") {
    // `3/3 tasks` means three tasks are moving data. Counting RESTARTING or UNASSIGNED as running would
    // make the figure equal `taskCount` permanently, which is a number that says nothing — and the
    // moment an operator watches it is precisely the restart.
    val during = connector("RUNNING", List(task(0, "RUNNING"), task(1, "RESTARTING"), task(2, "UNASSIGNED")))

    assertEquals(during.runningTasks, 1)
    assertEquals(during.taskCount, 3)
  }

  test("a running connector with a failed task is failed, which is the whole of the drawer's count") {
    // §7.7's finding: the worker reports the connector as RUNNING and its sink task as dead. An operator
    // shown only the connector's own state is told everything is fine.
    val partly = connector("RUNNING", List(task(0, "RUNNING"), task(1, "FAILED", Some("boom"))))

    assert(partly.isFailed)
    assertEquals(partly.reason, Some("boom"))
  }

  test("the connector's own trace wins over its tasks', and the failed tasks are read in id order") {
    val bad = connector(
      "FAILED",
      List(task(2, "FAILED", Some("second")), task(1, "FAILED", Some("first"))),
      trace = Some("the connector configuration is invalid")
    )

    assertEquals(bad.reason, Some("the connector configuration is invalid"))
    assertEquals(bad.copy(trace = None).reason, Some("first"))
  }

  test("a healthy connector has no reason at all") {
    assertEquals(connector("RUNNING", List(task(0, "RUNNING"))).reason, None)
    assert(!connector("RUNNING", List(task(0, "RUNNING"))).isFailed)
  }

  test("facts with an unreadable connector say so rather than reporting a shorter list") {
    val facts = ConnectorFacts(List(connector("RUNNING", Nil)), List("elastic-sink"))

    assert(facts.partial)
    assert(!ConnectorFacts.complete(facts.connectors).partial)
  }

  test("the three operations spell their audit names the way a MutationKind spells one") {
    // Dotted, lower case, most general segment first — so the day `MutationKind` grows these cases,
    // nothing that reads the audit trail has to change. `MutationKindGapSuite` is the other half.
    assertEquals(
      ConnectorOperation.values.map(_.operation).toList,
      List("connect.connector.pause", "connect.connector.resume", "connect.connector.restart")
    )
    assert(ConnectorOperation.values.forall(op => op.operation == op.operation.toLowerCase))
    assertEquals(ConnectorOperation.fromWire("restart"), Some(ConnectorOperation.Restart))
    assertEquals(ConnectorOperation.fromWire("delete"), None)
  }
}
