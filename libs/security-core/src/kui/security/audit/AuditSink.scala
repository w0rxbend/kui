package kui.security.audit

import java.time.Instant

import cats.Applicative

import kui.kernel.ClusterId

/** What kind of change an operation makes to a cluster.
  *
  * The marker ADR-047 requires. It exists from KUI's very first mutation rather than from the milestone that
  * first writes it down, because M5's read-only policy and M6's RBAC both key on this classification, and
  * both are far cheaper as a policy over an existing marker than as a hunt through endpoints that have
  * already shipped.
  *
  * Each service's contract suite enumerates its own endpoints and asserts that every one of them is
  * classified — as a mutation, or explicitly as a read. An unclassified endpoint fails the build.
  */
enum MutationKind(val operation: String) {

  /** A record written to a topic (MP-001). */
  case Produce extends MutationKind("produce")

  /** A range of records copied into another topic (MP-003). Two clusters' worth of consequences and one
    * operation, which is why it is not simply "produce, several times".
    */
  case Resend extends MutationKind("resend")

  /** Records deleted below a new low watermark (MS-008). **Irreversible**: the records below the watermark
    * are gone, and no amount of KUI can bring them back. It is the operation ADR-047 exists for.
    */
  case Purge extends MutationKind("purge")

  /** A topic created, with its partitions, replication factor and configuration (`MT-001`). */
  case CreateTopic extends MutationKind("topic.create")

  /** A topic's dynamic configuration set or reset (`MT-002`). */
  case AlterTopicConfig extends MutationKind("topic.config.alter")

  /** A topic's partition count raised (`MT-003`). **Irreversible**: Kafka has no way to remove a partition,
    * and every future record with an existing key may land somewhere other than the records already written
    * under that key.
    */
  case IncreasePartitions extends MutationKind("topic.partitions.increase")

  /** A topic and every record in it (`MT-004`). **Irreversible.** */
  case DeleteTopic extends MutationKind("topic.delete")
}

object MutationKind {
  given CanEqual[MutationKind, MutationKind] = CanEqual.derived
}

/** How a mutation ended. A failed mutation is recorded too — a record of what someone *tried* to do to a
  * production cluster is often the more interesting one.
  */
enum MutationOutcome {
  case Succeeded, Failed, Refused

  def label: String = this match {
    case Succeeded => "succeeded"
    case Failed => "failed"
    case Refused => "refused"
  }
}

object MutationOutcome {
  given CanEqual[MutationOutcome, MutationOutcome] = CanEqual.derived
}

/** One thing that was done, or attempted, to a cluster.
  *
  * @param principal
  *   who did it. `system` until identity exists (M6). It is a field now rather than later because adding it
  *   later would leave every record written before then indistinguishable from every record written after.
  * @param resource
  *   what was operated on, in the shape an operator recognises: a topic name, a group id, `orders:3`.
  * @param before
  *   the value that was there, where KUI knew it. `None` for an operation that has no before — a produce.
  * @param after
  *   the value that resulted, where KUI knows it.
  * @param detail
  *   anything else worth recording, as short strings. **Never a credential and never a payload**: an audit
  *   log is routinely more widely readable than the data it describes, which is exactly why it must not
  *   contain the data.
  */
final case class MutationRecord(
    at: Instant,
    principal: String,
    cluster: ClusterId,
    kind: MutationKind,
    resource: String,
    before: Option[String],
    after: Option[String],
    outcome: MutationOutcome,
    detail: Map[String, String]
)

object MutationRecord {

  /** The principal recorded until KUI has identities to record. Spelled out rather than left empty so that a
    * reader of an old record can tell "nobody was signed in" from "the field was not populated".
    */
  val SystemPrincipal: String = "system"

  given CanEqual[MutationRecord, MutationRecord] = CanEqual.derived
}

/** Where mutation records go.
  *
  * One port, declared once in `libs/security-core` because three services will write through it and ADR-023
  * already places the audit model here. M3 ships this and a structured-log sink; M5 adds the `__kui_audit`
  * Kafka topic behind the same interface and the viewer that reads it; M6 fills in the principal.
  *
  * `record` must not fail the operation it is describing. A sink that could refuse would make the audit trail
  * a availability dependency of every mutation, which inverts the point: a cluster you cannot change because
  * the audit log is down is worse than a change you find out about a minute late.
  */
trait AuditSink[F[_]] {
  def record(entry: MutationRecord): F[Unit]
}

object AuditSink {

  /** For tests and for a deployment that has deliberately turned auditing off. Never the default. */
  def noop[F[_]: Applicative]: AuditSink[F] = new AuditSink[F] {
    def record(entry: MutationRecord): F[Unit] = Applicative[F].unit
  }
}
