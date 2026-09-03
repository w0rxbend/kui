package kui.cluster.domain

import java.time.Instant

/** Reading the current time, as a port the domain owns.
  *
  * The domain needs to know *what* the time is and must not know *how* it is obtained: `Instant.now()` called
  * inside a value object would make every rule that depends on time untestable, because a suite cannot ask
  * the system clock to be a particular Tuesday. The application layer receives an implementation — the real
  * clock in production, `kui.testkit.fakes.FakeClock` in a suite — and the domain stays a set of rules that
  * can be reasoned about on paper.
  *
  * `F[_]` has no bound at all here (ADR-002: the weakest bound a port can carry). Reading a clock is an
  * effect, but it is not an effect that needs `Sync` or `Async` to *describe*; only the adapter that
  * implements it does.
  */
trait ClockPort[F[_]] {

  /** The current instant, as the service sees it. */
  def now: F[Instant]
}
