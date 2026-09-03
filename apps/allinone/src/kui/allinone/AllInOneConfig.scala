package kui.allinone

import kui.config.{GatewayConfig, KuiConfig, ServerConfig, TelemetryConfig}
import kui.gateway.app.GatewayServiceConfig

/** Everything the all-in-one process reads out of the configuration, and nothing else.
  *
  * `libs/config` loads one `KuiConfig` for every KUI process, because the precedence chain — defaults, then
  * files, then environment, then flags — is a product decision that must not be implemented eleven times.
  * This is the all-in-one deployment's slice of the result.
  *
  * It is the gateway's three sections, because in this shape the gateway is the only thing that listens and
  * the only thing that is configured. Two of the keys inside them describe a topology this process does not
  * have, and both are ignored rather than obeyed:
  *
  *   - `kui.gateway.services` names the addresses of separate service containers. There are none here; the
  *     services are objects in this JVM, reached through [[InProcessServiceClient]].
  *   - `kui.gateway.principalKeys` are the shared signing keys of a distributed deployment. Nothing is signed
  *     here, because nothing leaves the process — ADR-005 requires `PrincipalCodec.inProcess` in this shape.
  *
  * Ignoring them silently would be the wrong move: the shipped `deployment/compose/kui.yaml` sets both, and
  * an operator who points the all-in-one image at it deserves to be told that half the file did nothing.
  * [[AllInOneWiring.warnAboutIgnoredKeys]] is where that is said out loud.
  *
  * @param server
  *   the interface, the port and the base path the one listener serves under
  * @param gateway
  *   the gateway's own settings: the readiness poll interval, the CORS posture, the cookie policy — and the
  *   two ignored keys above
  * @param telemetry
  *   the exporters and the log format, for the single otel4s provider this process starts
  */
final case class AllInOneConfig(
    server: ServerConfig,
    gateway: GatewayConfig,
    telemetry: TelemetryConfig
) {

  /** The same settings in the shape `GatewayWiring` wants.
    *
    * The gateway's composition root is reused whole rather than copied (ADR-010), so what it is handed has to
    * be the type it already takes. Nothing is stripped out on the way: `GatewayWiring.over` is given the
    * service clients directly and never reads `gateway.services`, so leaving the configured URLs in place
    * cannot cause a connection.
    */
  def gatewayView: GatewayServiceConfig = GatewayServiceConfig(server, gateway, telemetry)

  /** Whether this configuration describes upstreams that this shape will not dial. */
  def hasIgnoredServiceUrls: Boolean = gateway.services.nonEmpty

  /** Whether this configuration carries signing keys that this shape will not sign with. */
  def hasIgnoredPrincipalKeys: Boolean = gateway.principalKeys.nonEmpty
}

object AllInOneConfig {

  /** The all-in-one deployment's slice of a loaded configuration. */
  def from(config: KuiConfig): AllInOneConfig =
    AllInOneConfig(config.server, config.gateway, config.telemetry)

  /** What the process runs on when nothing at all is configured: every interface, port 8080, no telemetry
    * exporter, and — correctly for this shape — no upstream addresses and no signing keys. Unlike a single
    * service, the all-in-one deployment starts and serves in exactly that state, which is what makes
    * `./mill apps.allinone.run` work on a machine with no configuration file on it.
    */
  val Default: AllInOneConfig = from(KuiConfig.Default)

  given CanEqual[AllInOneConfig, AllInOneConfig] = CanEqual.derived
}
