package kui.schema.api

import cats.effect.IO

import kui.contracts.capability.CapabilityState
import kui.kernel.error.InfrastructureError
import kui.kernel.{ClusterId, Subject}
import kui.schema.application.{ClusterRegistries, RegistryProfile}
import kui.schema.contract.{SchemaEndpoints, SchemaMutationEndpoints}
import kui.schema.domain.*
import kui.security.audit.MutationKind
import kui.testkit.KuiIOSuite
import kui.testkit.fakes.FakeStructuredLogger

/** The capability report, which is how a deployment with no Schema Registry stays usable.
  *
  * The distinction these tests defend is the whole reason this service is written the way it is: "no registry
  * configured" hides the feature, "the registry is down" shows it degraded with a reason, and the two must
  * never be reported as the same thing. Reporting the first as the second puts a permanently red panel on a
  * deployment where nothing is wrong — which is how people learn to ignore red panels.
  */
final class SchemaCapabilitiesSuite extends KuiIOSuite {

  private val configured = ClusterId.unsafe("has-registry")
  private val bare = ClusterId.unsafe("no-registry")

  /** A port that either answers or refuses, and does nothing else. */
  private def port(failure: Option[kui.kernel.error.KuiError]): SchemaRegistryPort[IO] =
    new SchemaRegistryPort[IO] {
      private def answer[A](value: A) = IO.pure(failure.toLeft(value))
      def subjects = answer(List.empty[Subject])
      def versions(subject: Subject) = answer(None)
      def schema(subject: Subject, version: VersionSelector) = answer(None)
      def globalCompatibility = answer(CompatibilityLevel.Backward)
      def subjectCompatibility(subject: Subject) = answer(None)
      def setGlobalCompatibility(level: CompatibilityLevel) = answer(())
      def setSubjectCompatibility(subject: Subject, level: CompatibilityLevel) = answer(())
      def checkCompatibility(subject: Subject, version: VersionSelector, proposed: ProposedSchema) =
        answer(None)
    }

  private def registries(ports: Map[ClusterId, SchemaRegistryPort[IO]]): ClusterRegistries[IO] =
    new ClusterRegistries[IO] {
      val profiles = List(
        RegistryProfile(configured, "Has a registry", hasRegistry = true, readOnly = false),
        RegistryProfile(bare, "Has none", hasRegistry = false, readOnly = false)
      )
      def all = IO.pure(profiles)
      def profile(cluster: ClusterId) = IO.pure(profiles.find(_.cluster == cluster))
      def registry(cluster: ClusterId) = IO.pure(ports.get(cluster))
    }

  private def report(ports: Map[ClusterId, SchemaRegistryPort[IO]]) =
    FakeStructuredLogger[IO].flatMap(logger =>
      SchemaCapabilities.make[IO](registries(ports), logger).report
    )

  test("a cluster with no registry is not_configured, not degraded, and says which key to set") {
    report(Map(configured -> port(None))).map { capabilities =>
      val row = capabilities(bare)

      assertEquals(row.configured, false)
      assertEquals(row.status, CapabilityState.NotConfigured.status)
      assert(clue(row.reason).exists(_.contains("schemaRegistry.url")))
    }
  }

  test("a reachable registry is available and carries the cluster's display name") {
    report(Map(configured -> port(None))).map { capabilities =>
      val row = capabilities(configured)

      assertEquals(row.status, CapabilityState.Available.status)
      assertEquals(row.configured, true)
      assertEquals(row.name, Some("Has a registry"))
    }
  }

  test("a configured registry that does not answer is degraded and stays configured") {
    val down = InfrastructureError.Unreachable("schema-registry", "connection refused")

    report(Map(configured -> port(Some(down)))).map { capabilities =>
      val row = capabilities(configured)

      // `configured = true` is the load-bearing half: it is what stops the gateway folding this into
      // NotConfigured and hiding a feature that exists and is merely broken.
      assertEquals(row.configured, true)
      assertEquals(row.status, "degraded")
      // The reason is the error's own display message, which names the upstream and deliberately not
      // the cause: `InfrastructureError.Unreachable` drops the connection failure's text because that
      // text routinely contains a URL with a password in it. The screen therefore says which upstream
      // is unreachable, and the detail stays in the log.
      assert(clue(row.reason).exists(_.contains("schema-registry")))
    }
  }

  test("every configured cluster appears, so a registry outage never removes a row from the switcher") {
    report(Map(configured -> port(None))).map(capabilities =>
      assertEquals(capabilities.keySet, Set(configured, bare))
    )
  }

  test("a port that throws is reported as degraded rather than failing the whole report") {
    val throwing = new SchemaRegistryPort[IO] {
      private def boom[A]: IO[Either[kui.kernel.error.KuiError, A]] =
        IO.raiseError(new RuntimeException("a defect below the port"))
      def subjects = boom
      def versions(subject: Subject) = boom
      def schema(subject: Subject, version: VersionSelector) = boom
      def globalCompatibility = boom
      def subjectCompatibility(subject: Subject) = boom
      def setGlobalCompatibility(level: CompatibilityLevel) = boom
      def setSubjectCompatibility(subject: Subject, level: CompatibilityLevel) = boom
      def checkCompatibility(subject: Subject, version: VersionSelector, proposed: ProposedSchema) = boom
    }

    report(Map(configured -> throwing)).map { capabilities =>
      assertEquals(capabilities(configured).status, "degraded")
      // The other cluster's row survives, which is the point: one broken registry must not take the
      // report down at the moment the browser needs it most.
      assertEquals(capabilities(bare).status, CapabilityState.NotConfigured.status)
    }
  }
}

/** The seam between the contract and the audit vocabulary, which no other module can see at once. */
final class SchemaEndpointClassificationSuite extends munit.FunSuite {

  test("every endpoint this service publishes is classified as a mutation or explicitly as a read") {
    val published = SchemaEndpoints.all ++ SchemaMutationEndpoints.all

    val mutations = published.filter(kui.contracts.KuiEndpoint.isMutation)
    val reads = published.filterNot(kui.contracts.KuiEndpoint.isMutation)

    assertEquals(mutations.flatMap(_.info.name).toSet, Set(
      "schema.compatibility.global.set",
      "schema.compatibility.subject.set"
    ))

    // The compatibility check carries a body and is deliberately *not* a mutation: it registers nothing.
    // If that ever changes, this assertion is what says so.
    assert(reads.flatMap(_.info.name).contains("schema.compatibility.check"))
  }

  test("the contract's operation names are the audit vocabulary's, exactly") {
    val fromContract = Set(
      SchemaMutationEndpoints.SetGlobalCompatibilityOperation,
      SchemaMutationEndpoints.SetSubjectCompatibilityOperation
    )

    val fromAudit =
      Set(MutationKind.SetGlobalCompatibility.operation, MutationKind.SetSubjectCompatibility.operation)

    // Two spellings of one operation is how an audit trail comes to have two vocabularies, and this is
    // the only place in the build that can see both.
    assertEquals(fromContract, fromAudit)
  }

  test("every mutating endpoint declares itself non-destructive: a level can be set back") {
    val markers = SchemaMutationEndpoints.all.flatMap(_.attribute(kui.contracts.KuiEndpoint.MutationKey))

    assertEquals(markers.map(_.destructive).toSet, Set(false))
  }
}
