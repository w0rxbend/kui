package kui.gateway.application.cluster

import java.time.Instant

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite

/** That the fallback holds one value and cannot be left half-written.
  *
  * It is three methods and it is still worth its own suite: everything that reads it is reading it *because*
  * an upstream has already failed, which is the worst moment to discover that the cache is empty when it
  * should not be, or holds a value merged from two different answers.
  */
final class LastKnownSuite extends CatsEffectSuite {

  private val at = Instant.parse("2026-09-03T10:11:12Z")

  test("there is nothing before the first success") {
    // `None` rather than an empty list: "nothing has ever worked" and "the answer was empty" are different
    // statements, and only the second one is safe to show a user as data.
    LastKnown.of[IO, List[String]].flatMap(_.get).map(assertEquals(_, None))
  }

  test("a value is remembered with the instant it was fetched") {
    for {
      cache <- LastKnown.of[IO, List[String]]
      _ <- cache.put(List("a"), at)
      held <- cache.get
    } yield assertEquals(held, Some((List("a"), at)))
  }

  test("a later value replaces the earlier one whole") {
    // Whole, not merged: a merge would produce a row set that no upstream ever returned.
    for {
      cache <- LastKnown.of[IO, List[String]]
      _ <- cache.put(List("a", "b"), at)
      _ <- cache.put(List("c"), at.plusSeconds(30))
      held <- cache.get
    } yield assertEquals(held, Some((List("c"), at.plusSeconds(30))))
  }

  test("a thousand concurrent writers leave exactly one of their values") {
    for {
      cache <- LastKnown.of[IO, Int]
      _ <- (1 to 1000).toList.parTraverse_(n => cache.put(n, at.plusSeconds(n.toLong)))
      held <- cache.get
    } yield held match {
      case Some((value, when)) =>
        // Whichever writer won, its value and its timestamp came from the same write. A torn pair - one
        // writer's rows with another's fetch time - would be a stale marker that lies about its age.
        assertEquals(when, at.plusSeconds(value.toLong))
      case None => fail("a thousand writes must leave a value")
    }
  }
}
