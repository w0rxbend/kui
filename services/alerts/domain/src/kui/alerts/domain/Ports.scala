package kui.alerts.domain

import kui.kernel.ClusterId

/** Where the facts a rule reads come from.
  *
  * One method and not three, because a pass evaluates all four rules together and three ports would let a
  * caller evaluate two rules against a cluster as it was a minute ago and two against it as it is now. The
  * three readings inside [[ClusterFacts]] still fail independently, which is the property that actually
  * mattered; what is fixed here is that they are all read for one instant.
  *
  * It never fails. Every way of not having a fact is a `FactReading.Unreadable` inside the answer, because a
  * pass that raised would take the whole feed down for one refused admin call — and the feed is the screen an
  * operator is looking at precisely when admin calls are being refused.
  */
trait ClusterFactsPort[F[_]] {
  def read(cluster: ClusterId): F[ClusterFacts]
}
