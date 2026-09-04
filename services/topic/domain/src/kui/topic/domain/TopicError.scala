package kui.topic.domain

import kui.kernel.{ClusterId, TopicName}

/** Why a topic operation could not answer.
  *
  * Four cases, because the caller renders four different things: a "no such topic" page, a "you are not
  * allowed to see this" page, a greyed stale screen with a retry, and a red one. Anything coarser would make
  * the edge guess which of those to show, and a guess at the edge is how a permissions problem comes to look
  * like an outage.
  *
  * A `KuiError` from `libs/kernel` is mapped into one of these by the adapter and never reaches a use case:
  * an error type that carries an HTTP status and a transport's vocabulary would put both into a business
  * rule. That mapping is exhaustive and the compiler checks it (`KafkaToTopicDomain.error`).
  */
enum TopicError {

  /** The cluster answered and does not have this topic. Distinct from [[ClusterNotFound]] because the
    * remedies differ: one is "check the name", the other is "check KUI's configuration".
    */
  case NotFound(topic: TopicName)

  /** No cluster with this id is configured. The edge answers 404, not an empty list — an empty list of topics
    * reads as "this cluster has no topics", which is a different and much more alarming statement.
    */
  case ClusterNotFound(cluster: ClusterId)

  /** The cluster refused on authorization grounds. It is never [[Unreachable]]: an authorization failure is
    * an `ApplicationError`, it is not a sign that anything is broken, and per ADR-039 §6 it must not dim a
    * capability or take a service out of its healthy state.
    */
  case Forbidden(detail: String)

  /** KUI could not get an answer out of the cluster.
    *
    * @param retryable
    *   whether trying again shortly is worth it — a timeout or a leader election, yes; a malformed TLS
    *   configuration, no. It is what decides whether a screen offers a retry button or an explanation
    */
  case Unreachable(detail: String, retryable: Boolean)

  /** The cluster already has a topic with this name (M5's create).
    *
    * Its own case rather than a [[Rejected]] because it is the one refusal of a create that has an obvious
    * remedy — pick another name, or open the topic that is already there — and a screen can only offer that
    * if the code tells it which refusal this was.
    */
  case AlreadyExists(topic: TopicName)

  /** The cluster understood the request and would not carry it out (M5's mutations).
    *
    * A configuration key the broker does not accept, a replication factor above the broker count, a partition
    * count that is not an increase, a delete on a cluster with `delete.topic.enable=false`, a create refused
    * by a `create.topic.policy`. All of them are "no, and here is why", none of them means anything is
    * broken, and every one of them is fixed by the operator changing what they asked for.
    *
    * @param detail
    *   one sentence, safe to show a user, naming what the cluster refused
    */
  case Rejected(detail: String)

  /** One sentence, safe to show a user. */
  def message: String = this match {
    case NotFound(topic) => s"topic '${topic.value}' does not exist on this cluster"
    case ClusterNotFound(cluster) => s"no cluster named '${cluster.value}' is configured"
    case Forbidden(detail) => s"KUI is not authorized: $detail"
    case Unreachable(detail, _) => s"the cluster could not be reached: $detail"
    case AlreadyExists(topic) => s"topic '${topic.value}' already exists on this cluster"
    case Rejected(detail) => s"the cluster refused the change: $detail"
  }
}

object TopicError {
  given CanEqual[TopicError, TopicError] = CanEqual.derived
}
