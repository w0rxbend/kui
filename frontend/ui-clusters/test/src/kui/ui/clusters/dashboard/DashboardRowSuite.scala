package kui.ui.clusters.dashboard

import munit.FunSuite

import kui.contracts.Section
import kui.contracts.capability.ReasonCode
import kui.ui.clusters.dashboard.ClusterFixtures.*

/** The row model as a table: one case per line of the rule the screen is built from. */
class DashboardRowSuite extends FunSuite {

  private def only(section: Section[kui.contracts.cluster.ClusterSummaryDto]): DashboardRow =
    DashboardRow.of(response(row("local", section = section))).head

  test("okSectionProducesAnOnlineRowWithItsData") {
    val current = only(Section.Ok(summary(brokers = 5), scrapedAt))
    assertEquals(current.status, RowStatus.Online)
    assertEquals(current.brokerCount, Some(5))
    assertEquals(current.version, Some("4.0.0"))
    assertEquals(current.fetchedAt, Some(scrapedAt))
    assert(!current.isStale)
  }

  test("staleSectionProducesADegradedRowThatKeepsItsData") {
    val current = only(stale(ReasonCode.UpstreamTimeout))
    // The payload is still on the row. Throwing the numbers away because they are old is exactly the
    // failure ADR-032 exists to prevent.
    assertEquals(current.brokerCount, Some(3))
    assertEquals(current.diskUsageBytes, Some(1024L * 1024 * 1024))
    assertEquals(current.status, RowStatus.Degraded("cluster too slow to answer"))
    assert(current.isStale)
  }

  test("unavailableSectionProducesDashesAndKeepsTheIdentity") {
    val current = DashboardRow
      .of(response(row("prod", name = "Production", readOnly = true, section = unavailable("connection refused"))))
      .head

    // The identity comes from configuration and is known whether or not the cluster answers, which is
    // precisely why the contract keeps it outside the section.
    assertEquals(current.name, "Production")
    assertEquals(current.clusterId, clusterId("prod"))
    assertEquals(current.readOnly, true)
    assertEquals(current.status, RowStatus.Unavailable("connection refused", Some(scrapedAt)))

    assertEquals(current.version, None)
    assertEquals(current.brokerCount, None)
    assertEquals(current.controller, None)
    assertEquals(current.diskUsageBytes, None)
    assertEquals(current.fetchedAt, None)
  }

  test("forbiddenSectionProducesDashesAndAForbiddenChip") {
    val current = only(Section.Forbidden)
    assertEquals(current.status, RowStatus.Forbidden)
    assertEquals(current.brokerCount, None)
    assert(!current.isUnavailable)
  }

  test("notConfiguredClustersAreNotRows") {
    // Hidden, not dimmed: this deployment has no such cluster, so putting it on screen would invite a
    // user to click something that will never work.
    val rows = DashboardRow.of(response(row("a"), row("b", section = Section.NotConfigured)))
    assertEquals(rows.map(_.clusterId.value), List("a"))
  }

  test("statusOrderPutsProblemsFirst") {
    val statuses = List(
      RowStatus.Online,
      RowStatus.Forbidden,
      RowStatus.Degraded("slow"),
      RowStatus.Unavailable("refused", None)
    )
    assertEquals(
      statuses.sortBy(DashboardRow.statusOrder),
      List(
        RowStatus.Unavailable("refused", None),
        RowStatus.Degraded("slow"),
        RowStatus.Forbidden,
        RowStatus.Online
      )
    )
  }

  test("countsIgnoreRowsThatAreNotRendered") {
    val rows = DashboardRow.of(
      response(
        row("a"),
        row("b", section = unavailable("refused")),
        row("c", section = Section.NotConfigured),
        row("d", section = stale())
      )
    )
    val (online, notOnline) = DashboardRow.counts(rows)
    assertEquals(online, 1)
    assertEquals(notOnline, 2)
    // The two numbers always add up to what is on screen, which is what makes the strip trustworthy.
    assertEquals(online + notOnline, rows.length)
  }

  test("onlyUnavailableKeepsDegradedRowsOut") {
    // The toggle means unavailable, not "not perfectly healthy". A degraded cluster is still serving
    // data, and sweeping it in here would make the toggle mean something other than its label.
    val rows = DashboardRow.of(response(row("a"), row("b", section = stale()), row("c", section = unavailable("x"))))
    assertEquals(DashboardRow.onlyUnavailable(rows).map(_.clusterId.value), List("c"))
  }
}
