package kui.config.store

import java.time.Instant

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import cats.effect.{Async, Clock, Ref}
import cats.syntax.all.*

/** How the tail follower retries when it loses the store cluster.
  *
  * One place, so a change is one change, and shaped like every other backoff in KUI (ADR-037) rather than
  * being a second policy with its own opinions.
  */
final case class StoreRetryPolicy(initialDelay: FiniteDuration, maxDelay: FiniteDuration, jitter: Double) {

  /** The delay before attempt `n`, counting from zero.
    *
    * Exponential up to `maxDelay`, then flat, with jitter so that a fleet of KUI replicas that all lost the
    * same broker do not reconnect in lockstep and knock it over again as it comes back.
    */
  def delay(attempt: Int, random: Double): FiniteDuration = {
    val exponential = initialDelay * math.pow(2.0, attempt.toDouble.min(16.0))
    val capped = if exponential > maxDelay then maxDelay else exponential
    val factor = 1.0 + jitter * (random * 2.0 - 1.0)
    val millis = (capped.toMillis * factor).toLong
    FiniteDuration(math.max(1L, millis), java.util.concurrent.TimeUnit.MILLISECONDS)
  }
}

object StoreRetryPolicy {

  /** One second, doubling to thirty, ±20%. It never gives up: an operator restarting a broker for twenty
    * minutes must not have to restart KUI as well.
    */
  val Default: StoreRetryPolicy = StoreRetryPolicy(1.second, 30.seconds, 0.2)
}

/** Owns the store's health value and its transitions.
  *
  * Separate from `StoreState` because the two change for different reasons and are read by different callers:
  * state is what the log said, health is how well this process is keeping up with it.
  */
final class StoreHealthRef[F[_]: Async] private (ref: Ref[F, StoreHealth]) {

  def get: F[StoreHealth] = ref.get

  /** Called after a batch of records applies. Clears a `Degraded` and moves `since` forward. */
  def markHealthy(lastAppliedOffset: Long): F[Unit] =
    now.flatMap(at =>
      ref.update {
        // Already healthy: `since` stays put, so it answers "how long has this been fine" rather than
        // "when did the last record arrive".
        case StoreHealth.Healthy(_, since, unreadable) =>
          StoreHealth.Healthy(lastAppliedOffset, since, unreadable)
        case other => StoreHealth.Healthy(lastAppliedOffset, at, other.unreadableKeys)
      }
    )

  /** Called when the follower's stream fails.
    *
    * Idempotent in the way that matters: a second failure while already degraded keeps the original `since`,
    * so "degraded for twenty minutes" stays true instead of being reset to zero by every retry. A `since`
    * that every attempt resets is a `since` that can never trigger an alert.
    */
  def markDegraded(reason: String): F[Unit] =
    now.flatMap(at =>
      ref.update {
        case StoreHealth.Degraded(existing, since, offset, unreadable) =>
          StoreHealth.Degraded(existing, since, offset, unreadable)
        case StoreHealth.Healthy(offset, _, unreadable) =>
          StoreHealth.Degraded(reason, at, offset, unreadable)
        case readOnly @ StoreHealth.ReadOnly(_, _) => readOnly
      }
    )

  /** One record that could not be read. Explicitly *not* a degraded store: KUI is keeping up with the log
    * perfectly, and one entry of it is unusable. The key is named so an operator can go and look at it.
    */
  def markUnreadable(keys: List[StoreKey]): F[Unit] =
    ref.update {
      case StoreHealth.Healthy(offset, since, _) => StoreHealth.Healthy(offset, since, keys)
      case StoreHealth.Degraded(reason, since, offset, _) => StoreHealth.Degraded(reason, since, offset, keys)
      case StoreHealth.ReadOnly(reason, _) => StoreHealth.ReadOnly(reason, keys)
    }

  private def now: F[Instant] = Clock[F].realTime.map(since => Instant.ofEpochMilli(since.toMillis))
}

object StoreHealthRef {

  def of[F[_]: Async](initial: StoreHealth): F[StoreHealthRef[F]] =
    Ref.of[F, StoreHealth](initial).map(new StoreHealthRef(_))

  /** Turns a consumer or producer failure into a short, stable classification.
    *
    * It reaches a user through a capability banner, so it must be short, stable and free of hosts, ports and
    * credentials — a Kafka client's exception message is none of those things. The class name is not used
    * either: `org.apache.kafka.common.errors.SaslAuthenticationException` is not an explanation.
    */
  def classify(error: Throwable): String =
    error match {
      case _: org.apache.kafka.common.errors.SaslAuthenticationException => "authentication failed"
      case _: org.apache.kafka.common.errors.SslAuthenticationException => "authentication failed"
      case _: org.apache.kafka.common.errors.TopicAuthorizationException => "not authorized"
      case _: org.apache.kafka.common.errors.GroupAuthorizationException => "not authorized"
      case _: org.apache.kafka.common.errors.UnknownTopicOrPartitionException => "topic deleted"
      case _: org.apache.kafka.common.errors.TimeoutException => "connection timed out"
      case _: org.apache.kafka.common.errors.DisconnectException => "connection refused"
      case _: java.net.ConnectException => "connection refused"
      case _ => "unknown"
    }
}
