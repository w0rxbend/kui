package kui.contracts

import io.circe.{Codec, Decoder, Encoder}

import kui.kernel.*

/** The wire form of every kernel identifier and value object.
  *
  * This module is the only place kernel types acquire codecs (ADR-007). The kernel itself has no JSON
  * dependency, which is what lets a domain module use `TopicName` without acquiring an opinion about how a
  * topic name is serialised — and what stops a wire format change from reaching into business rules.
  *
  * Two rules bind every identifier here and every contract module that follows:
  *
  *   - an identifier encodes as its underlying primitive, never as an object. `"orders"`, not
  *     `{"value": "orders"}`. The wrapper is a compile-time device and has no business on the wire.
  *   - a 64-bit identifier (`Offset`, `ByteSize`) is a JSON number, with one documented limit: a browser's
  *     `JSON.parse` reproduces integers exactly only up to 2^53 - 1, and loses the last digits above it.
  *     Kafka offsets and byte counts do not reach that magnitude, so KUI does not pay the cost of
  *     stringifying every offset; `KernelCodecsSuite` asserts the boundary on both platforms so the limit
  *     stays a known one.
  *   - decoding runs the same smart constructor as everything else, so a topic name that Kafka would refuse
  *     cannot enter the system through a JSON body any more than through a function call. A rejection carries
  *     the validation message, which `libs/http` renders as `KUI-VALIDATION`.
  *
  * The codecs are written out one per type rather than derived from a shared helper, because a derived one
  * hides which types have a wire form, and "what does this look like on the wire" should be answerable by
  * reading one file.
  */
object KernelCodecs {

  given Codec[ClusterId] = Codec.from(
    Decoder[String].emap(ClusterId.from(_).left.map(_.message)),
    Encoder[String].contramap(_.value)
  )

  given Codec[KafkaClusterId] = Codec.from(
    Decoder[String].emap(KafkaClusterId.from(_).left.map(_.message)),
    Encoder[String].contramap(_.value)
  )

  given Codec[TopicName] = Codec.from(
    Decoder[String].emap(TopicName.from(_).left.map(_.message)),
    Encoder[String].contramap(_.value)
  )

  given Codec[GroupId] = Codec.from(
    Decoder[String].emap(GroupId.from(_).left.map(_.message)),
    Encoder[String].contramap(_.value)
  )

  given Codec[Subject] = Codec.from(
    Decoder[String].emap(Subject.from(_).left.map(_.message)),
    Encoder[String].contramap(_.value)
  )

  given Codec[ConnectName] = Codec.from(
    Decoder[String].emap(ConnectName.from(_).left.map(_.message)),
    Encoder[String].contramap(_.value)
  )

  given Codec[ConnectorName] = Codec.from(
    Decoder[String].emap(ConnectorName.from(_).left.map(_.message)),
    Encoder[String].contramap(_.value)
  )

  given Codec[CorrelationId] = Codec.from(
    Decoder[String].emap(CorrelationId.from(_).left.map(_.message)),
    Encoder[String].contramap(_.value)
  )

  given Codec[ServiceId] = Codec.from(
    Decoder[String].emap(ServiceId.from(_).left.map(_.message)),
    Encoder[String].contramap(_.value)
  )

  given Codec[UserName] = Codec.from(
    Decoder[String].emap(UserName.from(_).left.map(_.message)),
    Encoder[String].contramap(_.value)
  )

  given Codec[RoleName] = Codec.from(
    Decoder[String].emap(RoleName.from(_).left.map(_.message)),
    Encoder[String].contramap(_.value)
  )

  given Codec[Host] = Codec.from(
    Decoder[String].emap(Host.from(_).left.map(_.message)),
    Encoder[String].contramap(_.value)
  )

  given Codec[PartitionId] = Codec.from(
    Decoder[Int].emap(PartitionId.from(_).left.map(_.message)),
    Encoder[Int].contramap(_.value)
  )

  given Codec[BrokerId] = Codec.from(
    Decoder[Int].emap(BrokerId.from(_).left.map(_.message)),
    Encoder[Int].contramap(_.value)
  )

  given Codec[SchemaId] = Codec.from(
    Decoder[Int].emap(SchemaId.from(_).left.map(_.message)),
    Encoder[Int].contramap(_.value)
  )

  given Codec[TaskId] = Codec.from(
    Decoder[Int].emap(TaskId.from(_).left.map(_.message)),
    Encoder[Int].contramap(_.value)
  )

  given Codec[Port] = Codec.from(
    Decoder[Int].emap(Port.from(_).left.map(_.message)),
    Encoder[Int].contramap(_.value)
  )

  given Codec[PositiveInt] = Codec.from(
    Decoder[Int].emap(PositiveInt.from(_).left.map(_.message)),
    Encoder[Int].contramap(_.value)
  )

  given Codec[Offset] = Codec.from(
    Decoder[Long].emap(Offset.from(_).left.map(_.message)),
    Encoder[Long].contramap(_.value)
  )

  given Codec[ByteSize] = Codec.from(
    Decoder[Long].emap(ByteSize.from(_).left.map(_.message)),
    Encoder[Long].contramap(_.value)
  )

  given Codec[PageToken] = Codec.from(
    Decoder[String].emap(PageToken.from(_).left.map(_.message)),
    Encoder[String].contramap(_.value)
  )

  /** Lowercase on the wire — `asc`, `desc` — never the ordinal and never the case name. An ordinal is
    * unreadable in a URL and breaks the moment a case is reordered.
    */
  given Codec[SortOrder] = Codec.from(
    Decoder[String].emap(raw => SortOrder.fromWire(raw).toRight(s"'$raw' is not a sort order")),
    Encoder[String].contramap(_.wire)
  )

  given Codec[TopicPartition] = Codec.from(
    Decoder.instance(cursor =>
      for {
        topic <- cursor.get[TopicName]("topic")
        partition <- cursor.get[PartitionId]("partition")
      } yield TopicPartition(topic, partition)
    ),
    Encoder.instance(tp =>
      io.circe.Json.obj(
        "topic" -> summon[Encoder[TopicName]](tp.topic),
        "partition" -> summon[Encoder[PartitionId]](tp.partition)
      )
    )
  )

  /** A page of anything, given a codec for the thing.
    *
    * `totalItems` and `nextPageToken` are encoded as `null` when absent rather than omitted, because a client
    * reading `page.totalItems` should get `null` — "not counted" — rather than `undefined`, which is
    * indistinguishable from a typo in the field name.
    */
  given [A: {Encoder, Decoder}] => Codec[Page[A]] = Codec.from(
    Decoder.instance(cursor =>
      for {
        items <- cursor.getOrElse[List[A]]("items")(Nil)
        page <- cursor.get[Int]("page")
        pageSize <- cursor.get[Int]("pageSize")
        totalItems <- cursor.get[Option[Long]]("totalItems")
        nextPageToken <- cursor.get[Option[PageToken]]("nextPageToken")
      } yield Page(items, page, pageSize, totalItems, nextPageToken)
    ),
    Encoder.instance(page =>
      io.circe.Json.obj(
        "items" -> summon[Encoder[List[A]]](page.items),
        "page" -> io.circe.Json.fromInt(page.page),
        "pageSize" -> io.circe.Json.fromInt(page.pageSize),
        "totalItems" -> summon[Encoder[Option[Long]]](page.totalItems),
        "nextPageToken" -> summon[Encoder[Option[PageToken]]](page.nextPageToken)
      )
    )
  )
}
