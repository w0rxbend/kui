package kui.alerts.app

import scala.concurrent.duration.DurationInt

import cats.effect.testkit.TestControl
import cats.effect.{IO, Ref}
import munit.CatsEffectSuite

import kui.alerts.application.EvaluateAlerts
import kui.alerts.domain.{AlertRuleState, Evaluation}
import kui.kernel.ClusterId
import kui.testkit.fakes.FakeStructuredLogger

/** The cadence, and what a pass that raises does to the fibre.
  *
  * In virtual time: the loop's whole contract is about what has happened after an interval, and a suite that
  * slept through one would be slow on every run and flaky on a loaded machine.
  */
final class AlertEvaluationLoopSuite extends CatsEffectSuite {

  private val cluster = ClusterId.unsafe("prod-eu")
  private val interval = 60.seconds

  private def counting(passes: Ref[IO, Int]): EvaluateAlerts[IO] =
    new EvaluateAlerts[IO] {
      def pass(id: ClusterId): IO[Evaluation] =
        passes.update(_ + 1).as(Evaluation(Nil, Nil, Nil, AlertRuleState.empty, Nil))
    }

  test("the first pass runs immediately, so a fresh deployment is not blind for a minute") {
    // The operator watching a process start is the one most likely to be looking, and a loop that slept
    // first would answer every request in its first interval with a feed that has never been evaluated.
    TestControl
      .executeEmbed(
        for {
          passes <- Ref.of[IO, Int](0)
          logger <- FakeStructuredLogger[IO]
          _ <- AlertEvaluationLoop.resource[IO](cluster, counting(passes), interval, logger).use { _ =>
            IO.sleep(1.second) >> passes.get
          }
          seen <- passes.get
        } yield seen
      )
      .map(seen => assertEquals(seen, 1))
  }

  test("a pass runs once per interval and not once per tick") {
    TestControl
      .executeEmbed(
        for {
          passes <- Ref.of[IO, Int](0)
          logger <- FakeStructuredLogger[IO]
          seen <- AlertEvaluationLoop.resource[IO](cluster, counting(passes), interval, logger).use { _ =>
            IO.sleep(interval * 3 + 1.second) >> passes.get
          }
        } yield seen
      )
      .map(seen => assertEquals(seen, 4))
  }

  test("the fibre stops when the composition root's resource closes") {
    // A pass in flight at shutdown must not outlive the process it belongs to.
    TestControl
      .executeEmbed(
        for {
          passes <- Ref.of[IO, Int](0)
          logger <- FakeStructuredLogger[IO]
          _ <- AlertEvaluationLoop
            .resource[IO](cluster, counting(passes), interval, logger)
            .use(_ => IO.sleep(1.second))
          before <- passes.get
          _ <- IO.sleep(interval * 5)
          after <- passes.get
        } yield (before, after)
      )
      .map((before, after) => assertEquals(after, before))
  }

  test("a pass that raises is logged and the fibre keeps its cadence") {
    // A port that raised rather than answering is a bug in an adapter, and it must not be able to end the
    // fibre: the cluster would go unevaluated for the life of the process with nothing on any screen
    // saying so.
    val raising = new EvaluateAlerts[IO] {
      def pass(id: ClusterId): IO[Evaluation] = IO.raiseError(new RuntimeException("the adapter threw"))
    }

    TestControl
      .executeEmbed(
        for {
          logger <- FakeStructuredLogger[IO]
          _ <- AlertEvaluationLoop
            .resource[IO](cluster, raising, interval, logger)
            .use(_ => IO.sleep(interval * 2 + 1.second))
          logged <- logger.entries
        } yield logged.count(_.message.contains("raised instead of answering"))
      )
      .map(raised => assertEquals(raised, 3))
  }
}
