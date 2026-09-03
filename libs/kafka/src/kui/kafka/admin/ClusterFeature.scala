package kui.kafka.admin

import java.time.Instant

/** What a Kafka cluster can do.
  *
  * A closed enum rather than a set of strings: a feature name that exists only as a string will be misspelled
  * in a comparison somewhere, and the compiler will not care.
  */
enum ClusterFeature {
  case IncrementalAlterConfigs
  case ConfigDocumentation
  case AuthorizedOperations
  case AclManagement
  case AclEdit
  case ClientQuotas
  case TopicDeletion
  case LogDirs
  case KRaftQuorum
  case ProducersAndTransactions
  case TieredStorage
  case NewGroupProtocol
}

object ClusterFeature {
  given CanEqual[ClusterFeature, ClusterFeature] = CanEqual.derived

  val all: Set[ClusterFeature] = values.toSet
}

/** The probe's result. **Three** sets, not two.
  *
  * `unknown` is the set the reference implementations do not have, and it is the one that matters most. A
  * probe that timed out tells you nothing; recording that as "absent" hides a screen until the next hourly
  * probe for a reason that was never true, and the user is left with a tab that disappeared and no
  * explanation.
  *
  * Present means asked and yes. Absent means asked and no. Unknown means could not ask.
  */
final case class ClusterFeatures(
    present: Set[ClusterFeature],
    absent: Set[ClusterFeature],
    unknown: Set[ClusterFeature],
    probedAt: Instant
) {

  /** Present only. An unknown feature is not offered, but it is also not recorded as missing. */
  def has(feature: ClusterFeature): Boolean = present.contains(feature)

  def isKnown(feature: ClusterFeature): Boolean = !unknown.contains(feature)

  /** `present ++ absent ++ unknown` is every `ClusterFeature`, and no feature is in two of them.
    *
    * Asserted by a property test on both sides of the port boundary, because a feature that appears in none
    * of the three sets is a feature that silently disappears from the UI.
    */
  def isTotal: Boolean =
    (present ++ absent ++ unknown) == ClusterFeature.all &&
      present.intersect(absent).isEmpty &&
      present.intersect(unknown).isEmpty &&
      absent.intersect(unknown).isEmpty
}

object ClusterFeatures {

  /** Everything unknown: the value before the first probe completes, and the value a cluster KUI cannot reach
    * at all ends up with.
    */
  def unprobed(at: Instant): ClusterFeatures =
    ClusterFeatures(Set.empty, Set.empty, ClusterFeature.all, at)

  given CanEqual[ClusterFeatures, ClusterFeatures] = CanEqual.derived
}
