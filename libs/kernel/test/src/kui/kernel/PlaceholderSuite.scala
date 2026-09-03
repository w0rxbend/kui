package kui.kernel

import munit.FunSuite

/** Proves that both test runners — the JVM one and the Scala.js one — actually execute a test.
  *
  * It asserts nothing about KUI. Its only job is that a green `libs.kernel.jvm.test` and a green
  * `libs.kernel.js.test` mean the wiring works, so that the first real suite (KERN-001) fails for
  * reasons in its own code rather than in the build. KERN-001 replaces it.
  */
final class PlaceholderSuite extends FunSuite {
  test("the test runner runs a test") {
    assertEquals(1 + 1, 2)
  }
}
