package kui.ui.shell.page

import java.time.Instant

import munit.FunSuite

import kui.contracts.Section
import kui.contracts.capability.{CapabilityState, ReasonCode}
import kui.contracts.cluster.{ClusterRowDto, ClusterSecurityDto, ClusterSummaryDto}
import kui.gateway.contract.dto.{
  ClusterOverviewDto,
  ClusterOverviewRow,
  GroupTotalsDto,
  TopicMagnitudeDto,
  TopicTotalsDto
}
import kui.kernel.group.GroupState
import kui.kernel.{ClusterId, TopicName}

/** The dashboard's one arithmetic rule: a total nobody can compute is absent, never partial.
  *
  * Every case here is one where the obvious implementation produces a plausible number that is wrong. A sum
  * over the clusters that happened to answer looks exactly like the real figure, grows when an outage ends,
  * and would have an operator reading a recovery as a change in their fleet — and none of that is visible in
  * a screenshot, which is why the reduction is a pure function with a table of cases rather than something
  * built inside the rendering.
  */
final class DashboardTotalsSuite extends FunSuite {

  private val at = Instant.parse("2026-09-04T10:00:00Z")

  private def summary(brokers: Int): ClusterSummaryDto =
    ClusterSummaryDto(None, None, None, ClusterSummaryDto.KRaft, brokers, None, None, None, None, Nil, at)

  private def row(
      id: String,
      cluster: Section[ClusterSummaryDto],
      topics: Section[TopicTotalsDto] = Section.NotConfigured,
      groups: Section[GroupTotalsDto] = Section.NotConfigured
  ): ClusterOverviewRow =
    ClusterOverviewRow(
      ClusterRowDto(
        id = ClusterId.unsafe(id),
        name = s"cluster $id",
        readOnly = false,
        bootstrapServers = s"$id:9092",
        security = ClusterSecurityDto("PLAINTEXT", None, false, false),
        summary = cluster
      ),
      CapabilityState.Available,
      topics,
      groups
    )

  private def topics(count: Long, partitions: Option[Int]): Section[TopicTotalsDto] =
    Section.Ok(
      TopicTotalsDto(count, count.toInt, partitions, List(TopicMagnitudeDto(TopicName.unsafe("a"), 3))),
      at
    )

  private def groups(count: Long, lag: Option[Long]): Section[GroupTotalsDto] =
    Section.Ok(GroupTotalsDto(count, List.empty, lag, 0), at)

  private val unreachable: Section[Nothing] =
    Section.Unavailable(ReasonCode.UpstreamUnavailable, "connection refused", Some(at))

  // ---------------------------------------------------------------------------------------------

  test("everyClusterContributingGivesEveryTotal") {
    val figures = DashboardTotals.of(
      List(
        row("a", Section.Ok(summary(3), at), topics(7L, Some(20)), groups(2L, Some(9L))),
        row("b", Section.Ok(summary(1), at), topics(2L, Some(6)), groups(1L, Some(0L)))
      )
    )

    assertEquals(figures.clusters, 2)
    assertEquals(figures.clustersOnline, 2)
    assertEquals(figures.brokers, Some(4))
    assertEquals(figures.topics, Some(9L))
    assertEquals(figures.partitions, Some(26))
    assertEquals(figures.consumerGroups, Some(3L))
    assertEquals(figures.missingBrokers, 0)
  }

  test("oneUnreachableClusterMakesTheBrokerTotalAbsentRatherThanSmaller") {
    // Three brokers is not "the number of brokers" when a second cluster did not answer, and it is the
    // number a reader would act on.
    val figures = DashboardTotals.of(
      List(row("a", Section.Ok(summary(3), at)), row("b", unreachable))
    )

    assertEquals(figures.clusters, 2)
    assertEquals(figures.clustersOnline, 1)
    assertEquals(figures.brokers, None)
    assertEquals(figures.missingBrokers, 1)
  }

  test("aClusterWhoseTopicSectionFailedTakesTheTopicAndPartitionTotalsWithIt") {
    val figures = DashboardTotals.of(
      List(
        row("a", Section.Ok(summary(1), at), topics(7L, Some(20))),
        row("b", Section.Ok(summary(1), at), unreachable)
      )
    )

    assertEquals(figures.topics, None)
    assertEquals(figures.partitions, None)
    assertEquals(figures.missingTopics, 1)
    // The brokers still add up: one dead section costs its own figures and nothing else.
    assertEquals(figures.brokers, Some(2))
  }

