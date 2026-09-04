package kui.message.contract

import cats.Order
import cats.data.NonEmptySet
import sttp.tapir.CodecFormat.TextPlain
import sttp.tapir.{Codec, DecodeResult, Schema}

import kui.kernel.browse.{Direction, IsolationLevel, SeekMode}
import kui.kernel.{Offset, PartitionId}

/** The query-parameter grammar of a browse, in one place.
  *
  * The stream endpoint and the page endpoint accept the same seek, the same direction, the same partition
  * list. They must parse them the same way — a page that started somewhere the stream would not is a page
  * whose "load more" moves the user sideways — so the parsing is written once and both endpoints use these
  * codecs rather than their own.
  *
  * Every failure here is a Tapir decode failure carrying a message that names the parameter and says what was
  * expected. That matters more than it looks: a decode failure becomes a `400` with a field name, where a
  * value that parsed loosely and then failed three layers down becomes a `500` an operator has to read a
  * stack trace to understand.
  *
  * ==The `seekTo` grammar==
  *
  * One repeated parameter, because a seek is either one thing or one thing per partition, and two parameters
  * that must not both be present is a shape every client gets wrong once:
  *
  * {{{
  * seekTo=beginning                    the oldest record each partition still has
  * seekTo=latest                       the newest; reads backwards by default
  * seekTo=offset::41284                that offset on every selected partition
  * seekTo=timestamp::1767225600000     the first record at or after that epoch millisecond
  * seekTo=0::100&seekTo=3::250         offset 100 on partition 0, 250 on partition 3
  * }}}
  *
  * The per-partition form is KUI's addition (DEVPLAN §10 D10). The reference product's second API version
  * dropped it, which left a user unable to express in a request what its own cursors express internally: a
  * cursor resumes each partition at its own offset, so a "start here" that could not do the same could never
  * be written down by hand or shared as a link.
  *
  * The forms do not mix. `beginning` with a per-partition pair is refused rather than resolved by a
  * precedence rule, because a precedence rule is a thing a caller has to know and a refusal is a thing they
  * are told.
  */
object BrowseParams {

  /** The separator between a seek's two halves. Two colons, not one, so that a future timestamp form written
    * as an ISO instant — which contains single colons — needs no escaping and no change here.
    */
  private val Separator: String = "::"

  private val Beginning: String = "beginning"
  private val Latest: String = "latest"
  private val OffsetPrefix: String = "offset"
  private val TimestampPrefix: String = "timestamp"

  /** Partition ids are ordered so that a `NonEmptySet` of them can exist, and so that the set a client sends
    * in any order is the same set on the server.
    */
  given Order[PartitionId] = Order.by(_.value)

  /** The seek, from the repeated `seekTo` parameter.
    *
    * An empty list decodes as missing rather than as a default, so that the *endpoint* decides what "no seek
    * given" means. Defaulting here would put that decision in a codec, where neither the OpenAPI document nor
    * a reader of the endpoint could see it.
    */
  val seekModeCodec: Codec[List[String], SeekMode, TextPlain] =
    Codec
      .id[List[String], TextPlain](TextPlain(), Schema.schemaForString.asIterable[List])
      .mapDecode(decodeSeek)(encodeSeek)

  /** The same, optional: the shape an endpoint uses for a parameter a caller may leave out. */
  val optionalSeekModeCodec: Codec[List[String], Option[SeekMode], TextPlain] =
    Codec
      .id[List[String], TextPlain](TextPlain(), Schema.schemaForString.asIterable[List])
      .mapDecode {
        case Nil => DecodeResult.Value(None)
        case raw => decodeSeek(raw).map(Some(_))
      }(_.toList.flatMap(encodeSeek))

  /** Which partitions to read, from the repeated `partition` parameter.
    *
    * Absent means every partition, which is what a person opening a topic wants. An explicit empty value is a
    * failure rather than "all": a client whose partition filter produced nothing is a client with a bug, and
    * silently reading the whole topic instead is the most expensive possible way to hide it.
    *
    * Duplicates are refused rather than folded away. `partition=0&partition=0` is a request nobody means, and
    * a request that means something the caller did not intend is worth one round trip to correct.
    */
  val partitionsCodec: Codec[List[String], Option[NonEmptySet[PartitionId]], TextPlain] =
    Codec
      .id[List[String], TextPlain](TextPlain(), Schema.schemaForString.asIterable[List])
      .mapDecode(decodePartitions)(encodePartitions)

  /** `FORWARD` or `BACKWARD`, case-insensitively, because a hand-written URL should not fail on capitals. */
  given directionCodec: Codec[String, Direction, TextPlain] =
    Codec.string.mapDecode(raw =>
      Direction.from(raw.trim.toUpperCase) match {
        case Right(direction) => DecodeResult.Value(direction)
        case Left(error) => DecodeResult.Error(raw, new IllegalArgumentException(error.message))
      }
    )(_.wire)

  /** `READ_UNCOMMITTED` or `READ_COMMITTED`. */
  given isolationCodec: Codec[String, IsolationLevel, TextPlain] =
    Codec.string.mapDecode(raw =>
      IsolationLevel.from(raw.trim.toUpperCase) match {
        case Right(level) => DecodeResult.Value(level)
        case Left(error) => DecodeResult.Error(raw, new IllegalArgumentException(error.message))
      }
    )(_.wire)

