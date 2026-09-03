package kui.cluster.api

import cats.effect.kernel.{Async, Clock}
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.metrics.Counter
import sttp.tapir.server.ServerEndpoint

import kui.cluster.application.ClusterWriteUseCase
import kui.cluster.contract.ClusterWriteEndpoints
import kui.kernel.error.{ApplicationError, ErrorCode, KuiError}
import kui.security.{Principal, PrincipalCodec}

/** `PUT /internal/v1/clusters/{clusterId}`, the one write M1 ships.
  *
  * It has no user interface: the cluster wizard is M8. It exists because the metadata store's guarantees
  * cannot be demonstrated without a writer, and because building the surface once - now, against the store's
  * own contract - is cheaper than building it twice.
  *
  * ==Permission is checked here==
  *
  * The gateway's permission seam guards proxied routes, and this route is deliberately not proxied, so the
  * service checks for itself. With authentication disabled nothing grants the permission, which is what makes
  * "reachable only by an internal caller and by tests" a property of the *permission* rather than of the
  * network - the honest reading of the decision that put this endpoint here with no UI.
  *
  * ==What comes back==
  *
  * The redacted profile, never an echo of the request. Echoing would put every secret the caller just sent
  * back on the wire and into any proxy log between the two, for no benefit: a caller that wants to confirm
  * what it sent already has the version.
  */
object ClusterWriteRoutes {

  /** The permission a caller needs. Nothing grants it while `kui.auth.type` is `disabled`, which is the
    * point: an anonymous principal cannot change a deployment's clusters.
    */
  val RequiredPermission: String = "ApplicationConfig.Edit"

  def apply[F[_]: Async](
      write: ClusterWriteUseCase[F],
      principals: PrincipalCodec[F],
      rejections: Counter[F, Long],
      logger: StructuredLogger[F],
      permitted: Principal => Boolean = defaultPermission
  ): List[ServerEndpoint[Any, F]] = {
    val secured = ClusterApi.Securing[F](principals, rejections, logger)

    List(
      secured(ClusterWriteEndpoints.put) { principal =>
        { case (id, ifMatch, request) =>
          if !permitted(principal) then
            Async[F].pure(
              Left(
                ApplicationError.Forbidden(
                  s"changing a cluster requires $RequiredPermission"
                ): KuiError
              )
            )
          else
            ClusterWriteMapping.versionOf(ifMatch) match {
              case Left(error) => Async[F].pure(Left(error))
              case Right(expected) =>
                ClusterWriteMapping.profileOf(id, expected, request) match {
                  case Left(error) => Async[F].pure(Left(error))
                  case Right(profile) =>
                    for {
                      written <- write.put(profile, expected)
                      now <- Clock[F].realTimeInstant
                    } yield written.map(ClusterMapping.profile(_, now))
                }
            }
        }
      }
    )
  }

  /** Whether this principal may change a cluster.
    *
    * Roles are compared by name because the role vocabulary itself is M6's: the check has to exist now so
    * that M6 replaces one function rather than finding every route that forgot to ask, and it has to *fail*
    * now so that an endpoint with no UI is not an endpoint anyone can call.
    */
  def defaultPermission(principal: Principal): Boolean =
    principal.roles.exists(_.value == RequiredPermission)

  /** The error a caller sees when this deployment has nowhere to write.
    *
    * `Unsupported`, which `ErrorEnvelope.statusOf` renders as 501, and the message names the setting: an
    * operator reading "not implemented" with no further detail would reasonably think KUI cannot do this at
    * all, when in fact their deployment simply has no metadata store.
    */
  val NoStore: KuiError = ApplicationError.Unsupported(
    "the metadata store is not configured; set kui.store.kafka.bootstrapServers to enable runtime " +
      "cluster changes"
  )

  /** The conflict a losing writer sees. Named here so that a suite can assert the exact code a caller matches
    * on rather than a message that may be reworded.
    */
  val ConflictCode: ErrorCode = ErrorCode.ConfigVersionConflict

  /** The endpoints of this file, for the service's own OpenAPI document. */
  val endpoints: List[sttp.tapir.AnyEndpoint] = ClusterWriteEndpoints.all
}
