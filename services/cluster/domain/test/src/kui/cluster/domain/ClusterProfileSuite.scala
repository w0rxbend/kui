package kui.cluster.domain

import org.scalacheck.Prop.forAll

import kui.kernel.cluster.{AdminTuning, BootstrapServers, ClientProperties, ClusterSecurity, PropertyValue}
import kui.kernel.error.DomainError
import kui.kernel.{ClusterId, Secret}
import kui.testkit.{ClusterGenerators, KuiSuite, RedactionAssertions}

/** The rules of the first real value object of the first real domain.
  *
  * Two of these tests are about the shape of the *reporting* rather than about a rule:
  * `accumulatesEveryViolation` fails if `from` is ever rewritten as a `for`-comprehension over `Either`,
  * which short-circuits, and `profileToStringRedactsEverySecret` is the domain's half of the promise that no
  * secret reaches a log line.
  */
final class ClusterProfileSuite extends KuiSuite {

  import ClusterProfileFixtures.arbitraryProfile

  private def build(
      name: String = "Production",
      properties: ClientProperties = ClientProperties.empty,
      colour: Option[String] = None,
      version: ProfileVersion = ProfileVersion.Static
  ): Either[DomainError, ClusterProfile] =
    ClusterProfile.from(
      id = ClusterId.unsafe("prod"),
      displayName = name,
      bootstrap = BootstrapServers.unsafe("broker-1:9092"),
      security = ClusterSecurity.Plaintext,
      properties = properties,
      admin = AdminTuning.default,
      readOnly = false,
      colour = colour,
      version = version,
      origin = ProfileOrigin.Static
    )

  private def fieldsOf(result: Either[DomainError, ClusterProfile]): List[String] =
    result.left.toOption.toList.flatMap(_.details.flatMap(_.field))

  test("acceptsAMinimalPlaintextProfile") {
    val built = build()

    assert(built.isRight, s"a minimal profile must be accepted, got $built")
    assertEquals(built.map(_.displayName), Right("Production"))
    assertEquals(built.map(_.origin), Right(ProfileOrigin.Static))
  }

  test("rejectsABlankDisplayName") {
    List("   ", "").foreach { name =>
      assertEquals(fieldsOf(build(name = name)), List("displayName"), s"'$name' must be refused")
    }
  }

  test("rejectsAnOverLongDisplayName") {
    // Both sides of the boundary, because an off-by-one here is invisible in any other test.
    assert(build(name = "x" * ClusterProfile.MaxDisplayNameLength).isRight)
    assert(build(name = "x" * (ClusterProfile.MaxDisplayNameLength + 1)).isLeft)
  }

  test("rejectsControlCharactersInTheDisplayName") {
    assertEquals(fieldsOf(build(name = "pr\u0007od")), List("displayName"))
  }

  test("accumulatesEveryViolation") {
    val result = build(
      name = "   ",
      properties = ClientProperties(("sasl.jaas.config", PropertyValue.Sensitive(Secret("x")))),
      colour = Some("neon")
    )

    assertEquals(result.left.toOption.map(_.details.size), Some(3))
    assertEquals(fieldsOf(result).toSet, Set("displayName", "colour", "properties"))
  }

  test("rejectsEveryReservedPropertyKey") {
    ClusterProfile.ReservedPropertyKeys.toList.sorted.foreach { key =>
      val result = build(properties = ClientProperties((key, PropertyValue.Plain("x"))))
      val restrictions = result.left.toOption.toList.flatMap(_.details.flatMap(_.restrictions))

      assertEquals(fieldsOf(result), List("properties"), s"'$key' must be refused")
      assert(restrictions.exists(_.contains(key)), s"the message must name '$key', got $restrictions")
    }
  }

  test("acceptsANonReservedPropertyKey") {
    assert(build(properties = ClientProperties(("reconnect.backoff.ms", PropertyValue.Plain("100")))).isRight)
  }

  test("colourIsCaseInsensitiveAndClosed") {
    assertEquals(build(colour = Some("AMBER")).map(_.colour), Right(Some(ColourTag.Amber)))
    assert(build(colour = Some("neon")).isLeft)
  }

  test("versionOrderingAndNext") {
    assertEquals(ProfileVersion.Static.next.value, 1L)
    assert(ProfileVersion.from(-1L).isLeft)
    assert(ProfileVersion.from(0L).isRight)
    assert(Ordering[ProfileVersion].lt(ProfileVersion.Static, ProfileVersion.Static.next))
  }

  property("refAndLabelDoNotCarryConnectionSettings") {
    forAll { (profile: ClusterProfile) =>
      val rendered = s"${profile.ref}|${profile.label}"

      RedactionAssertions.assertNoLeak(rendered, profile.bootstrap.value)

      ClusterGenerators.secretsOfSecurity(profile.security).filter(_.nonEmpty).foreach { secret =>
        RedactionAssertions.assertNoLeak(rendered, secret)
      }
    }
  }

  property("profileToStringRedactsEverySecret") {
    forAll { (profile: ClusterProfile) =>
      // The first place a profile is printed is a log line, not a response body, which is why this
      // assertion is here and not only in the contract suite.
      ClusterGenerators.secretsOfSecurity(profile.security).filter(_.nonEmpty).foreach { secret =>
        RedactionAssertions.assertNoLeak(profile.toString, secret)
      }
    }
  }
}
