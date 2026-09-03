package kui.cluster.domain

import java.time.Instant

/** What KUI has established, per feature, about one cluster — with three answers rather than two.
  *
  * The third set is the whole point. A probe that timed out has not established that the cluster *cannot* do
  * something, and recording it as absent hides a working screen for an hour for a reason that was never true.
  * `present` means "tried it, it worked", `absent` means "tried it, the cluster said no", and `unknown` means
  * "have not established it" — a probe that failed for a reason unrelated to the feature, or one that has not
  * run yet.
  *
  * The same three-set shape exists in `libs/kafka`, where the probing happens, and the adapter maps one onto
  * the other field for field. The partition invariant below is asserted on both sides deliberately: a shared
  * invariant checked on one side only is half a check.
  */
final case class ClusterFeatures(
    present: Set[ClusterFeature],
    absent: Set[ClusterFeature],
    unknown: Set[ClusterFeature],
    probedAt: Instant
) {

  def has(feature: ClusterFeature): Boolean = present.contains(feature)

  def isAbsent(feature: ClusterFeature): Boolean = absent.contains(feature)

  def isUnknown(feature: ClusterFeature): Boolean = unknown.contains(feature)

  /** The tokens of the features that are actually available, for the capability report and the wire. */
  def tokens: Set[String] = present.map(_.token)

  /** The three sets cover every feature exactly once.
    *
    * A value that fails this is not merely untidy: `has` and `isUnknown` would both answer `false` for the
    * same feature, and the caller would render "not supported" for something nobody ever asked about.
    */
  def isTotal: Boolean =
    (present ++ absent ++ unknown) == ClusterFeature.All &&
      present.intersect(absent).isEmpty &&
      present.intersect(unknown).isEmpty &&
      absent.intersect(unknown).isEmpty
}

object ClusterFeatures {

  /** Nothing has been established yet: every feature is unknown.
    *
    * This is what a cluster looks like before its first probe, and it is deliberately not "every feature
    * absent" — the screens gated on a feature show "not determined" rather than switching themselves off
    * during the first thirty seconds of a process.
    */
  def unprobed(at: Instant): ClusterFeatures =
    ClusterFeatures(Set.empty, Set.empty, ClusterFeature.All, at)

  /** Builds from the present and absent sets, putting everything else in `unknown`.
    *
    * The only constructor a mapper should need: it makes `isTotal` true by construction, so the invariant
    * cannot be broken by forgetting a feature that was added later.
    */
  def of(
      present: Set[ClusterFeature],
      absent: Set[ClusterFeature],
      at: Instant
  ): ClusterFeatures = {
    val decided = present ++ absent
    ClusterFeatures(present, absent -- present, ClusterFeature.All -- decided, at)
  }

  given CanEqual[ClusterFeatures, ClusterFeatures] = CanEqual.derived
}
