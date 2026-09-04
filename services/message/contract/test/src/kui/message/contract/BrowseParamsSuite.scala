package kui.message.contract

import cats.data.NonEmptySet
import munit.FunSuite
import sttp.tapir.DecodeResult

import kui.kernel.browse.{Direction, IsolationLevel, SeekMode}
import kui.message.contract.BrowseParams.given
import kui.kernel.{Offset, PartitionId}

/** That a seek written into a URL means the same thing on the stream endpoint and on the page endpoint, and
  * that a seek nobody meant is refused rather than reinterpreted.
  *
  * Runs on both platforms. The browser builds these query strings and the service parses them; a suite that
  * ran on only one of the two would be testing half a seam.
  */
final class BrowseParamsSuite extends FunSuite {

  private def seek(raw: String*): DecodeResult[SeekMode] =
    BrowseParams.seekModeCodec.decode(raw.toList)

  private def partitions(raw: String*): DecodeResult[Option[NonEmptySet[PartitionId]]] =
    BrowseParams.partitionsCodec.decode(raw.toList)

  private def partition(n: Int): PartitionId = PartitionId.unsafe(n)
  private def offset(n: Long): Offset = Offset.unsafe(n)

  private def roundTrips(mode: SeekMode): Unit = {
    val encoded = BrowseParams.seekModeCodec.encode(mode)
    assertEquals(BrowseParams.seekModeCodec.decode(encoded), DecodeResult.Value(mode), encoded.mkString("&"))
  }

  test("seekModeRoundTripsForEveryCase") {
    roundTrips(SeekMode.Beginning)
    roundTrips(SeekMode.Latest)
    roundTrips(SeekMode.AtOffset(offset(0L)))
    roundTrips(SeekMode.AtOffset(offset(41284L)))
    roundTrips(SeekMode.AtTimestamp(0L))
    roundTrips(SeekMode.AtTimestamp(1767225600000L))
    roundTrips(SeekMode.AtOffsets(Map(partition(0) -> offset(100L))))
    roundTrips(SeekMode.AtOffsets((0 until 200).map(n => partition(n) -> offset(n.toLong * 7)).toMap))
  }

  test("anEmptyPerPartitionSeekIsNotExpressibleOnTheWire") {
    // `AtOffsets(Map.empty)` is a value the kernel's ADT permits and the wire deliberately cannot carry: it
    // is a seek that names no partition, which is the same request as sending no seek at all. Encoding it
    // produces no parameter, and no parameter is `Missing` — the endpoint's default, not a silent
    // reinterpretation of an empty map as "everywhere".
    assertEquals(BrowseParams.seekModeCodec.encode(SeekMode.AtOffsets(Map.empty)), Nil)
    assertEquals(seek(), DecodeResult.Missing)
  }

  test("seekToParsesEachOfItsFiveForms") {
    assertEquals(seek("beginning"), DecodeResult.Value(SeekMode.Beginning))
    assertEquals(seek("latest"), DecodeResult.Value(SeekMode.Latest))
    assertEquals(seek("offset::41284"), DecodeResult.Value(SeekMode.AtOffset(offset(41284L))))
    assertEquals(seek("timestamp::1767225600000"), DecodeResult.Value(SeekMode.AtTimestamp(1767225600000L)))
    assertEquals(
      seek("0::100", "3::250"),
      DecodeResult.Value(SeekMode.AtOffsets(Map(partition(0) -> offset(100L), partition(3) -> offset(250L))))
    )
  }

  test("seekToIsCaseInsensitiveForTheTwoWordForms") {
    // A person types a URL by hand. Failing on a capital letter is a failure with nothing behind it.
    assertEquals(seek("BEGINNING"), DecodeResult.Value(SeekMode.Beginning))
    assertEquals(seek("Latest"), DecodeResult.Value(SeekMode.Latest))
  }

  test("aPerPartitionSeekEncodesInPartitionOrder") {
    // The same seek must always produce the same query string: two URLs differing only in the order of a set
    // are two cache entries, two log lines and two things to compare by eye.
    val mode = SeekMode.AtOffsets(Map(partition(3) -> offset(250L), partition(0) -> offset(100L)))
    assertEquals(BrowseParams.seekModeCodec.encode(mode), List("0::100", "3::250"))
  }

