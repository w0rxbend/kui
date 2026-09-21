package kui.cluster.infrastructure

import scala.jdk.CollectionConverters.*

import cats.effect.IO
import org.apache.kafka.clients.admin.{
  Admin,
  KuiClusterAdminResults,
  ListTopicsOptions,
  TopicDescription,
  TopicListing
}
import org.apache.kafka.common.errors.TopicAuthorizationException
import org.apache.kafka.common.{KafkaFuture, Node, TopicCollection, TopicPartitionInfo, Uuid}

import kui.kernel.{BrokerId, TopicName}
import kui.testkit.KuiIOSuite
import kui.testkit.fakes.FakeStructuredLogger

/** `KafkaPartitionSweeper.sweep` itself — the listing, the chunking, and the refusal.
  *
  * `KafkaPartitionSweeperSuite` beside this one covers the pure conversions, which is where a wrong reading
  * of Kafka's vocabulary shows. This one covers the part that has no pure function in it: what the sweep does
  * when the cluster answers the listing and then refuses one of the describes. That is the case the whole
  * refuse-rather-than-partially-sum design exists for, it is the one a live broker cannot be made to produce
  * on demand, and until now nothing entered it — the three testcontainer suites construct a sweeper only to
  * satisfy `ClusterAdminAdapter.create` and never call `sweepPartitions`.
  *
  * The assertions are made through `TopicSweep.complete`, not through `unreadable`, because `complete` is the
  * only route the service has to the census and therefore the seam where the rule is actually applied.
  */
final class KafkaPartitionSweepSuite extends KuiIOSuite {

  private val connection = TestProfiles.profile().connection

  private def node(id: Int): Node = new Node(id, s"broker-$id.example", 9092)

  private def description(name: String, partitions: Int): TopicDescription =
    new TopicDescription(
      name,
      false,
      (0 until partitions).toList
        .map(index =>
          new TopicPartitionInfo(index, node(1), List(node(1), node(2)).asJava, List(node(1), node(2)).asJava)
        )
        .asJava
    )

  /** A cluster that lists `names` and answers each describe with whatever `describe` says.
    *
    * `describe` returning `Left` is Kafka's per-topic failure: `describeTopics` hands back one future per
    * name and only that one fails, which is the shape `KafkaPartitionSweeper.describeChunk` isolates.
    */
  private def cluster(
      names: List[String],
      describe: String => Either[Throwable, TopicDescription]
  ): Admin =
    StubAdmin {
      case ("listTopics", _) =>
        KuiClusterAdminResults.listTopics(
          names.map(name => name -> new TopicListing(name, Uuid.randomUuid(), false)).toMap.asJava
        )
      case ("describeTopics", (asked: TopicCollection.TopicNameCollection) :: _) =>
        KuiClusterAdminResults.describeTopics(
          asked.topicNames.asScala.toList
            .map(name =>
              name -> (describe(name) match {
                case Left(refusal) => KuiClusterAdminResults.failed[TopicDescription](refusal)
                case Right(found) => KafkaFuture.completedFuture(found)
              })
            )
            .toMap
            .asJava
        )
    }

  private def sweeperOver(admin: Admin): IO[(KafkaPartitionSweeper[IO], RecordingAdminPool)] =
    for {
      pool <- RecordingAdminPool(Some(admin))
      logger <- FakeStructuredLogger[IO]
    } yield (new KafkaPartitionSweeper[IO](pool, logger), pool)

  test("theListingAsksForInternalTopics, because an under-replicated __consumer_offsets is an outage") {
    // `__consumer_offsets` holds fifty partitions on most clusters, and a cluster-wide count that quietly
    // excluded it would disagree with the broker's own `UnderReplicatedPartitions` gauge by exactly the
    // partitions an operator most needs to see. The adapter's own comment says so; nothing checked it —
    // `listInternal(true)` becomes `false` and `./mill libs.__.test + services.*` stays at 2633/2633,
    // because every fixture's listing is built by the fixture rather than filtered by Kafka.
    // The stub is called from inside a `delay`, so the flag is captured in a plain atomic rather than in a
    // `Ref`: there is no effect to sequence at the point Kafka's own API hands the options over.
    val asked = new java.util.concurrent.atomic.AtomicReference[Option[Boolean]](None)

    val admin = StubAdmin {
      case ("listTopics", (options: ListTopicsOptions) :: _) =>
        asked.set(Some(options.shouldListInternal))
        KuiClusterAdminResults.listTopics(
          Map("orders" -> new TopicListing("orders", Uuid.randomUuid(), false)).asJava
        )
      case ("describeTopics", (collection: TopicCollection.TopicNameCollection) :: _) =>
        KuiClusterAdminResults.describeTopics(
          collection.topicNames.asScala.toList
            .map(name => name -> KafkaFuture.completedFuture(description(name, 1)))
            .toMap
            .asJava
        )
    }

    for {
      (sweeper, _) <- sweeperOver(admin)
      swept <- sweeper.sweep(connection)
    } yield {
      assertEquals(asked.get, Some(true))
      assertEquals(swept.map(_.topics), Right(1))
    }
  }

