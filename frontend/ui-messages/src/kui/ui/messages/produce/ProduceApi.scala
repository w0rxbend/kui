package kui.ui.messages.produce

import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody

import kui.contracts.KernelSchemas.given
import kui.contracts.{ErrorEnvelope, KuiEndpoint, PublicApi}
import kui.kernel.{ClusterId, TopicName}
import kui.message.contract.*

/** The message service's two writes, as the *browser* calls them.
  *
  * ## Why this is not `MessageMutationEndpoints`
  *
  * A browser never talks to a service. It talks to the gateway, which derives its public routes from each
  * service's published contract by rewriting the leading `/internal/v1` to `/api/v1` and replacing the signed
  * principal header — which the gateway mints and a browser must never send — with the session it already
  * holds (ADR-040). The endpoint the browser calls therefore has a different path and a different security
  * input from the one the service serves, and cannot be the same value.
  *
  * What it *can* be is built from the same pieces, which is what this does: every path segment and every
  * document comes from `MessageMutationEndpoints` and its DTOs. Renaming a segment in
  * `services/message/contract` stops this file compiling, which is the whole reason a contract is
  * cross-compiled — and it is why a string literal like `"/api/v1/clusters"` anywhere in this module would be
  * a review failure.
  *
  * ## What is deliberately absent
  *
  * The CSRF header the service declares. `ApiClient` puts one on every request that is not a `GET`, so
  * declaring it here would put a second, empty one on the wire, and a header declared in two places is a
  * header that stops agreeing.
  */
object ProduceApi {

  private val messagesOf: EndpointInput[(ClusterId, TopicName)] =
    PublicApi.prefix / MessageMutationEndpoints.ClustersSegment /
      path[ClusterId](MessageMutationEndpoints.ClusterIdParam) /
      MessageMutationEndpoints.TopicsSegment /
      path[TopicName](MessageMutationEndpoints.TopicNameParam) /
      MessageMutationEndpoints.MessagesSegment

  /** `POST /api/v1/clusters/{clusterId}/topics/{topicName}/messages` — publish a record.
    *
    * The answer is where every copy landed, which is what lets the drawer say "partition 2, offset 41 284"
    * rather than "done". An operator who has just written a record needs to be able to go and look at it, and
    * a screen that only says it worked leaves them searching a topic for their own message.
    */
  val produce
      : PublicEndpoint[(ClusterId, TopicName, ProduceRequestDto), ErrorEnvelope, ProduceResultDto, Any] =
    KuiEndpoint.base.post
      .in(messagesOf)
      .in(jsonBody[ProduceRequestDto])
      .out(jsonBody[ProduceResultDto])
      .name("message.produce")

  /** `POST /api/v1/clusters/{clusterId}/topics/{topicName}/messages/resend` — copy records into another
    * topic, byte for byte.
    *
    * The topic in the path is the **source**; the destination is in the document. That reads the right way
    * round for the operation it is: an operator is looking at a topic and asking for some of its records to
    * be sent somewhere else.
    */
  val resend: PublicEndpoint[(ClusterId, TopicName, ResendRequestDto), ErrorEnvelope, ResendResultDto, Any] =
    KuiEndpoint.base.post
      .in(messagesOf / MessageMutationEndpoints.ResendSegment)
      .in(jsonBody[ResendRequestDto])
      .out(jsonBody[ResendResultDto])
      .name("message.resend")

  /** Every client this module has. The suite walks it, so a third cannot be added untested. */
  val all: List[AnyEndpoint] = List(produce, resend)
}
