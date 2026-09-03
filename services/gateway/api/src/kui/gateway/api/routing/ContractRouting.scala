package kui.gateway.api.routing

import cats.effect.kernel.{Async, Clock}
import cats.syntax.all.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.*
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.ServerEndpoint

import kui.contracts.ErrorEnvelope
import kui.contracts.capability.ReasonCode
import kui.gateway.api.auth.SessionMiddleware
import kui.gateway.application.capability.{CapabilitySignals, ReadinessSignal}
import kui.gateway.application.client.{CallContext, ServiceClient}
import kui.kernel.error.{InfrastructureError, KuiError}
import kui.kernel.{ClusterId, ServiceId}
import kui.observability.Correlation
import kui.security.Principal

/** Turns another service's published endpoints into gateway routes, with no path written by hand.
  *
  * ADR-003 forbids hand-written path lists, and this is where that rule stops being an aspiration. The
  * gateway is handed the `List[AnyEndpoint]` the owning team published; it rewrites the `/internal/v1` prefix
  * to `/api/v1`, keeps every input, output and codec exactly as declared, and delegates to that service's
  * `ServiceClient`. A route the gateway serves therefore cannot drift from the route the service serves,
  * because there is one definition and both sides read it.
  *
  * Eleven services and a few hundred endpoints are coming. Writing this now, against one endpoint, is the
  * difference between a gateway that stays a thin composition layer and one that slowly becomes a
  * hand-maintained copy of everybody else's API.
  */
object ContractRouting {

  val InternalPrefix: List[String] = List("internal", "v1")
  val PublicPrefix: List[String] = List("api", "v1")

  /** Every endpoint of one service, as gateway routes.
    *
    * Returns `Left` rather than throwing, and it does so at *construction*: a contract whose paths are not
    * under `/internal/v1` fails the composition root before the server binds, rather than producing a route
    * that 404s in production. A malformed contract must not be able to ship.
    */
  def derive[F[_]: Async](
      service: ServiceId,
      endpoints: List[AnyEndpoint],
      client: ServiceClient[F],
      signals: CapabilitySignals[F],
      rbac: RbacPreCheck[F]
  ): Either[String, List[ServerEndpoint[Fs2Streams[F], F]]] =
    endpoints.traverse(route(service, _, client, signals, rbac))

  /** The public path of one endpoint, for logs, metrics and the merged OpenAPI document. */
  def publicPathOf(endpoint: AnyEndpoint): Either[String, String] =
    rewrittenSegments(endpoint).map(_.mkString("/", "/", ""))

  private def rewrittenSegments(endpoint: AnyEndpoint): Either[String, List[String]] = {
    val segments = pathSegments(endpoint.input)
    if segments.take(2) == InternalPrefix then Right(PublicPrefix ++ segments.drop(2))
    else
      Left(
        s"${endpoint.info.name.getOrElse(endpoint.showShort)} is not under /internal/v1 " +
          s"(its path is ${segments.mkString("/", "/", "")}); the public prefix belongs to the gateway, " +
          "so a service's own endpoints must all sit under /internal/v1"
      )
  }

  /** The fixed path segments of an input, in order. Path *parameters* are not segments here: they are not
    * part of the prefix and are carried through untouched.
    */
  def pathSegments(input: EndpointInput[?]): List[String] =
    leaves(input).collect { case EndpointInput.FixedPath(segment, _, _) => segment }

  private def leaves(input: EndpointInput[?]): List[EndpointInput[?]] =
    input match {
      case EndpointInput.Pair(left, right, _, _) => leaves(left) ++ leaves(right)
      case EndpointIO.Pair(left, right, _, _) => leaves(left) ++ leaves(right)
      case EndpointInput.MappedPair(wrapped, _) => leaves(wrapped)
      case EndpointIO.MappedPair(wrapped, _) => leaves(wrapped)
      case leaf => List(leaf)
    }

