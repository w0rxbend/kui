package kui.identity.domain

import kui.kernel.Secret
import kui.testkit.KuiSuite

/** That an encoded password hash survives the round trip, and that everything which is not one is refused
  * with a message rather than an exception.
  *
  * The refusals matter more than the round trip. A hash is read out of a configuration file an operator
  * typed, and the two ways of getting it wrong — a truncated paste and a hand-edited iteration count — both
  * produce a string that looks close enough to be plausible.
  */
final class PasswordHashSuite extends KuiSuite {

  private val encoded: String = "pbkdf2-sha256$210000$c2FsdHktc2FsdA$ZGVyaXZlZC1rZXk"

  test("an encoded hash parses into its four parts and encodes back to the same string") {
    val parsed = PasswordHash.parse(Secret(encoded)).fold(problem => fail(problem.message), identity)

    assertEquals(parsed.algorithm, PasswordAlgorithm.Pbkdf2HmacSha256)
    assertEquals(parsed.iterations, 210000)
    assertEquals(parsed.saltBase64, "c2FsdHktc2FsdA")
    assertEquals(parsed.hashBase64, "ZGVyaXZlZC1rZXk")
    assertEquals(parsed.encoded, encoded)
  }

  test("the algorithm travels in the hash, so a future one can be added without a migration") {
    // Not a bug report: this is the property the format exists for. A hash written by today's algorithm
    // keeps naming today's algorithm, whatever `PasswordAlgorithm.Current` becomes later.
    assertEquals(PasswordAlgorithm.Current, PasswordAlgorithm.Pbkdf2HmacSha256)
    assertEquals(PasswordAlgorithm.fromWire("pbkdf2-sha256"), Some(PasswordAlgorithm.Pbkdf2HmacSha256))
    assertEquals(PasswordAlgorithm.fromWire("bcrypt"), None)
  }

  test("a truncated hash is refused, and the failure does not echo the value") {
    val truncated = "pbkdf2-sha256$210000$c2FsdHktc2FsdA"
    val problem = PasswordHash
      .parse(Secret(truncated))
      .fold(identity, _ => fail("expected a truncated hash to be refused"))

    assert(problem.message.contains("four"), problem.message)
    assert(!problem.message.contains("c2FsdHktc2FsdA"), problem.message)
  }

  test("an unknown algorithm names the ones that exist") {
    val problem = PasswordHash
      .parse(Secret("bcrypt$12$c2FsdA$aGFzaA"))
      .fold(identity, _ => fail("expected an unknown algorithm to be refused"))

    assert(problem.message.contains("pbkdf2-sha256"), problem.message)
  }

  test("an iteration count that is not a positive number is refused") {
    List("pbkdf2-sha256$0$c2FsdA$aGFzaA", "pbkdf2-sha256$many$c2FsdA$aGFzaA").foreach { bad =>
      assert(PasswordHash.parse(Secret(bad)).isLeft, bad)
    }
  }

  test("an empty salt or key is refused, because both would verify against nothing") {
    assert(PasswordHash.parse(Secret("pbkdf2-sha256$210000$$aGFzaA")).isLeft)
    assert(PasswordHash.parse(Secret("pbkdf2-sha256$210000$c2FsdA$")).isLeft)
  }
}

/** That the password rules are the ones NIST still recommends, and that they are enforced as values. */
final class PasswordRulesSuite extends KuiSuite {

  test("a password shorter than the minimum is refused, and says both bounds") {
    val problem = PasswordRules
      .check(Secret("short"))
      .fold(identity, _ => fail("expected a short password to be refused"))

    assert(problem.message.contains(s"${PasswordRules.MinimumLength} characters"), problem.message)
  }

  test("a password at the minimum length is accepted") {
    val exactly = "a" * PasswordRules.MinimumLength
    assertEquals(PasswordRules.check(Secret(exactly)).map(_.value), Right(exactly))
  }

  test("an enormous password is refused, because hashing it is the denial of service") {
    assert(PasswordRules.check(Secret("a" * (PasswordRules.MaximumLength + 1))).isLeft)
  }

  test("there is no composition rule, deliberately") {
    // A long passphrase with no digit and no symbol is exactly what SP 800-63B asks for, and a rule that
    // refused it would be pushing people back towards `Password1!`.
    assert(PasswordRules.check(Secret("correct horse battery staple")).isRight)
  }
}
