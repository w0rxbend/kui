package kui.config.store

import java.time.Instant

import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.testkit.TestControl

import kui.testkit.KuiIOSuite

/** That losing the store cluster degrades KUI rather than breaking it, and that recovering says so.
  *
  * The rule most worth protecting here is that `since` is sticky. A `Degraded` whose timestamp every
  * retry reset would make "this store has been unreachable for twenty minutes" impossible to say — and
  * that sentence is the one an alert is written against.
  */
final class StoreHealthSuite extends KuiIOSuite {

  private val healthy = StoreHealth.Healthy(10L, Instant.EPOCH, Nil)
  private val key = StoreKey(StoreSection.Cluster, "prod-eu")

  private def degradedSince(health: StoreHealth): Instant =
    health match {
      case StoreHealth.Degraded(_, since, _, _) => since
      case other => fail(s"expected a degraded store, got $other")
    }

  test("degradedKeepsItsOriginalSince") {
    val test = for {
      ref <- StoreHealthRef.of[IO](healthy)
      _ <- IO.sleep(1.minute)
      _ <- ref.markDegraded("connection refused")
      first <- ref.get
      _ <- IO.sleep(20.minutes)
      // Every retry that fails calls this again. A `since` that each attempt reset could never trigger
      // an alert, because it would always read as "degraded for a moment".
      _ <- ref.markDegraded("connection refused")
      later <- ref.get
    } yield (degradedSince(first), degradedSince(later))
    TestControl.executeEmbed(test).map((first, later) => assertEquals(first, later))
  }

  test("recoveryResetsSince") {
    val test = for {
      ref <- StoreHealthRef.of[IO](healthy)
      _ <- IO.sleep(1.minute)
      _ <- ref.markDegraded("connection refused")
      _ <- IO.sleep(5.minutes)
      _ <- ref.markHealthy(42L)
      recovered <- ref.get
      _ <- IO.sleep(1.minute)
      // Already healthy: `since` stays put, so it answers "how long has this been fine" rather than
      // "when did the last record arrive".
      _ <- ref.markHealthy(43L)
      later <- ref.get
    } yield (recovered, later)
    TestControl.executeEmbed(test).map { (recovered, later) =>
      recovered match {
        case StoreHealth.Healthy(offset, since, _) =>
          assertEquals(offset, 42L)
          assertEquals(since, Instant.ofEpochMilli(6.minutes.toMillis))
        case other => fail(s"expected a healthy store, got $other")
      }
      assertEquals(later, StoreHealth.Healthy(43L, Instant.ofEpochMilli(6.minutes.toMillis), Nil))
      assert(later.writable)
    }
  }

  test("unreadableKeyDoesNotDegrade") {
    // KUI keeping up with the log perfectly and one entry of that log being unusable are different
    // facts. One bad record must not grey out a feature for everybody.
    val test = for {
      ref <- StoreHealthRef.of[IO](healthy)
      _ <- ref.markUnreadable(List(key))
      health <- ref.get
    } yield health
    TestControl.executeEmbed(test).map { health =>
      assert(health.writable, "one unreadable record must leave the store writable")
      assertEquals(health.unreadableKeys, List(key))
    }
  }

  test("unreadableKeyIsClearedWhenTheKeyIsWrittenSuccessfully") {
    // An operator who fixes a record must not have to restart KUI to clear the warning.
    val test = for {
      ref <- StoreHealthRef.of[IO](healthy)
      _ <- ref.markUnreadable(List(key))
      _ <- ref.markUnreadable(Nil)
      health <- ref.get
    } yield health.unreadableKeys
    TestControl.executeEmbed(test).map(keys => assertEquals(keys, Nil))
  }

  test("reasonIsAClassificationNotAnExceptionMessage") {
    // The reason reaches a user through a capability banner, so it has to be short, stable and free of
    // hosts, ports and credentials — none of which a Kafka client's exception message is.
    val cases = List(
      new org.apache.kafka.common.errors.SaslAuthenticationException("Authentication failed for user kui@broker-1:9093")
        -> "authentication failed",
      new org.apache.kafka.common.errors.TopicAuthorizationException("__kui_config") -> "not authorized",
      new org.apache.kafka.common.errors.UnknownTopicOrPartitionException("__kui_config") -> "topic deleted",
      new org.apache.kafka.common.errors.TimeoutException("Timed out waiting for broker-1:9092") -> "connection timed out",
      new java.net.ConnectException("Connection refused: broker-1/10.0.0.1:9092") -> "connection refused",
      new IllegalStateException("something nobody anticipated") -> "unknown"
    )
    cases.foreach { (error, expected) =>
      val reason = StoreHealthRef.classify(error)
      assertEquals(reason, expected)
      assert(!reason.contains("Exception"), reason)
      assert(!reason.contains("9092") && !reason.contains("9093"), reason)
      assert(!reason.contains("broker-1"), reason)
    }
  }

  test("backoffIsBoundedAndJittered") {
    // Jitter matters: a fleet of KUI replicas that all lost the same broker must not reconnect in
    // lockstep and knock it over again as it comes back.
    val policy = StoreRetryPolicy.Default
    val samples = for {
      attempt <- 0 to 40
      random <- List(0.0, 0.25, 0.5, 0.75, 1.0)
    } yield (attempt, random, policy.delay(attempt, random))

    samples.foreach { (attempt, random, delay) =>
      val exponential = policy.initialDelay * math.pow(2.0, attempt.toDouble.min(16.0))
      val base = if exponential > policy.maxDelay then policy.maxDelay else exponential
      val clue = s"attempt=$attempt random=$random delay=$delay base=$base"
      assert(delay.toMillis >= (base.toMillis * 0.8).toLong - 1L, clue)
      assert(delay.toMillis <= (base.toMillis * 1.2).toLong + 1L, clue)
      assert(delay <= policy.maxDelay * 1.2, clue)
      assert(delay.toMillis >= 1L, clue)
    }
    assert(samples.map(_._3).distinct.size > 1, "the delay must actually vary with the jitter input")
  }

  test("theBackoffGrowsAndThenFlattens") {
    val policy = StoreRetryPolicy(1.second, 30.seconds, 0.0)
    // No jitter, so the shape is visible: 1s, 2s, 4s … capped at 30s, and it never gives up.
    assertEquals(policy.delay(0, 0.5), 1.second)
    assertEquals(policy.delay(1, 0.5), 2.seconds)
    assertEquals(policy.delay(4, 0.5), 16.seconds)
    assertEquals(policy.delay(5, 0.5), 30.seconds)
    assertEquals(policy.delay(40, 0.5), 30.seconds)
  }

  test("aReadOnlyStoreStaysReadOnly") {
    // The file adapter cannot become degraded: it holds no connection, so there is nothing to lose.
    val test = for {
      ref <- StoreHealthRef.of[IO](StoreHealth.ReadOnly("no metadata store is configured", Nil))
      _ <- ref.markDegraded("connection refused")
      health <- ref.get
    } yield health
    TestControl.executeEmbed(test).map {
      case StoreHealth.ReadOnly(_, _) => ()
      case other => fail(s"a read-only store must stay read-only, got $other")
    }
  }
}
