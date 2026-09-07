package kui.gateway.api.routing

import scala.concurrent.duration.DurationInt

import cats.effect.IO
import cats.effect.kernel.{Ref, Resource}
import cats.syntax.all.*
import fs2.Stream
import io.circe.parser.decode
import munit.CatsEffectSuite
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.*

import kui.cluster.contract.ClusterWriteEndpoints
import kui.contracts.{ErrorEnvelope, KuiEndpoint}
import kui.gateway.api.GatewayTestServer
import kui.gateway.application.capability.{CapabilityRegistry, CapabilitySignals, RegistryConfig}
import kui.gateway.application.client.{CallContext, ServiceClient}
import kui.http.sse.SseEvent
import kui.http.upstream.CircuitEvent
import kui.kernel.error.{ApplicationError, ErrorCode, KuiError}
import kui.kernel.{ClusterId, RoleName, ServiceId}
import kui.metrics.contract.MetricsEndpoints
import kui.security.rbac.{
  ClusterFlags,
  DefaultRole,
  RbacPolicy,
  Resource as RbacResource,
  ResourcePattern,
  Role
}
import kui.security.{Principal, SignedPrincipal}
import kui.testkit.fakes.FakeStructuredLogger

/** The gateway's real permission check, constructed.
  *
  * Every other routing suite injects `RbacPreCheck.allowAll` or `denyAll`, which proves the *seam* is
  * consulted and says nothing whatever about the class that decides the answer. Until this file,
  * `grep -rn PolicyRbacPreCheck services/gateway --include=*.scala` matched no test: the `Decision.Denied`
  * branch could be made to log the denial and then return `().asRight` — letting the request through to the
  * upstream service — with the whole gateway suite green.
  *
  * The two halves are asserted together on purpose. A suite that only ever watched a refusal would pass just
  * as well against a check that refuses everything, and a gateway that refuses everything is not a
  * permission model, it is an outage.
  */
final class PolicyRbacPreCheckSuite extends CatsEffectSuite {

  private val metrics = ServiceId.unsafe("metrics")
  private val cluster = ClusterId.unsafe("prod-eu")

  private val throughput = "/api/v1/clusters/prod-eu/metrics/throughput?range=24h"

  /** A policy that is **on** and grants this caller nothing.
    *
    * One role naming another cluster and admitting nobody. `RbacPolicy.enabled` is true because a role
    * exists, an anonymous caller holds no roles, and the cluster gate refuses before any resource is
    * considered — which is the ordinary shape of a real deployment refusing a real request.
    */
  private val denying: RbacPolicy =
    RbacPolicy(
      List(Role(RoleName.unsafe("operators"), Set(ClusterId.unsafe("prod-us")), Nil, Nil)),
      defaultRole = None
    )

  /** A policy that is on and grants everybody everything, so that the refusal beside it means something.
    *
    * Both an unnamed and a wildcard-named permission per resource: `Permission.covers` treats the two as
    * different questions on purpose — a forgotten `value` must deny rather than grant — so a role that held
    * only one of them would allow half the endpoints in the product and no others.
    */
  private val permissive: RbacPolicy =
    RbacPolicy(
      Nil,
      Some(
        DefaultRole(
          RbacResource.values.toList.flatMap(resource =>
            List(
              RbacPolicy.allPermission(resource, None),
              RbacPolicy.allPermission(resource, Some(ResourcePattern.Everything))
            )
          )
        )
      )
    )

  /** An endpoint that declares no permission at all, which no shipped endpoint may be.
    *
    * `EndpointAuthorizationSuite` fails the build for one, so this is written here rather than borrowed: the
    * branch it exercises is a *bug* branch, and the question this suite asks is what the gateway does on the
    * day somebody ships one anyway. The answer must be "refuses it", because the alternative is an endpoint
    * that is unprotected until somebody notices.
    */
  private val undeclared: AnyEndpoint =
    KuiEndpoint.internal.get.in("internal" / "v1" / "undeclared").name("gateway.undeclared")

  private def check(
      policy: RbacPolicy,
      flags: ClusterFlags = ClusterFlags.Writable
  ): IO[(PolicyRbacPreCheck[IO], FakeStructuredLogger[IO])] =
    FakeStructuredLogger[IO].map(logger =>
      (new PolicyRbacPreCheck[IO](policy, _ => IO.pure(flags), logger), logger)
    )

  /** A stub metrics upstream that records every call it is given, so a denial can be observed as a call that
    * did not happen.
    */
  private def stubClient: IO[(ServiceClient[IO], Ref[IO, List[String]])] =
    Ref.of[IO, List[String]](Nil).map { calls =>
      val client = new ServiceClient[IO] {
        val service: ServiceId = metrics
        def circuitStates: Stream[IO, CircuitEvent] = Stream.empty

        def call[I, O](endpoint: Endpoint[SignedPrincipal, I, ErrorEnvelope, O, Any], input: I)(
            ctx: CallContext
        ): IO[Either[KuiError, O]] =
          calls
            .update(_ :+ endpoint.info.name.getOrElse("<unnamed>"))
            .as(Left(ApplicationError.NotFound("cluster", "prod-eu", ErrorCode.ClusterNotFound)))

        def callPublic[I, O](endpoint: PublicEndpoint[I, ErrorEnvelope, O, Any], input: I)(
            ctx: CallContext
        ): IO[Either[KuiError, O]] = IO.raiseError(new UnsupportedOperationException)

        def stream[I](
            endpoint: Endpoint[SignedPrincipal, I, ErrorEnvelope, Stream[IO, Byte], Fs2Streams[IO]],
            input: I
        )(ctx: CallContext): Stream[IO, SseEvent] = Stream.empty
      }
      (client, calls)
    }

