package kui.cluster.domain

import java.time.Instant

import cats.data.NonEmptyList

import kui.kernel.BrokerId
import kui.kernel.error.{DomainError, FieldError}

/** One member of the KRaft metadata quorum. `lag` is derived by the quorum, never stored here, so two
  * members' lags can never be computed against two different high watermarks.
  */
final case class ReplicaState(
    replicaId: BrokerId,
    logEndOffset: Long,
    lastFetch: Option[Instant],
    lastCaughtUp: Option[Instant]
)

object ReplicaState {
  given CanEqual[ReplicaState, ReplicaState] = CanEqual.derived
}

/** The KRaft metadata quorum, as `describeMetadataQuorum` reported it. */
final case class QuorumInfo private (
    leaderId: BrokerId,
    leaderEpoch: Long,
    highWatermark: Long,
    voters: NonEmptyList[ReplicaState],
    observers: List[ReplicaState]
) {

  /** How far behind the leader's high watermark this member is.
    *
    * Never negative: a follower reporting a log end offset above the leader's high watermark is a racing
    * read, not a member that is ahead of the truth.
    */
  def lagOf(state: ReplicaState): Long = math.max(0L, highWatermark - state.logEndOffset)

  def leader: Option[ReplicaState] = voters.find(_.replicaId == leaderId)
}

object QuorumInfo {

  /** Fails when the leader is not among the voters, or when an offset is negative. */
  def from(
      leaderId: BrokerId,
      leaderEpoch: Long,
      highWatermark: Long,
      voters: List[ReplicaState],
      observers: List[ReplicaState]
  ): Either[DomainError, QuorumInfo] = {
    val problems = List.newBuilder[FieldError]

    if leaderEpoch < 0L then
      problems += FieldError.of("leaderEpoch", s"must not be negative, got $leaderEpoch")

    if highWatermark < 0L then
      problems += FieldError.of("highWatermark", s"must not be negative, got $highWatermark")

    (voters ++ observers).filter(_.logEndOffset < 0L).foreach { state =>
      problems += FieldError.of(
        "logEndOffset",
        s"broker ${state.replicaId.value} reported a negative log end offset (${state.logEndOffset})"
      )
    }

    val nonEmpty = NonEmptyList.fromList(voters)

    if nonEmpty.isEmpty then problems += FieldError.of("voters", "a quorum must have at least one voter")
    else if !voters.exists(_.replicaId == leaderId) then
      problems += FieldError.of(
        "leaderId",
        s"the leader (${leaderId.value}) must be one of the voters"
      )

    val found = problems.result()

    (found, nonEmpty) match {
      case (Nil, Some(members)) =>
        Right(QuorumInfo(leaderId, leaderEpoch, highWatermark, members, observers))
      case _ =>
        Left(DomainError.InvariantViolation("the cluster reported an impossible metadata quorum", found))
    }
  }

  given CanEqual[QuorumInfo, QuorumInfo] = CanEqual.derived
}
