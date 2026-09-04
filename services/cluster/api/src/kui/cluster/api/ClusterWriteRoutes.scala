package kui.cluster.api

import cats.effect.kernel.{Async, Clock}
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.metrics.Counter
import sttp.tapir.server.ServerEndpoint

import kui.cluster.application.{ClusterProbeUseCase, ClusterWriteUseCase}
import kui.cluster.contract.ClusterWriteEndpoints
import kui.cluster.contract.dto.{ClusterWriteRequest, ConnectivityDto}
import kui.cluster.domain.{Connectivity, ProfileVersion}
import kui.http.principal.SecuredRoutes
import kui.kernel.ClusterId
import kui.kernel.error.{ApplicationError, ErrorCode, KuiError}
import kui.security.rbac.{AccessRequest, Action, ClusterFlags, Decision, Rbac, RbacPolicy, Resource}
import kui.security.{Principal, PrincipalCodec}

/** Registering, changing, removing and testing a cluster.
  *
  * ==Permission is checked here==
  *
  * These routes are now proxied by the gateway like any other, so the gateway's permission seam sees them
  * too. The check below stays, and it is not redundant: a service trusts the signed principal and nothing
  * else about who is calling it (ADR-020), and the day someone deploys the cluster service reachable on a
  * network the gateway is not the only thing on, this is the check that still holds.
  *
  * The check is the deployment's own RBAC policy, evaluated here through `Rbac.decide`, which is the same
  * evaluator the gateway's edge check and `/api/v1/auth/me` use. One rule, three places that ask it.
  *
  * A deployment with no roles configured has RBAC switched off, and `Rbac.decide` allows — which is what the
  * rest of KUI already does and what `/api/v1/auth/me` already advertises to the browser (a wildcard grant of
  * `APPLICATIONCONFIG EDIT` over every cluster). Anything else makes the browser draw a form the server will
  * refuse, which is what this used to do: the check compared the principal's roles to a role *named*
  * "ApplicationConfig.Edit", a name no role vocabulary produces, so every deployment refused every cluster
  * write and the administration screen was three buttons and a 403.
  *
  * ==What comes back from a write==
  *
  * The redacted profile, never an echo of the request. Echoing would put every secret the caller just sent
  * back on the wire and into any proxy log between the two, for no benefit: a caller that wants to confirm
  * what it sent already has the version.
  */
object ClusterWriteRoutes {

  /** The permission a caller needs for all three routes.
    *
    * The connection test needs it as much as the write does. It takes an address from a caller and opens a
    * connection to it, so an unguarded one would let anybody use KUI as a scanner of whatever KUI's network
    * can reach and read the answers off the three verdicts.
    */
  val RequiredPermission: String = "ApplicationConfig.Edit"

