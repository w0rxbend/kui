package kui.ui.clusters.brokers

import java.time.Instant

import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.scalajs.dom

import kui.contracts.cluster.{QuorumDto, QuorumMemberDto}
import kui.kernel.BrokerId

/** The metadata quorum panel: the data the cluster service has collected since M1 and never shown.
  *
  * The assertions are about the three judgements this panel makes that a table of numbers cannot make for
  * itself — whether a metadata write can still commit, which members are behind, and when to render nothing
  * at all.
  */
final class QuorumPanelSuite extends FunSuite {

  private val at: Instant = Instant.parse("2026-09-04T12:00:00Z")

  private def member(id: Int, lag: Long, leader: Boolean = false, voter: Boolean = true): QuorumMemberDto =
    QuorumMemberDto(
      replicaId = BrokerId.unsafe(id),
      logEndOffset = 1000L - lag,
      lag = lag,
      isLeader = leader,
      isVoter = voter,
      lastFetch = Option.when(!leader)(at.minusSeconds(2L)),
      lastCaughtUp = Option.when(lag == 0L && !leader)(at.minusSeconds(2L))
    )

  private def quorum(voters: List[QuorumMemberDto], observers: List[QuorumMemberDto] = Nil): QuorumDto =
    QuorumDto(BrokerId.unsafe(1), 7L, 1000L, voters, observers)

  private def mounted[A](element: HtmlElement)(check: dom.Element => A): A = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container): Unit
    val root = render(container, element)
    try check(element.ref)
    finally {
      root.unmount(): Unit
      dom.document.body.removeChild(container): Unit
    }
  }

  private def optionalTestId(root: dom.Element, testId: String): Option[dom.Element] =
    Option(root.querySelector(s"[data-testid='$testId']"))

  private def byTestId(root: dom.Element, testId: String): dom.Element =
    optionalTestId(root, testId)
      .getOrElse(fail(s"no element with data-testid='$testId' in ${root.outerHTML}"))

  private def panel(value: Option[QuorumDto]): HtmlElement =
    QuorumPanel(Val(value), Val("UTC"), () => at)

  test("aClusterWithNoQuorumRendersNothingAtAll") {
    // A ZooKeeper cluster, a KRaft cluster older than 3.3, and one that refused the call all send no
    // quorum. An empty panel headed "Metadata quorum" would read as a quorum with no members, which is a
    // cluster that cannot function -- the opposite of the truth on a healthy ZooKeeper deployment.
    mounted(panel(None)) { root =>
      assertEquals(optionalTestId(root, "quorum-panel"), None)
    }
  }

  test("aHealthyQuorumSaysMetadataChangesCanStillCommit") {
    val healthy = quorum(List(member(1, 0L, leader = true), member(2, 0L), member(3, 12L)))

    mounted(panel(Some(healthy))) { root =>
      val _ = byTestId(root, "quorum-panel")
      assertEquals(byTestId(root, "quorum-leader").textContent, "1")
      assertEquals(byTestId(root, "quorum-high-watermark").textContent, "1000")
      assertEquals(byTestId(root, "quorum-voter-count").textContent, "3")

      val verdict = byTestId(root, "quorum-verdict").textContent
      assert(verdict.contains("2 of 3"), verdict)
      assert(verdict.contains("can still be committed"), verdict)
    }
  }

  test("aQuorumWithoutAMajorityLevelWithTheLeaderIsAnAlarm") {
    // The state whose first symptom on every other screen is a topic create that hangs. Saying it here is
    // the difference between noticing now and debugging it from the other end an hour later.
    val degraded = quorum(List(member(1, 0L, leader = true), member(2, 400L), member(3, 900L)))

    assert(!QuorumPanel.hasMajority(degraded))

    mounted(panel(Some(degraded))) { root =>
      val verdict = byTestId(root, "quorum-verdict").textContent
      assert(verdict.contains("Only 1 of 3"), verdict)
      assert(verdict.contains("may not be able to commit"), verdict)
    }
  }

  test("aMajorityIsStrictlyMoreThanHalf") {
    // Two of four is not a majority. Raft commits on more than half, and a panel that called an even split
    // healthy would be reassuring about the exact state that stops working.
    assert(!QuorumPanel.hasMajority(quorum(List(member(1, 0L), member(2, 0L), member(3, 5L), member(4, 5L)))))
    assert(QuorumPanel.hasMajority(quorum(List(member(1, 0L), member(2, 0L), member(3, 0L), member(4, 5L)))))
    // A single-node quorum is a legitimate development cluster and is healthy when it is level with itself.
    assert(QuorumPanel.hasMajority(quorum(List(member(1, 0L, leader = true)))))
  }

  test("aMemberThatIsBehindIsMarkedAndOneThatIsLevelIsNot") {
    val mixed = quorum(List(member(1, 0L, leader = true), member(2, 42L)))

    mounted(panel(Some(mixed))) { root =>
      val level = byTestId(root, "quorum-member-1-lag")
      val behind = byTestId(root, "quorum-member-2-lag")

      // Zero is spelled out rather than dashed: level with the leader is the healthy case, and an em dash
      // would read as "not known".
      assertEquals(level.textContent, "0")
      assertEquals(behind.textContent, "42")
      assert(behind.getAttribute("class").contains("behind"), behind.outerHTML)
      assert(!level.getAttribute("class").contains("behind"), level.outerHTML)
    }
  }

  test("observersAreShownSeparatelyFromVoters") {
    // They fail differently. A lagging voter is a cluster close to being unable to change anything; a
    // lagging observer is one broker with a stale view. One table sorted by lag would present them alike.
    val withObservers =
      quorum(List(member(1, 0L, leader = true), member(2, 0L)), List(member(9, 30L, voter = false)))

    mounted(panel(Some(withObservers))) { root =>
      val _ = byTestId(root, "quorum-voters")
      val _ = byTestId(root, "quorum-observers")
      assertEquals(optionalTestId(root, "quorum-no-observers"), None)
    }
  }

  test("aCombinedDeploymentSaysItHasNoObserversRatherThanShowingAnEmptyTable") {
    val combined = quorum(List(member(1, 0L, leader = true), member(2, 0L), member(3, 0L)))

    mounted(panel(Some(combined))) { root =>
      assertEquals(optionalTestId(root, "quorum-observers"), None)
      assert(byTestId(root, "quorum-no-observers").textContent.contains("every node"))
    }
  }
}
