package kui.schema.domain

import kui.kernel.Subject
import kui.kernel.error.KuiError

/** One cluster's Schema Registry, reduced to the questions this service asks.
  *
  * Stated in domain terms and implemented in `infrastructure` over the registry's REST API, which is the
  * dependency direction rule A1 requires: nothing in this file knows that a registry speaks HTTP, and nothing
  * in this file can be broken by the registry changing its media type.
  *
  * ==Absence is a value, never an exception==
  *
  * Three methods answer `Option`, and each `None` is an ordinary fact rather than a failure:
  *
  *   - a subject that does not exist ([[versions]], [[schema]], [[checkCompatibility]]) — following a stale
  *     link should show "no such subject", and the route turns this into a 404 with a code, not a 500;
  *   - a subject with no compatibility level of its own ([[subjectCompatibility]]) — it follows the global
  *     level, and saying so is the difference between an honest screen and one that invites an operator to
  *     write an override they did not intend.
  *
  * Everything that genuinely went wrong is a `Left[KuiError]`: unreachable, timed out, refused, or an answer
  * KUI could not parse. There is no third channel — this port never throws, because the caller is a use case
  * that has to keep working when the registry does not.
  */
trait SchemaRegistryPort[F[_]] {

  /** Every subject the registry holds, unsorted and unfiltered.
    *
    * The registry has no search, no sort and no paging on this call: it returns the whole list of names or
    * nothing. That is why [[SubjectCatalog]] does the filtering here rather than pushing it upstream — there
    * is nowhere to push it to.
    */
  def subjects: F[Either[KuiError, List[Subject]]]

  /** The version numbers of one subject, ascending. `None` when the subject does not exist. */
  def versions(subject: Subject): F[Either[KuiError, Option[List[SchemaVersion]]]]

  /** One version's schema. `None` when the subject or that version does not exist. */
  def schema(subject: Subject, version: VersionSelector): F[Either[KuiError, Option[RegisteredSchema]]]

  /** The registry-wide compatibility level, which every subject without its own follows. */
  def globalCompatibility: F[Either[KuiError, CompatibilityLevel]]

  /** One subject's own level, or `None` when it has none and follows the global one. */
  def subjectCompatibility(subject: Subject): F[Either[KuiError, Option[CompatibilityLevel]]]

  /** Sets the registry-wide level. */
  def setGlobalCompatibility(level: CompatibilityLevel): F[Either[KuiError, Unit]]

  /** Sets one subject's own level, overriding the global one from now on. */
  def setSubjectCompatibility(subject: Subject, level: CompatibilityLevel): F[Either[KuiError, Unit]]

  /** Asks the registry whether a proposed schema would be accepted for a subject.
    *
    * The check runs **in the registry**, deliberately. Compatibility rules differ per schema language, change
    * between registry versions and are the registry's own definition of what it will accept; a second
    * implementation in KUI would eventually disagree with the thing that actually decides, and the answer an
    * operator would trust is the one that turns out to be wrong.
    *
    * `None` when the subject does not exist. A schema for a brand-new subject is trivially compatible and the
    * registry says so, but that is the registry's answer to give, not KUI's to fabricate.
    */
  def checkCompatibility(
      subject: Subject,
      version: VersionSelector,
      proposed: ProposedSchema
  ): F[Either[KuiError, Option[CompatibilityVerdict]]]
}
