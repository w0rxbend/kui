package kui.message.application.purge

import java.time.Instant

import cats.effect.IO

import kui.kernel.{ClusterId, Offset, PartitionId, Secret, TopicName}
import kui.message.domain.PlannedPurge

/** The token that makes "delete exactly what I was shown" enforceable rather than aspirational.
  *
  * Every case here is a way an operator could end up deleting something they never agreed to lose, and each
  * one is refused.
  */
final class PurgeTokenSuite extends munit.CatsEffectSuite {

  private val cluster = ClusterId.unsafe("prod")
  private val topic = TopicName.unsafe("orders.v1")
  private val at = Instant.parse("2026-09-04T09:00:00Z")
  private val later = at.plusSeconds(60)

  private val tokens = PurgeToken.make[IO](Secret("a key long enough for HMAC-SHA256".getBytes("UTF-8")))
  private val other = PurgeToken.make[IO](Secret("a different key, equally long here".getBytes("UTF-8")))

  private val planned: List[PlannedPurge] = List(
    PlannedPurge(PartitionId.unsafe(0), Offset.unsafe(900L), Offset.unsafe(1000L)),
    PlannedPurge(PartitionId.unsafe(1), Offset.unsafe(0L), Offset.unsafe(5L))
  )

  private def mint: IO[String] = tokens.mint(cluster, topic, planned, at.plusSeconds(300))

  test("aTokenCarriesBackTheExactOffsetsItWasMintedWith") {
    // Both watermarks, not just the delete-before offset: after the purge the log's start *is* its end, so
    // the number of records destroyed cannot be read off the cluster afterwards at all.
    for {
      token <- mint
      verified <- tokens.verify(cluster, topic, token, later)
    } yield assertEquals(verified, Right(planned))
  }

  test("aTamperedTokenIsRefused") {
    for {
      token <- mint
      verified <- tokens.verify(cluster, topic, s"${token}x", later)
    } yield assert(verified.isLeft, verified.toString)
  }

  test("aTokenSignedWithAnotherKeyIsRefused") {
    // What stops a token minted by one deployment being spent against another.
    for {
      token <- other.mint(cluster, topic, planned, at.plusSeconds(300))
      verified <- tokens.verify(cluster, topic, token, later)
    } yield assert(verified.isLeft, verified.toString)
  }

  test("aTokenForAnotherTopicOrClusterIsRefused") {
    // The failure this prevents is concrete: the same topic name on staging and on production, two tabs
    // open, and a plan read on one confirmed against the other.
    for {
      token <- mint
      elsewhere <- tokens.verify(cluster, TopicName.unsafe("payments.v1"), token, later)
      otherCluster <- tokens.verify(ClusterId.unsafe("staging"), topic, token, later)
    } yield {
      assert(elsewhere.isLeft, elsewhere.toString)
      assert(otherCluster.isLeft, otherCluster.toString)
    }
  }

  test("anExpiredTokenIsRefused") {
    // Five minutes (ADR-045). Past that the cluster has probably moved and the numbers on the operator's
    // screen no longer describe it.
    for {
      token <- mint
      verified <- tokens.verify(cluster, topic, token, at.plusSeconds(301))
    } yield assert(verified.isLeft, verified.toString)
  }

  test("aTokenIsAcceptedRightUpToItsExpiryAndNotAfter") {
    for {
      token <- mint
      onTheDot <- tokens.verify(cluster, topic, token, at.plusSeconds(300))
      justAfter <- tokens.verify(cluster, topic, token, at.plusSeconds(300).plusMillis(1))
    } yield {
      assert(onTheDot.isRight, onTheDot.toString)
      assert(justAfter.isLeft, justAfter.toString)
    }
  }

  test("somethingThatIsNotATokenAtAllIsRefusedRatherThanCrashing") {
    List("", ".", "not-base64.not-base64", "a.b.c").traverseCase { raw =>
      tokens.verify(cluster, topic, raw, later).map(verified => assert(verified.isLeft, raw))
    }
  }

  extension [A](values: List[A])
    /** Runs a check over every value, sequentially. Written out rather than pulled from cats' syntax so this
      * suite depends on nothing but the effect it is already using.
      */
    private def traverseCase(check: A => IO[Unit]): IO[Unit] =
      values.foldLeft(IO.unit)((acc, value) => acc.flatMap(_ => check(value)))
}
