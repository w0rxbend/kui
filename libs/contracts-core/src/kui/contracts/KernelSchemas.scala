package kui.contracts

import sttp.tapir.CodecFormat.TextPlain
import sttp.tapir.{Codec as TapirCodec, DecodeResult, Schema}

import kui.kernel.*

/** The Tapir half of the kernel's wire form: what an identifier looks like in generated documentation, and
  * how it is read out of a path or a query string.
  *
  * The path codecs matter more than they look. Without one, `/clusters/{clusterId}` takes a `String` and the
  * endpoint validates it by hand — or, more often, does not, and an invalid id reaches a Kafka client and
  * comes back as a 500. With one, a malformed id is refused by the transport, with the same message the smart
  * constructor would have given, and every endpoint gets that for free.
  */
/** The reason a path or query parameter was refused, carried through Tapir's decode failure.
  *
  * Tapir's own `DecodeResult.Error` takes a `Throwable`, so the kernel's `ValidationError` travels inside
  * one. `libs/http`'s interceptor (HTTP-001) matches on this type to render the failure as a `KUI-VALIDATION`
  * envelope with the offending field named, rather than as a generic 400 with no detail.
  */
final case class KernelDecodeFailure(error: ValidationError) extends Exception(error.message)

object KernelSchemas {

  /** Turns a smart constructor's answer into Tapir's, keeping the validation message so that `libs/http` can
    * render it as the `details` of a `KUI-VALIDATION` envelope.
    */
  private def decoded[A](raw: String, result: Either[ValidationError, A]): DecodeResult[A] =
    result match {
      case Right(value) => DecodeResult.Value(value)
      case Left(error) => DecodeResult.Error(raw, KernelDecodeFailure(error))
    }

  given Schema[ClusterId] =
    Schema.string[ClusterId].description("a lowercase slug: ^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$")

  /** Path and query codec: `/clusters/{clusterId}` and friends. A value the smart constructor refuses becomes
    * a decode failure carrying the validation message, so the endpoint answers `400 KUI-VALIDATION` instead
    * of failing somewhere deeper as a 500.
    */
  given TapirCodec[String, ClusterId, TextPlain] =
    TapirCodec.string.mapDecode(raw => decoded(raw, ClusterId.from(raw)))(_.value)

  given Schema[KafkaClusterId] =
    Schema.string[KafkaClusterId].description("the cluster id the brokers report")

  /** Path and query codec: `/clusters/{clusterId}` and friends. A value the smart constructor refuses becomes
    * a decode failure carrying the validation message, so the endpoint answers `400 KUI-VALIDATION` instead
    * of failing somewhere deeper as a 500.
    */
  given TapirCodec[String, KafkaClusterId, TextPlain] =
    TapirCodec.string.mapDecode(raw => decoded(raw, KafkaClusterId.from(raw)))(_.value)

  given Schema[TopicName] = Schema
    .string[TopicName]
    .description("a Kafka topic name: 1-249 characters from [a-zA-Z0-9._-], not '.' or '..'")

  /** Path and query codec: `/clusters/{clusterId}` and friends. A value the smart constructor refuses becomes
    * a decode failure carrying the validation message, so the endpoint answers `400 KUI-VALIDATION` instead
    * of failing somewhere deeper as a 500.
    */
  given TapirCodec[String, TopicName, TextPlain] =
    TapirCodec.string.mapDecode(raw => decoded(raw, TopicName.from(raw)))(_.value)

  given Schema[GroupId] = Schema.string[GroupId].description("a consumer group id")

  /** Path and query codec: `/clusters/{clusterId}` and friends. A value the smart constructor refuses becomes
    * a decode failure carrying the validation message, so the endpoint answers `400 KUI-VALIDATION` instead
    * of failing somewhere deeper as a 500.
    */
  given TapirCodec[String, GroupId, TextPlain] =
    TapirCodec.string.mapDecode(raw => decoded(raw, GroupId.from(raw)))(_.value)

  given Schema[Subject] = Schema.string[Subject].description("a schema registry subject")

  /** Path and query codec: `/clusters/{clusterId}` and friends. A value the smart constructor refuses becomes
    * a decode failure carrying the validation message, so the endpoint answers `400 KUI-VALIDATION` instead
    * of failing somewhere deeper as a 500.
    */
  given TapirCodec[String, Subject, TextPlain] =
    TapirCodec.string.mapDecode(raw => decoded(raw, Subject.from(raw)))(_.value)

  given Schema[ConnectName] =
    Schema.string[ConnectName].description("the configured name of a Kafka Connect cluster")

  /** Path and query codec: `/clusters/{clusterId}` and friends. A value the smart constructor refuses becomes
    * a decode failure carrying the validation message, so the endpoint answers `400 KUI-VALIDATION` instead
    * of failing somewhere deeper as a 500.
    */
  given TapirCodec[String, ConnectName, TextPlain] =
    TapirCodec.string.mapDecode(raw => decoded(raw, ConnectName.from(raw)))(_.value)

  given Schema[ConnectorName] = Schema.string[ConnectorName].description("a connector name")

  /** Path and query codec: `/clusters/{clusterId}` and friends. A value the smart constructor refuses becomes
    * a decode failure carrying the validation message, so the endpoint answers `400 KUI-VALIDATION` instead
    * of failing somewhere deeper as a 500.
    */
  given TapirCodec[String, ConnectorName, TextPlain] =
    TapirCodec.string.mapDecode(raw => decoded(raw, ConnectorName.from(raw)))(_.value)

