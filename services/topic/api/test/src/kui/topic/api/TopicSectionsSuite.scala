package kui.topic.api

import java.time.Instant

import munit.FunSuite

import kui.cache.{Snapshot, SnapshotStatus}
import kui.contracts.Section
import kui.contracts.capability.ReasonCode
import kui.kernel.error.{ApplicationError, InfrastructureError}
import kui.topic.application.Fresh

/** The staleness table, one row per snapshot state.
  *
  * The row that matters most is the fourth: a cluster that has never been scraped is `Unavailable`, never an
  * `Ok` over an empty value. An empty list from a cluster that has ten thousand topics is a lie that looks
  * like data, and M1's dashboard shipped exactly that.
  */
final class TopicSectionsSuite extends FunSuite {

  private val scrapedAt = Instant.parse("2026-09-03T10:11:12Z")
  private val now = Instant.parse("2026-09-03T10:12:00Z")
  private val since = Instant.parse("2026-09-03T10:10:00Z")

  private def sectionOf(snapshot: Snapshot[String]): Section[String] =
    TopicSections.of(snapshot, now)(identity)

  test("a current snapshot is Ok, stamped with when it was scraped and not with now") {
    // `fetchedAt` is what a screen renders as "as of ...". Stamping it with the time of the *request*
    // would make every response look freshly scraped.
    assertEquals(
      sectionOf(Snapshot(Some("rows"), SnapshotStatus.Online, Some(scrapedAt))),
      Section.Ok("rows", scrapedAt)
    )
  }

  test("a snapshot that could not be renewed is Stale, with its data and the real reason") {
    assertEquals(
      sectionOf(Snapshot(Some("rows"), SnapshotStatus.Offline(TopicTestServer.Timeout, since), Some(scrapedAt))),
      Section.Stale("rows", scrapedAt, ReasonCode.UpstreamTimeout)
    )
  }

  test("a timeout reports TIMEOUT and not UPSTREAM_UNAVAILABLE") {
    // M1's cluster service collapsed every failing scrape to UPSTREAM_UNAVAILABLE, because the type it read
    // freshness from had already flattened the error into a sentence (CLAPI-004 deviation 2). A slow
    // cluster and a gone cluster get different remedies and this is the field that tells them apart.
    val timedOut = Snapshot(Some("rows"), SnapshotStatus.Offline(TopicTestServer.Timeout, since), Some(scrapedAt))
    val gone = Snapshot(
      Some("rows"),
      SnapshotStatus.Offline(InfrastructureError.Unreachable("kafka", "connection refused"), since),
      Some(scrapedAt)
    )

    assertNotEquals(sectionOf(timedOut), sectionOf(gone))
  }

  test("a first scrape in flight over existing data is Stale and says it is starting") {
    assertEquals(
      sectionOf(Snapshot(Some("rows"), SnapshotStatus.Initializing, Some(scrapedAt))),
      Section.Stale("rows", scrapedAt, ReasonCode.Starting)
    )
  }

  test("aNeverScrapedClusterIsSectionUnavailableNotAnEmptyPage") {
    val section = sectionOf(Snapshot(None, SnapshotStatus.Initializing, None))

    assertEquals(section, Section.Unavailable(ReasonCode.Starting, TopicSections.StartingMessage, Some(now)))
    assertEquals(section.toOption, None)
  }

  test("a failed first scrape is Unavailable with the reason and the time it started failing") {
    assertEquals(
      sectionOf(Snapshot(None, SnapshotStatus.Offline(TopicTestServer.Timeout, since), None)),
      Section.Unavailable(ReasonCode.UpstreamTimeout, TopicTestServer.Timeout.message, Some(since))
    )
  }

  test("the reason classification covers every failure shape a scrape produces") {
    assertEquals(TopicSections.reasonOf(ApplicationError.Forbidden("no")), ReasonCode.Forbidden)
    assertEquals(TopicSections.reasonOf(ApplicationError.Unsupported("x")), ReasonCode.NotConfigured)
    assertEquals(
      TopicSections.reasonOf(InfrastructureError.CircuitOpen("kafka", since)),
      ReasonCode.CircuitOpen
    )
    assertEquals(TopicSections.reasonOf(InfrastructureError.Timeout("x", 1L)), ReasonCode.UpstreamTimeout)
    assertEquals(TopicSections.reasonOf(InfrastructureError.AuthFailed("kafka")), ReasonCode.UpstreamAuth)
    assertEquals(
      TopicSections.reasonOf(InfrastructureError.Unreachable("kafka", "refused")),
      ReasonCode.UpstreamUnavailable
    )
  }

  test("a live read is Ok and a fallback read is Stale, mapped straight through") {
    // The use case already decided that the live call failed and that it is serving the snapshot instead.
    // Re-deciding it here would give two layers an opinion about one fact.
    assertEquals(TopicSections.ofFresh(Fresh.Live("live"), now)(identity), Section.Ok("live", now))
    assertEquals(
      TopicSections.ofFresh(Fresh.FromSnapshot("old", scrapedAt, "the describe timed out"), now)(identity),
      Section.Stale("old", scrapedAt, ReasonCode.UpstreamTimeout)
    )
  }

  test("a fallback whose reason names nothing recognisable is still reported, as unavailable") {
    assertEquals(
      TopicSections.ofFresh(Fresh.FromSnapshot("old", scrapedAt, "something went wrong"), now)(identity),
      Section.Stale("old", scrapedAt, ReasonCode.UpstreamUnavailable)
    )
  }
}