  private def route[F[_]: Async](
      service: ServiceId,
      endpoint: AnyEndpoint,
      client: ServiceClient[F],
      signals: CapabilitySignals[F],
      rbac: RbacPreCheck[F]
  ): Either[String, ServerEndpoint[Fs2Streams[F], F]] =
    rewrittenSegments(endpoint).map { _ =>
      val typed = Unsafe.narrow(endpoint)

      val public: Endpoint[ServerRequest, Any, ErrorEnvelope, Any, Fs2Streams[F]] =
        Endpoint(
          securityInput = extractFromRequest[ServerRequest](identity),
          input = rewritePrefix(typed.input),
          errorOutput = typed.errorOutput,
          output = typed.output,
          info = typed.info
        )

      public
        .serverSecurityLogic[Principal, F](request => principalOf[F](request).map(Right(_)))
        .serverLogic { principal => input =>
          proxy(service, endpoint, typed, client, signals, rbac, principal, input)
        }
    }

  /** Replaces the first fixed path segment, which `rewrittenSegments` has already proved is `internal`.
    *
    * Only the first, and only a *path* segment. An endpoint whose path is `/internal/v1/connect/internal`
    * keeps its second `internal`, and a query parameter or header whose value happens to be `internal` is
    * never looked at. A blanket string replacement would corrupt both, silently, on some service nobody is
    * thinking about today.
    */
  def rewritePrefix[T](input: EndpointInput[T]): EndpointInput[T] = {
    var replaced = false

    def go[A](in: EndpointInput[A]): EndpointInput[A] =
      in match {
        case EndpointInput.Pair(left, right, combine, split) =>
          val rewrittenLeft = go(left)
          Unsafe.retype[A](EndpointInput.Pair(rewrittenLeft, go(right), combine, split))
        case EndpointInput.MappedPair(wrapped, mapping) =>
          Unsafe.retype[A](
            EndpointInput.MappedPair(Unsafe.asPair(go(wrapped)), Unsafe.asMapping[A](mapping))
          )
        case fixed @ EndpointInput.FixedPath(segment, codec, info) if !replaced =>
          replaced = true
          if segment == InternalPrefix.head then
            Unsafe.retype[A](EndpointInput.FixedPath(PublicPrefix.head, codec, info))
          else fixed
        case other => other
      }

    go(input)
  }

  /** One proxied call: check permission, call the service, translate the failure.
    *
    * The order is the point. The permission check happens before the upstream call, so a denied request costs
    * the service nothing.
    */
  private def proxy[F[_]: Async](
      service: ServiceId,
      original: AnyEndpoint,
      typed: Endpoint[Any, Any, ErrorEnvelope, Any, Any],
      client: ServiceClient[F],
      signals: CapabilitySignals[F],
      rbac: RbacPreCheck[F],
      principal: Principal,
      input: Any
  ): F[Either[ErrorEnvelope, Any]] =
    for {
      correlationId <- Correlation.newRandom[F]
      cluster <- Async[F].pure(none[ClusterId])
      permitted <- rbac.check(principal, original, cluster)
      result <- permitted match {
        case Left(denied) => Async[F].pure(Left(denied))
        case Right(_) =>
          client.call(Unsafe.secured(typed), input)(CallContext(principal, correlationId, cluster))
      }
      answer <- result match {
        case Right(output) => Async[F].pure(Right(output))
        case Left(error) => reportIfInfrastructure(service, signals, error).as(Left(error))
      }
      envelope <- answer match {
        case Right(output) => Async[F].pure(Right(output))
        case Left(error) =>
          Clock[F].realTimeInstant.map(now => Left(ErrorEnvelope.of(error, correlationId, now)))
      }
    } yield envelope

  /** Only transport failures dim a capability (ADR-039 §6).
    *
    * A user asking for a topic that does not exist gets a 404, and that 404 says something about the request,
    * not about the topic service, which answered correctly and promptly. Reporting it would let anyone dim a
    * feature for everyone else by typing a bad URL. The other direction matters just as much: an unreachable
    * upstream *must* be reported, or the page shows an error while the sidebar still looks green and nothing
    * on screen explains the failure.
    */
  def reportIfInfrastructure[F[_]: Async](
      service: ServiceId,
      signals: CapabilitySignals[F],
      error: KuiError
  ): F[Unit] =
    error match {
      case infrastructure: InfrastructureError =>
        Clock[F].realTimeInstant.flatMap { now =>
          signals.update(service)(
            _.copy(readiness =
              Some(ReadinessSignal.NotReady(reasonOf(infrastructure), infrastructure.message, now))
            )
          )
        }
      case _ => Async[F].unit
    }

