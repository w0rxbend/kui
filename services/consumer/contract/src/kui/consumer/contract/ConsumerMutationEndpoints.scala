package kui.consumer.contract

import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody

import kui.security.rbac.{Action, Resource}
import kui.contracts.rbac.{EndpointAuthorization, ResourceRequirement}
import kui.consumer.contract.dto.*
import kui.consumer.contract.dto.ConsumerCodecs.given
import kui.contracts.{ErrorEnvelope, KuiEndpoint}
import kui.kernel.{ClusterId, GroupId, TopicName}
import kui.security.SignedPrincipal

/** The first destructive operations in KUI, shaped so that the destructive request is the second one.
  *
  * ## The two-phase reset (ADR-045)
  *
  * `planReset` reads the group's live offsets, resolves the specification against them and answers with the
  * exact numbers that would be written. `applyReset` takes **only** the token that plan was signed with, and
  * writes exactly those numbers. There is no request anywhere in this contract that takes a specification and
  * writes offsets in one hop.
  *
  * The reason is what an operator can see. A form submission carries what the operator typed; it does not
  * carry what the cluster will actually do. The reference implementations studied for this milestone submit a
  * specification directly: one never shows the resulting offsets, and one does not clamp an out-of-range
  * offset at all (`research/kafka/admin-capabilities.md` §3 calls the second "a foot-gun"). The number an
  * operator needs to see before a destructive action is the number that will be written, and only the server
  * can compute it.
  *
  * ## The marker (ADR-047)
  *
  * M4 ships mutations before read-only mode, RBAC and the audit topic exist — those are M5 and M6. Every
  * mutating endpoint therefore carries `KuiEndpoint.MutationKey`, so that M5's read-only enumeration finds
  * them already classified instead of having to retrofit a classification across a shipped service, and so
  * that the per-cluster `readOnly` refusal has something to key on today.
  */
object ConsumerMutationEndpoints {

  val ClustersSegment: String = ConsumerEndpoints.ClustersSegment
  val GroupsSegment: String = ConsumerEndpoints.GroupsSegment
  val OffsetsSegment: String = "offsets"
  val PlanSegment: String = "plan"

  val TopicParam: String = "topic"

  /** The operation names. They are `val`s rather than literals at the use site because the same three strings
    * name the application layer's `MutationKind` cases and appear in every audit record; the api module's
    * suite — the only place that can see both a contract and an application type — asserts the two sets are
    * equal. Two enums in two modules with the same names is the drift build rule A14 exists for, and this is
    * the assertion that catches it across a boundary the rule cannot see.
    */
  val ResetOffsetsOperation: String = "consumer.offsets.reset"
  val DeleteGroupOperation: String = "consumer.group.delete"
  val DeleteOffsetsOperation: String = "consumer.offsets.delete"

  private val clustersBase = "internal" / "v1" / ClustersSegment

  private val clusterIdPath: EndpointInput[ClusterId] =
    path[ClusterId](ConsumerEndpoints.ClusterIdParam).description("The configured cluster's slug id")

  private val groupIdPath: EndpointInput[GroupId] =
    path[GroupId](ConsumerEndpoints.GroupIdParam).description("The consumer group id")

  private def groupBase: EndpointInput[(ClusterId, GroupId)] =
    clustersBase / clusterIdPath / GroupsSegment / groupIdPath

  /** What a reset would do. Changes nothing.
    *
    * Marked with `destructive = false`, and it is genuinely not a mutation: it reads. It nevertheless refuses
    * on a read-only cluster, which is why it carries the marker at all. The asymmetry is deliberate — a
    * wizard that happily renders a plan the operator is not allowed to apply teaches them that the refusal at
    * the end is a bug, and the honest moment to say "not on this cluster" is before they compose the change,
    * not after.
    */
  val planReset: Endpoint[
    SignedPrincipal,
    (String, ClusterId, GroupId, ResetPlanRequest),
    ErrorEnvelope,
    ResetPlanDto,
    Any
  ] =
    KuiEndpoint
      .mutation(ResetOffsetsOperation, destructive = false)
      .post
      .in(groupBase / OffsetsSegment / PlanSegment)
      .in(jsonBody[ResetPlanRequest])
      .out(jsonBody[ResetPlanDto])
      .name("consumer.offsets.plan")
      .attribute(
        EndpointAuthorization.Key,
        EndpointAuthorization.one(
          "consumer.offsets.reset",
          ResourceRequirement
            .named(Resource.ConsumerGroup, ConsumerEndpoints.GroupIdParam, Action.ConsumerGroupResetOffsets)
        )
      )
      .summary("What resetting this group's offsets would do")
      .description(
        KuiEndpoint.mutationNote(ResetOffsetsOperation, destructive = false) +
          "Resolves the request against the group's live offsets and returns the exact offsets that would be " +
          "written, any clamping as a warning, and a token valid for five minutes. Changes nothing. " +
          "Refused on a read-only cluster, so that the wizard never renders a plan that cannot be applied."
      )
      .tag("consumer")

