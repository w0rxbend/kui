package kui.topic.domain

import java.time.Instant

import cats.effect.{IO, Ref}

import kui.testkit.KuiIOSuite

/** The fixed clock every later suite about staleness is built on.
  *
  * It is a suite of its own because a broken test double is worse than no test double: a `now` that quietly
  * advanced would make `scrapedAtComesFromTheClockPort` pass for the wrong reason.
  */
final class ClockPortSuite extends KuiIOSuite {

  import ClockPortSuite.*

  test("aFixedClockDoesNotMove") {
    val at = Instant.parse("2026-09-04T10:00:00Z")

    fixed(at).now.flatMap(first => fixed(at).now.map(second => assertEquals(first, second)))
  }

  test("aSteppableClockMovesOnlyWhenItIsTold") {
    for {
      clock <- steppable(Instant.parse("2026-09-04T10:00:00Z"))
      before <- clock.now
      _ <- clock.advanceBy(java.time.Duration.ofSeconds(30))
      after <- clock.now
    } yield assertEquals(after, before.plusSeconds(30))
  }
}

object ClockPortSuite {

  /** A clock stopped at one instant. */
  def fixed(at: Instant): ClockPort[IO] = new ClockPort[IO] {
    def now: IO[Instant] = IO.pure(at)
  }

  trait SteppableClock extends ClockPort[IO] {
    def advanceBy(amount: java.time.Duration): IO[Unit]
  }

  /** A clock that moves only when a test moves it, so that "the snapshot is thirty seconds old" is a fact the
    * suite states rather than a delay it waits for.
    */
  def steppable(from: Instant): IO[SteppableClock] =
    Ref.of[IO, Instant](from).map { state =>
      new SteppableClock {
        def now: IO[Instant] = state.get
        def advanceBy(amount: java.time.Duration): IO[Unit] = state.update(_.plus(amount))
      }
    }
}
