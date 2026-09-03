package kui.cluster.infrastructure

import kui.cluster.domain.{ClusterProfile, ProfileOrigin, ProfileVersion}
import kui.kernel.ClusterId
import kui.kernel.cluster.{AdminTuning, BootstrapServers, ClientProperties, ClusterSecurity}

/** Profiles for the suites in this module.
  *
  * Built through `ClusterProfile.from` rather than by copying a case class, so that a domain rule which
  * later refuses one of these values fails the tests here rather than being quietly bypassed by a test-only
  * constructor.
  */
object TestProfiles {

  def profile(
      id: String = "local",
      version: Long = 0L,
      bootstrap: String = "broker-1:9092"
  ): ClusterProfile =
    ClusterProfile
      .from(
        id = ClusterId.unsafe(id),
        displayName = id.capitalize,
        bootstrap = BootstrapServers.unsafe(bootstrap),
        security = ClusterSecurity.Plaintext,
        properties = ClientProperties.empty,
        admin = AdminTuning.default,
        readOnly = false,
        colour = None,
        version = ProfileVersion.unsafe(version),
        origin = ProfileOrigin.Static
      )
      .fold(error => throw new IllegalStateException(error.message), identity)
}