  private def signals: Resource[IO, CapabilitySignals[IO]] =
    for {
      logger <- Resource.eval(FakeStructuredLogger[IO])
      registry <- CapabilityRegistry.resource[IO](
        RegistryConfig.Default.copy(debounce = 1.millisecond),
        GatewayTestServer.noTelemetry,
        logger
      )
      built <- Resource.eval(CapabilitySignals.make[IO](RegistryConfig.Default, registry, List(metrics)))
    } yield built

  /** A gateway routing the metrics contract behind the **real** permission check. */
  private def serving[A](
      policy: RbacPolicy
  )(body: (GatewayTestServer.Running, Ref[IO, List[String]]) => IO[A]): IO[A] =
    (signals, Resource.eval(stubClient), Resource.eval(check(policy))).tupled.use {
      case (signal, (client, calls), (rbac, _)) =>
        val routes = ContractRouting
          .derive[IO](metrics, ServiceContracts.proxied(metrics), client, signal, rbac)
          .fold(problem => fail(problem), identity)

        GatewayTestServer.resource(extraRoutes = routes).use(body(_, calls))
    }

  test("aRequestThePolicyDeniesDoesNotReachTheUpstreamService") {
    // The rule the class exists for, observed as a request that was never made. A denial that still reached
    // the service would mean the service does the work and the gateway throws the answer away — so a caller
    // with no permission at all could still cause load, and, on a mutation, an effect.
    serving(denying) { (server, calls) =>
      for {
        response <- server.get(throughput)
        asked <- calls.get
      } yield {
        assertEquals(response.code.code, 403, response.body)
        val envelope = decode[ErrorEnvelope](response.body).fold(error => fail(error.getMessage), identity)
        assertEquals(envelope.code, "KUI-FORBIDDEN")
        assertEquals(asked, Nil, "the upstream was called for a request the policy refused")
      }
    }
  }

  test("aRequestThePolicyAllowsDoesReachTheUpstreamService") {
    // The other half, and the one that makes the case above mean something: the same route, the same real
    // check, a policy that grants the permission, and the call goes through. Without it, a check that
    // refused every request in the product would pass the denial case perfectly.
    serving(permissive) { (server, calls) =>
      for {
        response <- server.get(throughput)
        asked <- calls.get
      } yield {
        assertNotEquals(response.code.code, 403, response.body)
        assertEquals(asked, List("metrics.throughput"))
      }
    }
  }

  test("aRefusalNamesNeitherTheRoleNorThePatternToTheCaller") {
    // A 403 that explained itself would be a map of the permission model, and naming a resource the caller
    // may not see leaks its existence. The reason is recorded where an operator can read it and a caller
    // cannot, which is the log line asserted below.
    check(denying).flatMap { (rbac, logger) =>
      for {
        decision <- rbac.check(Principal.Anonymous, MetricsEndpoints.throughput, Some(cluster), Nil)
        entries <- logger.entries
      } yield {
        val refusal = decision.swap.getOrElse(fail("an anonymous caller must be refused by a live policy"))
        assertEquals(refusal.message, "You do not have permission to do that")
        assert(!refusal.message.contains("prod-eu"), refusal.message)
        assert(!refusal.message.contains("METRICS"), refusal.message)

        assertEquals(entries.map(_.level), List("warn"))
        assertEquals(entries.head.context.get("operation"), Some("metrics.throughput"))
        assertEquals(entries.head.context.get("cluster"), Some("prod-eu"))
        assert(
          entries.head.context.getOrElse("reason", "").contains("prod-eu"),
          entries.head.context.toString
        )
      }
    }
  }

  test("aWriteToAReadOnlyClusterIsRefusedEvenWithNoPolicyConfigured") {
    // Read-only is a fact about the deployment's connection to a cluster, not about anybody's role, so it
    // applies to the quickstart — which configures no roles at all — exactly as it does to a deployment
    // with a hundred. `RbacPolicy.Disabled` is the harder case on purpose: it is the one where every other
    // gate is off.
    check(RbacPolicy.Disabled, ClusterFlags(readOnly = true)).flatMap { (rbac, _) =>
      rbac
        .check(Principal.Anonymous, ClusterWriteEndpoints.delete, Some(cluster), Nil)
        .map(decision => assert(decision.isLeft, decision.toString))
    }
  }

  test("theSameWriteIsAllowedOnAWritableClusterWithNoPolicyConfigured") {
    // Both directions of the flag, so that "read-only refuses" cannot be satisfied by a check that refuses
    // the endpoint outright.
    check(RbacPolicy.Disabled, ClusterFlags.Writable).flatMap { (rbac, _) =>
      rbac
        .check(Principal.Anonymous, ClusterWriteEndpoints.delete, Some(cluster), Nil)
        .map(decision => assertEquals(decision, Right(())))
    }
  }

  test("anEndpointThatDeclaresNoPermissionIsRefusedAndLoggedAtErrorLevel") {
    // Fail closed, and loudly. Allowing it would leave a new endpoint unprotected until somebody noticed;
    // refusing it makes the endpoint unreachable, which is impossible to ship past a smoke test. The error
    // level is the other half — this branch should never run in a shipped deployment, so when it does it has
    // to wake somebody rather than blend into the request log.
    check(RbacPolicy.Disabled).flatMap { (rbac, logger) =>
      for {
        decision <- rbac.check(Principal.Anonymous, undeclared, Some(cluster), Nil)
        entries <- logger.entries
      } yield {
        assert(decision.isLeft, decision.toString)
        assertEquals(entries.map(_.level), List("error"))
        assertEquals(entries.head.context.get("endpoint"), Some("gateway.undeclared"))
      }
    }
  }
}