  test("aClusterWithMoreTopicsThanTheGatewaySummedLosesThePartitionTotalAndKeepsTheTopicCount") {
    val figures = DashboardTotals.of(
      List(row("a", Section.Ok(summary(1), at), topics(900L, None)))
    )

    assertEquals(figures.topics, Some(900L))
    assertEquals(figures.partitions, None)
    assertEquals(figures.missingPartitions, 1)
    assertEquals(figures.missingTopics, 0)
  }

  test("aStaleSectionStillCounts") {
    // Stale is real data, just old. Dropping it would empty the strip the moment a service went quiet,
    // which is exactly when somebody is looking at it.
    val stale = Section.Stale(
      TopicTotalsDto(4L, 4, Some(12), Nil),
      at,
      ReasonCode.UpstreamUnavailable
    )
    val figures = DashboardTotals.of(List(row("a", Section.Ok(summary(1), at), stale)))

    assertEquals(figures.topics, Some(4L))
    assertEquals(figures.partitions, Some(12))
  }

  test("anEmptyFleetTotalsZeroBecauseZeroIsNotAGuess") {
    val figures = DashboardTotals.of(Nil)
    assertEquals(figures.clusters, 0)
    assertEquals(figures.brokers, Some(0))
    assertEquals(figures.topics, Some(0L))
  }

  test("rowsComeFromAStaleListAsWellAsAFreshOne") {
    val list = List(row("a", Section.Ok(summary(1), at)))
    assertEquals(
      DashboardTotals.rowsOf(ClusterOverviewDto(Section.Stale(list, at, ReasonCode.UpstreamTimeout), at)),
      list
    )
    assertEquals(
      DashboardTotals.rowsOf(
        ClusterOverviewDto(Section.Unavailable(ReasonCode.Unknown, "no", None), at)
      ),
      Nil
    )
  }

  test("aNotConfiguredSectionIsHiddenAndEveryOtherStateIsDrawn") {
    // ADR-032: "this deployment has no such thing" is not an error and must not be rendered as one.
    assert(!DashboardTotals.isPresent(Section.NotConfigured))
    assert(DashboardTotals.isPresent(Section.Forbidden))
    assert(DashboardTotals.isPresent(unreachable))
    assert(DashboardTotals.isPresent(Section.Ok(1, at)))
  }

  test("groupStatesAreCountedInDeclarationOrderAndStatesThatDoNotOccurAreLeftOut") {
    val totals = GroupTotalsDto.of(
      List(GroupState.Empty, GroupState.Stable, GroupState.Stable),
      List(Some(1L), Some(2L), Some(3L)),
      groupCount = 3L
    )

    assertEquals(
      totals.byState.map(entry => entry.state.wire -> entry.count),
      List("STABLE" -> 2, "EMPTY" -> 1)
    )
    assertEquals(totals.totalLag, Some(6L))
  }

  test("aGroupWithNoLagLeavesTheClusterTotalAbsent") {
    val totals = GroupTotalsDto.of(
      List(GroupState.Stable, GroupState.Empty),
      List(Some(9L), None),
      groupCount = 2L
    )

    assertEquals(totals.totalLag, None)
    assertEquals(totals.groupsWithoutLag, 1)
  }

  test("topicTotalsSumPartitionsOnlyWhenThePageHeldEveryTopic") {
    val complete = TopicTotalsDto.of(
      List(TopicMagnitudeDto(TopicName.unsafe("a"), 6), TopicMagnitudeDto(TopicName.unsafe("b"), 3)),
      topicCount = 2L
    )
    assertEquals(complete.partitionCount, Some(9))
    assertEquals(complete.largest.map(_.name.value), List("a", "b"))

    val truncated = TopicTotalsDto.of(
      List(TopicMagnitudeDto(TopicName.unsafe("a"), 6)),
      topicCount = 900L
    )
    assertEquals(truncated.partitionCount, None)
    assertEquals(truncated.countedTopics, 1)
  }
}
