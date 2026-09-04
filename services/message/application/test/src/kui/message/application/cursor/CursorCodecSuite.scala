package kui.message.application.cursor

import java.nio.charset.StandardCharsets
import java.time.Instant
import scala.concurrent.duration.DurationInt

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import kui.kernel.browse.{Direction, IsolationLevel, SeekMode}
import kui.kernel.error.ErrorCode
import kui.kernel.serde.SerdeName
import kui.kernel.{ClusterId, Offset, PartitionId, Secret, TopicName}
import kui.message.domain.{BrowseRequest, MessageGenerators}
import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import org.scalacheck.{Arbitrary, Gen}

/** The cursor exists so that "next page" works on a replica that has never heard of this browse.
  *
  * That is one property — a codec built from the same key decodes what another one encoded — and it is the
  * one the reference product's process-local cache could not have. Everything else here defends it: the
  * signature, the version, the expiry, the binding, and the one piece of arithmetic that duplicates or skips
  * exactly one record per page when it is wrong.
  */
final class CursorCodecSuite extends ScalaCheckSuite {

  private val key = Secret("a-shared-signing-key-for-every-replica".getBytes(StandardCharsets.UTF_8))
  private val otherKey = Secret("a-different-key-entirely".getBytes(StandardCharsets.UTF_8))

  private val cluster = ClusterId.unsafe("prod-eu")
  private val topic = TopicName.unsafe("orders")
  private val otherTopic = TopicName.unsafe("payments")
  private val now = Instant.parse("2026-09-04T12:00:00Z")

  private val codec = CursorCodec.hmacSha256[IO](key)

  /** A second instance with the same key: a different replica, in every way that matters here. */
  private val secondReplica = CursorCodec.hmacSha256[IO](key)

  private def cursorOf(
      offsets: Map[PartitionId, Offset],
      direction: Direction = Direction.Forward,
      expiresAt: Instant = now.plusSeconds(3600)
  ): BrowseCursor =
    BrowseCursor(
      v = BrowseCursor.Version,
      cluster = cluster,
      topic = topic,
      direction = direction,
      perPartitionNext = offsets,
      filterId = Some("0123456789abcdef"),
      keySerde = Some(SerdeName.String),
      valueSerde = Some(SerdeName.Json),
      limit = 100,
      isolation = IsolationLevel.ReadCommitted,
      expiresAt = expiresAt
    )

  private val partitionMaps: Gen[Map[PartitionId, Offset]] =
    Gen.oneOf(
      Gen.const(Map.empty[PartitionId, Offset]),
      Gen.const(Map(PartitionId.unsafe(0) -> Offset.unsafe(43L))),
      Gen
        .listOfN(400, Gen.chooseNum(0L, 1000000L))
        .map(_.zipWithIndex.map((offset, index) => PartitionId.unsafe(index) -> Offset.unsafe(offset)).toMap)
    )

  private given Arbitrary[Map[PartitionId, Offset]] = Arbitrary(partitionMaps)

  private def encoded(cursor: BrowseCursor): String =
    codec.encode(cursor).unsafeRunSync().fold(error => fail(s"encode refused: $error"), identity)

  private def decodedBy(instance: CursorCodec[IO], raw: String, at: Instant = now) =
    instance.decode(raw, (cluster, topic), at).unsafeRunSync()

  property("roundTripsForArbitraryPayloads") {
    forAll { (offsets: Map[PartitionId, Offset]) =>
      val cursor = cursorOf(offsets)
      assertEquals(decodedBy(codec, encoded(cursor)), Right(cursor))
    }
  }

  test("decodesOnASecondCodecInstanceWithTheSameKey") {
    // The whole reason this type exists. The reference product keys its paging state by a random id in a
    // process-local cache, so this exact assertion is one it cannot make.
    val cursor = cursorOf(Map(PartitionId.unsafe(0) -> Offset.unsafe(43L), PartitionId.unsafe(1) -> Offset.unsafe(17L)))
    assertEquals(decodedBy(secondReplica, encoded(cursor)), Right(cursor))
  }

  test("aDifferentKeyRejects") {
    val stranger = CursorCodec.hmacSha256[IO](otherKey)
    assertEquals(
      decodedBy(stranger, encoded(cursorOf(Map.empty))).swap.map(_.code),
      Right(ErrorCode.CursorInvalid)
    )
  }

  property("tamperedPayloadRejects") {
    val raw = encoded(cursorOf(Map(PartitionId.unsafe(3) -> Offset.unsafe(9L))))
    val mutations: Gen[String] =
      for {
        at <- Gen.chooseNum(0, raw.length - 1)
        replacement <- Gen.alphaNumChar.suchThat(_ != raw.charAt(at))
      } yield raw.updated(at, replacement)

    forAll(mutations) { tampered =>
      // Unpadded base64url has slack in its final character: the low bits of the last symbol are not part
      // of any output byte, so two different strings can decode to identical bytes. Such a mutation has not
      // tampered with anything and is correctly accepted; every mutation that actually changes a byte must
      // be rejected.
      if sameBytes(raw, tampered) then ()
      else assert(decodedBy(codec, tampered).isLeft, s"a tampered cursor decoded: $tampered")
    }
  }