  given Schema[CorrelationId] =
    Schema.string[CorrelationId].description("the id that ties a response to its log lines")

  /** Path and query codec: `/clusters/{clusterId}` and friends. A value the smart constructor refuses becomes
    * a decode failure carrying the validation message, so the endpoint answers `400 KUI-VALIDATION` instead
    * of failing somewhere deeper as a 500.
    */
  given TapirCodec[String, CorrelationId, TextPlain] =
    TapirCodec.string.mapDecode(raw => decoded(raw, CorrelationId.from(raw)))(_.value)

  given Schema[ServiceId] =
    Schema.string[ServiceId].description("a KUI service: cluster, topic, message, ...")

  /** Path and query codec: `/clusters/{clusterId}` and friends. A value the smart constructor refuses becomes
    * a decode failure carrying the validation message, so the endpoint answers `400 KUI-VALIDATION` instead
    * of failing somewhere deeper as a 500.
    */
  given TapirCodec[String, ServiceId, TextPlain] =
    TapirCodec.string.mapDecode(raw => decoded(raw, ServiceId.from(raw)))(_.value)

  given Schema[UserName] = Schema.string[UserName].description("an authenticated user or machine account")

  /** Path and query codec: `/clusters/{clusterId}` and friends. A value the smart constructor refuses becomes
    * a decode failure carrying the validation message, so the endpoint answers `400 KUI-VALIDATION` instead
    * of failing somewhere deeper as a 500.
    */
  given TapirCodec[String, UserName, TextPlain] =
    TapirCodec.string.mapDecode(raw => decoded(raw, UserName.from(raw)))(_.value)

  given Schema[RoleName] = Schema.string[RoleName].description("an RBAC role name")

  /** Path and query codec: `/clusters/{clusterId}` and friends. A value the smart constructor refuses becomes
    * a decode failure carrying the validation message, so the endpoint answers `400 KUI-VALIDATION` instead
    * of failing somewhere deeper as a 500.
    */
  given TapirCodec[String, RoleName, TextPlain] =
    TapirCodec.string.mapDecode(raw => decoded(raw, RoleName.from(raw)))(_.value)

  given Schema[Host] = Schema.string[Host].description("a host name or IP address")

  /** Path and query codec: `/clusters/{clusterId}` and friends. A value the smart constructor refuses becomes
    * a decode failure carrying the validation message, so the endpoint answers `400 KUI-VALIDATION` instead
    * of failing somewhere deeper as a 500.
    */
  given TapirCodec[String, Host, TextPlain] =
    TapirCodec.string.mapDecode(raw => decoded(raw, Host.from(raw)))(_.value)

  given Schema[PartitionId] = Schema.schemaForInt.as[PartitionId].description("a partition number, from 0")

  given TapirCodec[String, PartitionId, TextPlain] =
    TapirCodec.int.mapDecode(raw => decoded(raw.toString, PartitionId.from(raw)))(_.value)

  given Schema[BrokerId] = Schema.schemaForInt.as[BrokerId].description("a broker node id, from 0")

  given TapirCodec[String, BrokerId, TextPlain] =
    TapirCodec.int.mapDecode(raw => decoded(raw.toString, BrokerId.from(raw)))(_.value)

  given Schema[SchemaId] = Schema.schemaForInt.as[SchemaId].description("a schema registry schema id")

  given TapirCodec[String, SchemaId, TextPlain] =
    TapirCodec.int.mapDecode(raw => decoded(raw.toString, SchemaId.from(raw)))(_.value)

  given Schema[TaskId] = Schema.schemaForInt.as[TaskId].description("a connector task number, from 0")

  given TapirCodec[String, TaskId, TextPlain] =
    TapirCodec.int.mapDecode(raw => decoded(raw.toString, TaskId.from(raw)))(_.value)

  given Schema[Port] = Schema.schemaForInt.as[Port].description("a TCP port, 1-65535")

  given TapirCodec[String, Port, TextPlain] =
    TapirCodec.int.mapDecode(raw => decoded(raw.toString, Port.from(raw)))(_.value)

  given Schema[PositiveInt] =
    Schema.schemaForInt.as[PositiveInt].description("a whole number greater than zero")

  given TapirCodec[String, PositiveInt, TextPlain] =
    TapirCodec.int.mapDecode(raw => decoded(raw.toString, PositiveInt.from(raw)))(_.value)

  given Schema[Offset] =
    Schema.schemaForLong.as[Offset].description("a record's position in a partition, from 0")

  given TapirCodec[String, Offset, TextPlain] =
    TapirCodec.long.mapDecode(raw => decoded(raw.toString, Offset.from(raw)))(_.value)

  given Schema[ByteSize] = Schema.schemaForLong.as[ByteSize].description("a number of bytes")

  given TapirCodec[String, ByteSize, TextPlain] =
    TapirCodec.long.mapDecode(raw => decoded(raw.toString, ByteSize.from(raw)))(_.value)

  given Schema[PageToken] = Schema.string[PageToken].description("An opaque continuation token")

  given Schema[SortOrder] = Schema.string[SortOrder].description("asc or desc")

  given TapirCodec[String, SortOrder, TextPlain] = TapirCodec.string.mapDecode(raw =>
    decoded(
      raw,
      SortOrder
        .fromWire(raw)
        .toRight(ValidationError.Format("sort", "'asc' or 'desc'", raw))
    )
  )(_.wire)

  given [A: Schema] => Schema[Page[A]] = Schema
    .derived[Page[A]]
    .description("One page of a list, with the total the page was cut from")
}
