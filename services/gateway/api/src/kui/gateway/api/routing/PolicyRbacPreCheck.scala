package kui.gateway.api.routing

import cats.effect.kernel.Sync
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger
import sttp.tapir.AnyEndpoint

import kui.contracts.rbac.{EndpointAuthorization, EndpointDecision}
import kui.kernel.error.{ApplicationError, KuiError}
import kui.kernel.ClusterId
import kui.security.Principal
import kui.security.rbac.{ClusterFlags, Decision, RbacPolicy}

/** The gateway's real permission check: the deployment's roles, applied to the endpoint's own declaration.
  *
  * ==Why the answer is one sentence for every refusal==
  *
  * A denial says `403` and "You do not have permission to do that", and it names neither the role that was
  * missing nor the pattern that did not match. The reason it was refused *is* recorded — in the log line and,
  * once AD-001 lands, in the audit record — where an operator can read it and a caller cannot. Telling the
  * caller which permission they lack turns a 403 into a map of the permission model, and telling them a topic
  * name they may not see was checked leaks the topic's existence.
  *
  * ==Why an endpoint that declares nothing is refused==
  *
  * It is a bug, not a request, and the two possible behaviours are "allow it" and "refuse it". Allowing it
  * means a new endpoint is unprotected until somebody notices, which is precisely the failure this project
  * keeps hitting; refusing it means a new endpoint is unreachable until somebody notices, which is loud,
  * immediate and impossible to ship past a smoke test. `GatewayContractsSuite` makes even that unnecessary by
  * failing the build for an endpoint with no declaration, so this path should never run in a shipped
  * deployment — and it is logged at error level for the day it does.
  *
  * @param flagsFor
  *   what is true of a cluster, as the gateway knows it. Read-only is a property of the deployment's
  *   connection to that cluster and is enforced here as well as in the owning service, because a call refused
  *   at the edge costs the service nothing and reaches the operator faster.
  */
final class PolicyRbacPreCheck[F[_]: Sync](
    policy: RbacPolicy,
    flagsFor: ClusterId => F[ClusterFlags],
    logger: StructuredLogger[F]
) extends RbacPreCheck[F] {

  private val refusal: KuiError =
    ApplicationError.Forbidden("You do not have permission to do that")

  def check(
      principal: Principal,
      endpoint: AnyEndpoint,
      cluster: Option[ClusterId],
      requestSegments: List[String]
  ): F[Either[KuiError, Unit]] =
    cluster.fold(ClusterFlags.Writable.pure[F])(flagsFor).flatMap { flags =>
      EndpointDecision.decide(policy, principal, flags, endpoint, cluster, requestSegments) match {
        case Right(Decision.Allowed) => ().asRight[KuiError].pure[F]

        case Right(Decision.Denied(reason)) =>
          logger
            .warn(
              Map(
                "principal" -> principal.name.value,
                "operation" -> operationOf(endpoint),
                "cluster" -> cluster.fold("-")(_.value),
                "reason" -> reason.describe
              )
            )("a request was refused by the gateway's permission check")
            .as(refusal.asLeft[Unit])

        case Left(problem) =>
          logger
            .error(Map("endpoint" -> operationOf(endpoint)))(
              s"$problem; the request was refused because it could not be authorized"
            )
            .as(refusal.asLeft[Unit])
      }
    }

  private def operationOf(endpoint: AnyEndpoint): String =
    EndpointAuthorization
      .of(endpoint)
      .map(_.operation)
      .orElse(endpoint.info.name)
      .getOrElse(endpoint.showShort)
}