  def apply[F[_]: Async](
      write: ClusterWriteUseCase[F],
      probe: ClusterProbeUseCase[F],
      principals: PrincipalCodec[F],
      rejections: Counter[F, Long],
      logger: StructuredLogger[F],
      permitted: Principal => Boolean = defaultPermission
  ): List[ServerEndpoint[Any, F]] = {
    val secured = ClusterApi.Securing[F](principals, rejections, logger)

    /** The permission gate, as one value, so that three routes cannot check it three slightly different ways
      * — or, as has happened elsewhere in this project, so that the third route cannot forget.
      */
    def guarded[A](principal: Principal)(action: => F[Either[KuiError, A]]): F[Either[KuiError, A]] =
      if permitted(principal) then action
      else
        Async[F].pure(
          Left(ApplicationError.Forbidden(s"changing a cluster requires $RequiredPermission"): KuiError)
        )

    List(
      // `withBody`, not `secured`: this endpoint carries a request body, and ADR-020 Amendment 1 binds
      // the token to it by hashing the bytes the gateway signed — reconstructed by re-encoding the
      // decoded request through this very contract's codec. Bound to the request line alone, every call
      // to it would be refused as `request_mismatch`.
      secured.withBody(ClusterWriteEndpoints.put)((_, _, _, request) => SecuredRoutes.bodyBytes(request)) {
        principal =>
          { case (_, id, ifMatch, request) =>
            guarded(principal) {
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
      },

      // No body, so the ordinary binding: the request line is the whole digest.
      secured(ClusterWriteEndpoints.delete) { principal =>
        { case (_, id, ifMatch) =>
          guarded(principal) {
            ClusterWriteMapping.versionOf(ifMatch) match {
              case Left(error) => Async[F].pure(Left(error))
              // "Create" is not a version anything can be deleted at. Refusing it by name beats handing
              // zero to the store, which would answer "no record at version 0" and read as a bug.
              case Right(expected) if expected.isStatic =>
                Async[F].pure(Left(NotAVersionToDeleteAt))
              case Right(expected) => write.delete(id, expected)
            }
          }
        }
      },

      secured.withBody(ClusterWriteEndpoints.probe)((_, request) => SecuredRoutes.bodyBytes(request)) {
        principal =>
          { case (_, request) =>
            guarded(principal) {
              profileToProbe(request) match {
                case Left(error) => Async[F].pure(Left(error))
                case Right(profile) => probe.probe(profile).map(_.map(connectivity))
              }
            }
          }
      }
    )
  }

  /** The unsaved profile a connection test is run against.
    *
    * The id is derived from the name exactly as a save would derive it (ADR-031), and the whole write
    * validation runs, so the button answers "that address is not a bootstrap list" before it answers
    * "unreachable" — which is the difference between an operator fixing a typo and an operator debugging a
    * network.
    *
    * The version is `Static` because nothing is being written and no version is being claimed.
    */
  def profileToProbe(request: ClusterWriteRequest): Either[KuiError, kui.cluster.domain.ClusterProfile] =
    kui.config.ClusterConfig
      .slug(request.name)
      .leftMap(problem =>
        ApplicationError.Invalid(
          "the cluster is not valid",
          List(kui.kernel.error.FieldError(Some("name"), List(problem)))
        ): KuiError
      )
      .flatMap((id: ClusterId) => ClusterWriteMapping.profileOf(id, ProfileVersion.Static, request))

  /** The domain verdict as the wire's three-value string plus its sentence. */
  def connectivity(verdict: Connectivity): ConnectivityDto = verdict match {
    case Connectivity.Reachable => ConnectivityDto(ConnectivityDto.Reachable, reachable = true, None)
    case Connectivity.AuthenticationFailed(detail) =>
      ConnectivityDto(ConnectivityDto.AuthenticationFailed, reachable = false, Some(detail))
    case Connectivity.Unreachable(detail) =>
      ConnectivityDto(ConnectivityDto.Unreachable, reachable = false, Some(detail))
  }

  /** The question these three routes ask, as one value.
    *
    * Global rather than cluster-scoped: a cluster registration names a cluster that KUI may not know yet, and
    * a connection test names one that may never exist. The thing being changed is KUI's own configuration,
    * which is what `Resource.ApplicationConfig` is.
    */
  val Access: AccessRequest =
    AccessRequest.global(
      "cluster.write",
      kui.security.rbac.ResourceAccess.unnamed(Resource.ApplicationConfig, Action.ApplicationConfigEdit)
    )

  /** Whether this principal may change a cluster, according to this deployment's policy.
    *
    * `ClusterFlags.Writable` because the request names no cluster: read-only is a property of a Kafka
    * cluster, and the thing being written here is KUI's list of them.
    */
  def permissionFrom(policy: RbacPolicy): Principal => Boolean =
    principal => Rbac.decide(policy, principal, ClusterFlags.Writable, Access) == Decision.Allowed

  /** The check for a deployment that has been given no policy at all.
    *
    * Allows, because a policy with no roles in it is RBAC switched off, and that is the one answer
    * `Rbac.decide` gives for every other endpoint in the product. A stricter default here would only ever
    * disagree with what the browser was told it could do.
    */
  def defaultPermission(principal: Principal): Boolean = permissionFrom(RbacPolicy.Disabled)(principal)

  /** `If-Match: "0"` on a delete. */
  val NotAVersionToDeleteAt: KuiError = ApplicationError.Invalid(
    "If-Match: \"0\" means 'create'; a delete must name the version it is removing",
    List(kui.kernel.error.FieldError(Some("If-Match"), List("the version currently stored, quoted")))
  )

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
