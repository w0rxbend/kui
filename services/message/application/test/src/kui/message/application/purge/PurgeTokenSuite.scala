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

  test("theSamePlanInAnyOrderMintsTheSameTokenByteForByte") {
    /*
     * Ungated until now: removing `.sortBy(_.partition.value)` from `PurgeToken.render` left
     * `./mill services.message.__.test` at 1442/1442 green, because every case in this file passes
     * `planned`, which is already in partition order, and nothing compared two mintings of one plan.
     *
     * The canonical rendering is what makes the token a statement about a *plan* rather than about the
     * order a list happened to arrive in. `KafkaRecordDeleter.watermarks` builds its partitions from a
     * `describeTopics` answer and `PurgePlan.of` sorts them, but the token is minted from whatever list it
     * is handed; an unsorted rendering means two replicas that resolved the same partitions in different
     * orders mint two different tokens for one plan, and a plan read on one cannot be confirmed against
     * the other.
     */
    for {
      ordered <- tokens.mint(cluster, topic, planned, at.plusSeconds(300))
      reversed <- tokens.mint(cluster, topic, planned.reverse, at.plusSeconds(300))
      verified <- tokens.verify(cluster, topic, reversed, later)
    } yield {
      assertEquals(
        reversed,
        ordered,
        clue = "one plan minted two tokens: the rendering is not canonical"
      )
      // And the offsets come back in the canonical order too, so the receipt reads the same either way.
      assertEquals(verified, Right(planned))
    }
  }

  test("somethingThatIsNotATokenAtAllIsRefusedRatherThanCrashing") {
    // A codec that parses before it verifies is one an attacker can drive with a payload they never had to
    // sign; a codec that throws on rubbish is one a stray request turns into a 500.
    val rubbish = List("", ".", "not-base64.not-base64", "a.b.c")

    rubbish
      .foldLeft(IO.unit)((checked, raw) =>
        checked.flatMap(_ =>
          tokens.verify(cluster, topic, raw, later).map(verified => assert(verified.isLeft, raw))
        )
      )
  }
}
