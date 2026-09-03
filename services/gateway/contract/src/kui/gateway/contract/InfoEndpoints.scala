package kui.gateway.contract

import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody

import kui.contracts.ErrorEnvelope
import kui.gateway.contract.dto.AppInfo

/** `GET /api/v1/info` — which build is running (feature CW-001).
  *
  * It is unauthenticated on purpose, and that is a decision rather than an oversight. This is the endpoint a
  * health dashboard, a deployment pipeline and a support engineer all read, and none of them has a session
  * cookie. That is precisely why `AppInfo` is forbidden to contain a URL, a hostname or a key id: the
  * document is readable by anyone who can reach the gateway at all.
  */
object InfoEndpoints {

  val info: PublicEndpoint[Unit, ErrorEnvelope, AppInfo, Any] =
    GatewayEndpoints.base.get
      .in("info")
      .out(jsonBody[AppInfo])
      .name("gateway.info")
      .summary("Which build of KUI is running, and what this deployment enables")
      .description(
        "Unauthenticated. Contains no URL, hostname or key id: it lists configured service ids only."
      )
      .tag("gateway")

  val all: List[AnyEndpoint] = List(info)
}
