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
    // **The rule this packet owns**, and the fixture is the half of it that was missing. The tasks were
    // `[2, 1]`, where `sortBy(_.id.value)` and `reverse` produce the same list, so replacing the sort with
    // `tasks.reverse` left all 129 cases green and `reason`'s own promise — *"in task-id order, so that two
    // screens reading one document cannot pick different tasks"* — was held by nothing.
    //
    // Three tasks in document order `[2, 1, 3]` separate all three candidate readings, which is what a
    // fixture has to do to be a gate: sorted picks task 1, the worker's own order picks task 2, and
    // reversed picks task 3. A worker is under no obligation to list tasks in id order — `ConnectHttp`
    // sorts them for this reason — and the card's sentence must not depend on which of the three the
    // document happened to be in.
    val unordered = List(
      task(2, "FAILED", Some("task 2 said")),
      task(1, "FAILED", Some("task 1 said")),
      task(3, "FAILED", Some("task 3 said"))
    )

    val bad = connector("FAILED", unordered, trace = Some("the connector configuration is invalid"))

    assertEquals(bad.reason, Some("the connector configuration is invalid"))
    assertEquals(bad.copy(trace = None).reason, Some("task 1 said"))
  }

  test("a task that is not failed lends no reason, however loud the trace it kept") {
    // The other half of `reason`'s sentence — *"the first **failed** task's reason, in task-id order"* —
    // and the half the id-order case above cannot reach, because every task in that fixture is failed.
    // W8-02 closed the `sortBy` half of this one expression and left the `filter` half measured by
    // nothing: dropping `.filter(_.state.isFailed)` kept all 138 connect cases green.
    //
    // A RUNNING task carrying a `trace` is a document a worker really sends. Connect keeps the trace of a
    // task that failed and was restarted, so the trace outlives the failure it describes. Read as the
    // connector's reason it prints a fault the operator already cleared as the live one, underneath a red
    // pill that came from a different task entirely — and [[ConnectorTask.reason]]'s own promise is
    // *"never a reason invented"*.
    //
    // Task 1 sorts first, so the filter is the only thing standing between the stale trace and the card.
    // The id-order case beside it stays green under the same mutation, which is why this is its own case.
    val stale = List(
      task(1, "RUNNING", Some("stale trace from a restart")),
      task(2, "FAILED", Some("task 2 said"))
    )

    assertEquals(connector("RUNNING", stale).reason, Some("task 2 said"))
  }

  test("a healthy connector has no reason at all") {
    assertEquals(connector("RUNNING", List(task(0, "RUNNING"))).reason, None)
    assert(!connector("RUNNING", List(task(0, "RUNNING"))).isFailed)
  }

  test("facts with an unreadable connector say so rather than reporting a shorter list") {
    // `unreadable` is read directly, here and on the wire. `ConnectorFacts.partial` used to wrap this
    // expression and had no production caller — `ConnectMapping.worker` puts the list itself in
    // `ConnectorsDto` — so it was deleted rather than kept as a declaration only a test uses.
    val facts = ConnectorFacts(List(connector("RUNNING", Nil)), List("elastic-sink"))

    assertEquals(facts.unreadable, List("elastic-sink"))
    assertEquals(ConnectorFacts.complete(facts.connectors).unreadable, Nil)
  }

  test("the three operations spell their audit names the way a MutationKind spells one") {
    // Dotted, lower case, most general segment first — so the day `MutationKind` grows these cases,
    // nothing that reads the audit trail has to change. `MutationKindGapSuite` is the other half.
    assertEquals(
      ConnectorOperation.values.map(_.operation).toList,
      List("connect.connector.pause", "connect.connector.resume", "connect.connector.restart")
    )
    assert(ConnectorOperation.values.forall(op => op.operation == op.operation.toLowerCase))
    // `wire` is the worker's own verb and is what `ConnectHttp.operate` builds each path from. There is no
    // `fromWire` any more: the routes bind one operation per endpoint statically, so nothing in the product
    // ever parsed one out of a string and the parser had only this line reading it.
    assertEquals(ConnectorOperation.values.map(_.wire).toList, List("pause", "resume", "restart"))
  }
}
