package kui.topic.domain

import java.time.Instant

/** The current instant, as a port.
  *
  * `scrapedAt` is domain data: it is on the screen, it decides whether a snapshot is rendered as stale, and
  * it appears in the capability report. A use case that reached for `Instant.now()` would be a use case whose
  * output cannot be asserted, so every suite about staleness would become a suite about how long the test
  * machine took — the definition of a flaky test.
  *
  * One method, deliberately. A port with a `sleep` on it would be a scheduler, and scheduling is the effect
  * runtime's job, not the domain's.
  */
trait ClockPort[F[_]] {
  def now: F[Instant]
}
