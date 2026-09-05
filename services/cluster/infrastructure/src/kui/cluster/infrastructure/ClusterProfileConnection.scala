package kui.cluster.infrastructure

import kui.cluster.domain.ClusterProfile
import kui.kernel.cluster.ClusterConnection

/** Projects a domain profile onto the pure connection value `libs/kafka` accepts.
  *
  * There is no mapping and no lost information here, on purpose. Decision D1 of the M1 plan put
  * `BootstrapServers`, `ClusterSecurity`, `AdminTuning` and the `properties` override map in `libs/kernel`,
  * and `ClusterProfile` composes them, so this is a projection of fields the profile already holds rather
  * than a translation that could be wrong.
  *
  * It exists as its own file even though the domain already assembles the value, because it is the single
  * named seam between the cluster domain's vocabulary and the Kafka client's. If either side renames a field,
  * exactly one file in this module has to change.
  */
object ClusterProfileConnection {

  def of(profile: ClusterProfile): ClusterConnection = profile.connection
}
