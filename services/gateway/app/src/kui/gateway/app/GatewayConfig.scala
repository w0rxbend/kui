package kui.gateway.app

import kui.config.{GatewayConfig, KuiConfig, ServerConfig, TelemetryConfig}
import kui.gateway.api.GatewayServiceConfigView

/** Everything the gateway process reads out of the configuration, and nothing else.
  *
  * `libs/config` loads one `KuiConfig` for every KUI process, because the precedence chain — defaults, then
  * files, then environment, then flags — is a product decision that must not be implemented eleven times.
  * This is the gateway's slice of the result.
  *
  * Narrowing is worth a type of its own for two reasons. A composition root that takes exactly what it uses
  * cannot quietly grow a dependency on a section belonging to another service, and a suite can construct one
  * of these in a line instead of assembling a whole `KuiConfig` to exercise one field.
  *
  * The three sections are the three M0 needs: where to listen ([[ServerConfig]]), which upstreams exist and
  * how to sign for them ([[GatewayConfig]]), and where telemetry goes ([[TelemetryConfig]]). GW-009 adds the
  * session settings; nothing else is expected before M1.
  *
  * @param server
  *   the interface, the port and the base path this process serves under
  * @param gateway
  *   the upstream services, the readiness poll interval, the principal signing keys and the CORS posture
  * @param telemetry
  *   the exporters and the log format
  */
final case class GatewayServiceConfig(
    server: ServerConfig,
    gateway: GatewayConfig,
    telemetry: TelemetryConfig
) {

  /** The two sections `GatewayApi.routes` reads.
    *
    * The route list is assembled in the `api` module, which this one depends on, so `api` cannot name this
    * type. Handing it a narrow view rather than the whole configuration is the better shape anyway: the
    * signature then says which settings assembling a route list actually involves — not the telemetry
    * exporters, and not the signing keys.
    */
  def view: GatewayServiceConfigView = GatewayServiceConfigView(server, gateway)
}

object GatewayServiceConfig {

  /** The gateway's slice of a loaded configuration. */
  def from(config: KuiConfig): GatewayServiceConfig =
    GatewayServiceConfig(config.server, config.gateway, config.telemetry)

  /** What the process runs on when nothing at all is configured: every interface, port 8080, no upstreams, no
    * telemetry exporter. It starts and serves — see `GatewayWiring.make` for why that matters.
    */
  val Default: GatewayServiceConfig = from(KuiConfig.Default)

  given CanEqual[GatewayServiceConfig, GatewayServiceConfig] = CanEqual.derived
}
