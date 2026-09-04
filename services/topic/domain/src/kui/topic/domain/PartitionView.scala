package kui.topic.domain

import kui.kernel.{BrokerId, PartitionId, ValidationError}

/** One replica of one partition, with the two flags the partition table renders as chips.
  *
  * The in-sync flag is carried on the replica rather than as a second list beside it. Kafka reports the
  * replica set and the in-sync set separately, and a reference product that keeps them separate can — and
  * does — end up rendering "5 of 3 in sync", because nothing stops the second list from holding a broker the
  * first one does not. Here that state cannot be built: [[PartitionView.from]] refuses it once, at the
  * boundary, and everything downstream reads a single list.
  */
final case class Replica(broker: BrokerId, isLeader: Boolean, isInSync: Boolean)

object Replica {
  given Ordering[Replica] = Ordering.by((replica: Replica) => replica.broker.value)
  given CanEqual[Replica, Replica] = CanEqual.derived
}

/** One partition as the topic detail page shows it.
  *
  * `leader` is `None` for an offline partition — never `-1`, which the reference products use and which then
  * sorts before every real broker id and sums into every total as a number.
  *
  * The offsets are `Option` for one reason and it is worth spelling out: KUI does not ask a leaderless
  * partition for its offsets at all. `listOffsets` against a partition with no leader does not fail fast, it
  * retries until the sixty-second client timeout expires (`libs/kafka/PORT-INVARIANTS.md` §1), so a single
  * offline partition would stall a whole scrape. The port skips those partitions, the skip arrives here as an
  * absent offset, and [[messageCount]] refuses rather than guessing.
  */
final case class PartitionView private (
    partition: PartitionId,
    leader: Option[BrokerId],
    replicas: List[Replica],
    earliestOffset: Option[Long],
    latestOffset: Option[Long],
    sizeBytes: Option[Long]
) {

  /** How many records this partition holds: the latest offset minus the earliest.
    *
    * `None` when either offset is missing — the same refusal as `TopicSummary.messageCount`, one level down,
    * and the level the aggregate is computed from. An *empty* partition is not a missing one: when the two
    * offsets are equal and both present the answer is `Some(0)`, and confusing the two is how a screen tells
    * an operator a topic is empty when in fact KUI could not read it.
    */
  def messageCount: Option[Long] =
    for {
      earliest <- earliestOffset
      latest <- latestOffset
    } yield latest - earliest

  def isLeaderless: Boolean = leader.isEmpty

  /** Replicas the leader considers caught up. A partition whose in-sync set is smaller than its replica set
    * is under-replicated, which is the count the list column reports.
    */
  def inSyncReplicas: List[Replica] = replicas.filter(_.isInSync)

  /** How many of this partition's replicas are lagging. Replicas, not partitions: the reference product's own
    * column is "out of sync replicas" and counting partitions instead is an easy and invisible mistake.
    */
  def outOfSyncReplicas: Int = replicas.count(!_.isInSync)
}

object PartitionView {

  /** Builds a partition view from what a broker reports, refusing the states a broker should never report.
    *
    * Every rule below has been observed in the wild in at least one of the reference products, and each of
    * them renders as a plausible-looking lie rather than as an obvious failure — which is why they are
    * refused here, once, instead of being defended against at every screen.
    *
    * @param replicas
    *   every broker holding a copy, in the order Kafka reported them (the first is the preferred leader)
    * @param inSync
    *   the subset of `replicas` the leader considers caught up
    */
  def from(
      partition: PartitionId,
      leader: Option[BrokerId],
      replicas: List[BrokerId],
      inSync: List[BrokerId],
      earliestOffset: Option[Long],
      latestOffset: Option[Long],
      sizeBytes: Option[Long]
  ): Either[ValidationError, PartitionView] = {
    val field = s"partition[${partition.value}]"

    def invariant(rule: String): Either[ValidationError, Nothing] =
      Left(ValidationError.Invariant(field, rule))

    val replicaSet = replicas.toSet
    val inSyncSet = inSync.toSet

    for {
      _ <-
        if replicas.distinct.sizeIs == replicas.size then Right(())
        else invariant("a broker appears twice in the replica set")
      _ <-
        if inSyncSet.subsetOf(replicaSet) then Right(())
        else
          invariant(
            "the in-sync replica set contains a broker that is not a replica, which would render as " +
              "'more replicas in sync than exist'"
          )
      _ <- leader match {
        case Some(id) if !replicaSet.contains(id) =>
          invariant("the leader is not one of the partition's replicas")
        case _ => Right(())
      }
      _ <- (leader, earliestOffset.orElse(latestOffset)) match {
        case (None, Some(_)) =>
          invariant(
            "a leaderless partition carries offsets; KUI never asks a leaderless partition for its " +
              "offsets, so a value here means something invented one"
          )
        case _ => Right(())
      }
      _ <- (earliestOffset, latestOffset) match {
        case (Some(earliest), _) if earliest < 0L => invariant("the earliest offset is negative")
        case (_, Some(latest)) if latest < 0L => invariant("the latest offset is negative")
        case (Some(earliest), Some(latest)) if earliest > latest =>
          invariant(s"the earliest offset ($earliest) is after the latest ($latest)")
        case _ => Right(())
      }
      _ <- sizeBytes match {
        case Some(size) if size < 0L => invariant("the size in bytes is negative")
        case _ => Right(())
      }
    } yield new PartitionView(
      partition = partition,
      leader = leader,
      replicas = replicas.map(id => Replica(id, isLeader = leader.contains(id), isInSync = inSyncSet(id))),
      earliestOffset = earliestOffset,
      latestOffset = latestOffset,
      sizeBytes = sizeBytes
    )
  }

  given Ordering[PartitionView] = Ordering.by((view: PartitionView) => view.partition.value)
  given CanEqual[PartitionView, PartitionView] = CanEqual.derived
}