  test("aSweepThatDescribedEveryTopicFillsTheCensus") {
    val admin = cluster(List("orders", "payments"), name => Right(description(name, 3)))

    for {
      (sweeper, _) <- sweeperOver(admin)
      swept <- sweeper.sweep(connection)
    } yield {
      val census = swept.toOption.flatMap(_.complete)

      assertEquals(swept.map(_.topics), Right(2))
      assertEquals(census.map(_.partitions), Some(6))
      assertEquals(census.map(_.online), Some(6))
      assertEquals(census.map(_.hosted(BrokerId.unsafe(2))), Some(6))
      assertEquals(census.map(_.led(BrokerId.unsafe(1))), Some(6))
    }
  }

  test("oneTopicTheClusterWouldNotDescribeWithholdsAllFiveFiguresTogether") {
    // The line under test is `unreadable = batch.skipped.keySet`, and this is the only case that reaches it.
    // A cluster where KUI lacks DESCRIBE on one namespace still answers `listTopics` for it, so the sweep
    // sees the topic, cannot count it, and must publish no partition figure at all — not the sum over the
    // topics that did answer, which is a number that looks measured and reassures in the wrong direction.
    val admin = cluster(
      List("orders", "audit.internal"),
      {
        case "audit.internal" => Left(new TopicAuthorizationException("audit.internal"))
        case name => Right(description(name, 4))
      }
    )

    for {
      (sweeper, _) <- sweeperOver(admin)
      swept <- sweeper.sweep(connection)
    } yield {
      val sweep = swept.toOption.getOrElse(fail(s"the sweep itself failed: $swept"))

      assertEquals(sweep.unreadable, Set(TopicName.unsafe("audit.internal")))
      assertEquals(sweep.topics, 2)
      assertEquals(sweep.described, 1)
      // All five at once: the cluster-wide totals, and both per-broker maps the broker rows read.
      assertEquals(sweep.complete, None)
      assertEquals(sweep.complete.map(_.partitions), None)
      assertEquals(sweep.complete.map(_.online), None)
      assertEquals(sweep.complete.map(_.underReplicated), None)
      assertEquals(sweep.complete.map(_.hosted(BrokerId.unsafe(1))), None)
      assertEquals(sweep.complete.map(_.led(BrokerId.unsafe(1))), None)
    }
  }

  test("theCallCountIsBoundedByTheChunkSizeAndNotByTheTopicCount") {
    // `AdminTuning.default.topicChunkSize` is 200, so five hundred topics are three describes and one
    // listing. A sweep that asked per topic would be five hundred round trips a minute, for ever, which is
    // the cost that keeps cluster-wide partition figures off products that do it that way.
    val names = (1 to 500).toList.map(index => f"topic-$index%03d")
    val admin = cluster(names, name => Right(description(name, 1)))

    for {
      (sweeper, pool) <- sweeperOver(admin)
      swept <- sweeper.sweep(connection)
      describes <- pool.runsOf(KafkaPartitionSweeper.DescribeTopics)
      listings <- pool.runsOf(KafkaPartitionSweeper.ListTopics)
    } yield {
      assertEquals(swept.toOption.flatMap(_.complete).map(_.partitions), Some(500))
      assertEquals(listings, 1)
      assertEquals(describes, 3)
    }
  }

  test("aClusterThatRefusedTheListingIsNoSweepAtAllRatherThanAnEmptyOne") {
    // The one failure that is not per topic. It has to stay a `Left`: an empty `TopicSweep` would be
    // complete and would report a cluster with topics as having none.
    val admin = StubAdmin { case ("listTopics", _) =>
      KuiClusterAdminResults.listTopicsFailure(new TopicAuthorizationException("no describe on this cluster"))
    }

    for {
      (sweeper, _) <- sweeperOver(admin)
      swept <- sweeper.sweep(connection)
    } yield assert(swept.isLeft, s"a refused listing must not become a sweep, got $swept")
  }

  test("aClusterWithNoTopicsIsCountedRatherThanRefused") {
    val admin = cluster(Nil, name => Right(description(name, 1)))

    for {
      (sweeper, pool) <- sweeperOver(admin)
      swept <- sweeper.sweep(connection)
      describes <- pool.runsOf(KafkaPartitionSweeper.DescribeTopics)
    } yield {
      assertEquals(swept.toOption.flatMap(_.complete).map(_.partitions), Some(0))
      assertEquals(describes, 0)
    }
  }
}
