package kui.ui.topics.list

import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import org.scalacheck.{Arbitrary, Gen}

import kui.contracts.paging.{PageDto, PageInfo}
import kui.contracts.topic.TopicRowDto
import kui.kernel.TopicName

/** The row model as a table of inputs and expected values.
  *
  * Nothing here mounts a DOM, and that is the point of the row model existing at all: every rendering rule on
  * the topic list is decided by a total function, so each rule is one test row rather than a rendering to
  * inspect by eye.
  */
final class TopicRowSuite extends ScalaCheckSuite {

  private def dto(
      name: String,
      internal: Boolean = false,
      partitions: Int = 3,
      replicationFactor: Option[Int] = Some(3),
      outOfSync: Int = 0,
      offlinePartitions: Int = 0,
      messages: Option[Long] = Some(100L),
      sizeBytes: Option[Long] = Some(1024L)
  ): TopicRowDto =
    TopicRowDto(
      name = TopicName.unsafe(name),
      internal = internal,
      partitionCount = partitions,
      replicationFactor = replicationFactor,
      outOfSyncReplicas = outOfSync,
      offlinePartitions = offlinePartitions,
      messageCount = messages,
      sizeBytes = sizeBytes
    )

  private def pageOf(items: List[TopicRowDto]): PageDto[TopicRowDto] =
    PageDto(items, PageInfo(1, 25, Some(items.size.toLong), None))

  private def rowsOf(items: List[TopicRowDto], favourites: Set[String] = Set.empty): List[TopicRow] =
    TopicRow.of(pageOf(items), favourites)

  test("everyRowOfThePageBecomesExactlyOneRow") {
    // Total, deliberately. The server has already applied every filter and its total counts what it kept, so
    // dropping a row here would make the count on screen disagree with the rows under it.
    val rows = rowsOf(List(dto("a"), dto("b"), dto("c")))
    assertEquals(rows.map(_.name), List("a", "b", "c"))
  }

  test("internalRowsCarryTheFlag") {
    val rows = rowsOf(List(dto("__consumer_offsets", internal = true), dto("orders")))
    assertEquals(rows.map(_.internal), List(true, false))
  }

  test("aPresentCountHasNoReason") {
    assertEquals(rowsOf(List(dto("orders", messages = Some(0L)))).head.missingCountReason, None)
    // Zero is a real count and must not be confused with an absent one. This is the assertion that keeps the
    // em-dash rule from swallowing a genuinely empty topic.
    assertEquals(rowsOf(List(dto("orders", messages = Some(0L)))).head.messages, Some(0L))
  }

  test("anAbsentMessageCountWithOfflinePartitionsHasAReason") {
    val row = rowsOf(List(dto("orders", messages = None, offlinePartitions = 3))).head
    val reason = row.missingCountReason.getOrElse(fail("an absent count must say why"))
    assert(reason.contains("3"), reason)
    assert(reason.contains("leader"), reason)
  }

  test("theReasonIsSingularForOneOfflinePartition") {
    val reason = rowsOf(List(dto("orders", messages = None, offlinePartitions = 1))).head.missingCountReason
    assertEquals(reason, Some("No count: 1 partition has no leader"))
  }

  test("anAbsentMessageCountWithNoOfflinePartitionsHasADifferentReason") {
    // Two absences, two explanations, because they call for two different actions: a broken cluster, or a
    // broker that would not report offsets — usually a permission or a version.
    val offline = rowsOf(List(dto("a", messages = None, offlinePartitions = 2))).head.missingCountReason
    val silent = rowsOf(List(dto("b", messages = None, offlinePartitions = 0))).head.missingCountReason
    assert(silent.exists(_.contains("offsets")), silent.toString)
    assertNotEquals(offline, silent)
  }

  test("anAbsentReplicationFactorStaysAbsent") {
    // It means the topic's partitions disagree during a reassignment. A number here would be a guess at
    // which partition to believe.
    assertEquals(rowsOf(List(dto("orders", replicationFactor = None))).head.replicationFactor, None)
  }

  test("favouritesAreMarkedFromTheSetAndNotFromTheServer") {
    val rows = rowsOf(List(dto("a"), dto("b")), favourites = Set("b", "not-on-this-page"))
    assertEquals(rows.map(_.favourite), List(false, true))
  }

  // --- Pinning ---------------------------------------------------------------------------------

  private given Arbitrary[List[TopicRow]] = Arbitrary(
    for {
      names <- Gen.listOfN(12, Gen.alphaLowerStr.suchThat(_.nonEmpty))
      flags <- Gen.listOfN(12, Arbitrary.arbitrary[Boolean])
    } yield names.distinct
      .zip(flags)
      .map((name, favourite) =>
        TopicRow(name, false, 1, Some(1), 0, 0, Some(1L), Some(1L), favourite = favourite)
      )
  )

  property("favouritesArePinnedWithinThePage") {
    forAll { (rows: List[TopicRow]) =>
      val pinned = TopicRow.pin(rows)
      val flags = pinned.map(_.favourite)
      // No unfavourited row appears before a favourited one.
      !flags.sliding(2).exists { case Seq(first, second) => !first && second; case _ => false }
    }
  }

  property("pinningKeepsEveryRowExactlyOnce") {
    forAll { (rows: List[TopicRow]) =>
      TopicRow.pin(rows).sortBy(_.name) == rows.sortBy(_.name)
    }
  }

  property("pinningIsStable") {
    // Within each group the server's order survives. Without stability a re-render could shuffle rows that
    // nothing had changed, and a user reading a list would lose their place for no reason.
    forAll { (rows: List[TopicRow]) =>
      val pinned = TopicRow.pin(rows)
      pinned.filter(_.favourite).map(_.name) == rows.filter(_.favourite).map(_.name) &&
      pinned.filterNot(_.favourite).map(_.name) == rows.filterNot(_.favourite).map(_.name)
    }
  }

  property("pinningIsIdempotent") {
    forAll { (rows: List[TopicRow]) =>
      TopicRow.pin(TopicRow.pin(rows)) == TopicRow.pin(rows)
    }
  }
}