  private def reasonOf(error: InfrastructureError): ReasonCode =
    error match {
      case InfrastructureError.CircuitOpen(_, _) => ReasonCode.CircuitOpen
      case InfrastructureError.Timeout(_, _) => ReasonCode.UpstreamTimeout
      case InfrastructureError.AuthFailed(_) => ReasonCode.UpstreamAuth
      case _ => ReasonCode.UpstreamUnavailable
    }

  /** The caller, from the session the edge attached. Anonymous when there is none, which in M0 is always:
    * authentication is disabled, and the session exists to carry the CSRF secret (ADR-019).
    */
  private def principalOf[F[_]: Async](request: ServerRequest): F[Principal] =
    Async[F].pure(
      request.attribute(SessionMiddleware.Attribute).map(_.principal).getOrElse(Principal.Anonymous)
    )

}

/** The casts this file cannot avoid, in one place with one justification.
  *
  * `AnyEndpoint` is `Endpoint[?, ?, ?, ?, ?]`: Tapir erases an endpoint's type parameters as soon as it is
  * put in a list, and there is no supported way to recover them. Attaching server logic needs them, so
  * deriving routes from a published contract needs a cast. `DisableSyntax.asInstanceOf` is right to forbid
  * this everywhere else, and the suppression is scoped to this object rather than sprinkled over the routing
  * logic so that a reader can check the whole argument at once.
  *
  * Why each one is sound:
  *
  *   - `narrow` and `secured`: `KuiEndpoint.internal` is the only way a KUI service builds an internal
  *     endpoint, so its security input is a `SignedPrincipal` and its error output is an `ErrorEnvelope`.
  *     `PingDtosSuite` and its equivalent in every service's contract assert exactly that, per endpoint,
  *     which is what makes this a checked assumption rather than a hope.
  *   - `retype`, `asPair` and `asMapping`: the path rewrite rebuilds an input tree node for node with the
  *     same shape and the same combine/split functions, replacing one fixed path segment with another fixed
  *     path segment. The value type is unchanged by construction -- a `FixedPath` carries `Unit` either way
  *     -- and the compiler simply cannot see it through the existential.
  *
  * The alternative is a hand-written, type-preserving route per endpoint, which is the hand-maintained copy
  * of everybody else's API that ADR-003 forbids and that this file exists to remove.
  */
// scalafix:off DisableSyntax.asInstanceOf
private object Unsafe {

  def narrow(endpoint: AnyEndpoint): Endpoint[Any, Any, ErrorEnvelope, Any, Any] =
    endpoint.asInstanceOf[Endpoint[Any, Any, ErrorEnvelope, Any, Any]]

  def secured(
      endpoint: Endpoint[Any, Any, ErrorEnvelope, Any, Any]
  ): Endpoint[kui.security.SignedPrincipal, Any, ErrorEnvelope, Any, Any] =
    endpoint.asInstanceOf[Endpoint[kui.security.SignedPrincipal, Any, ErrorEnvelope, Any, Any]]

  def retype[A](input: EndpointInput[?]): EndpointInput[A] = input.asInstanceOf[EndpointInput[A]]

  def asPair(input: EndpointInput[?]): EndpointInput.Pair[Any, Any, Any] =
    input.asInstanceOf[EndpointInput.Pair[Any, Any, Any]]

  def asMapping[A](mapping: sttp.tapir.Mapping[?, ?]): sttp.tapir.Mapping[Any, A] =
    mapping.asInstanceOf[sttp.tapir.Mapping[Any, A]]
}
// scalafix:on DisableSyntax.asInstanceOf
