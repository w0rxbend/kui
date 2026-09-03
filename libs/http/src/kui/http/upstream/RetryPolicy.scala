package kui.http.upstream

import scala.concurrent.duration.FiniteDuration

import sttp.model.Method

/** When a failed call may be tried again, and how long to wait first.
  *
  * Retrying is not free and it is not always safe. Two rules keep it from making things worse:
  *
  *   - **only for calls that can be repeated safely.** A `GET` that failed may or may not have reached the
  *     upstream; repeating it changes nothing either way. A `POST` that failed may have created something,
  *     and repeating it may create a second one. HTTP calls these idempotent methods, and only those are
  *     retried unless the caller explicitly names a status that is safe to repeat (Kafka Connect's 409
  *     "rebalance in progress" is the one KUI knows about, and it arrives in M7).
  *   - **with full jitter.** When an upstream comes back after an outage, every client that was waiting
  *     retries at once and knocks it over again. Randomising each wait over the whole interval — rather than
  *     adding a small wobble to a fixed backoff — spreads the returning load out instead of synchronising it.
  */
object RetryPolicy {

  /** The methods that may be repeated without changing what the upstream did. */
  val IdempotentMethods: Set[Method] = Set(Method.GET, Method.HEAD, Method.OPTIONS)

  def isIdempotent(method: Method): Boolean = IdempotentMethods.contains(method)

  /** Whether this outcome, for this method, is worth another attempt.
    *
    * A connection-level failure (`Left`) is retried for an idempotent method: nothing was necessarily done,
    * and the next attempt may reach a different, healthy instance. A *response* is retried only when the
    * caller named that status as retryable — a 500 that arrived is a decision the upstream made, and
    * repeating the call usually just repeats the decision.
    */
  def shouldRetry(
      method: Method,
      outcome: Either[Throwable, Int],
      retryableStatuses: Set[Int]
  ): Boolean =
    outcome match {
      case Left(_) => isIdempotent(method)
      case Right(status) => retryableStatuses.contains(status) && isIdempotent(method)
    }

  /** Full jitter: a wait chosen uniformly from `[0, base * 2^attempt]`.
    *
    * `attempt` counts from 0 for the wait before the first retry. The interval is capped so that a long-lived
    * client with a high retry count cannot end up waiting for minutes.
    */
  def backoff(attempt: Int, base: FiniteDuration, random: Double = math.random()): FiniteDuration = {
    val exponent = math.min(math.max(attempt, 0), MaxDoublings)
    val ceilingNanos = math.min(
      (base.toNanos.toDouble * math.pow(2.0, exponent.toDouble)).toLong,
      MaxBackoff.toNanos
    )
    val fraction = math.max(0.0, math.min(1.0, random))

    FiniteDuration((ceilingNanos.toDouble * fraction).toLong, java.util.concurrent.TimeUnit.NANOSECONDS)
  }

  /** After this many doublings the interval stops growing. Eight doublings of a 100 ms base is already 25
    * seconds, which is longer than any call KUI makes is allowed to take.
    */
  private val MaxDoublings: Int = 8

  private val MaxBackoff: FiniteDuration =
    FiniteDuration(30, java.util.concurrent.TimeUnit.SECONDS)
}
