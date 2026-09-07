package kui.metrics.contract.dto

import io.circe.syntax.*
import io.circe.{Codec, HCursor, Json}
import sttp.tapir.Schema as TapirSchema

import kui.contracts.Section

/** One topic and the rate at which bytes are arriving into it.
  *
  * ==`topic`, and the design asked for a `client.id`==
  *
  * `SCREENS-V4.md` §4 draws "Top producers" by `client.id`. A Kafka broker publishes no per-`client.id` byte
  * rate unless client quotas are configured — checked against the quickstart broker, whose stock-ruleset
  * exposition carries no quota family at all — and it does publish `BytesInPerSec` dimensioned by `topic`.
  * That is a real number and it is not the number the design named, so this field is called `topic`. A field
  * called `clientId` carrying a topic name is the defect ADR-052 exists to prevent, and the card's title is
  * what changes.
  */
final case class TopicProducerDto(topic: String, bytesInPerSecond: Double)

object TopicProducerDto {

  given Codec[TopicProducerDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        topic <- cursor.get[String]("topic")
        rate <- cursor.get[Double]("bytesInPerSecond")
      } yield TopicProducerDto(topic, rate),
    (producer: TopicProducerDto) =>
      Json.obj("topic" -> producer.topic.asJson, "bytesInPerSecond" -> producer.bytesInPerSecond.asJson)
  )

  given TapirSchema[TopicProducerDto] = TapirSchema
    .derived[TopicProducerDto]
    .description("A topic and the broker's one-minute bytes-in rate for it. A topic, not a client id")

  given CanEqual[TopicProducerDto, TopicProducerDto] = CanEqual.derived
}

/** The busiest topics, largest rate first.
  *
  * `measuredBy` travels with them so that a card cannot be labelled from an assumption. It is the one field
  * whose whole job is to say what the list is *of*, and it is a fixed string rather than a free-text sentence
  * because the browser branches on it to choose the card's title.
  *
  * An empty list is an answer and not a failure: the exporter published the family and no line carrying a
  * topic. It has two causes an exposition cannot tell apart — no topic is receiving traffic, or the
  * exporter's ruleset has a broker-wide rule and no per-topic one — and a card drawn from it says both rather
  * than claiming the cluster is idle. A family the exporter does not publish at all is an `unavailable`
  * section instead, so *that* is never confused with either.
  */
final case class TopProducersDto(measuredBy: String, topics: List[TopicProducerDto])

object TopProducersDto {

  /** What the list is of. One value today, and the reason it is a field at all: the day a deployment
    * configures client quotas there is a second thing this endpoint could be measured by, and a client
    * reading a list has to be able to tell which it got.
    */
  val ByTopic: String = "topic"

  given Codec[TopProducersDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        measuredBy <- cursor.get[String]("measuredBy")
        topics <- cursor.getOrElse[List[TopicProducerDto]]("topics")(Nil)
      } yield TopProducersDto(measuredBy, topics),
    (producers: TopProducersDto) =>
      Json.obj("measuredBy" -> producers.measuredBy.asJson, "topics" -> producers.topics.asJson)
  )

  given TapirSchema[TopProducersDto] = TapirSchema
    .derived[TopProducersDto]
    .description(
      "The busiest topics by bytes in, largest first. `measuredBy` is `topic`: a broker publishes no " +
        "per-client.id byte rate unless quotas are configured (ADR-052)"
    )

  given CanEqual[TopProducersDto, TopProducersDto] = CanEqual.derived
}

/** The top-producers endpoint's whole answer. */
final case class TopProducersResponse(producers: Section[TopProducersDto])

object TopProducersResponse {

  given Codec[TopProducersResponse] = Codec.from(
    (cursor: HCursor) => cursor.get[Section[TopProducersDto]]("producers").map(TopProducersResponse(_)),
    (response: TopProducersResponse) => Json.obj("producers" -> response.producers.asJson)
  )

  given TapirSchema[TopProducersResponse] = TapirSchema
    .derived[TopProducersResponse]
    .description("Who is producing the traffic, or the reason KUI cannot say")

  given CanEqual[TopProducersResponse, TopProducersResponse] = CanEqual.derived
}
