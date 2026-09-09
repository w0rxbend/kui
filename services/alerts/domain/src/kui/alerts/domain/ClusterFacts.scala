package kui.alerts.domain

import kui.kernel.error.KuiError
import kui.kernel.{BrokerId, GroupId}

/** One fact, or the reason there is none.
  *
  * The distinction this type exists for is the one the whole product is built on: **not measured is not
  * zero**. A `describeLogDirs` that failed must not reach a rule as "no directory is above its threshold",
  * because that reads on the screen as a cluster with plenty of disk. Every rule below therefore takes its
  * facts as one of these and an unreadable fact opens nothing, closes nothing, and is reported in words.
  */
enum FactReading[+A] {

  case Read[A](value: A) extends FactReading[A]

  /** The call was made and did not answer. The failure travels whole, because the feed renders its message
    * and the `Section` around it renders its code.
    */
  case Unreadable(reason: KuiError) extends FactReading[Nothing]

  def toOption: Option[A] = this match {
    case Read(value) => Some(value)
    case Unreadable(_) => None
  }

  def failure: Option[KuiError] = this match {
    case Read(_) => None
    case Unreadable(error) => Some(error)
  }
}

object FactReading {
  given [A] => CanEqual[FactReading[A], FactReading[A]] = CanEqual.derived
}

/** What one `describeTopics` sweep counted.
  *
  * A count and not a list of partitions, for the reason `AlertKey` gives: one row saying fourteen beats
  * fourteen rows. The list is on the topics screen, where it belongs.
  *
  * @param complete
  *   whether every topic in the cluster was described. A partial sweep is **not** a fact: a cluster where KUI
  *   could describe half the topics and found no offline partition has not established that there are none.
  *   The adapter reports it and the rules refuse to fire on it.
  */
final case class PartitionFacts(offline: Int, underReplicated: Int, complete: Boolean)

object PartitionFacts {
  given CanEqual[PartitionFacts, PartitionFacts] = CanEqual.derived
}

/** One log directory, as the disk rule needs to see it.
  *
  * @param usedPercent
  *   `None` when the broker did not report a capacity. Brokers before 3.3 do not publish `totalBytes` at all
  *   (`kui.kafka.admin.LogDir`'s own note), and a directory whose capacity is unknown has **no** percentage:
  *   the used bytes alone cannot say whether they are most of a disk or a rounding error on one. The rule
  *   opens nothing for such a directory and the feed says how many it skipped, which is the honest reading
  *   and is the rule ADR-053 §4 names.
  */
final case class LogDirectoryFact(broker: BrokerId, path: String, usedPercent: Option[Int]) {

  /** How the directory is named in an event's subject and detail line: `broker-1:/var/lib/kafka/data`. */
  def label: String = s"broker-${broker.value}:$path"
}

object LogDirectoryFact {
  given CanEqual[LogDirectoryFact, LogDirectoryFact] = CanEqual.derived
}

/** Everything one evaluation pass reads about one cluster.
  *
  * Three readings and not one, because they come from three admin calls that fail independently. A cluster
  * whose `describeLogDirs` is refused by an ACL still has readable partition counts, and a feed that lost all
  * four rules to one refusal would be a feed that goes dark exactly when something is wrong.
  */
final case class ClusterFacts(
    partitions: FactReading[PartitionFacts],
    rebalancingGroups: FactReading[Set[GroupId]],
    logDirectories: FactReading[List[LogDirectoryFact]]
)

object ClusterFacts {

  /** Every fact unreadable, for the same stated reason. The shape a pass takes when the cluster itself could
    * not be reached at all — no admin client, no connection, no answer to any of the three questions.
    */
  def allUnreadable(failure: KuiError): ClusterFacts =
    ClusterFacts(
      partitions = FactReading.Unreadable(failure),
      rebalancingGroups = FactReading.Unreadable(failure),
      logDirectories = FactReading.Unreadable(failure)
    )

  given CanEqual[ClusterFacts, ClusterFacts] = CanEqual.derived
}
