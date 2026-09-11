package kui.message.app

import cats.effect.IO

import kui.testkit.KuiIOSuite

/** The cursor-signing key, and the two numbers this composition root pins.
  *
  * `services/message/app` declared a test module in `build.mill` and shipped no test source, so the whole of
  * this file was ungated: a cursor is trusted precisely because it was signed, and a key that was a literal —
  * or sixteen bytes instead of thirty-two — would let anyone mint a cursor naming any cluster with every
  * suite in the repository green.
  */
final class MessageWiringSuite extends KuiIOSuite {

  test("each process's cursor key is freshly random, and never a literal") {
    // Two keys, taken the way the composition root takes one. Equal keys mean either a constant or a
    // seeded generator; both are the failure `newCursorKey`'s own docstring argues against.
    for {
      first <- MessageWiring.newCursorKey[IO]
      second <- MessageWiring.newCursorKey[IO]
    } yield {
      assertEquals(first.value.length, MessageWiring.CursorKeyBytes)
      assert(
        !java.util.Arrays.equals(first.value, second.value),
        "two cursor keys minted in one process were identical, so the key is predictable"
      )
      assert(
        first.value.exists(_ != 0.toByte),
        "the cursor key is all zeroes, which is a literal wearing a random's clothes"
      )
    }
  }

  test("the key is 256 bits, which is the block size HMAC-SHA256 wants") {
    // Written out rather than read from the constant, for the reason `SchemaWiringSuite` gives: a case
    // that reads the constant it is checking passes at every value of it.
    assertEquals(MessageWiring.CursorKeyBytes, 32)
  }

  test("the service and its instrumentation are named the same thing everywhere") {
    // A dashboard filtering on the meter's scope and a log search filtering on `service.name` have to
    // agree; nothing else in this repository compares them.
    assertEquals(MessageWiring.Instrumentation, "kui.message")
    assertEquals(kui.message.api.MessageApi.Id.value, "message")
    assertEquals(ClusterSerdeFactories.Attribution.value, "message")
  }

  test("the serde profile version is one, because a restart is what changes a static profile") {
    // It keys the serde registry's caches. Moving it without moving the cache keys would serve a
    // decoded record from the previous profile; it starts mattering when profiles become editable.
    assertEquals(MessageWiring.ProfileVersion, 1L)
  }
}
