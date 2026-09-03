package kui.http.upstream

import scala.concurrent.duration.DurationInt

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import munit.CatsEffectSuite

import kui.kernel.PositiveInt

/** That KUI stops calling something that is plainly down, and finds out when it is back.
  *
  * Every case runs under `TestControl`, so the reset timer is asserted rather than waited for:
  * there is no `sleep` in this suite and nothing in it can be flaky on a loaded CI machine.
  */
final class CircuitBreakerSuite extends CatsEffectSuite {

  private val upstream = "schema-registry"
  private val threshold = PositiveInt.unsafe(3)
  private val reset = 30.seconds

  private def breaker: IO[CircuitBreaker[IO]] =
    CircuitBreaker.make[IO](upstream, threshold, reset)

  private val boom = new RuntimeException("connection refused")

  private def fail(b: CircuitBreaker[IO]): IO[Unit] =
    b.protect(IO.raiseError[Unit](boom)).attempt.void

  private def succeed(b: CircuitBreaker[IO]): IO[Unit] =
    b.protect(IO.unit).attempt.void

  test("opensAfterConsecutiveFailuresAndNotAfterInterleavedSuccesses") {
    val consecutive = for {
      b <- breaker
      _ <- fail(b).replicateA_(3)
      state <- b.state
    } yield state

    val interleaved = for {
      b <- breaker
      // Two failures, a success, two more failures. An upstream answering one call in three is
      // degraded, not down: cutting it off entirely would lose the calls that were working.
      _ <- fail(b) *> fail(b) *> succeed(b) *> fail(b) *> fail(b)
      state <- b.state
    } yield state

    TestControl.executeEmbed((consecutive, interleaved).tupled).map { (opened, stillClosed) =>
      assertEquals(opened, CircuitState.Open)
      assertEquals(stillClosed, CircuitState.Closed)
    }
  }

  test("openRejectsImmediatelyWithoutCallingTheBackend") {
    val program = for {
      b <- breaker
      calls <- Ref.of[IO, Int](0)
      _ <- fail(b).replicateA_(3)
      // Twenty more attempts while open. Not one of them may reach the backend: that is the whole
      // point of the breaker, and counting is the only way to prove it.
      outcomes <- b.protect(calls.update(_ + 1)).attempt.replicateA(20)
      reached <- calls.get
    } yield (reached, outcomes)

    TestControl.executeEmbed(program).map { (reached, outcomes) =>
      assertEquals(reached, 0, "a call reached the backend while the circuit was open")
      assert(outcomes.forall(_.isLeft))
      assert(
        outcomes.forall {
          case Left(CircuitOpenException(name, _)) => name == upstream
          case _ => false
        },
        outcomes.toString
      )
    }
  }

  test("halfOpenAdmitsExactlyOneProbe") {
    val program = for {
      b <- breaker
      admitted <- Ref.of[IO, Int](0)
      _ <- fail(b).replicateA_(3)
      _ <- IO.sleep(reset + 1.second)
      // Ten callers arrive at once the moment the circuit becomes eligible. Sending ten probes at
      // a recovering upstream is how it goes down again, so exactly one may pass.
      _ <- List
        .fill(10)(b.protect(admitted.update(_ + 1) *> IO.sleep(1.second)).attempt)
        .parSequence
      probes <- admitted.get
    } yield probes

    TestControl.executeEmbed(program).map(probes => assertEquals(probes, 1))
  }

  test("successInHalfOpenCloses") {
    val program = for {
      b <- breaker
      _ <- fail(b).replicateA_(3)
      _ <- IO.sleep(reset + 1.second)
      _ <- succeed(b)
      state <- b.state
      // And the circuit really is usable again, not merely labelled closed.
      allowed <- b.protect(IO.pure(42)).attempt
    } yield (state, allowed)

    TestControl.executeEmbed(program).map { (state, allowed) =>
      assertEquals(state, CircuitState.Closed)
      assertEquals(allowed, Right(42))
    }
  }

  test("failureInHalfOpenReopensAndResetsTheTimer") {
    val program = for {
      b <- breaker
      _ <- fail(b).replicateA_(3)
      _ <- IO.sleep(reset + 1.second)
      _ <- fail(b)
      reopened <- b.state
      // The failed probe restarts the full timer. Half a reset later the circuit is still shut:
      // the upstream had its chance and is still unwell, so the next probe is a whole reset away.
      _ <- IO.sleep(reset / 2)
      midway <- b.protect(IO.unit).attempt
      _ <- IO.sleep(reset)
      afterwards <- b.protect(IO.unit).attempt
    } yield (reopened, midway, afterwards)

    TestControl.executeEmbed(program).map { (reopened, midway, afterwards) =>
      assertEquals(reopened, CircuitState.Open)
      assert(midway.isLeft, "a probe was admitted before the reset timer had run again")
      assertEquals(afterwards, Right(()))
    }
  }

  test("a response that arrived but was not a success still counts as a failure") {
    // An upstream answering 503 to everything is as down as one refusing connections. A breaker
    // that only noticed exceptions would never open for it.
    val program = for {
      b <- breaker
      _ <- b.protect(IO.pure(503))(status => status < 500).replicateA_(3)
      state <- b.state
    } yield state

    TestControl.executeEmbed(program).map(state => assertEquals(state, CircuitState.Open))
  }

  test("emitsOneCircuitEventPerTransitionAndNoneForRepeats") {
    val program = for {
      b <- breaker
      collected <- b.events.take(3).compile.toList.start
      _ <- IO.sleep(1.second)
      _ <- fail(b).replicateA_(3) // -> Open, one event
      _ <- fail(b).replicateA_(5) // still open, refused, no events
      _ <- IO.sleep(reset + 1.second)
      _ <- succeed(b) // -> HalfOpen then Closed, two events
      events <- collected.joinWithNever
    } yield events

    TestControl.executeEmbed(program).map { events =>
      assertEquals(
        events.map(_.state),
        List(CircuitState.Open, CircuitState.HalfOpen, CircuitState.Closed)
      )
      assert(events.forall(_.upstream == upstream), events.toString)
      assertEquals(events.head.lastError, Some("connection refused"))
      // The transition that closed it carries no error, so a reader can tell recovery from failure
      // without parsing the state name.
      assertEquals(events.last.lastError, None)
    }
  }

  test("the state has a numeric form, so the gauge can be graphed") {
    assertEquals(CircuitState.Closed.code, 0L)
    assertEquals(CircuitState.Open.code, 1L)
    assertEquals(CircuitState.HalfOpen.code, 2L)
  }
}
