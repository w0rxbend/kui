package kui.config

import java.time.Instant

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import kui.kernel.{PositiveInt, Secret, ServiceId}

/** How the gateway talks to one downstream KUI service.
  *
  * @param url
  *   the service's base address, already checked against the deployment's [[UrlPolicy]]
  * @param timeout
  *   the whole-call budget. A caller may treat every upstream call as bounded by this (HTTP-003).
  * @param maxConcurrent
  *   the bulkhead: how many calls to this one service may be in flight at once. It exists so a service that
  *   has become slow cannot consume every thread and take the other services down with it.
  */
final case class UpstreamServiceConfig(
    url: SafeUrl,
    timeout: FiniteDuration,
    maxConcurrent: PositiveInt
)

object UpstreamServiceConfig {
  val DefaultTimeout: FiniteDuration = 10.seconds
  val DefaultMaxConcurrent: PositiveInt = PositiveInt.unsafe(32)

  given CanEqual[UpstreamServiceConfig, UpstreamServiceConfig] = CanEqual.derived
}

/** Cross-origin resource sharing: whether a page served by some other origin may call this API.
  *
  * Off by default (ADR-019). The shipped deployment serves the single-page application from the gateway
  * itself, so the browser and the API share an origin and CORS is not involved at all. Turning it on is for
  * people embedding KUI's API elsewhere, and it then requires an explicit list of origins — `*` is refused at
  * load time, because `*` combined with credentials is exactly the configuration that lets any website read a
  * logged-in user's Kafka data.
  */
final case class CorsConfig(enabled: Boolean, origins: List[String])

object CorsConfig {
  val Default: CorsConfig = CorsConfig(enabled = false, origins = Nil)

  given CanEqual[CorsConfig, CorsConfig] = CanEqual.derived
}

/** One key the gateway may use to sign the `X-Kui-Principal` header (ADR-020).
  *
  * Several are configured at once so a key can be rotated without downtime: the gateway signs with the newest
  * key whose `notBefore` has passed, and services keep accepting the older ones until they are removed.
  *
  * @param kid
  *   the key id that travels in the token header, so a verifier knows which key to use
  * @param key
  *   the shared secret itself, wrapped so that printing it yields `Secret(***)`
  * @param notBefore
  *   the instant from which this key may be used for signing
  */
final case class PrincipalKeyConfig(kid: String, key: Secret[String], notBefore: Instant)

object PrincipalKeyConfig {
  given CanEqual[PrincipalKeyConfig, PrincipalKeyConfig] = CanEqual.derived
}

/** Everything the gateway process needs that is not shared with the other services.
  *
  * @param services
  *   the downstream services by id. Empty is legal: an all-in-one deployment calls its services in-process
  *   and configures none here.
  * @param readinessInterval
  *   how often the gateway polls each service's `/health/ready` to keep the capability registry current
  * @param principalKeys
  *   the signing keys above, newest last
  * @param cors
  *   the cross-origin posture
  */
final case class GatewayConfig(
    services: Map[ServiceId, UpstreamServiceConfig],
    readinessInterval: FiniteDuration,
    principalKeys: List[PrincipalKeyConfig],
    cors: CorsConfig
)

object GatewayConfig {
  val DefaultReadinessInterval: FiniteDuration = 10.seconds

  val Default: GatewayConfig =
    GatewayConfig(Map.empty, DefaultReadinessInterval, Nil, CorsConfig.Default)

  given CanEqual[GatewayConfig, GatewayConfig] = CanEqual.derived
}
