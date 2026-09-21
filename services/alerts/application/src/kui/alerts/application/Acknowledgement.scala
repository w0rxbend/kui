package kui.alerts.application

import java.time.Instant

import cats.Applicative

import kui.kernel.ClusterId
import kui.security.Principal
import kui.security.audit.MutationOutcome

/** One acknowledgement, attempted or made.
  *
  * ==Why this is not a `MutationRecord`==
  *
  * ADR-047 §3 requires one audit record per mutation and `kui.security.audit.MutationRecord` is that record.
  * Its `kind` is a `MutationKind`, a sealed enum in `libs/security-core` with **twelve** cases and none for
  * an acknowledgement. Wave 6's partition gives `libs/security-core` to nobody, so W6-01 cannot add the
  * thirteenth, and ADR-051 §5 already settled what a service does in that position: it does not invent a
  * second vocabulary locally, and it does not reuse the nearest case, because either produces an audit trail
  * whose answer to "what changed today" depends on which service did it.
  *
  * What it does instead is `AuthenticationRecord`'s answer, which is the precedent in the same package for
  * exactly this shape: a **second record type** that shares the vocabulary rather than forking it. This
  * record shares `MutationOutcome`, so "how it ended" is one enum across the whole trail; it renders its
  * principal through `AuditPrincipal`, so "who" has one spelling; and the sink that writes it writes
  * `LoggingAuditSink`'s **own field names**, imported from that object rather than retyped, so a query over
  * the trail reads one set of keys.
  *
  * The one line that would remove this type is named in ADR-053 §5 and asserted rather than described:
  * `MutationKindGapSuite` fails the day `MutationKind` grows a case whose operation starts with `alerts.`,
  * which is the day this record collapses into `MutationRecord` and this file is deleted.
  *
  * @param resource
  *   the event's id. Not its title: a title is prose that changes with the rule's wording, and an audit
  *   trail's `resource` has to be a value somebody can look up.
  */
final case class AcknowledgementRecord(
    at: Instant,
    principal: Principal,
    cluster: ClusterId,
    resource: String,
    outcome: MutationOutcome,
    detail: Map[String, String]
)

object AcknowledgementRecord {

  /** The operation name, which is the string the audit line carries and the string the endpoint declares.
    *
    * It is spelled the way a `MutationKind.operation` is spelled — dotted, lower case, most general segment
    * first — so that the day it becomes one, nothing that reads the trail has to change.
    */
  val Operation: String = "alerts.event.acknowledge"

  given CanEqual[AcknowledgementRecord, AcknowledgementRecord] = CanEqual.derived
}

/** Where acknowledgement records go.
  *
  * The port has `AuditSink`'s contract, which is the part that matters: `record` must not fail the operation
  * it is describing. A sink that could refuse would make the audit trail an availability dependency of
  * clearing an alert, and an operator who cannot silence a bell because a log disk is full is worse off than
  * one whose bookkeeping is a minute late.
  */
trait AcknowledgementSink[F[_]] {
  def record(entry: AcknowledgementRecord): F[Unit]
}

object AcknowledgementSink {

  /** For tests and for a deployment that has deliberately turned auditing off. Never the default. */
  def noop[F[_]: Applicative]: AcknowledgementSink[F] = new AcknowledgementSink[F] {
    def record(entry: AcknowledgementRecord): F[Unit] = Applicative[F].unit
  }
}
