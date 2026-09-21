package kui.alerts.application

import cats.effect.{Deferred, IO}
import munit.CatsEffectSuite

import kui.alerts.domain.AlertRule
import kui.kernel.error.ErrorCode
import kui.security.audit.{AuditPrincipal, MutationOutcome}
import kui.testkit.fakes.FakeStructuredLogger

/** The write: who may make it, what it records, and what it records when it does not finish. */
final class AcknowledgementSuite extends CatsEffectSuite {

  import AlertsRig.*

  private def rig: IO[(FakeStore, RecordingSink, AlertUseCases[IO])] =
    for {
      store <- FakeStore.create
      audit <- RecordingSink.create
      logger <- FakeStructuredLogger[IO]
      guard = MutationGuard.make[IO](new Profiles, audit, logger)
    } yield (store, audit, AlertUseCases.make[IO](new Profiles, store, guard))

  test("an acknowledgement closes the event, names the principal and answers the new open count") {
    val first = event(AlertRule.OfflinePartitions, "")
    val second = event(AlertRule.DiskUsage, "broker-1:/var")

    for {
      (store, audit, alerts) <- rig
      _ <- store.seed(cluster, List(first, second))
      result <- alerts.acknowledge(caller, cluster, first.id)
      records <- audit.written.get
    } yield {
      assertEquals(result.map(_.event.isOpen), Right(false))
      assertEquals(result.map(_.event.resolution.flatMap(_.by)), Right(Some(AuditPrincipal.render(caller))))
      // Read back from the store rather than decremented from the page the caller held.
      assertEquals(result.map(_.openCount), Right(1))
      assertEquals(records.map(_.outcome), List(MutationOutcome.Succeeded))
      assertEquals(records.head.resource, first.id.value)
    }
  }

  test("an acknowledgement on a read-only cluster is refused without the store being written") {
    // `Action.AlertsAcknowledge` is altering and `isAlter` answers the read-only question too, so this
    // refusal is the vocabulary's rather than this service's invention (ADR-053 §1). What is asserted
    // here is that it happens *before* the store, which is the half a vocabulary cannot enforce.
    for {
      (store, audit, alerts) <- rig
      _ <- store.seed(readOnlyCluster, List(event(AlertRule.DiskUsage, "broker-1:/var")))
      result <- alerts.acknowledge(caller, readOnlyCluster, event(AlertRule.DiskUsage, "broker-1:/var").id)
      writes <- store.writes.get
      records <- audit.written.get
    } yield {
      assertEquals(result.left.map(_.code), Left(ErrorCode.ReadOnly))
      assertEquals(writes, 0, clue = "the store must not be touched on a read-only cluster")
      assertEquals(records.map(_.outcome), List(MutationOutcome.Refused))
    }
  }

  test("an acknowledgement of an event that is already closed is a 409 and is audited as refused") {
    val open = event(AlertRule.OfflinePartitions, "")

    for {
      (store, audit, alerts) <- rig
      _ <- store.seed(cluster, List(open))
      _ <- alerts.acknowledge(caller, cluster, open.id)
      again <- alerts.acknowledge(caller, cluster, open.id)
      records <- audit.written.get
    } yield {
      assertEquals(again.left.map(_.code), Left(ErrorCode.InvalidState))
      assertEquals(again.left.map(_.code.httpStatus), Left(409))
      assertEquals(records.map(_.outcome), List(MutationOutcome.Succeeded, MutationOutcome.Refused))
    }
  }

  test("an id that names no event answers the same 409 and never says whether it existed") {
    for {
      (_, _, alerts) <- rig
      result <- alerts.acknowledge(caller, cluster, event(AlertRule.DiskUsage, "nope").id)
    } yield {
      assertEquals(result.left.map(_.code), Left(ErrorCode.InvalidState))
      assert(!result.left.exists(_.message.contains("exist")), clue = result)
    }
  }

  test("a cancelled acknowledgement is audited as Unknown and never as a success or a failure") {
    // `MutationOutcome.Unknown`'s own scaladoc is the rule, and it survives the move from a Kafka write to
    // KUI's own store: a cancellation lands between the compare and the swap, and a record saying `Failed`
    // would tell an incident review the bell was still ringing when it may not have been. The topic and
    // message services write `Failed` here; the consumer service is the one this copies.
    for {
      audit <- RecordingSink.create
      logger <- FakeStructuredLogger[IO]
      guard = MutationGuard.make[IO](new Profiles, audit, logger)
      started <- Deferred[IO, Unit]
      running <- guard
        .guard(caller, cluster, "disk-usage.broker-1.0")(
          started.complete(()) >> IO.never[Either[kui.kernel.error.KuiError, Unit]]
        )
        .start
      _ <- started.get
      _ <- running.cancel
      records <- audit.written.get
    } yield {
      assertEquals(records.map(_.outcome), List(MutationOutcome.Unknown))
      assertEquals(
        records.head.detail.get("reason"),
        Some("the operation was cancelled after the store was asked to close the event")
      )
    }
  }

  test("an acknowledgement on a cluster KUI has never heard of is a 404 and writes no store") {
    for {
      (store, _, alerts) <- rig
      result <- alerts.acknowledge(
        caller,
        kui.kernel.ClusterId.unsafe("nowhere"),
        event(AlertRule.DiskUsage, "x").id
      )
      writes <- store.writes.get
    } yield {
      assertEquals(result.left.map(_.code), Left(ErrorCode.ClusterNotFound))
      assertEquals(writes, 0)
    }
  }

  test("no acknowledgement record carries anything but the event, the principal and the outcome") {
    val open = event(AlertRule.OfflinePartitions, "")

    for {
      (store, audit, alerts) <- rig
      _ <- store.seed(cluster, List(open))
      _ <- alerts.acknowledge(caller, cluster, open.id)
      records <- audit.written.get
    } yield {
      val record = records.head

      assertEquals(record.cluster, cluster)
      assertEquals(record.resource, open.id.value)
      assertEquals(AuditPrincipal.render(record.principal), "anonymous (authentication is not enabled)")
      // A flat set of scalars: there is no field on this record that could hold a connection, a property
      // map or a secret, which is the property `MutationRecord` has and this one had to keep.
      assertEquals(record.detail, Map.empty[String, String])
    }
  }
}
