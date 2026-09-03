package kui.kafka

import org.apache.kafka.common.errors.*

import kui.kernel.error.{ApplicationError, ErrorCode, InfrastructureError, KuiError}

/** Everything a Kafka client can throw, turned into an error KUI can act on.
  *
  * Three separate decisions key on this file, which is why it is a first-mover rather than a detail:
  *
  *   - **Whether a failure dims a capability.** ADR-039 §6 reports only `InfrastructureError` to the
  *     capability registry. Classify "you lack an ACL for this topic" as infrastructure and one user's
  *     missing permission greys the feature out for everybody.
  *   - **What HTTP status the user gets.** `ErrorCode` decides it through `ErrorEnvelope.statusOf`, so a
  *     wrong row here is a 500 where a 403 belonged.
  *   - **Whether the connection is thrown away.** That was decided in KAFKA-004, and `classify` calls
  *     `AdminInvalidation.isReconnectClass` rather than restating the list, so the two cannot drift.
  *
  * Nothing here throws, ever. It is the last thing between a Java SDK and an HTTP response, and a mapper that
  * can fail turns a handled error into a 500.
  */
object KafkaErrorMapper {

  /** How a failure should be handled, independent of how it is rendered. */
  enum FailureClass {

    /** The connection is finished; the pool rebuilds it. Exactly `AdminInvalidation`'s set. */
    case Reconnect

    /** This request failed; the connection is fine. */
    case Request

    /** The broker, or the managed service in front of it, does not offer this call at all. */
    case Unsupported

    /** The principal KUI authenticates as lacks an ACL. */
    case NotAuthorized

    /** The thing that was asked about does not exist. */
    case NotFound
  }

  object FailureClass {
    given CanEqual[FailureClass, FailureClass] = CanEqual.derived
  }

  /** Turns anything a Kafka client can throw into a `KuiError`.
    *
    * Total by construction rather than by enumeration. After the documented rows come three fallbacks — a
    * `RetriableException` is a reachability problem, an `ApiException` is a statement about the request, and
    * anything else is an upstream KUI cannot classify — so a Kafka release that adds an exception class lands
    * in a sensible bucket instead of an unhandled match.
    *
    * `operation` is the short label from the closed set `AdminClientPool` uses, and it appears in the message
    * ("describeLogDirs did not finish within 30000ms"), which is what turns a user's report into something
    * searchable. `apiTimeoutMs` is the bound that was actually in force — `AdminTuning.apiTimeout` — so that
    * the message names a number an operator can go and change, rather than a number this file invented.
    */
  def map(operation: String, t: Throwable, apiTimeoutMs: Long = 0L): KuiError =
    KafkaFutures.unwrap(t) match {

      // ---- Reconnect class. The connection, not the request, is what broke.
      case _: TimeoutException => InfrastructureError.Timeout(operation, apiTimeoutMs)
      case _: SaslAuthenticationException => InfrastructureError.AuthFailed("kafka")
      case _: SslAuthenticationException => InfrastructureError.AuthFailed("kafka")
      case _: BrokerNotAvailableException =>
        InfrastructureError.Unreachable("kafka", s"the broker is not available for $operation")

      // ---- Authorization. Application errors on purpose: a user without an ACL must not dim a
      // capability for everyone else, which is the exact failure ADR-039 §6 exists to prevent.
      case _: ClusterAuthorizationException => forbidden(operation, "the cluster")
      case _: TopicAuthorizationException => forbidden(operation, "one or more topics")
      case _: GroupAuthorizationException => forbidden(operation, "one or more consumer groups")
      case _: DelegationTokenAuthorizationException => forbidden(operation, "delegation tokens")
      case _: TransactionalIdAuthorizationException => forbidden(operation, "transactional ids")

      // ---- Unsupported. The managed-service path: MSK Serverless answers `InvalidRequestException`
      // and an older broker answers `UnsupportedVersionException` to calls they simply do not offer.
      case _: SecurityDisabledException => ApplicationError.Unsupported("acls")
      case _: UnsupportedVersionException => ApplicationError.Unsupported(operation)
      case _: InvalidRequestException => ApplicationError.Unsupported(operation)
      case _: TopicDeletionDisabledException => ApplicationError.Unsupported("topic deletion")

      // ---- Not found.
      case _: UnknownTopicOrPartitionException => notFound("topic", ErrorCode.TopicNotFound)
      case _: UnknownTopicIdException => notFound("topic", ErrorCode.TopicNotFound)
      case _: LogDirNotFoundException => notFound("log directory", ErrorCode.TopicNotFound)

      // ---- State of the cluster, reported as a request-level condition.
      case _: KafkaStorageException => ApplicationError.InvalidState("a log directory is offline")
      case _: TopicExistsException => ApplicationError.Conflict("the topic already exists")

      // ---- Validation. The message is KUI's own: Kafka's text for an invalid configuration can
      // contain the value that was rejected, and that value is sometimes a password.
      // The three subclasses come first: `InvalidTopicException` and
      // `InvalidReplicationFactorException` both extend `InvalidConfigurationException`, so a
      // `case` for the parent above them would swallow both and say "a configuration value" where
      // it could have said which one.
      case _: InvalidTopicException => invalid(operation, "the topic name")
      case _: InvalidReplicationFactorException => invalid(operation, "the replication factor")
      case _: InvalidConfigurationException => invalid(operation, "a configuration value")
      case _: PolicyViolationException => invalid(operation, "a broker policy")
      case _: InvalidPartitionsException => invalid(operation, "the partition count")
      case _: InvalidReplicaAssignmentException => invalid(operation, "the replica assignment")

      // ---- Conditions that resolve on their own, or after somebody acts.
      case _: GroupNotEmptyException =>
        ApplicationError.InvalidState("the consumer group still has members")
      case _: GroupSubscribedToTopicException =>
        ApplicationError.InvalidState("a consumer group is still subscribed to the topic")
      case _: ReassignmentInProgressException =>
        ApplicationError.InvalidState("a partition reassignment is already in progress")
      case _: NoReassignmentInProgressException =>
        ApplicationError.InvalidState("there is no partition reassignment in progress")
      case _: ElectionNotNeededException =>
        ApplicationError.InvalidState("the preferred leader is already elected")

      // ---- Absences that have no `ErrorCode` of their own yet; see the spec's Deviations note.
      case _: GroupIdNotFoundException =>
        ApplicationError.InvalidState("the consumer group does not exist")
      case _: TransactionalIdNotFoundException =>
        ApplicationError.InvalidState("the transactional id does not exist")
      case _: UnknownMemberIdException =>
        ApplicationError.InvalidState("the group member does not exist")

      // ---- Transient unreachability of a partition's leader or a group's coordinator.
      case _: CoordinatorNotAvailableException => unreachable(operation, "the group coordinator")
      case _: NotLeaderOrFollowerException => unreachable(operation, "the partition leader")
      case _: LeaderNotAvailableException => unreachable(operation, "the partition leader")
      case _: NotEnoughReplicasException => unreachable(operation, "enough in-sync replicas")

      case _: UnknownServerException => InfrastructureError.Upstream("kafka", 502)

      // ---- The three fallbacks that make this total.
      case _: RetriableException => unreachable(operation, "the cluster")
      case api: ApiException =>
        ApplicationError.InvalidState(s"$operation was refused by the cluster (${simpleName(api)})")
      case _ => InfrastructureError.Upstream("kafka", 502)
    }

