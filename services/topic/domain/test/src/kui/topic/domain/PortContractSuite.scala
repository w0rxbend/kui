package kui.topic.domain

import cats.effect.IO

import kui.kernel.{ClusterId, TopicName}
import kui.testkit.KuiIOSuite

/** The behaviours **every** `TopicAdmin` implementation must have, written once, with the port.
  *
  * It is written here, in the module that declares the port, and not after the first implementation, because
  * a contract written after the second implementation is a description of the first one. Two things run it:
  * the fake the application layer's suites are built on, and the live adapter in
  * `services/topic/infrastructure` against a real broker in a container. That is what makes it a contract
  * rather than a description — a fake that drifts from the adapter fails here, in the module both of them
  * answer to, instead of quietly making every use-case test agree with a bug.
  *
  * A subclass supplies the implementation and says what its cluster contains. The optional hooks return
  * `None` for a fixture that cannot produce that state — a stub with no notion of authorization, say — and
  * the corresponding case is skipped rather than passing vacuously.
  */
abstract class PortContractSuite extends KuiIOSuite {

  /** The implementation under test, and a cluster it knows about. */
  def admin: IO[TopicAdmin[IO]]

  def knownCluster: ClusterId

  /** A topic the fixture's cluster definitely has. */
  def knownTopic: TopicName

  /** A cluster id nothing is configured for. */
  def unknownCluster: ClusterId = ClusterId.unsafe("no-such-cluster-anywhere")

  /** A topic name the fixture's cluster definitely does not have. */
  def unknownTopic: TopicName = TopicName.unsafe("no-such-topic-anywhere")

  /** A topic that exists and whose configuration this implementation may not read. `None` when the fixture
    * has no way to express that, in which case the case is skipped.
    */
  def topicWithoutConfigPermission: Option[TopicName] = None

  /** A topic with at least one leaderless partition. `None` when the fixture cannot produce one. */
  def leaderlessTopic: Option[TopicName] = None

  /** A cluster whose scrape cannot read every topic. `None` when the fixture cannot produce one. */
  def clusterWithAnIncompleteScrape: Option[ClusterId] = None

  test("scrapeOfAnUnknownClusterIsClusterNotFound") {
    admin.flatMap(_.scrape(unknownCluster)).map { result =>
      assertEquals(result, Left(TopicError.ClusterNotFound(unknownCluster)))
    }
  }

  test("detailOfAnUnknownClusterIsClusterNotFound") {
    admin.flatMap(_.detail(unknownCluster, knownTopic)).map { result =>
      assertEquals(result, Left(TopicError.ClusterNotFound(unknownCluster)))
    }
  }

  test("detailOfAnUnknownTopicIsNotFound") {
    admin.flatMap(_.detail(knownCluster, unknownTopic)).map { result =>
      assertEquals(result, Left(TopicError.NotFound(unknownTopic)))
    }
  }

  test("aScrapeListsInternalTopicsRatherThanFilteringThem") {
    // The internal filter is a display rule with its own definition in the application layer (DEVPLAN §10
    // D3). A port that applied it would leave `showInternal=true` with nothing to show, and the failure
    // would look like an empty toggle rather than like a port doing someone else's job.
    admin.flatMap(_.scrape(knownCluster)).map {
      case Left(error) => fail(s"the seeded cluster should scrape: ${error.message}")
      case Right(result) => assert(result.topics.exists(_.name == knownTopic))
    }
  }

  test("scrapeReportsIncompleteRatherThanFailing") {
    clusterWithAnIncompleteScrape match {
      case None => assume(false, "this fixture cannot produce a partly readable cluster")
      case Some(cluster) =>
        admin.flatMap(_.scrape(cluster)).map {
          case Left(error) =>
            fail(s"a partly readable cluster must still produce a list, not an error: ${error.message}")
          case Right(result) =>
            assert(result.incomplete.nonEmpty, "the topics that could not be read must be named")
            assert(result.incomplete.values.forall(_.trim.nonEmpty), "every reason is display text")
        }
    }
  }

  test("configOfATopicWithoutPermissionIsNotPermittedNotForbidden") {
    topicWithoutConfigPermission match {
      case None => assume(false, "this fixture has no unreadable-configuration topic")
      case Some(topic) =>
        admin.flatMap(_.config(knownCluster, topic)).map {
          case Left(error) =>
            fail(s"an unreadable configuration must not fail the whole topic page: ${error.message}")
          case Right(view) =>
            assert(!view.isPermitted, "the tab must be able to say why it is empty")
            assertEquals(view.entries, Nil)
        }
    }
  }

  test("aLeaderlessPartitionYieldsNoTopicMessageCount") {
    leaderlessTopic match {
      case None => assume(false, "this fixture has no leaderless partition")
      case Some(topic) =>
        // Asserted at the port as well as in the domain, deliberately: two assertions at two levels is how a
        // rule survives a refactor of either one.
        admin.flatMap(_.detail(knownCluster, topic)).map {
          case Left(error) => fail(s"a topic with an offline partition is still a topic: ${error.message}")
          case Right(detail) =>
            assert(detail.partitions.exists(_.isLeaderless))
            assertEquals(detail.summary.messageCount, None)
            assert(detail.summary.offlinePartitions > 0, "the screen must be able to explain the missing count")
        }
    }
  }

  test("everyFailurePathIsAValueAndNotARaisedException") {
    // The one assertion that keeps an adapter's `TimeoutException` from reaching a use case and making every
    // signature in the application layer a lie.
    admin
      .flatMap { port =>
        port.scrape(unknownCluster).attempt.flatMap { scrape =>
          port.detail(unknownCluster, unknownTopic).attempt.flatMap { detail =>
            port.config(unknownCluster, unknownTopic).attempt.map(config => List(scrape, detail, config))
          }
        }
      }
      .map(_.foreach(result => assert(result.isRight, s"the port raised instead of returning a Left: $result")))
  }
}
