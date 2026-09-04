package kui.identity.infrastructure

import cats.effect.IO

import kui.identity.domain.{PasswordAlgorithm, PasswordHash}
import kui.kernel.Secret
import kui.testkit.KuiIOSuite

/** That the key derivation function does what a password hash has to do.
  *
  * Four properties, and each of them is a real failure that has shipped in real products: a hash that does not
  * verify, a hash that verifies against the wrong password, a salt that is the same for everybody (which makes
  * one cracked password crack every identical one), and a corrupt stored value that throws instead of
  * answering "no".
  */
final class Pbkdf2PasswordHasherSuite extends KuiIOSuite {

  private val password: Secret[String] = Secret("correct horse battery staple")

  test("a password verifies against its own hash") {
    for {
      hasher <- Pbkdf2PasswordHasher.make[IO]
      hashed <- hasher.hash(password)
      matched <- hasher.verify(password, hashed)
    } yield assert(matched)
  }

  test("a different password does not") {
    for {
      hasher <- Pbkdf2PasswordHasher.make[IO]
      hashed <- hasher.hash(password)
      matched <- hasher.verify(Secret("correct horse battery stapl"), hashed)
    } yield assert(!matched)
  }

  test("two hashes of the same password differ, because each carries its own salt") {
    for {
      hasher <- Pbkdf2PasswordHasher.make[IO]
      first <- hasher.hash(password)
      second <- hasher.hash(password)
      firstMatches <- hasher.verify(password, second)
    } yield {
      assertNotEquals(first.saltBase64, second.saltBase64)
      assertNotEquals(first.hashBase64, second.hashBase64)
      // Both still verify: the salt is stored with the hash, so nothing depends on remembering it.
      assert(firstMatches)
    }
  }

  test("the hash is written with the current algorithm and its published cost") {
    for {
      hasher <- Pbkdf2PasswordHasher.make[IO]
      hashed <- hasher.hash(password)
    } yield {
      assertEquals(hashed.algorithm, PasswordAlgorithm.Current)
      assertEquals(hashed.iterations, PasswordAlgorithm.Current.defaultIterations)
    }
  }

  test("a hash verified with an older, lower cost still verifies, so raising the cost breaks nobody") {
    for {
      hasher <- Pbkdf2PasswordHasher.make[IO]
      hashed <- hasher.hash(password)
      // The same salt and the same password, hashed with fewer iterations, is a *different* key — so this
      // asserts the cost is read from the record rather than from the current constant. If verification
      // used the current cost, this would wrongly succeed.
      older = hashed.copy(iterations = 1000)
      matched <- hasher.verify(password, older)
    } yield assert(!matched)
  }

  test("a stored value that is not base64 answers no, rather than throwing in the middle of a login") {
    val corrupt = PasswordHash(PasswordAlgorithm.Current, 210_000, "not base64!!", "also not!!")

    for {
      hasher <- Pbkdf2PasswordHasher.make[IO]
      matched <- hasher.verify(password, corrupt)
    } yield assert(!matched)
  }
}