  /** How a caller should handle the failure. */
  def classify(t: Throwable): FailureClass = {
    val cause = KafkaFutures.unwrap(t)

    // The one source of truth for "the connection is finished" lives in `AdminInvalidation`,
    // because the pool acts on it and this mapper only reports it.
    if AdminInvalidation.isReconnectClass(cause) then FailureClass.Reconnect
    else
      cause match {
        case _: AuthorizationException => FailureClass.NotAuthorized
        case _: SecurityDisabledException | _: UnsupportedVersionException | _: InvalidRequestException |
            _: TopicDeletionDisabledException =>
          FailureClass.Unsupported
        case _: UnknownTopicOrPartitionException | _: UnknownTopicIdException | _: LogDirNotFoundException |
            _: GroupIdNotFoundException | _: TransactionalIdNotFoundException | _: UnknownMemberIdException =>
          FailureClass.NotFound
        case _ => FailureClass.Request
      }
  }

  /** `Some(reason)` when a per-key failure should become a `Skipped` entry rather than fail the whole batch.
    *
    * Not-found, not-authorized and unsupported are suppressible because each of them is a true and stable
    * statement about that key: the topic is not there, KUI may not read it, the cluster does not offer the
    * call. A **timeout is deliberately not suppressible**. A partial result caused by slowness is
    * indistinguishable from a cluster that genuinely has less data, and rendering it would show an operator a
    * smaller cluster than they have and give them no way to tell.
    */
  def suppressible(t: Throwable): Option[SkipReason] = {
    val cause = KafkaFutures.unwrap(t)

    classify(cause) match {
      case FailureClass.NotFound => Some(SkipReason.NotFound(describe(cause)))
      case FailureClass.NotAuthorized => Some(SkipReason.NotAuthorized(describe(cause)))
      case FailureClass.Unsupported => Some(SkipReason.Unsupported(describe(cause)))
      case FailureClass.Request =>
        cause match {
          // Two request-level conditions that are per-key facts rather than call failures: one log
          // directory being offline must not blank the other eleven, and a broker that answers
          // `UnknownServerException` for itself must not fail the list it is one row of.
          case _: KafkaStorageException =>
            Some(SkipReason.Failed(ErrorCode.InvalidState, "the log directory is offline"))
          case _: UnknownServerException =>
            Some(SkipReason.Failed(ErrorCode.UpstreamUnavailable, "the broker did not answer"))
          case _ => None
        }
      case FailureClass.Reconnect => None
    }
  }

  // ------------------------------------------------------------------ helpers

  /** KUI's own words for what failed, from the exception's *class* and never from its message.
    *
    * Kafka's text for a `SaslAuthenticationException` routinely names the mechanism and the principal, and
    * its text for an `InvalidConfigurationException` can contain the configuration value that was rejected —
    * which is sometimes a password. The original throwable goes to the log with its stack trace, where the
    * operator's own access controls apply; it does not go into an HTTP response body.
    */
  private def describe(t: Throwable): String = simpleName(t)

  private def simpleName(t: Throwable): String = {
    val name = t.getClass.getSimpleName
    if name.endsWith("Exception") then name.dropRight("Exception".length) else name
  }

  private def forbidden(operation: String, what: String): KuiError =
    ApplicationError.Forbidden(s"KUI is not authorized to $operation on $what")

  private def notFound(what: String, code: ErrorCode): KuiError =
    ApplicationError.NotFound(what, "", code)

  private def invalid(operation: String, what: String): KuiError =
    ApplicationError.Invalid(s"$operation was rejected because $what is not valid", Nil)

  private def unreachable(operation: String, what: String): KuiError =
    InfrastructureError.Unreachable("kafka", s"$what was not available for $operation")
}
