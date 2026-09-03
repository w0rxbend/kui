package kui.gateway.application.capability

import scala.concurrent.duration.FiniteDuration

/** The last `size` readiness-call durations for one service, and the p95 of them.
  *
  * A sliding window rather than a running average, because an average hides exactly the thing this is for. A
  * service that answers in 20 ms ninety-nine times out of a hundred and in 8 seconds on the hundredth has a
  * fine average and a terrible experience, and it is the hundredth call that makes a user think the product
  * is broken. The window is bounded so that a gateway running for a month uses the same memory as one that
  * started a minute ago, and so that "slow" means "slow lately" rather than "was slow once in April".
  *
  * Immutable: `record` returns a new window. The registry holds it in a `Ref`, so there is no mutable state
  * here to reason about and the percentile calculation is a pure function that can be tested against a known
  * distribution.
  */
final class LatencyWindow private (private val samples: Vector[FiniteDuration], val size: Int) {

  def record(sample: FiniteDuration): LatencyWindow =
    new LatencyWindow((samples :+ sample).takeRight(size), size)

  /** `None` until there is at least one sample.
    *
    * The distinction matters: "we have not measured this service" must not fold to "this service is fast", or
    * every service would be reported as healthy for as long as nobody could reach it.
    */
  def p95: Option[FiniteDuration] = percentile(95)

  /** The nearest-rank percentile: the smallest sample at or above which the given share of samples lie.
    *
    * Nearest-rank rather than an interpolating definition because the answer is a duration that was actually
    * observed, which is what an operator reading it expects, and because it needs no special case for a
    * window holding two samples.
    */
  def percentile(rank: Int): Option[FiniteDuration] =
    Option.when(samples.nonEmpty) {
      val ordered = samples.sorted
      val at = math.ceil(rank.toDouble / 100.0 * ordered.size).toInt - 1
      ordered(math.min(math.max(at, 0), ordered.size - 1))
    }

  def count: Int = samples.size

  def values: List[FiniteDuration] = samples.toList
}

object LatencyWindow {

  /** Fifty samples: at one readiness poll every ten seconds that is a bit over eight minutes of history,
    * which is long enough to survive a single slow call and short enough that a service which recovered five
    * minutes ago is no longer reported as slow.
    */
  val DefaultSize: Int = 50

  def empty(size: Int = DefaultSize): LatencyWindow =
    new LatencyWindow(Vector.empty, math.max(1, size))

  given CanEqual[LatencyWindow, LatencyWindow] = CanEqual.derived
}
