package kui.kernel

import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll

/** The redaction guarantees of `Secret`, checked on every rendering path a secret can escape
  * through.
  */
final class SecretSuite extends ScalaCheckSuite {

  property("toString never contains the value it wraps") {
    forAll { (raw: String) =>
      val rendered = Secret(raw).toString
      assertEquals(rendered, "Secret(***)")
      assert(
        raw.isEmpty || !rendered.contains(raw),
        s"the rendering '$rendered' leaked the secret"
      )
    }
  }

  test("string interpolation redacts, in every interpolator") {
    val secret = Secret("hunter2")
    assertEquals(s"$secret", "Secret(***)")
    assertEquals(s"password=$secret", "password=Secret(***)")
    assertEquals(f"$secret%s", "Secret(***)")
  }

  test("a case class that holds a secret redacts it too, because it delegates to toString") {
    final case class Config(user: String, password: Secret[String])
    assertEquals(Config("kui", Secret("hunter2")).toString, "Config(kui,Secret(***))")
  }

  test("a collection of secrets redacts every element") {
    assertEquals(List(Secret("a"), Secret("b")).toString, "List(Secret(***), Secret(***))")
    assertEquals(Some(Secret("a")).toString, "Some(Secret(***))")
  }

  property("two secrets are equal exactly when their values are") {
    forAll { (left: String, right: String) =>
      assertEquals(Secret(left) == Secret(right), left == right)
    }
  }

  test("a secret is never equal to its bare value") {
    assertNotEquals[Any, Any](Secret("hunter2"), "hunter2")
  }

  property("the hash code is the same for every secret, so it fingerprints nothing") {
    forAll { (raw: String) =>
      assertEquals(Secret(raw).hashCode, Secret("").hashCode)
    }
  }

  test("map derives a new secret without exposing the value on the way") {
    val derived = Secret("hunter2").map(_.getBytes("UTF-8").length)
    assertEquals(derived.value, 7)
    assertEquals(derived.toString, "Secret(***)")
  }

  test("value is the only way in") {
    assertEquals(Secret("hunter2").value, "hunter2")
  }

  test("Secret is not a case class, so nothing generates a leaking toString or a copy") {
    assert(
      compileErrors("""val p: Product = Secret("hunter2")""").nonEmpty,
      "Secret must not be a Product: a case class would generate a toString that prints the value"
    )
    assert(
      compileErrors("""Secret("hunter2").copy(underlying = "leaked")""").nonEmpty,
      "Secret must have no copy method"
    )
    assert(
      compileErrors("""val Secret(raw) = Secret("hunter2")""").nonEmpty,
      "Secret must not be destructurable by pattern matching"
    )
  }

  test("the constructor is private, so a secret can only be made through apply") {
    assert(
      compileErrors("""new Secret("hunter2")""").nonEmpty,
      "the constructor must be private"
    )
  }
}
