package kui.consumer.domain

import java.time.Instant

import kui.kernel.error.KuiError
import kui.kernel.group.GroupState
import kui.kernel.{GroupId, Offset, TopicPartition}

/** Everything this context needs from a Kafka cluster, stated in its own types.
  *
  * The port speaks `ConsumerGroup` and `OffsetWindow`, never `libs/kafka`'s result types: rule A1 keeps the
  * Kafka client off the domain's classpath entirely, and the translation happens once, in the adapter.
  *
  * One port per cluster instance rather than a cluster id on every method: the adapter holds the connection,
  * and a use case that took a cluster id would be one refactor away from being able to reach a cluster it was
  * not asked about.
  */
trait GroupAdminPort[F[_]] {

  def list(states: Set[GroupState]): F[Either[KuiError, GroupListingPage]]

  def describe(ids: List[GroupId]): F[Either[KuiError, Map[GroupId, ConsumerGroup]]]

  /** Whether the group is really there.
    *
    * By listing, never by describing. Describing a group that does not exist answers with a fabricated dead
    * group — see `GroupAdmin.describeGroups` — so a describe cannot answer this question, and every offset
    * operation asks it first (DEVPLAN §10 D5).
    */
  def exists(id: GroupId): F[Either[KuiError, Boolean]]

  /** The begin, end, committed and (optionally) timestamp-resolved offsets for a scope, in one pass.
    *
    * One call because a plan built from a committed offset read at one moment and an end offset read at
    * another is a plan for a cluster that never existed.
    */
  def offsetWindow(
      group: GroupId,
      scope: ResetScope,
      at: Option[Instant]
  ): F[Either[KuiError, OffsetWindow]]

  def applyOffsets(group: GroupId, offsets: Map[TopicPartition, Offset]): F[Either[KuiError, Unit]]

  def deleteOffsets(group: GroupId, partitions: Set[TopicPartition]): F[Either[KuiError, Unit]]

  def deleteGroup(id: GroupId): F[Either[KuiError, Unit]]
}
