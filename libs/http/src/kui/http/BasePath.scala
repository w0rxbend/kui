package kui.http

import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint

/** Serving every route under a prefix, for deployments behind a reverse proxy.
  *
  * An operator who mounts KUI at `https://tools.example.com/kui` needs every route, every generated link and
  * the OpenAPI `servers` entry to agree on that prefix. Doing it here — once, over the whole endpoint list —
  * rather than in each contract is what keeps a contract module free of deployment concerns: the same
  * `ClusterEndpoints` value is served at `/clusters` in one deployment and `/kui/clusters` in another, with
  * nothing in the contract knowing the difference.
  */
object BasePath {

  /** The normal form of a configured base path: either `""` or something like `"/kui"`.
    *
    * `""`, `"/"`, `"/kui"` and `"/kui/"` are the four spellings an operator will actually write. The first
    * two mean "no prefix" and the last two mean the same prefix. Normalising once, here, is what stops
    * `//clusters` from appearing in a URL because two pieces of code each added a slash.
    */
  def normalize(raw: String): String =
    segments(raw) match {
      case Nil => ""
      case parts => parts.mkString("/", "/", "")
    }

  /** The path segments of a base path, with the empty ones dropped. */
  def segments(raw: String): List[String] = raw.split('/').toList.filter(_.nonEmpty)

  /** The same endpoint, served under `basePath`.
    *
    * The prefix is prepended to the *security* input rather than to the ordinary input because that is the
    * only position where adding a `Unit`-valued input leaves the endpoint's type unchanged; prepending to the
    * input would change the shape of the tuple the server logic receives, and every endpoint in the codebase
    * would have to know its deployment's prefix.
    */
  def prefix[R, F[_]](basePath: String, endpoint: ServerEndpoint[R, F]): ServerEndpoint[R, F] =
    segments(basePath) match {
      case Nil => endpoint
      case parts =>
        val prefixInput = parts.map(stringToPath).reduce[EndpointInput[Unit]](_.and(_))
        endpoint.prependSecurityIn(prefixInput)
    }

  /** [[prefix]] over a whole list, which is how a composition root uses it. */
  def prefixAll[R, F[_]](
      basePath: String,
      endpoints: List[ServerEndpoint[R, F]]
  ): List[ServerEndpoint[R, F]] =
    segments(basePath) match {
      case Nil => endpoints
      case _ => endpoints.map(prefix(basePath, _))
    }
}
