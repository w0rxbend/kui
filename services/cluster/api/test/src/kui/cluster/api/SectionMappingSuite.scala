package kui.cluster.api

import java.time.Instant

import munit.FunSuite

import kui.cluster.application.SnapshotFreshness
import kui.contracts.Section
import kui.contracts.capability.ReasonCode
import kui.kernel.error.{ApplicationError, ErrorCode, InfrastructureError}

/** The staleness contract, asserted as the table it is.
  *
  * Every row is here because a screen renders it differently, and because getting one of them wrong is
  * invisible until a cluster breaks: the most expensive mistake this mapping could make is calling healthy
  * data stale, which would grey out a working dashboard and teach everyone to ignore the marking.
  */
final class SectionMappingSuite extends FunSuite {

  private val scrapedAt = Instant.parse("2026-09-03T10:11:12Z")
  private val since = Instant.parse("2026-09-03T10:00:00Z")
  private val now = Instant.parse("2026-09-03T10:30:00Z")

  private def of(data: Option[String], freshness: SnapshotFreshness): Section[String] =
    SectionMapping.of(data, freshness, now)(identity)

  test("freshDataWithAValueIsOkAtTheTimeItWasScraped") {
    assertEquals(
      of(Some("v"), SnapshotFreshness.Fresh(scrapedAt)),
      Section.Ok("v", scrapedAt)
    )
  }

  test("aStaleSectionAlwaysCarriesAReason") {
    val section = of(Some("v"), SnapshotFreshness.Stale(scrapedAt, "connection refused", since))

    section match {
      case Section.Stale(data, fetchedAt, reason) =>
        assertEquals(data, "v")
        assertEquals(fetchedAt, scrapedAt)
        assertEquals(reason, ReasonCode.UpstreamUnavailable)
      case other => fail(s"expected a stale section, got $other")
    }
  }

  test("aFailingUpstreamWithNothingEverFetchedIsUnavailableWithTheReasonAndTheTimeItStarted") {
    assertEquals(
      of(None, SnapshotFreshness.Unavailable("connection refused", since)),
      Section.Unavailable(ReasonCode.UpstreamUnavailable, "connection refused", Some(since))
    )
  }

  test("aFailingUpstreamWhoseDataHasGoneIsAlsoUnavailable, and keeps the sticky since") {
    assertEquals(
      of(None, SnapshotFreshness.Stale(scrapedAt, "connection refused", since)),
      Section.Unavailable(ReasonCode.UpstreamUnavailable, "connection refused", Some(since))
    )
  }

  test("aServiceThatHasNotFinishedItsFirstScrapeIsStarting, not an error") {
    // The shell renders a "starting" pill rather than a failure. Every deployment looks like this for its
    // first two seconds, and a rollout that painted every cluster red for two seconds would train
    // operators to ignore red.
    assertEquals(
      of(None, SnapshotFreshness.Loading),
      Section.Unavailable(ReasonCode.Starting, SectionMapping.StartingMessage, Some(now))
    )
  }

  test("dataWithARefreshInFlightIsStaleRatherThanHidden") {
    assertEquals(of(Some("v"), SnapshotFreshness.Loading), Section.Stale("v", now, ReasonCode.Starting))
  }

  test("ageAloneNeverProducesStale") {
    // The snapshot was taken twenty minutes before `now` and the loop is healthy. That is not stale; it is
    // a service whose refresh interval is what it is. If age were the test, every response in a perfectly
    // healthy KUI would be marked stale within thirty seconds and the marking would mean nothing.
    val old = Instant.parse("2026-09-03T10:10:00Z")

    assertEquals(of(Some("v"), SnapshotFreshness.Fresh(old)), Section.Ok("v", old))
  }

  test("theRenderFunctionRunsOnlyWhenThereIsSomethingToRender") {
    var rendered = 0
    val _ = SectionMapping.of(None, SnapshotFreshness.Loading, now) { (value: String) =>
      rendered += 1
      value
    }

    assertEquals(rendered, 0)
  }

  test("reasonOfClassifiesByFailureCaseRatherThanByErrorCode") {
    // "Could not connect" and "the breaker is open" share an error code and mean different things on a
    // screen, which is why the classification is on the failure's case.
    assertEquals(
      SectionMapping.reasonOf(InfrastructureError.CircuitOpen("cluster", since)),
      ReasonCode.CircuitOpen
    )
    assertEquals(
      SectionMapping.reasonOf(InfrastructureError.Unreachable("cluster", "refused")),
      ReasonCode.UpstreamUnavailable
    )
    assertEquals(
      SectionMapping.reasonOf(InfrastructureError.AuthFailed("cluster")),
      ReasonCode.UpstreamAuth
    )
    assertEquals(
      SectionMapping.reasonOf(ApplicationError.Unsupported("no broker configuration here")),
      ReasonCode.NotConfigured
    )
    assertEquals(
      SectionMapping.reasonOf(ApplicationError.NotFound("cluster", "x", ErrorCode.ClusterNotFound)),
      ReasonCode.Unknown
    )
  }
}