  private def sameBytes(one: String, other: String): Boolean = {
    val decoder = java.util.Base64.getUrlDecoder
    def parts(value: String): Option[List[Seq[Byte]]] =
      value.split('.').toList match {
        case payload :: signature :: Nil =>
          scala.util.Try(List(decoder.decode(payload).toSeq, decoder.decode(signature).toSeq)).toOption
        case _ => None
      }

    (parts(one), parts(other)) match {
      case (Some(a), Some(b)) => a == b
      case _                  => false
    }
  }

  test("signatureIsVerifiedBeforeParsing") {
    // A payload that would throw if it were parsed, signed by nobody. A codec that parsed first could be
    // driven with input it never had to sign.
    val encoder = java.util.Base64.getUrlEncoder.withoutPadding
    val nonsense = encoder.encodeToString("not|a|cursor".getBytes(StandardCharsets.UTF_8))
    val unsigned = s"$nonsense.${encoder.encodeToString(Array[Byte](1, 2, 3))}"

    assertEquals(decodedBy(codec, unsigned).swap.map(_.code), Right(ErrorCode.CursorInvalid))
  }

  test("expiryIsEnforcedAndIsItsOwnCode") {
    val stale = encoded(cursorOf(Map.empty, expiresAt = now.plusSeconds(60)))

    assert(decodedBy(codec, stale, at = now).isRight)
    assertEquals(
      decodedBy(codec, stale, at = now.plusSeconds(61)).swap.map(_.code),
      Right(ErrorCode.CursorExpired)
    )
  }

  test("clusterAndTopicBindingIsEnforced") {
    val raw = encoded(cursorOf(Map.empty))

    assertEquals(
      codec.decode(raw, (cluster, otherTopic), now).unsafeRunSync().swap.map(_.code),
      Right(ErrorCode.CursorInvalid)
    )
    assertEquals(
      codec.decode(raw, (ClusterId.unsafe("staging"), topic), now).unsafeRunSync().swap.map(_.code),
      Right(ErrorCode.CursorInvalid)
    )
  }

  test("unknownVersionIsInvalidNotIgnored") {
    // Hand-built and correctly signed, but from a future release. Reading it as though the unknown field
    // were absent would start the browse somewhere the user did not ask for.
    val future = CursorCodec.render(cursorOf(Map.empty)).replaceFirst("^1\\|", "2|")
    assertEquals(CursorCodec.parse(future).swap.map(_.code), Right(ErrorCode.CursorInvalid))
  }

  test("oversizeIsRejectedAtEncodeNotTruncated") {
    val thousandPartitions =
      (0 until 1000).map(index => PartitionId.unsafe(index) -> Offset.unsafe(1234567L)).toMap
    val tiny = CursorCodec.hmacSha256[IO](key, maxBytes = 256)

    assertEquals(
      tiny.encode(cursorOf(thousandPartitions)).unsafeRunSync().swap.map(_.code),
      Right(ErrorCode.CursorTooLarge)
    )
    // and it says what to do about it, rather than only that it will not
    assert(
      tiny
        .encode(cursorOf(thousandPartitions))
        .unsafeRunSync()
        .swap
        .exists(_.message.contains("subset"))
    )
  }

  test("forwardAndBackwardBoundariesDifferByExactlyOne") {
    // The off-by-one that duplicates or skips exactly one record on every page boundary, pinned as its own
    // case. A forward page resumes *after* the last record it showed; a backward page's next window *ends*
    // where this one began, and the range is half-open, so it is the first offset seen and not one below it.
    val request = browseRequest(Direction.Forward)
    val seen = Map(PartitionId.unsafe(0) -> Offset.unsafe(99L))

    val forward = BrowseCursor.afterForward(request, seen, now, 1.hour)
    val backward = BrowseCursor.beforeBackward(browseRequest(Direction.Backward), seen, now, 1.hour)

    assertEquals(forward.perPartitionNext(PartitionId.unsafe(0)).value, 100L)
    assertEquals(backward.perPartitionNext(PartitionId.unsafe(0)).value, 99L)
  }

  test("aMintedCursorCarriesTheRequestItContinues") {
    val request = browseRequest(Direction.Forward)
    val cursor = BrowseCursor.afterForward(request, Map.empty, now, 1.hour)

    assertEquals(cursor.cluster, request.cluster)
    assertEquals(cursor.topic, request.topic)
    assertEquals(cursor.limit, request.limit)
    assertEquals(cursor.isolation, request.isolation)
    assertEquals(cursor.expiresAt, now.plusSeconds(3600))
  }

  property("keyNeverAppearsInAToStringOrAnErrorMessage") {
    val secret = "a-shared-signing-key-for-every-replica"

    forAll(Gen.alphaNumStr) { garbage =>
      val rejection = decodedBy(codec, garbage)
      assert(!rejection.toString.contains(secret), "the key reached an error value")
      assert(!key.toString.contains(secret), "the key reached a toString")
    }
  }

  private def browseRequest(direction: Direction): BrowseRequest =
    BrowseRequest
      .of(
        cluster = cluster,
        topic = topic,
        seek = SeekMode.Latest,
        direction = Some(direction),
        partitions = None,
        limit = Some(100),
        isolation = Some(IsolationLevel.ReadCommitted),
        keySerde = Some(SerdeName.String),
        valueSerde = Some(SerdeName.Json),
        stringFilter = None,
        filter = None,
        live = false
      )
      .fold(error => fail(s"could not build a request: $error"), identity)

  // Referenced so that the domain's generators are on this module's classpath for later suites.
  private val _ = MessageGenerators.topicName
}
