package kui.message.application.produce

import cats.effect.IO
import cats.effect.kernel.Deferred

import kui.kernel.error.{ErrorCode, KuiError}
import kui.security.Principal
import kui.security.audit.{MutationKind, MutationOutcome}
import kui.testkit.KuiIOSuite
import kui.testkit.fakes.FakeStructuredLogger

/** What the audit trail is allowed to claim about a produce nobody waited for.
  *
  * `MutationOutcome.Unknown`'s scaladoc in `libs/security-core` states the rule: "Kafka gives no guarantee
  * that it was *not* applied, so a record claiming either would be a lie." This guard's own comment repeated
  * the argument and then wrote `Failed` anyway, which is exactly such a claim — it tells an operator a
  * produce did not happen when it may well have. `services/consumer`'s copy of the same guard has always
  * written `Unknown` and has a case for it; these two never did, and turning this arm into `Succeeded` left
  * `./mill services.message.__.test` at 183/183 green.
  */
final class MutationAuditSuite extends KuiIOSuite {

  private val Caller: Principal = Principal.Anonymous

  test("a cancelled mutation is recorded as unknown, and never as a success or a failure") {
    for {
      audit <- ProduceRig.RecordingAudit.make
      logger <- FakeStructuredLogger[IO]
      guard = MutationGuard.make[IO](new ProduceRig.Profiles(readOnly = false), audit, logger)
      started <- Deferred[IO, Unit]
      running <- guard
        .guard(Caller, ProduceRig.Cluster, MutationKind.Produce, ProduceRig.Topic.value)(
          started.complete(()) >> IO.never[Either[KuiError, Unit]]
        )
        .start
      _ <- started.get
      _ <- running.cancel
      records <- audit.entries.get
    } yield {
      assertEquals(records.map(_.outcome), List(MutationOutcome.Unknown))
      assertEquals(
        records.head.detail.get("reason"),
        Some("the operation was cancelled after the request was sent")
      )
    }
  }

  test("a mutation whose client raised is recorded as failed, because KUI knows it did not complete") {
    // The arm beside the cancelled one, so that repairing the classification did not simply flatten the
    // two into one answer: an error is a fact, and a cancellation is the absence of one.
    for {
      audit <- ProduceRig.RecordingAudit.make
      logger <- FakeStructuredLogger[IO]
      guard = MutationGuard.make[IO](new ProduceRig.Profiles(readOnly = false), audit, logger)
      result <- guard
        .guard(Caller, ProduceRig.Cluster, MutationKind.Produce, ProduceRig.Topic.value)(
          IO.raiseError[Either[KuiError, Unit]](new RuntimeException("the producer threw"))
        )
        .attempt
      records <- audit.entries.get
    } yield {
      assert(result.isLeft, "an operation that raised must not be reported as an answer")
      assertEquals(records.map(_.outcome), List(MutationOutcome.Failed))
    }
  }

  test("a mutation that succeeded is recorded as succeeded") {
    for {
      audit <- ProduceRig.RecordingAudit.make
      logger <- FakeStructuredLogger[IO]
      guard = MutationGuard.make[IO](new ProduceRig.Profiles(readOnly = false), audit, logger)
      result <- guard.guard(Caller, ProduceRig.Cluster, MutationKind.Produce, ProduceRig.Topic.value)(
        IO.pure(Right(()): Either[KuiError, Unit])
      )
      records <- audit.entries.get
    } yield {
      assertEquals(result, Right(()))
      assertEquals(records.map(_.outcome), List(MutationOutcome.Succeeded))
    }
  }

  test("a read-only cluster is refused and the refusal is recorded, not the cancellation vocabulary") {
    for {
      audit <- ProduceRig.RecordingAudit.make
      logger <- FakeStructuredLogger[IO]
      guard = MutationGuard.make[IO](new ProduceRig.Profiles(readOnly = true), audit, logger)
      result <- guard.guard(Caller, ProduceRig.Cluster, MutationKind.Produce, ProduceRig.Topic.value)(
        IO.raiseError[Either[KuiError, Unit]](new AssertionError("the operation must not run"))
      )
      records <- audit.entries.get
    } yield {
      assertEquals(result.left.map(_.code), Left(ErrorCode.ReadOnly))
      assertEquals(records.map(_.outcome), List(MutationOutcome.Refused))
    }
  }

  test("an unknown cluster is refused before anything runs, and recorded as a failure") {
    for {
      audit <- ProduceRig.RecordingAudit.make
      logger <- FakeStructuredLogger[IO]
      guard = MutationGuard.make[IO](new ProduceRig.Profiles(readOnly = false, known = false), audit, logger)
      result <- guard.guard(Caller, ProduceRig.Cluster, MutationKind.Produce, ProduceRig.Topic.value)(
        IO.raiseError[Either[KuiError, Unit]](new AssertionError("the operation must not run"))
      )
      records <- audit.entries.get
    } yield {
      assertEquals(result.left.map(_.code), Left(ErrorCode.ClusterNotFound))
      assertEquals(records.map(_.outcome), List(MutationOutcome.Failed))
    }
  }
}
