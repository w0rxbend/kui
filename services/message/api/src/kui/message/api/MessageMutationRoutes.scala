package kui.message.api

import cats.effect.kernel.Async
import cats.syntax.all.*
import sttp.tapir.server.ServerEndpoint

import kui.contracts.message.HeaderDto
import kui.http.principal.SecuredRoutes
import kui.kernel.error.{ApplicationError, FieldError, KuiError}
import kui.kernel.serde.SerdeName
import kui.kernel.{ClusterId, OffsetRange, TopicName}
import kui.message.application.produce.{ProduceUseCase, ResendUseCase}
import kui.message.contract.*
import kui.message.domain.*

/** The two routes that write to a topic: publish, and resend.
  *
  * ==Read-only and audit (ADR-047)==
  *
  * Neither is implemented here. `MutationGuard` in the application layer owns both, and every write goes
  * through it: it resolves the cluster's profile, refuses a read-only cluster with `KUI-READ-ONLY` **before
  * any Kafka client is touched**, and records the attempt either way. There is deliberately no read-only
  * check written out in this file — one written here would be a second copy of the rule, and the copy that
  * can disagree with the first.
  *
  * ==The signed principal covers the body (ADR-020 Amendment 1)==
  *
  * Both endpoints carry one, so both are bound with `SecuredRoutes.withBody` and hand it the request document
  * to hash. Bound to the request line alone — which is what a `secured(...)` here would do — every call would
  * be refused with a 401 naming nothing, and a token intercepted on its way to a produce would be replayable
  * with any record its holder liked.
  *
  * ==The CSRF header==
  *
  * Both endpoints declare it and neither checks it. `KuiEndpoint.mutation` puts it in the contract, so a
  * request without one fails to decode and never reaches this file; binding its *value* to a session is M6's
  * job, because there is no session yet to bind it to.
  */
object MessageMutationRoutes {

  def apply[F[_]: Async](
      produce: ProduceUseCase[F],
      resend: ResendUseCase[F],
      secured: MessageApi.Securing[F],
      maxCount: Int = ProduceRequest.DefaultMaxCount
  ): List[ServerEndpoint[Any, F]] =
    List(
      produceRoute(produce, secured, maxCount),
      resendRoute(resend, secured)
    )

  /** Publish a record, `count` times. */
  private def produceRoute[F[_]: Async](
      produce: ProduceUseCase[F],
      secured: MessageApi.Securing[F],
      maxCount: Int
  ): ServerEndpoint[Any, F] =
    secured.withBody(MessageMutationEndpoints.produce)((_, _, _, request) =>
      SecuredRoutes.bodyBytes(request)
    ) { _ => (_, cluster, topic, request) =>
      requestOf(cluster, topic, request, maxCount) match {
        case Left(error) => error.asLeft[ProduceResultDto].pure[F]
        case Right(valid) =>
          produce
            .produce(valid)
            .map(_.map(records => ProduceResultDto(records.map(produced))))
      }
    }

  /** Copy one or more offset windows into another topic.
    *
    * The contract accepts several ranges — one per source partition — and the use case copies one at a time,
    * because one source partition is one read and one audit record. They are copied in the order they were
    * given and the tally is summed, so a caller that asked for three partitions gets one answer.
    *
    * A range that fails stops the resend, and the failure says how much had already been copied. It has to:
    * the ranges before it are written and cannot be taken back, and an error that did not mention them would
    * send an operator to retry the whole request and duplicate everything that had already worked.
    */
  private def resendRoute[F[_]: Async](
      resend: ResendUseCase[F],
      secured: MessageApi.Securing[F]
  ): ServerEndpoint[Any, F] =
    secured.withBody(MessageMutationEndpoints.resend)((_, _, _, request) => SecuredRoutes.bodyBytes(request)) {
      _ => (_, cluster, topic, request) =>
        resendRequests(cluster, topic, request) match {
          case Left(error) => error.asLeft[ResendResultDto].pure[F]
          case Right(requests) =>
            requests
              .foldLeftM(ResendResult(0L, 0L, Nil).asRight[(KuiError, ResendResult)]) {
                case (Left(stopped), _) => stopped.asLeft[ResendResult].pure[F]
                case (Right(sofar), one) =>
                  resend.resend(one).map {
                    case Right(result) =>
                      ResendResult(
                        read = sofar.read + result.read,
                        produced = sofar.produced + result.produced,
                        failures = sofar.failures ++ result.failures
                      ).asRight[(KuiError, ResendResult)]
                    case Left(error) => (error, sofar).asLeft[ResendResult]
                  }
              }
              .map {
                case Right(total) => ResendResultDto(request.toTopic, total.read, total.produced).asRight
                case Left((error, sofar)) => partialResend(error, sofar).asLeft
              }
        }
    }

