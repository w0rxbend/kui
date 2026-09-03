package kui.gateway.api.routing

import cats.Applicative
import cats.syntax.all.*
import sttp.tapir.AnyEndpoint

import kui.kernel.ClusterId
import kui.kernel.error.KuiError
import kui.security.Principal

/** Whether this principal may call this endpoint at all.
  *
  * In M0 the answer is always yes: authentication is disabled (ADR-015) and there are no roles to check
  * against. The seam exists now anyway, and is consulted on every proxied call, so that M6 replaces one
  * function rather than finding every route that forgot to ask. A permission check that is added later has to
  * be added everywhere, and "everywhere" is what gets missed.
  *
  * It runs *before* the upstream call, not after. A denied request must not reach the service — otherwise the
  * service does the work, the gateway throws the answer away, and a caller with no permission can still cause
  * load and side effects.
  */
trait RbacPreCheck[F[_]] {
  def check(
      principal: Principal,
      endpoint: AnyEndpoint,
      cluster: Option[ClusterId]
  ): F[Either[KuiError, Unit]]
}

object RbacPreCheck {

  /** The M0 implementation. M6 replaces it with the real decision. */
  def allowAll[F[_]: Applicative]: RbacPreCheck[F] =
    new RbacPreCheck[F] {
      def check(
          principal: Principal,
          endpoint: AnyEndpoint,
          cluster: Option[ClusterId]
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
          cluster: Option[ClusterId]
      ): F[Either[KuiError, Unit]] =
        Left(kui.kernel.error.ApplicationError.Forbidden(message): KuiError).pure[F]
    }
}