  /** Apply exactly the plan the token names.
    *
    * It returns the plan that was applied so that the wizard can show what happened without asking again —
    * and so that what it shows is what was written, rather than a second resolution of the same request
    * against a cluster that has moved on in the meantime.
    */
  val applyReset: Endpoint[
    SignedPrincipal,
    (String, ClusterId, GroupId, ResetApplyRequest),
    ErrorEnvelope,
    ResetPlanDto,
    Any
  ] =
    KuiEndpoint
      .mutation(ResetOffsetsOperation, destructive = true)
      .post
      .in(groupBase / OffsetsSegment)
      .in(jsonBody[ResetApplyRequest])
      .out(jsonBody[ResetPlanDto])
      .name("consumer.offsets.apply")
      .attribute(
        EndpointAuthorization.Key,
        EndpointAuthorization.one(
          "consumer.offsets.reset",
          ResourceRequirement
            .named(Resource.ConsumerGroup, ConsumerEndpoints.GroupIdParam, Action.ConsumerGroupResetOffsets)
        )
      )
      .summary("Write the offsets a plan token names")
      .description(
        KuiEndpoint.mutationNote(ResetOffsetsOperation, destructive = true) +
          "Takes only a plan token. The group's state and member list are re-checked immediately before the " +
          "write, because the group may have become active since the plan was made. An expired or tampered " +
          "token is a validation error, never a silent re-plan."
      )
      .tag("consumer")

  /** Delete a group outright. Refused unless the group is empty. */
  val deleteGroup: Endpoint[SignedPrincipal, (String, ClusterId, GroupId), ErrorEnvelope, Unit, Any] =
    KuiEndpoint
      .mutation(DeleteGroupOperation, destructive = true)
      .delete
      .in(groupBase)
      .name("consumer.group.delete")
      .attribute(
        EndpointAuthorization.Key,
        EndpointAuthorization.one(
          "consumer.group.delete",
          ResourceRequirement
            .named(Resource.ConsumerGroup, ConsumerEndpoints.GroupIdParam, Action.ConsumerGroupDelete)
        )
      )
      .summary("Delete a consumer group")
      .description(
        KuiEndpoint.mutationNote(DeleteGroupOperation, destructive = true) +
          "Refused with KUI-GROUP-NOT-EMPTY while the group still has members — the one refusal an operator " +
          "can act on directly, by stopping the consumers."
      )
      .tag("consumer")

  /** Delete a group's committed offsets for one topic.
    *
    * The topic is a query parameter rather than a path segment because the resource being deleted is "this
    * group's offsets", narrowed by topic. A path reading `/consumer-groups/{g}/topics/{t}/offsets` would name
    * a resource that does not exist — there is no such thing as a group's topic — and the first person to
    * write a client against it would try to `GET` it.
    */
  val deleteOffsets: Endpoint[
    SignedPrincipal,
    (String, ClusterId, GroupId, TopicName),
    ErrorEnvelope,
    DeletedOffsetsDto,
    Any
  ] =
    KuiEndpoint
      .mutation(DeleteOffsetsOperation, destructive = true)
      .delete
      .in(groupBase / OffsetsSegment)
      .in(query[TopicName](TopicParam).description("Delete this group's committed offsets for this topic"))
      .out(jsonBody[DeletedOffsetsDto])
      .name("consumer.offsets.delete")
      .attribute(
        EndpointAuthorization.Key,
        EndpointAuthorization.one(
          "consumer.offsets.delete",
          ResourceRequirement
            .named(Resource.ConsumerGroup, ConsumerEndpoints.GroupIdParam, Action.ConsumerGroupResetOffsets)
        )
      )
      .summary("Delete a group's committed offsets for one topic")
      .description(
        KuiEndpoint.mutationNote(DeleteOffsetsOperation, destructive = true) +
          "Answers with the partitions whose offsets were removed, so that 'the group had none' and 'they " +
          "were deleted' are distinguishable — which an empty 204 body cannot do."
      )
      .tag("consumer")

  /** Every mutating endpoint this service serves, plus the plan endpoint that reads.
    *
    * `planReset` is in this list and is deliberately not in `mutating`: it belongs with these four because it
    * is part of the same flow and shares their read-only refusal, and it is not a mutation because it writes
    * nothing.
    */
  val all: List[AnyEndpoint] = List(planReset, applyReset, deleteGroup, deleteOffsets)

  /** The three endpoints that change the cluster, read from the marker rather than listed by hand.
    *
    * Listing them by hand would be a fourth declaration of the same fact, and the one that nothing checks.
    */
  val mutating: List[AnyEndpoint] =
    all.filter(endpoint => endpoint.attribute(KuiEndpoint.MutationKey).exists(_.destructive))
}
