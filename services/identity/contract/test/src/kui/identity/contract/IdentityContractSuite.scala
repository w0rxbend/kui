package kui.identity.contract

import io.circe.parser
import io.circe.syntax.*

import kui.contracts.rbac.EndpointAuthorization
import kui.identity.contract.dto.*
import munit.ScalaCheckSuite

/** That what this service encodes is what a browser decodes, and that no endpoint is published without
  * saying what it needs permission for.
  *
  * The suite is cross-compiled and runs on the JVM and in a JavaScript engine, which is the point: the two
  * halves of every one of these shapes are compiled from this source, and the assertion that matters is that
  * both platforms agree about the discriminator, the field names and the optional fields.
  */
final class IdentityContractSuite extends ScalaCheckSuite {

  private def roundTrip[A: io.circe.Codec](value: A): A =
    parser.decode[A](value.asJson.noSpaces).fold(error => fail(error.getMessage), identity)

  test("the settings document round-trips, with and without a provider") {
    val withProvider = AuthSettingsDto("oidc", Some("Example SSO"), rbacEnabled = true)
    val without = AuthSettingsDto("disabled", None, rbacEnabled = false)

    assertEquals(roundTrip(withProvider), withProvider)
    assertEquals(roundTrip(without), without)
  }

  test("the settings document's field names are the ones a browser reads") {
    val json = AuthSettingsDto("form", None, rbacEnabled = true).asJson.noSpaces

    assert(json.contains("\"authType\":\"form\""), json)
    assert(json.contains("\"rbacEnabled\":true"), json)
  }

  test("a login response round-trips through both of its shapes, discriminated by status") {
    val signedIn: LoginResponse =
      LoginResponse.SignedIn(IdentityPrincipalDto("ada", List("operators"), "session"))
    val change: LoginResponse = LoginResponse.PasswordChangeRequired("a-challenge")

    assertEquals(roundTrip(signedIn), signedIn)
    assertEquals(roundTrip(change), change)
    assert(signedIn.asJson.noSpaces.contains(s"\"status\":\"${LoginResponse.SignedInStatus}\""))
    assert(change.asJson.noSpaces.contains(s"\"status\":\"${LoginResponse.PasswordChangeStatus}\""))
  }

  test("a login response with a status nobody knows is a decode failure, not a silent success") {
    val unknown = """{"status":"maybe","principal":{"name":"ada","roles":[],"kind":"session"}}"""

    assert(parser.decode[LoginResponse](unknown).isLeft, unknown)
  }

  test("a signed-in response carries no challenge, and a required change carries no principal") {
    // The two shapes are exclusive on purpose: a caller must not be able to read a principal off a
    // response that deliberately granted none.
    val change = (LoginResponse.PasswordChangeRequired("a-challenge"): LoginResponse).asJson.noSpaces
    val signedIn =
      (LoginResponse.SignedIn(IdentityPrincipalDto("ada", Nil, "session")): LoginResponse).asJson.noSpaces

    assert(!change.contains("principal"), change)
    assert(!signedIn.contains("challenge"), signedIn)
  }

  test("a grant round-trips, including the unnamed resources whose value is absent") {
    val named = GrantDto(List("local"), "TOPIC", Some(".*"), List("VIEW", "MESSAGES_READ"))
    val unnamed = GrantDto(List("*"), "AUDIT", None, List("VIEW"))

    assertEquals(roundTrip(named), named)
    assertEquals(roundTrip(unnamed), unnamed)
    assertEquals(roundTrip(PermissionsResponse(List(named, unnamed))).permissions.size, 2)
  }

  test("every published endpoint declares what it needs permission for") {
    // The same rule every other service's contract suite applies. An endpoint added next year with no
    // declaration is a build failure here rather than a hole nobody notices.
    val undeclared = IdentityEndpoints.all.filter(endpoint => EndpointAuthorization.of(endpoint).isEmpty)

    assertEquals(undeclared.flatMap(_.info.name), Nil)
  }

  test("every published endpoint has a name, and the names are unique") {
    val names = IdentityEndpoints.all.flatMap(_.info.name)

    assertEquals(names.size, IdentityEndpoints.all.size)
    assertEquals(names.distinct.size, names.size)
  }

  test("every path starts at the identity segment and never at the public prefix") {
    // `/api/v1` belongs to the gateway. A service that declared it would be publishing a second public
    // surface, which is the drift the architecture forbids.
    val paths = IdentityEndpoints.all.map(_.show)

    assert(paths.forall(_.contains(IdentityEndpoints.Segment)), paths.toString)
    assert(paths.forall(!_.contains("/api/")), paths.toString)
  }
}
