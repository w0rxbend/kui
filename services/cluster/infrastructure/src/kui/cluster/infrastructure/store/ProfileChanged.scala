package kui.cluster.infrastructure.store

import java.time.Instant

import kui.cluster.domain.ProfileVersion
import kui.kernel.ClusterId

/** What changed, for whoever is subscribed.
  *
  * Deliberately small. An event carrying the whole profile would carry secrets to a subscriber entitled only
  * to a redacted view — and the subscribers here are an SSE endpoint and, through it, other services. The id
  * and the version are everything a subscriber needs in order to decide whether to re-fetch: that is exactly
  * the ETag comparison ADR-036 describes.
  */
final case class ProfileChanged(
    clusterId: ClusterId,
    version: ProfileVersion,
    kind: ProfileChanged.Kind,
    at: Instant
)

object ProfileChanged {

  /** Removal is a case of its own because a subscriber must stop talking to a cluster that is gone, and
    * "version went up" does not say that.
    */
  enum Kind {
    case Added
    case Updated
    case Removed
  }

  object Kind {
    given CanEqual[Kind, Kind] = CanEqual.derived
  }

  given CanEqual[ProfileChanged, ProfileChanged] = CanEqual.derived
}
