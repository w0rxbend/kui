package kui.testkit.fakes

import java.time.Instant

import scala.concurrent.duration.FiniteDuration

import cats.effect.kernel.{Clock, Ref, Sync}
import cats.syntax.all.*

/** A clock a test controls.
  *
  * Anything that decides "is this cache entry stale", "has this token expired" or "how long did that take"
  * reads a clock, and a test of that decision must not read the real one: a suite that waits for time to pass
  * is slow, and a suite that hopes time has not passed is flaky. This clock only moves when a test moves it.
  */
final class FakeClock[F[_]: Sync] private (state: Ref[F, Instant]) {

  /** The `Clock` to hand to the code under test. */
  val clock: Clock[F] = new Clock[F] {
    def applicative: cats.Applicative[F] = summon[Sync[F]]

    def monotonic: F[FiniteDuration] = state.get.map(now => FiniteDuration(now.toEpochMilli, "ms"))

    def realTime: F[FiniteDuration] = monotonic
  }

  def now: F[Instant] = state.get

  def set(at: Instant): F[Unit] = state.set(at)

  /** Moves time forward. Negative durations are allowed on purpose: a service whose clock jumps backwards,
    * because an operator corrected it, is a case worth testing.
    */
  def advance(by: FiniteDuration): F[Unit] = state.update(_.plusMillis(by.toMillis))
}

object FakeClock {

  /** The instant every KUI suite starts from unless it says otherwise. A fixed, readable value rather than
    * "now", so that a failure message is the same on every machine and every day.
    */
  val Epoch: Instant = Instant.parse("2026-09-03T10:00:00Z")

  def apply[F[_]: Sync](from: Instant = Epoch): F[FakeClock[F]] =
    Ref.of[F, Instant](from).map(new FakeClock(_))
}