  // -----------------------------------------------------------------------------------------------

  private def decodeSeek(raw: List[String]): DecodeResult[SeekMode] = {
    val entries = raw.map(_.trim).filter(_.nonEmpty)

    entries match {
      case Nil => DecodeResult.Missing

      case single :: Nil if single.equalsIgnoreCase(Beginning) => DecodeResult.Value(SeekMode.Beginning)
      case single :: Nil if single.equalsIgnoreCase(Latest) => DecodeResult.Value(SeekMode.Latest)

      case single :: Nil if prefixOf(single).contains(OffsetPrefix) =>
        nonNegativeLong(single, suffixOf(single)).map(SeekMode.AtOffset.apply.compose(Offset.unsafe))

      case single :: Nil if prefixOf(single).contains(TimestampPrefix) =>
        nonNegativeLong(single, suffixOf(single)).map(SeekMode.AtTimestamp.apply)

      case pairs if pairs.forall(isPair) => perPartition(pairs)

      case mixed =>
        failure(
          mixed.mkString(", "),
          "seekTo must be one of 'beginning', 'latest', 'offset::<n>', 'timestamp::<millis>', " +
            "or one or more '<partition>::<offset>' pairs, and the forms may not be mixed"
        )
    }
  }

  private def perPartition(pairs: List[String]): DecodeResult[SeekMode] =
    pairs
      .foldLeft[DecodeResult[Map[PartitionId, Offset]]](DecodeResult.Value(Map.empty)) { (acc, entry) =>
        acc.flatMap { seen =>
          val partition = nonNegativeInt(entry, prefixOf(entry).getOrElse(""))
          val offset = nonNegativeLong(entry, suffixOf(entry))

          partition.flatMap { id =>
            if seen.contains(PartitionId.unsafe(id)) then failure(entry, s"seekTo names partition $id twice")
            else offset.map(value => seen.updated(PartitionId.unsafe(id), Offset.unsafe(value)))
          }
        }
      }
      .map(SeekMode.AtOffsets.apply)

  private def encodeSeek(mode: SeekMode): List[String] =
    mode match {
      case SeekMode.Beginning => List(Beginning)
      case SeekMode.Latest => List(Latest)
      case SeekMode.AtOffset(offset) => List(s"$OffsetPrefix$Separator${offset.value}")
      case SeekMode.AtTimestamp(millis) => List(s"$TimestampPrefix$Separator$millis")
      case SeekMode.AtOffsets(perPartition) =>
        // Sorted, so that the same seek always produces the same query string. Two URLs that differ only in
        // the order of a set are two cache entries and two things to compare by eye.
        perPartition.toList
          .sortBy((partition, _) => partition.value)
          .map((partition, offset) => s"${partition.value}$Separator${offset.value}")
    }

  private def decodePartitions(raw: List[String]): DecodeResult[Option[NonEmptySet[PartitionId]]] = {
    // Both spellings are accepted — `partition=0&partition=3` and `partition=0,3` — because a person writing
    // a URL reaches for one and a generated client emits the other, and refusing either would be a difference
    // with no meaning behind it.
    val entries = raw.flatMap(_.split(',').toList).map(_.trim).filter(_.nonEmpty)

    if raw.isEmpty then DecodeResult.Value(None)
    else if entries.isEmpty then
      failure(
        raw.mkString(","),
        "partition was given but names no partition; omit it to read every partition"
      )
    else
      entries
        .foldLeft[DecodeResult[List[PartitionId]]](DecodeResult.Value(Nil)) { (acc, entry) =>
          acc.flatMap { seen =>
            nonNegativeInt(entry, entry).flatMap { id =>
              val partition = PartitionId.unsafe(id)
              if seen.contains(partition) then failure(entry, s"partition $id is named twice")
              else DecodeResult.Value(seen :+ partition)
            }
          }
        }
        .map(ids => NonEmptySet.of(ids.head, ids.tail*))
        .map(Some(_))
  }

  private def encodePartitions(partitions: Option[NonEmptySet[PartitionId]]): List[String] =
    partitions.toList.flatMap(_.toSortedSet.toList.map(_.value.toString))

  private def prefixOf(entry: String): Option[String] =
    entry.indexOf(Separator) match {
      case -1 => None
      case at => Some(entry.substring(0, at))
    }

  private def suffixOf(entry: String): String =
    entry.indexOf(Separator) match {
      case -1 => ""
      case at => entry.substring(at + Separator.length)
    }

  private def isPair(entry: String): Boolean =
    prefixOf(entry).exists(prefix => prefix.nonEmpty && prefix.forall(_.isDigit))

  private def nonNegativeInt(entry: String, raw: String): DecodeResult[Int] =
    raw.toIntOption match {
      case Some(value) if value >= 0 => DecodeResult.Value(value)
      case _ => failure(entry, s"'$raw' is not a partition number (a whole number from 0)")
    }

  private def nonNegativeLong(entry: String, raw: String): DecodeResult[Long] =
    raw.toLongOption match {
      case Some(value) if value >= 0 => DecodeResult.Value(value)
      case _ => failure(entry, s"'$raw' is not a whole number from 0")
    }

  private def failure[A](raw: String, why: String): DecodeResult[A] =
    DecodeResult.Error(raw, new IllegalArgumentException(why))
}
