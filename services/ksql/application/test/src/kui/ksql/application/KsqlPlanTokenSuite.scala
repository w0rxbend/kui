package kui.ksql.application

import java.time.Instant

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite

import kui.kernel.error.ErrorCode
import kui.kernel.{ClusterId, Secret}

/** The token that says "run exactly the statement the operator was shown" (ADR-045).
  *
  * The properties below are the ones an attacker's day is spent against, and every one of them is asserted by
  * *failing* rather than by succeeding: a token that verifies is one line, and a token that must not verify
  * is the whole point of the type.
  */
final class KsqlPlanTokenSuite extends CatsEffectSuite {

  private val key: Secret[Array[Byte]] = Secret(Array.fill[Byte](32)(7))
  private val other: Secret[Array[Byte]] = Secret(Array.fill[Byte](32)(9))

  private val tokens = KsqlPlanToken.make[IO](key)
  private val elsewhere = KsqlPlanToken.make[IO](other)

  private val cluster = ClusterId.unsafe("prod-eu")
  private val staging = ClusterId.unsafe("staging")

  private val statement = "DROP STREAM ORDERS DELETE TOPIC;"
  private val now = Instant.parse("2026-09-03T10:11:12Z")
  private val expiry = now.plus(KsqlPlanToken.Ttl)

  test("a token minted for a statement verifies for that statement") {
    for {
      token <- tokens.mint(cluster, statement, expiry)
      answer <- tokens.verify(cluster, statement, token, now)
    } yield assertEquals(answer, Right(()))
  }

  test("a token cannot be spent on a different statement") {
    // The substitution the two-phase flow exists to make impossible, in the shape a free-form editor makes
    // it take: everything else about the request is identical, and only the text differs.
    for {
      token <- tokens.mint(cluster, statement, expiry)
      answer <- tokens.verify(cluster, "DROP TABLE USERS DELETE TOPIC;", token, now)
    } yield assertEquals(answer.left.toOption.map(_.code), Some(ErrorCode.Validation))
  }

  test("a token cannot be spent on a different cluster") {
    // A plan for `ORDERS` on staging must not be applicable to `ORDERS` on production, which is the same
    // statement text against a different set of records.
    for {
      token <- tokens.mint(staging, statement, expiry)
      answer <- tokens.verify(cluster, statement, token, now)
    } yield assert(answer.isLeft, clue = answer)
  }

  test("a token past its expiry is refused, and one a second before it is not") {
    for {
      token <- tokens.mint(cluster, statement, expiry)
      justInTime <- tokens.verify(cluster, statement, token, expiry.minusSeconds(1))
      tooLate <- tokens.verify(cluster, statement, token, expiry.plusSeconds(1))
    } yield {
      assertEquals(justInTime, Right(()))
      assert(tooLate.isLeft, clue = tooLate)
    }
  }

  test("a token signed with another key is refused, and the payload is never parsed") {
    // The signature is checked before anything is read out of the payload. A codec that parsed first is one
    // an attacker can drive with a payload they never had to sign.
    for {
      forged <- elsewhere.mint(cluster, statement, expiry)
      answer <- tokens.verify(cluster, statement, forged, now)
    } yield assert(answer.isLeft, clue = answer)
  }

  test("a token whose payload was edited is refused") {
    for {
      token <- tokens.mint(cluster, statement, expiry)
      tampered = token.take(4) + "AAAA" + token.drop(8)
      answer <- tokens.verify(cluster, statement, tampered, now)
    } yield assert(answer.isLeft, clue = answer)
  }

  test("anything that is not a token at all is refused rather than throwing") {
    val rubbish = List("", ".", "a.b.c", "not-base64!.also-not", "eyJ2IjoxfQ")

    rubbish.traverse_(raw =>
      tokens.verify(cluster, statement, raw, now).map(answer => assert(answer.isLeft, clue = raw))
    )
  }

  test("the refusal says one thing for four different failures, because the alternative is an oracle") {
    for {
      token <- tokens.mint(cluster, statement, expiry)
      wrongStatement <- tokens.verify(cluster, "DROP TABLE USERS DELETE TOPIC;", token, now)
      wrongCluster <- tokens.verify(staging, statement, token, now)
      expired <- tokens.verify(cluster, statement, token, expiry.plusSeconds(1))
    } yield {
      val messages = List(wrongStatement, wrongCluster, expired).flatMap(_.left.toOption).map(_.message)

      assertEquals(messages.distinct.size, 1, clue = messages)
    }
  }

  test("the fingerprint is a hash rather than the statement, so a long statement is a short token") {
    // The reason the payload carries a digest: a sixteen-kilobyte statement would otherwise make a
    // sixteen-kilobyte token, and a token that carried the text would put somebody's SQL into every log
    // that records a request body.
    val long = "SELECT * FROM ORDERS WHERE NOTE = '" + "x" * 8000 + "';"

    tokens.mint(cluster, long, expiry).map { token =>
      assert(clue(token.length) < 200)
      assert(!token.contains("xxxx"))
    }
  }

  test("the operation name in the payload is what keeps this token apart from the topic service's") {
    // Both take ADR-026's cursor key, so a deployment configures one secret. What stops a topic-deletion
    // confirmation being spent on a ksqlDB statement is the operation string inside the signed payload.
    assertEquals(KsqlPlanToken.Operation, "ksql.statement")
  }

  test("the fingerprint of two different statements differs, and of the same statement does not") {
    assertEquals(KsqlPlanToken.fingerprintOf(statement), KsqlPlanToken.fingerprintOf(statement))
    assertNotEquals(
      KsqlPlanToken.fingerprintOf(statement),
      KsqlPlanToken.fingerprintOf("DROP TABLE USERS DELETE TOPIC;")
    )
  }

  test("five minutes is the window ADR-045 fixes") {
    assertEquals(KsqlPlanToken.Ttl, java.time.Duration.ofMinutes(5))
  }
}
