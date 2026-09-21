package kui.connect.application

import cats.effect.IO

import kui.connect.domain.{ConnectorFacts, ConnectorOperation}
import kui.kernel.error.ErrorCode
import kui.kernel.{ClusterId, ConnectorName}
import kui.testkit.KuiIOSuite
import kui.testkit.fakes.FakeStructuredLogger

/** What the use cases answer, and what they ask the workers before answering it. */
final class ConnectUseCasesSuite extends KuiIOSuite {

  import ConnectRig.*

  private def useCases(
      workers: Map[(ClusterId, kui.kernel.ConnectName), kui.connect.domain.ConnectWorkerPort[IO]],
      sink: ConnectorOperationSink[IO] = ConnectorOperationSink.noop[IO]
  ): IO[ConnectUseCases[IO]] =
    FakeStructuredLogger[IO].map { logger =>
      val source = new Source(workers)
      ConnectUseCases.make[IO](source, MutationGuard.make[IO](source, sink, logger))
    }

  test("a worker that names three connectors and their task states reaches the wire with each task") {
    // The required case, at the layer that assembles the answer. Each connector's *tasks* travel, not a
    // running count computed here: `Connector.runningTasks` is the one place that decides what running
    // means, and the mapping reads it off the domain type.
    val facts = ConnectorFacts.complete(
      List(
        connector("orders-source", tasks = List(task(0, "RUNNING"), task(1, "RUNNING"), task(2, "RUNNING"))),
        connector("elastic-sink", tasks = List(task(0, "RUNNING"), task(1, "FAILED", Some("boom")))),
        connector("archive-sink", state = "PAUSED", tasks = List(task(0, "PAUSED")))
      )
    )

    for {
      worker <- FakeWorker.create(answer = Right(facts))
      other <- FakeWorker.create(answer = Right(ConnectorFacts.complete(Nil)))
      cases <- useCases(Map((cluster, payments) -> worker, (cluster, analytics) -> other))
      answer <- cases.connectors(caller, cluster)
    } yield answer match {
      case Right(ConnectListing.Workers(reports)) =>
        assertEquals(reports.map(_.connect.value), List("payments", "analytics"))

        val read = reports.head.facts.toOption.map(_.connectors).getOrElse(Nil)

        assertEquals(read.map(_.name.value), List("orders-source", "elastic-sink", "archive-sink"))
        assertEquals(
          read.map(_.tasks.map(_.state.wire)),
          List(
            List("RUNNING", "RUNNING", "RUNNING"),
            List("RUNNING", "FAILED"),
            List("PAUSED")
          )
        )
        assertEquals(read.map(_.runningTasks), List(3, 1, 0))
      case other => fail(s"expected a listing over two workers, got $other")
    }
  }

  test("a deployment with no Connect address answers not_configured rather than an empty list") {
    // Required case, application half. The HTTP half — that this is a 200 — is `ConnectRoutesSuite`'s.
    useCases(Map.empty)
      .flatMap(_.connectors(caller, bare))
      .assertEquals(Right(ConnectListing.NotConfigured))
  }

  test("a cluster KUI has never heard of is a failure and not an empty screen") {
    useCases(Map.empty).flatMap(_.connectors(caller, ClusterId.unsafe("nowhere"))).map {
      case Left(error) => assertEquals(error.code, ErrorCode.ClusterNotFound)
      case Right(found) => fail(s"expected a failure, got $found")
    }
  }

  test("a worker that is rebalancing keeps its own row and does not fail the read") {
    // One Connect cluster mid-rebalance must cost one row rather than the screen — the reason the section
    // is per worker. What the row *says* is `ConnectMapping.section`'s decision and is asserted there.
    for {
      answering <- FakeWorker.create()
      cases <- useCases(
        Map(
          (cluster, payments) -> answering,
          (cluster, analytics) -> new FakeWorker(
            Left(rebalancing),
            Right(()),
            answering.asked,
            answering.reads
          )
        )
      )
      answer <- cases.connectors(caller, cluster)
    } yield answer match {
      case Right(ConnectListing.Workers(reports)) =>
        assertEquals(reports.head.facts.map(_.connectors.size), Right(1))
        assertEquals(reports(1).facts.left.map(_.code), Left(ErrorCode.ConnectRebalancing))
      case other => fail(s"expected a listing, got $other")
    }
  }

  test("a Connect cluster that is configured with no client built says so rather than saying nothing") {
    // Configured and unbuilt is a KUI defect; reporting it as "not configured" would hide it behind a
    // screen that looks deliberately switched off.
    useCases(Map.empty).flatMap(_.connectors(caller, cluster)).map {
      case Right(ConnectListing.Workers(reports)) =>
        assertEquals(reports.size, 2)
        assert(reports.forall(_.facts.isLeft))
        assert(
          clue(reports.head.facts.fold(_.message, _.toString)).contains("could not build a client")
        )
      case other => fail(s"expected a listing, got $other")
    }
  }

  test("a restart names the connector the caller asked for, and answers when it was accepted") {
    for {
      worker <- FakeWorker.create()
      cases <- useCases(Map((cluster, payments) -> worker))
      answer <- cases.operate(caller, cluster, payments, elastic, ConnectorOperation.Restart)
      asked <- worker.asked.get
    } yield {
      assertEquals(asked, List((elastic, ConnectorOperation.Restart)))
      assertEquals(answer.map(_.connector), Right(elastic))
      assertEquals(answer.map(_.operation), Right(ConnectorOperation.Restart))
    }
  }

  test("a Connect cluster this deployment does not configure is refused before any worker is asked") {
    for {
      cases <- useCases(Map((cluster, payments) -> new NeverWorker))
      answer <- cases.operate(
        caller,
        cluster,
        kui.kernel.ConnectName.unsafe("ghost"),
        elastic,
        ConnectorOperation.Pause
      )
    } yield answer match {
      case Left(error) =>
        assertEquals(error.code, ErrorCode.Unsupported)
        assert(clue(error.message).contains("'ghost'"))
      case Right(accepted) => fail(s"expected a refusal, got $accepted")
    }
  }

  test("a worker's own refusal reaches the caller instead of a 200 that says nothing happened") {
    for {
      worker <- FakeWorker.create(accepted = Left(rebalancing))
      cases <- useCases(Map((cluster, payments) -> worker))
      answer <- cases.operate(caller, cluster, payments, elastic, ConnectorOperation.Pause)
    } yield assertEquals(answer.left.map(_.code), Left(ErrorCode.ConnectRebalancing))
  }

  test("a read-only cluster is refused before the worker is called, and the refusal is audited") {
    for {
      sink <- RecordingSink.create
      cases <- useCases(Map((readOnlyCluster, payments) -> new NeverWorker), sink)
      answer <- cases.operate(caller, readOnlyCluster, payments, elastic, ConnectorOperation.Restart)
      written <- sink.written.get
    } yield {
      assertEquals(answer.left.map(_.code), Left(ErrorCode.ReadOnly))
      assertEquals(written.map(_.outcome.label), List("refused"))
      assertEquals(written.map(_.resource), List("payments/elastic-sink"))
    }
  }

  test("the connector name in the audit record is the one the RBAC patterns are written against") {
    assertEquals(
      ConnectorOperationRecord.resourceOf(payments, ConnectorName.unsafe("orders-sink")),
      "payments/orders-sink"
    )
  }
}
