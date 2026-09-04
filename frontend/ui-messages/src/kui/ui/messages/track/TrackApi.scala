package kui.ui.messages.track

import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody

import kui.contracts.KernelSchemas.given
import kui.contracts.{ErrorEnvelope, KuiEndpoint, PublicApi}
import kui.kernel.ClusterId
import kui.message.contract.*

/** The track call, as the *browser* makes it (ET-001).
  *
  * Built like every other client in this module: the path segments and both documents come from
  * `TrackEndpoints` and its DTOs, so a rename in the contract stops this compiling rather than producing a
  * 404 at run time. What differs from the service's own value is what always differs — the `/internal/v1`
  * prefix the gateway rewrites, and the signed principal the browser must never send.
  */
object TrackApi {

  /** `POST /api/v1/clusters/{clusterId}/messages/track`. */
  val track: PublicEndpoint[(ClusterId, TrackQueryDto), ErrorEnvelope, TrackResultDto, Any] =
    KuiEndpoint.base.post
      .in(
        PublicApi.prefix / TrackEndpoints.ClustersSegment /
          path[ClusterId](TrackEndpoints.ClusterIdParam) /
          TrackEndpoints.MessagesSegment / TrackEndpoints.TrackSegment
      )
      .in(jsonBody[TrackQueryDto])
      .out(jsonBody[TrackResultDto])
      .name("message.track")

  val all: List[AnyEndpoint] = List(track)
}
