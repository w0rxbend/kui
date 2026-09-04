package kui.gateway.api.routing

import cats.Applicative
import cats.syntax.all.*
import sttp.tapir.AnyEndpoint

import kui.kernel.ClusterId
import kui.kernel.error.KuiError
import kui.security.Principal

/** Whether this principal may call this endpoint at all.
  *
  * It runs *before* the upstream call, not after. A denied request must not reach the service — otherwise the
  * service does the work, the gateway throws the answer away, and a caller with no permission can still cause
  * load and side effects.
  *
  * [[PolicyRbacPreCheck]] is the implementation a running gateway uses. The two below are for the cases where
  * the answer has to be fixed: a composition root with no policy at all, and a test that needs to observe
  * that the check really is consulted.
  *
  * @param requestSegments
  *   the request's path, segment by segment. The check needs it because a permission is granted over a
  *   *pattern* and the name being matched — a topic, a group — is in the path. It is passed rather than
  *   re-derived from the endpoint because the endpoint's own input types are erased by the time the gateway
  *   sees it in a list.
  */
trait RbacPreCheck[F[_]] {
  def check(
      principal: Principal,
      endpoint: AnyEndpoint,
      cluster: Option[ClusterId],
      requestSegments: List[String]
  ): F[Either[KuiError, Unit]]
}

object RbacPreCheck {

  /** Allows everything, for a deployment that has configured no authorization at all.
    *
    * It is deliberately *not* what a missing policy falls back to at the composition root: `RbacPolicy`
    * already models "no roles configured" as a policy that allows everything except what a read-only cluster
    * refuses, and using that keeps read-only enforced. This exists for tests and for the one caller that has
    * no cluster flags to consult.
    */
  def allowAll[F[_]: Applicative]: RbacPreCheck[F] =
    new RbacPreCheck[F] {
      def check(
          principal: Principal,
          endpoint: AnyEndpoint,
          cluster: Option[ClusterId],
          requestSegments: List[String]
      ): F[Either[KuiError, Unit]] = ().asRight[KuiError].pure[F]
    }

  /** Denies everything. Exists so that the "the check really is consulted before the upstream call" test can
    * prove it by observing a call that did not happen.
    */
  def denyAll[F[_]: Applicative](message: String): RbacPreCheck[F] =
    new RbacPreCheck[F] {
      def check(
          principal: Principal,
          endpoint: AnyEndpoint,
          cluster: Option[ClusterId],
          requestSegments: List[String]
      ): F[Either[KuiError, Unit]] =
        Left(kui.kernel.error.ApplicationError.Forbidden(message): KuiError).pure[F]
    }
}