  // -----------------------------------------------------------------------------------------------

  /** The wire request as the domain's, with every rule the domain owns applied by the domain.
    *
    * `ProduceRequest.of` is what refuses a `count` outside the bounds and a header with no name; writing
    * those checks here would be a second copy that the browser's own validation would eventually disagree
    * with. What this function does is only translation: serde names parsed, headers flattened, a null value
    * left null.
    */
  private[api] def requestOf(
      cluster: ClusterId,
      topic: TopicName,
      request: ProduceRequestDto,
      maxCount: Int
  ): Either[KuiError, ProduceRequest] =
    for {
      keySerde <- serdeOf("keySerde", request.keySerde)
      valueSerde <- serdeOf("valueSerde", request.valueSerde)
      produce <- ProduceRequest.of(
        cluster = cluster,
        topic = topic,
        partition = request.partition,
        key = request.key,
        // An absent value is a tombstone and is carried through as one. This is the single line where a
        // form that mapped "the user cleared the box" to an empty string would break compaction for
        // whoever relies on it, so it is a pass-through and not a `getOrElse`.
        value = request.value,
        headers = headersOf(request.headers),
        keySerde = keySerde,
        valueSerde = valueSerde,
        // No serde takes a parameter in this build. The fields exist in the domain because a
        // Schema-Registry serde needs a subject and a schema id, and adding them later would mean
        // changing every call site rather than one map.
        keySerdeProperties = Map.empty,
        valueSerdeProperties = Map.empty,
        count = Some(request.count),
        maxCount = maxCount
      )
    } yield produce

  /** One resend request per range.
    *
    * An empty `ranges` is refused rather than read as "the whole topic": a resend of everything must be asked
    * for explicitly, one range at a time, because the alternative is a request that copies a million records
    * because somebody left a field out.
    */
  private[api] def resendRequests(
      cluster: ClusterId,
      source: TopicName,
      request: ResendRequestDto
  ): Either[KuiError, List[ResendRequest]] =
    if request.ranges.isEmpty then
      Left(
        ApplicationError.Invalid(
          "a resend names no offsets, so there is nothing to copy",
          List(FieldError.of("ranges", "at least one partition range"))
        )
      )
    else
      request.ranges.traverse(range =>
        OffsetRange
          .from(range.from, range.until)
          .leftMap(invalid =>
            ApplicationError
              .Invalid(invalid.message, List(FieldError.of("ranges", "from at or before until")))
          )
          .flatMap(offsets =>
            ResendRequest.of(
              cluster = cluster,
              source = SourceRange(source, range.partition, offsets),
              // No destination partition: a resend lets Kafka's partitioner place the record, which keeps
              // key-based ordering in the destination topic. Pinning every copied record to one partition
              // is a way to make a replay behave unlike the traffic it is replaying.
              destination = Destination(request.toTopic, None),
              // Headers always travel. A resend that could drop them would be a resend that changes the
              // records, and an operator replaying a dead-letter queue would have no way to know.
              keepHeaders = true
            )
          )
      )

  private def serdeOf(field: String, raw: Option[String]): Either[KuiError, Option[SerdeName]] =
    raw.filter(_.nonEmpty) match {
      case None => Right(None)
      case Some(name) =>
        SerdeName
          .fromString(name)
          .bimap(
            why => ApplicationError.Invalid(why, List(FieldError.of(field, "a configured serde's name"))),
            Some(_)
          )
    }

  /** Headers as the domain wants them: name and text, in order.
    *
    * A header with no value is carried as an empty string rather than dropped. Kafka distinguishes a header
    * present with a null value from one that is absent, and some frameworks — Spring's dead-letter machinery
    * among them — read that difference; dropping it here would quietly change the record.
    */
  private def headersOf(headers: List[HeaderDto]): List[(String, String)] =
    headers.map(header => header.name -> header.value.getOrElse(""))

  private def produced(at: ProducedAt): ProducedRecordDto =
    ProducedRecordDto(partition = at.partition, offset = at.offset, timestamp = at.timestamp)

  /** The failure of a resend that had already copied something.
    *
    * The tally is in the message because there is nowhere else for it to go: an error response carries an
    * envelope, not a result, and an operator who retries a request that copied four thousand records without
    * knowing it will have eight thousand in their destination topic.
    */
  private[api] def partialResend(error: KuiError, sofar: ResendResult): KuiError =
    if sofar.produced == 0L then error
    else
      KuiError.remote(
        error.code,
        s"${error.message} — ${sofar.produced} of ${sofar.read} records had already been copied before " +
          "this failed, and they are still there; resume from where it stopped rather than repeating the " +
          "whole request",
        Nil
      )
}
