package kui.metrics.contract.dto

import io.circe.syntax.*
import io.circe.{Codec, HCursor, Json}
import sttp.tapir.Schema as TapirSchema

import kui.contracts.Section

/** One delayed-operation purgatory and how many requests are parked in it, on the wire.
  *
  * ==`delayedRequests` is a count, and the design asked for a percentage==
  *
  * `SCREENS-V4.md` §3.4 draws a third ring reading "38% PURGATORY". A broker publishes
  * `DelayedOperationPurgatory.PurgatorySize`, which is a queue **length** with no ceiling to divide it by, so
  * there is no percentage to send. The field is named for what it holds and its unit is in its name; ADR-052
  * records the decision, and the card draws a count rather than an arc.
  */
final case class PurgatoryQueueDto(operation: String, delayedRequests: Long)

object PurgatoryQueueDto {

  given Codec[PurgatoryQueueDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        operation <- cursor.get[String]("operation")
        delayed <- cursor.get[Long]("delayedRequests")
      } yield PurgatoryQueueDto(operation, delayed),
    (queue: PurgatoryQueueDto) =>
      Json.obj("operation" -> queue.operation.asJson, "delayedRequests" -> queue.delayedRequests.asJson)
  )

  given TapirSchema[PurgatoryQueueDto] = TapirSchema
    .derived[PurgatoryQueueDto]
    .description(
      "A broker delayed-operation queue and how many requests are parked in it. A count of requests, " +
        "never a percentage: the broker publishes no purgatory ceiling to divide by"
    )

  given CanEqual[PurgatoryQueueDto, PurgatoryQueueDto] = CanEqual.derived
}

/** What the broker's request-handling machinery was doing at the moment of the last scrape.
  *
  * ==Ratios, never pre-formatted percentages==
  *
  * Both idle figures are fractions of one, exactly as the broker publishes them. A service that shipped
  * `"64%"` would be choosing a rounding and a locale on the browser's behalf, and the ring gauge needs the
  * fraction in order to draw an arc at all. `null` means not measured and is not `0.0`, which would say the
  * broker was saturated.
  */
final case class RequestHandlersDto(
    requestHandlerIdleRatio: Option[Double],
    networkProcessorIdleRatio: Option[Double],
    purgatory: List[PurgatoryQueueDto]
)

object RequestHandlersDto {

  given Codec[RequestHandlersDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        handler <- cursor.getOrElse[Option[Double]]("requestHandlerIdleRatio")(None)
        network <- cursor.getOrElse[Option[Double]]("networkProcessorIdleRatio")(None)
        purgatory <- cursor.getOrElse[List[PurgatoryQueueDto]]("purgatory")(Nil)
      } yield RequestHandlersDto(handler, network, purgatory),
    (reading: RequestHandlersDto) =>
      Json.obj(
        "requestHandlerIdleRatio" -> reading.requestHandlerIdleRatio.asJson,
        "networkProcessorIdleRatio" -> reading.networkProcessorIdleRatio.asJson,
        "purgatory" -> reading.purgatory.asJson
      )
  )

  given TapirSchema[RequestHandlersDto] = TapirSchema
    .derived[RequestHandlersDto]
    .description(
      "Idle ratios in 0..1 as the broker publishes them, and the depth of each delayed-operation queue. " +
        "A null ratio is not measured and is not zero"
    )

  given CanEqual[RequestHandlersDto, RequestHandlersDto] = CanEqual.derived
}

/** The request-handlers endpoint's whole answer. */
final case class RequestHandlersResponse(requestHandlers: Section[RequestHandlersDto])

object RequestHandlersResponse {

  given Codec[RequestHandlersResponse] = Codec.from(
    (cursor: HCursor) =>
      cursor.get[Section[RequestHandlersDto]]("requestHandlers").map(RequestHandlersResponse(_)),
    (response: RequestHandlersResponse) => Json.obj("requestHandlers" -> response.requestHandlers.asJson)
  )

  given TapirSchema[RequestHandlersResponse] = TapirSchema
    .derived[RequestHandlersResponse]
    .description("How busy the broker's handlers are, or the reason there is nothing to show")

  given CanEqual[RequestHandlersResponse, RequestHandlersResponse] = CanEqual.derived
}
