package kui.config

import org.scalacheck.{Gen, Prop}

import kui.kernel.ClusterId
import kui.testkit.KuiSuite

/** ADR-031's slug derivation: what an operator's display name turns into in every URL, cache key and RBAC
  * rule.
  *
  * The rule matters more than it looks. The id is the durable identifier, so a derivation that quietly
  * produced something illegal — a trailing dash, an empty string, a 200-character name — would either fail
  * far away from the file that caused it or put a name the operator never chose into their bookmarks.
  */
final class ClusterIdSlugSuite extends KuiSuite {

  private def slug(name: String): String =
    ClusterConfig.slug(name).fold(problem => fail(problem), _.value)

  test("theDocumentedExamples") {
    assertEquals(slug("Production EU"), "production-eu")
    assertEquals(slug("prod  /  eu"), "prod-eu")
    assertEquals(slug("kafka_1"), "kafka-1")
    assertEquals(slug("  Staging  "), "staging")
    assertEquals(slug("EU-West-1"), "eu-west-1")
  }

  test("aNameWithNothingSluggableIsALeftNamingTheCluster") {
    ClusterConfig.slug("***") match {
      case Left(problem) =>
        assert(problem.contains("'***'"), problem)
        assert(problem.contains("kui.clusters.<n>.id"), problem)
      case Right(id) => fail(s"expected a failure, got '${id.value}'")
    }
  }

  test("aNameInANonLatinScriptIsALeftRatherThanAnInventedId") {
    assert(ClusterConfig.slug("производство").isLeft)
  }

  test("aNameLongerThanTheIdLimitIsTruncatedRatherThanRefused") {
    val id = slug("a" * 100)
    assertEquals(id.length, 64)
    assertEquals(ClusterId.from(id).map(_.value), Right(id))
  }

  test("aTruncationNeverLeavesATrailingDash") {
    // 63 letters, then a space: truncating at 64 would leave `...a-`, which is not a legal id.
    val id = slug(("a" * 63) + " b")
    assertEquals(id, "a" * 63)
  }

  property("everyDerivedIdIsAValidClusterId") {
    val names = Gen.nonEmptyListOf(Gen.oneOf(Gen.alphaNumChar, Gen.const(' '), Gen.oneOf('-', '_', '/', '.', 'é')))

    Prop.forAll(names.map(_.mkString)) { name =>
      ClusterConfig.slug(name) match {
        case Right(id) => ClusterId.from(id.value) == Right(id)
        case Left(_) => true
      }
    }
  }
}
