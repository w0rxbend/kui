package kui.cluster.domain

import java.time.Instant

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import kui.kernel.error.{DomainError, ErrorCode}
import kui.testkit.KuiSuite

/** `Ping`'s one rule, from both sides.
  *
  * The point of the suite is not the rule — "not empty, at most 128 characters" is hardly subtle. It is that
  * the rule is enforced in exactly one place and reported as a value with a field name attached, because
  * every other value object in the project is about to be written the same way.
  */
final class PingSuite extends KuiSuite {

  private val at: Instant = Instant.parse("2026-09-03T10:11:12Z")

  private val acceptedMessage: Gen[String] =
    Gen.choose(1, Ping.MaxMessageLength).flatMap(length => Gen.stringOfN(length, Gen.asciiPrintableChar))

  test("a message of a sensible length is accepted, and keeps both of its fields") {
    assertEquals(Ping.from("hello", at), Right(Ping.from("hello", at).toOption.get))
    assertEquals(Ping.from("hello", at).map(_.message), Right("hello"))
    assertEquals(Ping.from("hello", at).map(_.at), Right(at))
  }

  test("an empty message is refused, and the refusal names the field") {
    Ping.from("", at) match {
      case Right(ping) => fail(s"an empty message should not have produced $ping")
      case Left(error) =>
        assertEquals(error.code, ErrorCode.Validation)
        assertEquals(error.details.flatMap(_.field), List("message"))
    }
  }

  test("a message longer than the limit is refused") {
    val tooLong = "x" * (Ping.MaxMessageLength + 1)
    assert(Ping.from(tooLong, at).isLeft, "a 129-character message should have been refused")
  }

  test("the message exactly at the limit is accepted: the bound is inclusive") {
    assert(Ping.from("x" * Ping.MaxMessageLength, at).isRight)
  }

  property("every message between one character and the limit is accepted") {
    forAll(acceptedMessage) { message =>
      Ping.from(message, at).map(_.message) == Right(message)
    }
  }

  property("no message outside those bounds is ever accepted") {
    val rejected = Gen.oneOf(
      Gen.const(""),
      Gen
        .choose(Ping.MaxMessageLength + 1, Ping.MaxMessageLength + 64)
        .flatMap(length => Gen.stringOfN(length, Gen.const('x')))
    )

    forAll(rejected) { message =>
      Ping.from(message, at) match {
        case Left(_: DomainError) => true
        case _ => false
      }
    }
  }
}
