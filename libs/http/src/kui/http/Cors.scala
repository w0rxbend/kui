package kui.http

import sttp.model.Method
import sttp.tapir.server.interceptor.Interceptor
import sttp.tapir.server.interceptor.cors.{CORSConfig, CORSInterceptor}

import kui.config.CorsConfig

/** Whether a page served by some other origin may call this API.
  *
  * Off by default, and that is not caution for its own sake: the shipped deployment serves the single-page
  * application from the gateway itself, so the browser and the API share an origin and the browser never
  * performs a cross-origin check at all. Turning CORS on is for someone embedding KUI's API in another site,
  * and it is their decision to make explicitly.
  *
  * When it is on, the origins are an explicit list. `*` is refused when the configuration is loaded
  * (CFG-001), because `*` combined with credentials is precisely the setting that would let any website a
  * signed-in user visits read their Kafka data with their session cookie.
  */
object Cors {

  /** The methods a cross-origin caller may use.
    *
    * `TRACE`, `CONNECT` and the WebDAV verbs are absent because KUI serves none of them, and a preflight
    * response should describe what exists rather than what a library's default happens to list.
    */
  val AllowedMethods: List[Method] =
    List(Method.GET, Method.POST, Method.PUT, Method.PATCH, Method.DELETE, Method.HEAD, Method.OPTIONS)

  /** The interceptor, or `None` when CORS is off.
    *
    * `None` rather than a permissive interceptor is deliberate: a disabled configuration must add no CORS
    * headers at all, not headers that happen to deny. A response that carries `Access-Control-Allow-Origin`
    * with a value nobody matches still tells a reader that CORS is configured, and invites the next person to
    * "fix" it.
    */
  def interceptor[F[_]](config: CorsConfig): Option[Interceptor[F]] =
    if !config.enabled || config.origins.isEmpty then None
    else {
      val allowed = config.origins.map(_.toLowerCase).toSet

      val corsConfig = CORSConfig.default
        // Matching against a fixed set, rather than reflecting whatever the caller sent, is what
        // makes the allow-list an allow-list. Tapir echoes the matched origin back and adds
        // `Vary: Origin`, so a cache cannot serve one origin's response to another.
        .allowMatchingOrigins(origin => allowed.contains(origin.toLowerCase))
        // Credentials are allowed only because every origin here was named by an operator. This is
        // the pairing that `*` would make unsafe, and the reason `*` is refused at load time.
        .allowCredentials
        .allowMethods(AllowedMethods*)
        .reflectHeaders
        .exposeNoHeaders

      Some(CORSInterceptor.customOrThrow[F](corsConfig))
    }
}
