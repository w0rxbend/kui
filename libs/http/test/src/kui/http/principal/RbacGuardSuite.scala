package kui.http.principal

import cats.effect.IO
import munit.CatsEffectSuite
import sttp.tapir.*

import kui.contracts.rbac.{EndpointAuthorization, ResourceRequirement}
import kui.kernel.{ClusterId, RoleName, UserName}
import kui.security.rbac.*
import kui.security.{Principal, PrincipalKind}
import kui.testkit.fakes.FakeStructuredLogger

/** The guard itself, constructed — which nothing in this repository did before.
  *
  * ==Why a suite here and not only in a service==
  *
  * `services/cluster/api`'s `ServiceRbacGuardSuite` drives real routes through a real server and is the
  * better test of the *seam*. What it cannot reach is the branch that fires when the question cannot be
  * formed at all: every endpoint a KUI service publishes carries a declaration, because each contract module
  * has a suite that fails the build if one does not. So `EndpointDecision.decide` never answers `Left` in
  * that suite, and `RbacGuard.fromPolicy`'s fail-closed branch was executed by nothing in the repository —
  * made to return `().asRight`, `./mill services.cluster.api.test` stayed at 752/752.
  *
  * An endpoint with no declaration is exactly what this file constructs, and it is not a contrived shape: it
  * is what a route added next year without one looks like from inside the guard. The rule the branch keeps is
  * the one `EndpointAuthorization.accessRequest`'s own scaladoc states — *"answering 'allowed' because the
  * question could not be formed is how authorization bypasses happen"* — and this is where it is asserted.
  */
final class RbacGuardSuite extends CatsEffectSuite {

  private val Cluster: ClusterId = ClusterId.unsafe("local")
  private val reader: RoleName = RoleName.unsafe("reader")

  private def principal(roles: RoleName*): Principal =
    Principal(UserName.unsafe("tester"), roles.toSet, PrincipalKind.Session)

  /** A policy granting `reader` the named actions over one resource, on this cluster only. */
  private def policy(resource: Resource, actions: Action*): RbacPolicy =
    RbacPolicy(
      roles = List(
        Role(
          name = reader,
          clusters = Set(Cluster),
          subjects = Nil,
          permissions = List(RbacPolicy.permission(resource, None, actions.toSet))
        )
      ),
      defaultRole = None
    )

  /** A cluster-scoped read, declared the way every KUI endpoint declares itself. */
  private val declared: AnyEndpoint =
    endpoint.get
      .in("internal" / "v1" / "clusters" / path[String]("clusterId") / "topics")
      .attribute(
        EndpointAuthorization.Key,
        EndpointAuthorization.one(
          "listTopics",
          ResourceRequirement.unnamed(Resource.Topic, Action.TopicView)
        )
      )

  /** The same path, with no declaration attached at all. This is the shape the fail-closed branch exists for.
    */
  private val undeclared: AnyEndpoint =
    endpoint.get.in("internal" / "v1" / "clusters" / path[String]("clusterId") / "topics")

  /** An endpoint that names its resource with a path parameter its own path does not carry — the second way
    * `accessRequest` can fail to form a question, and the one a rename produces.
    */
  private val misdeclared: AnyEndpoint =
    endpoint.get
      .in("internal" / "v1" / "clusters" / path[String]("clusterId") / "topics" / path[String]("topic"))
      .attribute(
        EndpointAuthorization.Key,
        EndpointAuthorization.one(
          "getTopic",
          ResourceRequirement.named(Resource.Topic, "topicName", Action.TopicView)
        )
      )

  private def authorize(
      target: AnyEndpoint,
      requestPath: String,
      roles: Set[RoleName] = Set(reader),
      rbac: RbacPolicy = policy(Resource.Topic, Action.TopicView)
  ): IO[Either[kui.kernel.error.KuiError, Unit]] =
    FakeStructuredLogger[IO].flatMap { logger =>
      RbacGuard
        .fromPolicy[IO](rbac, _ => ClusterFlags.Writable, logger)
        .authorize(principal(roles.toSeq*), target, requestPath)
    }

  test("an endpoint that declares no permission is refused rather than allowed") {
    // The caller here holds the permission the *declared* endpoint needs, so a refusal cannot be a
    // permission failure: it is the guard refusing a question it could not form.
    authorize(undeclared, s"/internal/v1/clusters/${Cluster.value}/topics").map { decision =>
      assertEquals(decision.left.map(_.code.wire), Left("KUI-FORBIDDEN"))
    }
  }

  test("an endpoint naming a path parameter its path does not carry is refused") {
    authorize(misdeclared, s"/internal/v1/clusters/${Cluster.value}/topics/orders").map { decision =>
      assertEquals(decision.left.map(_.code.wire), Left("KUI-FORBIDDEN"))
    }
  }

  test("an undecidable endpoint is logged at error, because it is a bug and not a caller's mistake") {
    // `EndpointDecision`'s own scaladoc draws the distinction: a denial is a fact about the caller and
    // belongs in the audit trail; this is a fact about the code and should wake somebody. WARN and
    // ERROR are how the two are told apart by whatever reads the log.
    FakeStructuredLogger[IO].flatMap { logger =>
      val guard = RbacGuard.fromPolicy[IO](
        policy(Resource.Topic, Action.TopicView),
        _ => ClusterFlags.Writable,
        logger
      )

      guard
        .authorize(principal(reader), undeclared, s"/internal/v1/clusters/${Cluster.value}/topics")
        .flatMap(_ => logger.entries)
        .map { entries =>
          assertEquals(entries.map(_.level), List("error"))
          assert(
            entries.head.message.contains("could not be authorized"),
            entries.head.message
          )
        }
    }
  }

  test("a declared endpoint the caller holds the action for is allowed") {
    // The positive half, so that the two refusals above cannot be satisfied by a guard that refuses
    // everything — which is the shape a fail-closed assertion is easiest to fake with.
    authorize(declared, s"/internal/v1/clusters/${Cluster.value}/topics").map { decision =>
      assertEquals(decision, Right(()))
    }
  }

  test("a declared endpoint the caller holds no action for is refused by the ordinary denial branch") {
    authorize(
      declared,
      s"/internal/v1/clusters/${Cluster.value}/topics",
      rbac = policy(Resource.ConsumerGroup, Action.ConsumerGroupView)
    ).map { decision =>
      assertEquals(decision.left.map(_.code.wire), Left("KUI-FORBIDDEN"))
    }
  }

  test("a denial is logged at warn, and an undecidable endpoint at error") {
    FakeStructuredLogger[IO].flatMap { logger =>
      val guard = RbacGuard.fromPolicy[IO](
        policy(Resource.ConsumerGroup, Action.ConsumerGroupView),
        _ => ClusterFlags.Writable,
        logger
      )

      guard
        .authorize(principal(reader), declared, s"/internal/v1/clusters/${Cluster.value}/topics")
        .flatMap(_ => logger.entries)
        .map(entries => assertEquals(entries.map(_.level), List("warn")))
    }
  }
}