  test("malformedSeekIsADecodeFailureNamingTheParameter") {
    // One case per malformed shape. Each is a 400 with a sentence, not a 500 three layers down.
    List(
      "offset::banana",
      "offset::-1",
      "timestamp::",
      "timestamp::-1",
      "0::banana",
      "-1::100",
      "0::-5",
      "somewhere",
      "offset:41284" // one colon, not two
    ).foreach { raw =>
      assert(seek(raw).isInstanceOf[DecodeResult.Failure], s"'$raw' should not have decoded: ${seek(raw)}")
    }
  }

  test("theSeekFormsMayNotBeMixed") {
    // Refused rather than resolved by a precedence rule. A precedence rule is something the caller has to
    // know; a refusal is something they are told.
    assert(seek("beginning", "0::100").isInstanceOf[DecodeResult.Failure])
    assert(seek("offset::10", "0::100").isInstanceOf[DecodeResult.Failure])
    assert(seek("beginning", "latest").isInstanceOf[DecodeResult.Failure])
  }

  test("aPerPartitionSeekThatNamesAPartitionTwiceIsRefused") {
    assert(seek("0::100", "0::250").isInstanceOf[DecodeResult.Failure])
  }

  test("anAbsentSeekIsMissingAndNotADefault") {
    // The endpoint decides what "no seek given" means, so that the decision is visible in the endpoint and in
    // the generated document rather than buried in a codec.
    assertEquals(seek(), DecodeResult.Missing)
    assertEquals(BrowseParams.optionalSeekModeCodec.decode(Nil), DecodeResult.Value(None))
    assertEquals(
      BrowseParams.optionalSeekModeCodec.decode(List("latest")),
      DecodeResult.Value(Some(SeekMode.Latest))
    )
  }

  test("partitionListRejectsNegativesAndDuplicates") {
    assert(partitions("-1").isInstanceOf[DecodeResult.Failure])
    assert(partitions("0", "0").isInstanceOf[DecodeResult.Failure])
    assert(partitions("0,1,0").isInstanceOf[DecodeResult.Failure])
    assert(partitions("banana").isInstanceOf[DecodeResult.Failure])
  }

  test("anAbsentPartitionListMeansEveryPartitionButAnEmptyOneIsAFailure") {
    // A client whose partition filter produced nothing has a bug. Reading the whole topic instead would be
    // the most expensive possible way to hide it.
    assertEquals(partitions(), DecodeResult.Value(None))
    assert(partitions("").isInstanceOf[DecodeResult.Failure])
    assert(partitions(",").isInstanceOf[DecodeResult.Failure])
  }

  test("aPartitionListIsAcceptedRepeatedOrCommaSeparatedAndMeansTheSameThing") {
    val expected = DecodeResult.Value(Some(NonEmptySet.of(partition(0), partition(1), partition(3))))
    assertEquals(partitions("0", "1", "3"), expected)
    assertEquals(partitions("0,1,3"), expected)
    assertEquals(partitions("0", "1,3"), expected)
  }

  test("partitionListRoundTripsInSortedOrder") {
    val set = Some(NonEmptySet.of(partition(3), partition(0), partition(1)))
    val encoded = BrowseParams.partitionsCodec.encode(set)
    assertEquals(encoded, List("0", "1", "3"))
    assertEquals(BrowseParams.partitionsCodec.decode(encoded), DecodeResult.Value(set))
  }

  test("directionAndIsolationRoundTripAndAreCaseInsensitive") {
    Direction.All.foreach { direction =>
      assertEquals(BrowseParams.directionCodec.decode(direction.wire), DecodeResult.Value(direction))
      assertEquals(BrowseParams.directionCodec.decode(direction.wire.toLowerCase), DecodeResult.Value(direction))
      assertEquals(BrowseParams.directionCodec.encode(direction), direction.wire)
    }
    IsolationLevel.All.foreach { level =>
      assertEquals(BrowseParams.isolationCodec.decode(level.wire), DecodeResult.Value(level))
      assertEquals(BrowseParams.isolationCodec.encode(level), level.wire)
    }
    assert(BrowseParams.directionCodec.decode("sideways").isInstanceOf[DecodeResult.Failure])
    assert(BrowseParams.isolationCodec.decode("read_whatever").isInstanceOf[DecodeResult.Failure])
  }

  test("theDefaultDirectionOfASeekIsCarriedByTheKernelAndNotReinventedHere") {
    // Stated as a test because a second answer to "which way does 'latest' read?" in the contract would be a
    // second answer, and the two would differ the first time either changed.
    assertEquals(SeekMode.defaultDirection(SeekMode.Latest), Direction.Backward)
    assertEquals(SeekMode.defaultDirection(SeekMode.Beginning), Direction.Forward)
  }
}
