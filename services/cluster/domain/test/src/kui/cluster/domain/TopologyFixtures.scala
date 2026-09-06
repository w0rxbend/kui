package kui.cluster.domain

import java.time.Instant

import cats.data.NonEmptyList

import org.scalacheck.{Arbitrary, Gen}

import kui.kernel.{BrokerId, Host, KafkaClusterId, PartitionId, Port, TopicName, TopicPartition}

/** Topology values for this module's suites and for every application suite above it.
  *
  * The builders take the few things a test actually varies and fill the rest with something valid, so that a
  * suite about skew does not have to state a rack, and a suite about racks does not have to state a disk
  * size. Everything they produce satisfies the smart constructors: a fixture that could build an impossible
  * value would let a suite pass on data the product can never see.
  */
object TopologyFixtures {

  val ProbedAt: Instant = Instant.parse("2026-09-04T09:00:00Z")

  def broker(id: Int, rack: Option[String] = None): Broker =
    Broker(
      BrokerId.unsafe(id),
      Host.unsafe(s"broker-$id.example"),
      Port.unsafe(9092),
      rack.map(BrokerRack.unsafe)
    )

  /** A description of `n` brokers, with broker 1 as the controller. */
  def description(
      n: Int,
      controller: Option[Broker] = None,
      mode: ControllerMode = ControllerMode.KRaft,
      kafkaClusterId: Option[String] = Some("kafka-cluster-id")
  ): ClusterDescription = {
    val brokers = NonEmptyList.fromListUnsafe((1 to n).toList.map(id => broker(id)))

    ClusterDescription
      .from(
        kafkaClusterId = kafkaClusterId.map(KafkaClusterId.unsafe),
        controller = controller.orElse(Some(brokers.head)),
        controllerMode = mode,
        brokers = brokers,
        authorizedOperations = None
      )
      .fold(error => throw new IllegalStateException(error.message), identity)
  }

  def replica(topic: String, partition: Int, sizeBytes: Long, isFuture: Boolean = false): ReplicaInfo =
    ReplicaInfo(
      TopicPartition(TopicName.unsafe(topic), PartitionId.unsafe(partition)),
      sizeBytes,
      offsetLag = 0L,
      isFuture = isFuture
    )

  def logDir(
      path: String,
      replicas: List[ReplicaInfo] = Nil,
      error: Option[LogDirError] = None,
      totalBytes: Option[Long] = Some(1_000_000L),
      usableBytes: Option[Long] = Some(500_000L)
  ): LogDir =
    LogDir
      .from(LogDirPath.unsafe(path), error, totalBytes, usableBytes, replicas)
      .fold(problem => throw new IllegalStateException(problem.message), identity)

  /** A load for one broker holding `replicas` replicas in one directory, with skew unset — `withSkew`
    * computes it, and a fixture that pre-filled it would let a test assert a number nothing produced.
    */
  def load(replicas: Int, dirs: List[LogDir] = Nil): BrokerLoad =
    BrokerLoad(
      replicas = replicas,
      skewPercent = None,
      logDirs = if dirs.nonEmpty then dirs else List(logDir("/var/lib/kafka/data"))
    )

  /** One partition of one topic, placed. Defaults to a healthy three-replica partition led by `leader`, so
    * that a suite about under-replication states only the ISR it is about.
    */
  def placement(
      leader: Int,
      replicas: List[Int] = List(1, 2, 3),
      inSync: Option[List[Int]] = None
  ): PartitionPlacement =
    PartitionPlacement(
      leader = Some(BrokerId.unsafe(leader)),
      replicas = replicas.map(BrokerId.unsafe).toSet,
      inSync = inSync.getOrElse(replicas).map(BrokerId.unsafe).toSet
    )

  /** A complete sweep of `placements`: nothing unreadable, so every figure derived from it is a number. */
  def sweep(placements: List[PartitionPlacement], topics: Int = 1): TopicSweep =
    TopicSweep(PartitionCensus.of(placements), topics, Set.empty)

  def features(present: Set[ClusterFeature], absent: Set[ClusterFeature] = Set.empty): ClusterFeatures =
    ClusterFeatures.of(present, absent, ProbedAt)

  /** The three-broker, everything-supported cluster most suites start from. */
  val defaultDescription: ClusterDescription = description(3)

  val allFeatures: ClusterFeatures = features(ClusterFeature.All)

  val defaultVersion: Option[KafkaVersion] =
    KafkaVersion.parse("3.9", VersionSource.MetadataVersion).toOption

  def topology(
      ref: ClusterRef,
      description: ClusterDescription = defaultDescription,
      version: Option[KafkaVersion] = defaultVersion,
      quorum: Option[QuorumInfo] = None,
      features: ClusterFeatures = allFeatures,
      load: Map[BrokerId, BrokerLoad] = Map.empty,
      census: Option[PartitionCensus] = None,
      topics: Option[Int] = None,
      controllerUptime: Option[ControllerUptime] = None
  ): ClusterTopology =
    ClusterTopology(
      cluster = ref,
      description = description,
      version = version,
      quorum = quorum,
      features = features,
      load = BrokerLoad.withSkew(load),
      census = census,
      topics = topics,
      controllerUptime = controllerUptime
    )

  def quorum(leader: Int, voters: List[Int], highWatermark: Long = 100L): QuorumInfo =
    QuorumInfo
      .from(
        leaderId = BrokerId.unsafe(leader),
        leaderEpoch = 4L,
        highWatermark = highWatermark,
        voters = voters.map(id => ReplicaState(BrokerId.unsafe(id), highWatermark, None, None)),
        observers = Nil
      )
      .fold(error => throw new IllegalStateException(error.message), identity)

  private val genBroker: Gen[Broker] =
    for {
      id <- Gen.choose(0, 1000)
      rack <- Gen.option(Gen.oneOf("a", "b", "c"))
    } yield broker(id, rack)

  given Arbitrary[Broker] = Arbitrary(genBroker)

  given Arbitrary[LogDir] = Arbitrary(
    for {
      path <- Gen.choose(0, 3).map(n => s"/var/lib/kafka/data-$n")
      count <- Gen.choose(0, 5)
      sizes <- Gen.listOfN(count, Gen.choose(0L, 1_000_000L))
      failed <- Gen.oneOf(None, Some(LogDirError.Offline))
    } yield logDir(
      path,
      sizes.zipWithIndex.map((size, index) => replica("orders", index, size)),
      failed
    )
  )

}
