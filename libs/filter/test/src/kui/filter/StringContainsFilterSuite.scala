package kui.filter

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalacheck.Prop.forAll
import org.scalacheck.{Arbitrary, Gen}

import kui.testkit.KuiSuite

/** MS-006: the plain text search, and the three things a user assumes about it without being told. */
final class StringContainsFilterSuite extends KuiSuite {

  private def record(
      key: String = "",
      value: String = "",
      headers: Map[String, String] = Map.empty
  ): FilterableRecord = FilterableRecord(0, 0L, 0L, key, value, headers)

  private def matches(needle: String, r: FilterableRecord): Boolean =
    StringContainsFilter[IO](needle).test(r).unsafeRunSync() match {
      case Right(result) => result
      case Left(error)   => fail(s"the string filter cannot fail, and did: $error")
    }

  test("it matches the key, the value and a header value, each on its own") {
    assert(matches("order", record(key = "order-1")))
    assert(matches("FAILED", record(value = """{"status":"FAILED"}""")))
    assert(matches("abc", record(headers = Map("trace-id" -> "abc"))))
  }

  test("it does not match a header name") {
    // Searching names would make a search for `trace` match every record in a topic whose producer sets a
    // `trace-id` header, which is a search that returns everything and therefore means nothing.
    assert(!matches("trace-id", record(headers = Map("trace-id" -> "abc"))))
  }

  test("it does not match a record that contains none of it") {
    assert(!matches("missing", record(key = "order-1", value = "ok", headers = Map("h" -> "v"))))
  }

  property("it is case-insensitive in both directions") {
    forAll(Gen.alphaStr.suchThat(_.nonEmpty)) { text =>
      matches(text.toUpperCase, record(value = text.toLowerCase)) &&
      matches(text.toLowerCase, record(value = text.toUpperCase))
    }
  }

  property("any substring of the value matches it") {
    forAll(Gen.alphaStr.suchThat(_.length > 3)) { text =>
      matches(text.substring(1, text.length - 1), record(value = text))
    }
  }

  property("any substring of any header value matches it") {
    forAll(Gen.alphaStr.suchThat(_.nonEmpty), Gen.alphaStr.suchThat(_.nonEmpty)) { (name, value) =>
      matches(value, record(headers = Map(name -> value)))
    }
  }

  property("the empty filter matches everything") {
    // Through the same code path as a real filter rather than by being special-cased away at the call
    // site, which is what stops the filtered and unfiltered behaviours drifting apart.
    forAll(Arbitrary.arbitrary[String], Arbitrary.arbitrary[String]) { (key, value) =>
      matches("", record(key = key, value = value))
    }
  }

  property("it never fails, for any needle and any record") {
    forAll(Arbitrary.arbitrary[String], Arbitrary.arbitrary[String], Arbitrary.arbitrary[String]) {
      (needle, key, value) =>
        StringContainsFilter[IO](needle).test(record(key = key, value = value)).unsafeRunSync().isRight
    }
  }

  test("the always predicate is the no-filter case, and it is a predicate like any other") {
    assertEquals(MessagePredicate.always[IO].test(record()).unsafeRunSync(), Right(true))
  }
}
