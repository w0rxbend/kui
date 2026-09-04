package kui.gateway.contract

import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody

import kui.contracts.ErrorEnvelope
import kui.contracts.KernelSchemas.given
import kui.gateway.contract.dto.TopicOverviewDto
import kui.kernel.{ClusterId, TopicName}

/** The topic page's own endpoint, which the gateway answers rather than proxies.
  *
  * `/overview` is a gateway path and not a topic-service path, because the answer a browser needs is not the
  * answer one service gives: it is the topic, plus whatever the consumer, connect, security and schema
  * services have to say about it. In M2 four of those five services do not exist, and the sections say so.
  *
  * ==Why it ships in M2, with one real section==
  *
  * It is the page's request. The browser is written against it now, and adding it later would mean changing
  * the topic page's data flow in the milestone that adds the Consumers tab — which is the milestone that
  * should be adding a tab, not rewriting a page.
  *
  * It sits beside the proxied topic routes rather than replacing any of them: a deep link to a single tab
  * still fetches that tab's own endpoint, and the topic list is proxied untouched.
  */
object TopicOverviewEndpoints {

  val OverviewSegment: String = "overview"

  /** The path this endpoint shares with the topic service's own routes.
    *
    * Spelled out here rather than imported from `kui.topic.contract.TopicEndpoints`. This module is
    * cross-compiled, and taking an edge on the topic service's contract would pull every topic DTO into the
    * browser bundle of the shell — which loads this contract on every page — to reuse four short strings.
    *
    * The strings are not left to drift: `TopicOverviewSuite.theOverviewPathIsTheTopicPathPlusOverview`
    * compares each of them to the topic contract's own constant. It runs in `services/gateway/api`, which is
    * JVM-only and already sees both contracts, and it is the only module that can make the comparison.
    */
  val ClustersSegment: String = "clusters"
  val TopicsSegment: String = "topics"
  val ClusterIdParam: String = "clusterId"
  val TopicNameParam: String = "topicName"

  val overview: PublicEndpoint[(ClusterId, TopicName), ErrorEnvelope, TopicOverviewDto, Any] =
    GatewayEndpoints.base.get
      .in(
        ClustersSegment /
          path[ClusterId](ClusterIdParam).description("The configured cluster's slug id") /
          TopicsSegment /
          path[TopicName](TopicNameParam).description("The topic's name, as Kafka spells it") /
          OverviewSegment
      )
      .out(jsonBody[TopicOverviewDto])
      .name("gateway.topic.overview")
      .summary("Everything the topic page shows, in one document")
      .description(
        "Answers 200 whenever the topic exists, however many of its five sections could be filled: a " +
          "section whose service this deployment does not have is not_configured and is hidden, one whose " +
          "service could not be reached is unavailable and is shown with its reason. A topic that does not " +
          "exist is a 404, because that is a different fact from a service that could not answer."
      )
      .tag("topics")

  val all: List[AnyEndpoint] = List(overview)
}
