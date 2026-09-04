package kui.ui.clusters.brokers

import java.time.Instant

import com.raquo.laminar.api.L.*

import kui.contracts.cluster.{QuorumDto, QuorumMemberDto}
import kui.ui.clusters.{ClustersCss, Messages}
import kui.ui.kernel.component.*
import kui.ui.kernel.time.Timestamps

/** The KRaft metadata quorum, beside the brokers it is made of.
  *
  * ==Why this panel is worth building==
  *
  * The metadata quorum is the part of a KRaft cluster whose failure is least visible from anywhere else. A
  * controller that has fallen behind still answers; topics still list, messages still flow, and every
  * *administrative* change is being decided by a shrinking set of nodes. The first symptom on any other
  * screen is a topic create that times out, by which point an operator is debugging the wrong thing.
  *
  * KUI has called `describeMetadataQuorum` on every snapshot pass since M1 and carried the answer on
  * `ClusterTopology.quorum` ever since, and until now nothing rendered it.
  *
  * ==Voters and observers are separated, because they fail differently==
  *
  * A voter's acknowledgement is what a metadata write needs, so a lagging voter is a cluster that is closer
  * than it looks to being unable to change anything at all. An observer is a node replicating the metadata
  * log without a vote — a broker, in a cluster with dedicated controllers — and a lagging observer is a
  * broker with a stale view. Both matter; they are not the same alarm, and one table sorted by lag would
  * present them as though they were.
  *
  * ==The two timestamps are the diagnosis==
  *
  * "Last fetch" and "last caught up" together say which kind of unhealthy a member is. Fetching but never
  * catching up is a slow follower — a disk or a network. Not fetching at all is a dead one. One column could
  * not distinguish them, and the distinction is what decides where the operator goes next.
  *
  * ==Nothing is rendered at all when there is no quorum==
  *
  * A ZooKeeper cluster, a KRaft cluster older than 3.3, and one that refused the call all send no quorum. An
  * empty panel headed "Metadata quorum" would read as a quorum with no members, which is a cluster that
  * cannot function — the opposite of the truth on a perfectly healthy ZooKeeper deployment.
  */
object QuorumPanel {

  def apply(
      quorum: Signal[Option[QuorumDto]],
      zone: Signal[String],
      now: () => Instant = () => Instant.now()
  ): HtmlElement =
    div(child.maybe <-- quorum.map(_.map(panel(_, zone, now))))

  private def panel(quorum: QuorumDto, zone: Signal[String], now: () => Instant): HtmlElement =
    sectionTag(
      cls := ClustersCss.Quorum,
      dataAttr("testid") := "quorum-panel",
      h2(cls := ClustersCss.SectionHeading, Messages.QuorumHeading),
      div(
        cls := ClustersCss.QuorumSummary,
        item(Messages.QuorumLeader, quorum.leaderId.value.toString, "quorum-leader"),
        item(Messages.QuorumEpoch, quorum.leaderEpoch.toString, "quorum-epoch"),
        item(Messages.QuorumHighWatermark, quorum.highWatermark.toString, "quorum-high-watermark"),
        item(Messages.QuorumVoterCount, quorum.voters.size.toString, "quorum-voter-count")
      ),
      // The one sentence a panel of numbers cannot say for itself: whether a metadata write can still be
      // committed. It is computed from the voters that are level with the leader, because that is the set
      // whose acknowledgement a write needs.
      p(
        cls := (if hasMajority(quorum) then ClustersCss.Note else ClustersCss.QuorumWarning),
        dataAttr("testid") := "quorum-verdict",
        if hasMajority(quorum) then Messages.quorumHealthy(caughtUp(quorum), quorum.voters.size)
        else Messages.quorumAtRisk(caughtUp(quorum), quorum.voters.size)
      ),
      memberTable(Messages.QuorumVoters, quorum.voters, "quorum-voters", zone, now),
      if quorum.observers.isEmpty then
        p(cls := ClustersCss.Note, dataAttr("testid") := "quorum-no-observers", Messages.QuorumNoObservers)
      else memberTable(Messages.QuorumObservers, quorum.observers, "quorum-observers", zone, now)
    )

