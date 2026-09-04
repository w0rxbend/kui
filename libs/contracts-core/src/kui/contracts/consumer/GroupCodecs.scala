package kui.contracts.consumer

import io.circe.{Codec, Decoder, Encoder}
import sttp.tapir.CodecFormat.TextPlain
import sttp.tapir.{Codec as TapirCodec, DecodeResult, Schema}

import kui.contracts.KernelDecodeFailure
import kui.kernel.ValidationError
import kui.kernel.group.{GroupProtocol, GroupState, LagAnomaly, ResetTarget}

/** The wire form of `libs/kernel`'s consumer-group vocabulary.
  *
  * The vocabulary itself — the six group states, the three protocols, the six reset modes, the four lag
  * anomalies — is declared once in `kui.kernel.group` (M4 DEVPLAN §10 D1). This object gives those
  * declarations their JSON and their query-string form, and it does so by *calling* `wire` and `from` rather
  * than by re-spelling the strings.
  *
  * That distinction is the whole of build rule A14. A codec written as `case "STABLE" => Stable` is a second
  * declaration of the vocabulary: rename the state and the enum still compiles, the codec still compiles, and
  * the two now disagree in a way no test on either side can see. Every codec below is one line over `from`
  * and `wire`, so there is nothing here that *can* disagree.
  *
  * It lives in `libs/contracts-core` rather than in a service's contract module because three producers put
  * these values on the wire: the consumer service, the gateway's topic-overview aggregation, and the
  * gateway's group-page aggregation. `libs/contracts-core` is where a shape more than one service sends
  * already lives (see `kui.contracts.topic.TopicRowDto`'s own note).
  */
object GroupCodecs {

  /** Turns a kernel smart constructor's answer into Tapir's, keeping the message so that `libs/http` renders
    * the refusal as a `KUI-VALIDATION` envelope naming the parameter, rather than as a bare 400.
    */
  private def decoded[A](raw: String, result: Either[ValidationError, A]): DecodeResult[A] =
    result match {
      case Right(value) => DecodeResult.Value(value)
      case Left(error) => DecodeResult.Error(raw, KernelDecodeFailure(error))
    }

  private def circe[A](
      from: String => Either[ValidationError, A],
      wire: A => String
  ): Codec[A] =
    Codec.from(
      Decoder.decodeString.emap(raw => from(raw).left.map(_.message)),
      Encoder.encodeString.contramap(wire)
    )

  private def tapir[A](
      from: String => Either[ValidationError, A],
      wire: A => String
  ): TapirCodec[String, A, TextPlain] =
    TapirCodec.string.mapDecode(raw => decoded(raw, from(raw)))(wire)

  given Codec[GroupState] = circe(GroupState.from, _.wire)

  given TapirCodec[String, GroupState, TextPlain] = tapir(GroupState.from, _.wire)

  /** The accepted values are listed from `GroupState.All`, never typed out, so a state added to the enum is
    * documented by the act of adding it.
    */
  given Schema[GroupState] = Schema
    .string[GroupState]
    .description(s"A consumer group's lifecycle state: ${GroupState.All.map(_.wire).mkString(", ")}")

  given Codec[GroupProtocol] = circe(GroupProtocol.from, _.wire)

  given TapirCodec[String, GroupProtocol, TextPlain] = tapir(GroupProtocol.from, _.wire)

  given Schema[GroupProtocol] = Schema
    .string[GroupProtocol]
    .description(
      s"Which group protocol a member speaks: ${GroupProtocol.All.map(_.wire).mkString(", ")}"
    )

  given Codec[ResetTarget] = circe(ResetTarget.from, _.wire)

  given TapirCodec[String, ResetTarget, TextPlain] = tapir(ResetTarget.from, _.wire)

  given Schema[ResetTarget] = Schema
    .string[ResetTarget]
    .description(s"Where a reset moves the offsets: ${ResetTarget.All.map(_.wire).mkString(", ")}")

  given Codec[LagAnomaly] = circe(LagAnomaly.from, _.wire)

  given TapirCodec[String, LagAnomaly, TextPlain] = tapir(LagAnomaly.from, _.wire)

  given Schema[LagAnomaly] = Schema
    .string[LagAnomaly]
    .description(
      s"Why a partition's lag is not a plain number: ${LagAnomaly.All.map(_.wire).mkString(", ")}"
    )
}
