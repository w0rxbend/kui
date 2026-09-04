package kui.cluster.client

import scala.concurrent.duration.{DurationInt, DurationLong, FiniteDuration}

/** How a consuming service is told to talk to the cluster service.
  *
  * Every default is a number with an argument attached rather than a round figure, because these are the
  * knobs an operator reaches for at three in the morning and the reason each one is what it is has to be
  * readable from here.
  *
  * @param pollInterval
  *   how often the cluster list is re-read even when the change stream is healthy. Sixty seconds is ADR-036's
  *   own figure. It is a *fallback*, not the primary mechanism: with the stream up, a change arrives in
  *   milliseconds, and this is what bounds the damage when the stream has silently died — a socket that a
  *   middlebox dropped without telling either end looks exactly like a quiet cluster
  * @param requestTimeout
  *   how long one profile fetch may take. Five seconds, because the far side answers from memory and a longer
  *   bound would only make a wedged connection take longer to notice
  * @param reconnectBackoff
  *   the first delay after the stream drops
  * @param maxReconnectBackoff
  *   the cap. Thirty seconds, so a cluster service that is down for an hour is retried 120 times rather than
  *   twice — a client that has backed off to ten minutes takes ten minutes to notice a recovery that happened
  *   immediately after its last attempt
  */
final case class ClusterProfilesConfig(
    pollInterval: FiniteDuration = 60.seconds,
    requestTimeout: FiniteDuration = 5.seconds,
    reconnectBackoff: FiniteDuration = 1.second,
    maxReconnectBackoff: FiniteDuration = 30.seconds
) {

  /** The delay before attempt number `attempt`, counting from one: exponential, capped, and deterministic.
    *
    * Jitter is applied by the caller, which holds the source of randomness. Keeping the curve pure is what
    * lets `reconnectBackoffIsExponentialAndCapped` assert it as a table rather than as a range.
    */
  def backoffFor(attempt: Int): FiniteDuration =
    if attempt <= 1 then reconnectBackoff
    else {
      // Doubled in milliseconds and clamped before it becomes a `Duration`. Doubling a `FiniteDuration`
      // directly works in nanoseconds and overflows after about 63 steps; an overflowed duration is
      // negative, and a negative sleep is no sleep at all, so a backoff would silently become a hot loop
      // against a service that is already struggling.
      val doublings = math.min(attempt - 1, 32)
      val scaled = reconnectBackoff.toMillis * (1L << doublings)
      math.min(maxReconnectBackoff.toMillis, scaled).millis
    }
}

object ClusterProfilesConfig {
  val default: ClusterProfilesConfig = ClusterProfilesConfig()

  given CanEqual[ClusterProfilesConfig, ClusterProfilesConfig] = CanEqual.derived
}
