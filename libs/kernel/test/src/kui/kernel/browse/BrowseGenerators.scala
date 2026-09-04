package kui.kernel.browse

import scala.concurrent.duration.{DurationLong, FiniteDuration}

import kui.kernel.{Offset, OffsetRange, PartitionId}
import org.scalacheck.{Arbitrary, Gen}

/** ScalaCheck generators for the browse vocabulary.
  *
  * They live beside the types rather than in `libs/testkit` so that they stay correct when the types change:
  * a generator one module away is a generator that keeps compiling while it stops being representative.
  * `libs/testkit` re-exports them if another module needs them (MSG-042).
  */
object BrowseGenerators {

  /** Offsets stay well inside `Long` so that a property can add to one without arranging an overflow it was
    * not trying to test. The window arithmetic's saturation is tested with explicit extremes instead.
    */
  val offset: Gen[Offset] = Gen.chooseNum(0L, 1000000L).map(Offset.unsafe)

  val partitionId: Gen[PartitionId] = Gen.chooseNum(0, 64).map(PartitionId.unsafe)

  val offsetRange: Gen[OffsetRange] =
    for {
      begin <- Gen.chooseNum(0L, 1000000L)
      width <- Gen.chooseNum(0L, 5000L)
    } yield OffsetRange.from(Offset.unsafe(begin), Offset.unsafe(begin + width)).toOption.get

  val direction: Gen[Direction] = Gen.oneOf(Direction.All)

  val isolationLevel: Gen[IsolationLevel] = Gen.oneOf(IsolationLevel.All)

  val seekMode: Gen[SeekMode] =
    Gen.oneOf(
      Gen.const(SeekMode.Beginning),
      Gen.const(SeekMode.Latest),
      offset.map(SeekMode.AtOffset(_)),
      Gen.mapOf(Gen.zip(partitionId, offset)).map(SeekMode.AtOffsets(_)),
      Gen.chooseNum(0L, 4102444800000L).map(SeekMode.AtTimestamp(_))
    )

  val pollBudget: Gen[PollBudget] =
    for {
      records <- Gen.chooseNum(1, 10000)
      bytes <- Gen.chooseNum(1L, 100L * 1024L * 1024L)
      seconds <- Gen.chooseNum(1L, 600L)
    } yield PollBudget.unsafe(records, bytes, seconds.seconds, None)

  val finiteDuration: Gen[FiniteDuration] = Gen.chooseNum(0L, 600L).map(_.seconds)

  given Arbitrary[Offset] = Arbitrary(offset)
  given Arbitrary[PartitionId] = Arbitrary(partitionId)
  given Arbitrary[Direction] = Arbitrary(direction)
  given Arbitrary[IsolationLevel] = Arbitrary(isolationLevel)
  given Arbitrary[SeekMode] = Arbitrary(seekMode)
  given Arbitrary[PollBudget] = Arbitrary(pollBudget)
}
