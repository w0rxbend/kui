package kui.http.principal

import java.security.MessageDigest

import munit.FunSuite

import kui.kernel.UserName
import kui.security.{Principal, PrincipalKind}

/** The identity a log line may carry, pinned against a digest this file computes for itself.
  *
  * There is a case for this rule already — `services/cluster/api`'s `PrincipalVerificationSuite` drives a
  * real request and reads `user.id` off the line — but it builds its expectation by *calling `hashedUserId`*,
  * so it passes at every value of the truncation: `.take(16)` made `.take(48)` left `libs.http.test` and
  * `services.cluster.api.test` at 259 of 259 green. That case is still the better test of the seam; this one
  * is the test of the figure, and the expectation is computed here from the algorithm the scaladoc names
  * rather than from the function under test.
  *
  * Sixteen is not arbitrary. It is eight bytes of the digest — far more than enough to tell the users of one
  * deployment apart, and short enough to read — and the reason the log carries a hash at all is that a log
  * file is read by more people, kept for longer and exported more often than any other store in the system.
  */
final class PrincipalIdentitySuite extends FunSuite {

  private def principal(name: String): Principal =
    Principal(UserName.unsafe(name), Set.empty, PrincipalKind.Session)

  /** SHA-256 as hex, computed here so that the expectation does not come from the code being checked. */
  private def sha256Hex(text: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(text.getBytes("UTF-8"))
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

  test("a hashed user id is the first sixteen hex characters of the SHA-256 of the login name") {
    val hashed = PrincipalVerification.hashedUserId(principal("alice"))

    assertEquals(hashed, sha256Hex("alice").take(16))
    assertEquals(hashed.length, 16)
  }

  test("the login name itself never appears in the hash") {
    // The property the truncation is in service of, stated so that a "hash" that prefixed the name
    // would fail here rather than only in a service's route suite.
    val hashed = PrincipalVerification.hashedUserId(principal("alice"))

    assert(!hashed.contains("alice"), hashed)
    assert(hashed.forall(character => "0123456789abcdef".contains(character)), hashed)
  }

  test("two people hash differently and one person hashes the same way twice") {
    // The whole question an operator has of this field — "are these two entries the same person?" —
    // and the only two answers it must get right.
    assertEquals(
      PrincipalVerification.hashedUserId(principal("alice")),
      PrincipalVerification.hashedUserId(principal("alice"))
    )
    assertNotEquals(
      PrincipalVerification.hashedUserId(principal("alice")),
      PrincipalVerification.hashedUserId(principal("bob"))
    )
  }
}
