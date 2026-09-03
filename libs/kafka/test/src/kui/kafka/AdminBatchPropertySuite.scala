package kui.kafka

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.apache.kafka.common.errors.TopicAuthorizationException
import org.scalacheck.Prop.forAll
import org.scalacheck.{Gen, Prop}

import kui.testkit.KuiSuite

/** The two batching properties, in a ScalaCheck suite of their own.
  *
  * They live apart from `AdminBatchSuite` because that suite's subject is *time* — it runs under
  * `TestControl` on a `CatsEffectSuite`, which has no `property`. These two are about shape rather
  * than timing, so they run on the real runtime and are fast anyway.
  */
final class AdminBatchPropertySuite extends KuiSuite {

  private val operation = "describeConfigs"

  property("chunksArePartitionsOfTheInput") {
    forAll(Gen.listOf(Gen.chooseNum(0, 1000)), Gen.chooseNum(1, 50)) { (keys, size) =>
      val parts = AdminBatch.chunks(keys, size)

      assertEquals(parts.flatten, keys)
      assert(parts.forall(_.nonEmpty))
      assert(parts.forall(_.size <= size))
      Prop.passed
    }
  }

  property("everySkippedKeyHasAReasonAndEveryKeyComesBack") {
    // The invariant ADR-006 calls "never silent drops", asserted over arbitrary failure patterns
    // rather than assumed.
    val genPattern: Gen[List[Boolean]] = Gen.listOfN(6, Gen.oneOf(true, false))

    forAll(genPattern) { pattern =>
      val keys = (1 to 6).toList

      val result = AdminBatch
        .chunked[IO, Int, String](keys, chunkSize = 1, parallelism = 3, operation) { chunk =>
          if pattern(chunk.head - 1) then IO.pure(Map(chunk.head -> "v"))
          else IO.raiseError(new TopicAuthorizationException("no"))
        }
        .unsafeRunSync()

      assertEquals(result.requested, keys.toSet)
      assertEquals(result.values.keySet.intersect(result.skipped.keySet), Set.empty[Int])
      assert(result.skipped.values.forall(_.message.nonEmpty))
      Prop.passed
    }
  }
}
