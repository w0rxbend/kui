package kui.config

import kui.kernel.{Host, Port}

/** How one KUI process listens for HTTP.
  *
  * @param host
  *   the network interface to bind. `0.0.0.0` means "every interface", which is what a container needs;
  *   `127.0.0.1` would make the process unreachable from outside its own container.
  * @param port
  *   the TCP port to bind. A port already in use is a startup failure, never a silent retry on another port
  *   (HTTP-001).
  * @param basePath
  *   the path prefix every route is served under, for deployments behind a reverse proxy that mounts KUI at,
  *   say, `https://tools.example.com/kui`. Normalised by `kui.http.BasePath`: `"/"` and `""` both mean "no
  *   prefix".
  */
final case class ServerConfig(host: Host, port: Port, basePath: String)

object ServerConfig {

  /** Listen on every interface, on 8080, at the root of the host. */
  val Default: ServerConfig = ServerConfig(Host.unsafe("0.0.0.0"), Port.unsafe(8080), "/")

  given CanEqual[ServerConfig, ServerConfig] = CanEqual.derived
}