  /** How many voters are level with the leader's high watermark.
    *
    * "Level" is a lag of zero, and it is deliberately exact rather than "close enough": a voter behind the
    * high watermark by any amount has not acknowledged the last committed record, and a threshold here would
    * be a number invented on a screen.
    */
  def caughtUp(quorum: QuorumDto): Int = quorum.voters.count(_.lag == 0L)

  /** Whether a strict majority of voters is level with the leader.
    *
    * Raft's rule, applied to what the leader reported: a metadata write commits when more than half the
    * voters have it. A quorum of three with two caught up is working; with one, it is not, and the panel says
    * so before anybody notices by way of a create that hangs.
    */
  def hasMajority(quorum: QuorumDto): Boolean =
    quorum.voters.nonEmpty && caughtUp(quorum) * 2 > quorum.voters.size

  private def item(label: String, value: String, testId: String): HtmlElement =
    div(
      cls := ClustersCss.QuorumItem,
      span(cls := ClustersCss.QuorumItemLabel, label),
      span(cls := ClustersCss.QuorumItemValue, dataAttr("testid") := testId, value)
    )

  private def memberTable(
      heading: String,
      members: List[QuorumMemberDto],
      testId: String,
      zone: Signal[String],
      now: () => Instant
  ): HtmlElement =
    div(
      cls := ClustersCss.QuorumTable,
      h3(cls := ClustersCss.QuorumTableHeading, heading),
      DataTable[QuorumMemberDto](
        columns = columns(zone, now),
        rows = Val(members),
        rowKey = _.replicaId.value.toString,
        loading = Val(false),
        empty = () => EmptyState(Messages.QuorumNoMembers),
        testId = Some(testId)
      )
    )

  private def columns(zone: Signal[String], now: () => Instant): List[Column[QuorumMemberDto]] =
    List(
      Column[QuorumMemberDto](
        id = "replica",
        header = Messages.QuorumColumnNode,
        render = member =>
          span(
            dataAttr("testid") := s"quorum-member-${member.replicaId.value}",
            member.replicaId.value.toString,
            // The leader is marked on its own row rather than only in the summary above, because the
            // question being asked of this table is "which of these is behind", and the answer means
            // something different for the node the others are measured against.
            Option.when(member.isLeader)(Tag(label = Val(Messages.QuorumLeaderTag), tone = Tone.Info))
          )
      ),
      Column[QuorumMemberDto](
        id = "logEndOffset",
        header = Messages.QuorumColumnLogEnd,
        render = member => span(member.logEndOffset.toString),
        align = ColumnAlign.Numeric
      ),
      Column[QuorumMemberDto](
        id = "lag",
        header = Messages.QuorumColumnLag,
        render = member =>
          span(
            dataAttr("testid") := s"quorum-member-${member.replicaId.value}-lag",
            // Zero is spelled out rather than dashed: a member level with the leader is the healthy case
            // and an em dash would read as "not known".
            cls := (if member.lag == 0L then ClustersCss.QuorumCaughtUp else ClustersCss.QuorumBehind),
            member.lag.toString
          ),
        align = ColumnAlign.Numeric
      ),
      Column[QuorumMemberDto](
        id = "lastFetch",
        header = Messages.QuorumColumnLastFetch,
        render = member => timeCell(member.lastFetch, zone, now)
      ),
      Column[QuorumMemberDto](
        id = "lastCaughtUp",
        header = Messages.QuorumColumnLastCaughtUp,
        render = member => timeCell(member.lastCaughtUp, zone, now)
      )
    )

  /** A relative time with the absolute one on its title, or an em dash.
    *
    * The em dash is honest here: the leader does not fetch from anyone, and Kafka reports no time for it.
    * "Never" would be a claim about a follower that has stopped, which is a different and alarming thing.
    */
  private def timeCell(at: Option[Instant], zone: Signal[String], now: () => Instant): HtmlElement =
    at.fold(span(title := Messages.QuorumTimeUnknown, DataTable.missing))(instant =>
      span(
        Timestamps.relative(instant, now()),
        title <-- zone.map(zoneId => Timestamps.absolute(instant, zoneId))
      )
    )
}
