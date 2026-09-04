package kui.topic.domain

import java.time.Instant

import org.scalacheck.{Arbitrary, Gen}

import kui.kernel.{BrokerId, PartitionId, TopicName}

/** Generators for the topic domain's values.
  *
  * They live in this module's **test** tree and not in `libs/testkit`, because a generator of a domain type
  * is a dependency on that domain type, and rule A5 forbids a library from depending on a service. The
  * cluster domain's test module records the same reasoning, and the application and infrastructure suites
  * depend on this module's test module exactly as the cluster service's do.
  *
  * Every generator produces a *valid* value. A suite that wants an invalid one builds it by hand, so that the
  * invalid shape it is testing is visible in the test rather than hidden in a generator.
  */
object TopicGenerators {

  val topicName: Gen[TopicName] =
    for {
      head <- Gen.alphaLowerChar
      rest <- Gen.listOfN(8, Gen.oneOf(Gen.alphaLowerChar, Gen.numChar, Gen.const('-'), Gen.const('.')))
    } yield TopicName.unsafe((head :: rest).mkString)

  val brokerId: Gen[BrokerId] = Gen.choose(0, 9).map(BrokerId.unsafe)

  /** A partition whose invariants hold: a leader drawn from its own replicas, an in-sync set that is a subset
    * of them, and offsets that are absent together with the leader.
    */
  def partition(id: Int): Gen[PartitionView] =
    for {
      replicaCount <- Gen.choose(1, 4)
      replicas <- Gen.pick(replicaCount, 0 to 9).map(_.toList.map(BrokerId.unsafe))
      hasLeader <- Gen.frequency(9 -> true, 1 -> false)
      leader = if hasLeader then replicas.headOption else None
      inSyncCount <- Gen.choose(if hasLeader then 1 else 0, replicas.size)
      earliest <- Gen.choose(0L, 1000L)
      length <- Gen.choose(0L, 100000L)
      size <- Gen.option(Gen.choose(0L, 1000000L))
    } yield PartitionView
      .from(
        partition = PartitionId.unsafe(id),
        leader = leader,
        replicas = replicas,
        inSync = replicas.take(inSyncCount),
        earliestOffset = Option.when(hasLeader)(earliest),
        latestOffset = Option.when(hasLeader)(earliest + length),
        sizeBytes = size
      )
      .fold(error => throw new AssertionError(s"the generator built an invalid partition: $error"), identity)

  val partitions: Gen[List[PartitionView]] =
    for {
      count <- Gen.choose(0, 6)
      values <- Gen.sequence[List[PartitionView], PartitionView]((0 until count).map(partition))
    } yield values

  val topicSummary: Gen[TopicSummary] =
    for {
      name <- topicName
      internal <- Arbitrary.arbitrary[Boolean]
      parts <- partitions
    } yield TopicSummary.of(name, internal, parts)

  /** Distinct names, because a snapshot with two rows of the same name is not a state a scrape can produce
    * and a generator that produced one would make `byName` look inconsistent when it is not.
    */
  val topicSummaries: Gen[Vector[TopicSummary]] =
    Gen
      .listOf(topicSummary)
      .map(rows => rows.groupBy(_.name).values.map(_.head).toVector)

  val instant: Gen[Instant] = Gen.choose(0L, 4_000_000_000L).map(Instant.ofEpochSecond)

  val snapshot: Gen[TopicSnapshot] =
    for {
      rows <- topicSummaries
      at <- instant
    } yield TopicSnapshot.of(rows, at)

  given Arbitrary[TopicSummary] = Arbitrary(topicSummary)
  given Arbitrary[PartitionView] = Arbitrary(partition(0))
  given Arbitrary[TopicSnapshot] = Arbitrary(snapshot)

  /** A partition built from named arguments, for the suites that want one specific shape. */
  def partitionOf(
      id: Int,
      leader: Option[Int],
      replicas: List[Int],
      inSync: List[Int],
      earliest: Option[Long] = None,
      latest: Option[Long] = None,
      size: Option[Long] = None
  ): Either[kui.kernel.ValidationError, PartitionView] =
    PartitionView.from(
      partition = PartitionId.unsafe(id),
      leader = leader.map(BrokerId.unsafe),
      replicas = replicas.map(BrokerId.unsafe),
      inSync = inSync.map(BrokerId.unsafe),
      earliestOffset = earliest,
      latestOffset = latest,
      sizeBytes = size
    )

  /** The same, for a partition that is expected to be valid. */
  def validPartition(
      id: Int,
      leader: Option[Int],
      replicas: List[Int],
      inSync: List[Int],
      earliest: Option[Long] = None,
      latest: Option[Long] = None,
      size: Option[Long] = None
  ): PartitionView =
    partitionOf(id, leader, replicas, inSync, earliest, latest, size)
      .fold(error => throw new AssertionError(error.message), identity)
}
