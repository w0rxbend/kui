package kui.ui.shell.page

import kui.contracts.Section
import kui.gateway.contract.dto.{ClusterOverviewDto, ClusterOverviewRow}

/** The figures the dashboard's top strip shows, reduced from the whole response.
  *
  * ==One rule, applied five times: a total nobody can compute is absent==
  *
  * Every figure here is an `Option`, and it is `None` unless *every* cluster in the response contributed to
  * it. A sum over the clusters that happened to answer is not "the number of brokers"; it is a smaller number
  * that looks exactly like the real one, gets larger when an outage ends, and would have an operator reading
  * a recovery as a change in their fleet. The screen renders `—` for those, and each tile says how many
  * clusters it could not count — so the reader is told the figure is partial rather than shown a partial
  * figure.
  *
  * This is a plain function over plain values, with no `Signal` and no rendering in it, so that the rule
  * above is a table of test cases rather than something somebody has to squint at a screenshot to check.
  */
final case class DashboardTotals(
    clusters: Int,
    clustersOnline: Int,
    brokers: Option[Int],
    topics: Option[Long],
    partitions: Option[Int],
    consumerGroups: Option[Long],
    /** How many clusters could not contribute to `brokers`, `topics`, `partitions` and `consumerGroups`
      * respectively. Zero means the figure beside it is complete.
      */
    missingBrokers: Int,
    missingTopics: Int,
    missingPartitions: Int,
    missingGroups: Int
)

object DashboardTotals {

  given CanEqual[DashboardTotals, DashboardTotals] = CanEqual.derived

  val Empty: DashboardTotals =
    DashboardTotals(0, 0, None, None, None, None, 0, 0, 0, 0)

  /** The rows the dashboard draws: the cluster service's answer, or nothing when it did not answer.
    *
    * `toOption` keeps a `Stale` list, which is the point of the type — rows the gateway last saw are still
    * rows, and the screen marks them old rather than removing them.
    */
  def rowsOf(response: ClusterOverviewDto): List[ClusterOverviewRow] =
    response.clusters.toOption.toList.flatten

  def of(rows: List[ClusterOverviewRow]): DashboardTotals = {
    val brokerCounts = rows.map(_.cluster.summary.toOption.map(_.brokerCount))
    val topicTotals = rows.map(_.topics.toOption)
    val groupTotals = rows.map(_.consumerGroups.toOption)

    DashboardTotals(
      clusters = rows.size,
      // `Section.Ok` and nothing else. Not `toOption`, which also keeps `Stale` - that leniency is right for
      // deciding whether a row still has *numbers* to draw, and wrong for counting how many clusters are
      // answering. Stopping the quickstart's broker used to leave the strip reading "1 of 1 Clusters online"
      // above panels that all said "cluster not responding", and beside a cluster list that said "0 online,
      // 1 not online" - two screens of the same product disagreeing about whether the only cluster was up.
      clustersOnline = rows.count(row =>
        row.cluster.summary match {
          case Section.Ok(_, _) => true
          case _ => false
        }
      ),
      brokers = complete(brokerCounts).map(_.sum),
      topics = complete(topicTotals).map(_.map(_.topicCount).sum),
      // Two levels of "could not compute": a cluster whose topic section failed, and a cluster whose topic
      // list was longer than the page the gateway summed. Either one makes the fleet-wide total absent.
      partitions = complete(topicTotals.map(_.flatMap(_.partitionCount))).map(_.sum),
      consumerGroups = complete(groupTotals).map(_.map(_.groupCount).sum),
      missingBrokers = brokerCounts.count(_.isEmpty),
      missingTopics = topicTotals.count(_.isEmpty),
      missingPartitions = topicTotals.map(_.flatMap(_.partitionCount)).count(_.isEmpty),
      missingGroups = groupTotals.count(_.isEmpty)
    )
  }

  /** The values, when every one of them is there. An empty fleet totals zero, which is not a guess. */
  private def complete[A](values: List[Option[A]]): Option[List[A]] =
    Option.when(values.forall(_.isDefined))(values.flatten)

  /** Whether a section is one the screen draws a panel for at all.
    *
    * `NotConfigured` means this deployment has no such service, and ADR-032 says a screen hides that rather
    * than showing it as an error. A permanent red "consumer groups unavailable" panel on every dashboard of
    * every installation that never deployed the consumer service teaches an operator to ignore the colour
    * that matters.
    */
  def isPresent(section: Section[?]): Boolean =
    section match {
      case Section.NotConfigured => false
      case _ => true
    }
}
