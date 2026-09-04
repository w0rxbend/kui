package kui.message.domain

import java.time.Instant

import kui.kernel.browse.{Direction, IsolationLevel, SeekMode}
import kui.kernel.serde.{PayloadKind, SerdeName, Target}
import kui.kernel.{ClusterId, Offset, OffsetRange, PartitionId, TopicName}
import org.scalacheck.{Arbitrary, Gen}

/** Generators for every type the message domain declares.
  *
  * They live in the domain's own test module rather than in `libs/testkit` because rule A5 forbids a library
  * depending on a service — and because a generator that lives beside its type is one that stays
  * representative when the type changes. The application, infrastructure and contract suites depend on this
  * module for them, exactly as the cluster service's suites depend on its domain's.
  */
object MessageGenerators {

  val clusterId: Gen[ClusterId] =
    Gen.oneOf("local", "staging", "prod-eu", "prod-us").map(ClusterId.unsafe)

  val topicName: Gen[TopicName] =
    Gen.oneOf("orders", "payments", "shipments", "audit.events", "dlt-orders").map(TopicName.unsafe)

  val partitionId: Gen[PartitionId] = Gen.chooseNum(0, 15).map(PartitionId.unsafe)

  val offset: Gen[Offset] = Gen.chooseNum(0L, 100000L).map(Offset.unsafe)

  val serdeName: Gen[SerdeName] =
    Gen.oneOf(SerdeName.String, SerdeName.Json, SerdeName.Int64, SerdeName.Base64, SerdeName.Fallback)

  val instant: Gen[Instant] =
    Gen.chooseNum(1600000000000L, 1900000000000L).map(Instant.ofEpochMilli)

  val seekMode: Gen[SeekMode] =
    Gen.oneOf(
      Gen.const(SeekMode.Beginning),
      Gen.const(SeekMode.Latest),
      offset.map(SeekMode.AtOffset(_)),
      Gen.mapOf(Gen.zip(partitionId, offset)).map(SeekMode.AtOffsets(_)),
      Gen.chooseNum(1600000000000L, 1900000000000L).map(SeekMode.AtTimestamp(_))
    )

  /** The seek modes a live browse is allowed to use — the only two that name no start position. */
  val liveSeekMode: Gen[SeekMode] = Gen.oneOf(SeekMode.Beginning, SeekMode.Latest)

  val matchSource: Gen[MatchSource] =
    Gen.oneOf(
      Gen.const(MatchSource.Value),
      Gen.const(MatchSource.Key),
      Gen.oneOf("orderId", "traceparent", "kafka_dlt-original-topic").map(MatchSource.Header(_))
    )

  val matchOperator: Gen[MatchOperator] = Gen.oneOf(MatchOperator.All)

  /** Never `Regex`, so that a property about windows or topics is not also a property about pattern syntax. */
  val plainMatch: Gen[TrackMatch] =
    for {
      source <- matchSource
      operator <- Gen.oneOf(MatchOperator.All.filterNot(_ == MatchOperator.Regex))
      value <- Gen.alphaNumStr.suchThat(_.nonEmpty)
    } yield TrackMatch(source, operator, value)

  val decoded: Gen[Decoded] =
    for {
      text <- Gen.alphaNumStr
      kind <- Gen.oneOf(PayloadKind.Text, PayloadKind.Json)
      serde <- serdeName
    } yield Decoded(text, kind, serde, Map.empty)

  val decodeError: Gen[DecodeError] =
    for {
      target <- Gen.oneOf(Target.Key, Target.Value)
      serde <- serdeName
      cause <- Gen.oneOf("unknown schema id 42", "not valid JSON", "unexpected end of input")
    } yield DecodeError(target, serde, cause)

  val decodedRecord: Gen[DecodedRecord] =
    for {
      partition <- partitionId
      at <- offset
      timestamp <- instant
      timestampType <- Gen.oneOf(TimestampType.All)
      key <- decoded
      value <- decoded
      headers <- Gen.listOfN(2, Gen.zip(Gen.alphaLowerStr, Gen.alphaNumStr)).map(_.map(RenderedHeader.apply))
      errors <- Gen.listOfN(1, decodeError).flatMap(list => Gen.oneOf(Nil, list))
    } yield DecodedRecord(
      partition = partition,
      offset = at,
      timestamp = timestamp,
      timestampType = timestampType,
      key = key,
      value = value,
      headers = headers,
      keySize = key.text.length,
      valueSize = value.text.length,
      headersSize = headers.map(h => h.key.length + h.value.length).sum,
      decodeErrors = errors
    )

  val offsetRange: Gen[OffsetRange] =
    for {
      begin <- Gen.chooseNum(0L, 100000L)
      width <- Gen.chooseNum(1L, 500L)
    } yield OffsetRange.from(Offset.unsafe(begin), Offset.unsafe(begin + width)).toOption.get

  given Arbitrary[ClusterId] = Arbitrary(clusterId)
  given Arbitrary[TopicName] = Arbitrary(topicName)
  given Arbitrary[PartitionId] = Arbitrary(partitionId)
  given Arbitrary[Offset] = Arbitrary(offset)
  given Arbitrary[SeekMode] = Arbitrary(seekMode)
  given Arbitrary[Direction] = Arbitrary(Gen.oneOf(Direction.All))
  given Arbitrary[IsolationLevel] = Arbitrary(Gen.oneOf(IsolationLevel.All))
  given Arbitrary[DecodedRecord] = Arbitrary(decodedRecord)
  given Arbitrary[TrackMatch] = Arbitrary(plainMatch)
}
