package kui.security

import java.time.Instant

import cats.Id
import munit.FunSuite

import kui.kernel.{RoleName, ServiceId, UserName}

/** The rules a principal token is checked against, exercised through the unsigned in-process codec.
  *
  * They are asserted here rather than only in the JVM suite on purpose: the checks are shared code,
  * so the all-in-one deployment and the distributed one cannot disagree about what a valid token is,
  * and this suite runs on both platforms.
  */
final class PrincipalCodecSuite extends FunSuite {

  private val now: Instant     = Instant.parse("2026-09-03T10:00:00Z")
  private val service          = ServiceId.unsafe("topic")
  private val digest           = RequestDigest.ofRequestLine("get", "/internal/v1/topics")
  private val codec            = PrincipalCodec.inProcess[Id]

  private def claims(
      audience: ServiceId = service,
      expiresAt: Instant = now.plusSeconds(60),
      request: RequestDigest = digest
  ): PrincipalClaims =
    PrincipalClaims(
      subject = UserName.unsafe("ada"),
      roles = Set(RoleName.unsafe("viewer"), RoleName.unsafe("operator")),
      kind = PrincipalKind.Session,
      sessionRef = Some(SessionRef("f00d")),
      issuedAt = now,
      expiresAt = expiresAt,
      audience = audience,
      requestDigest = request
    )

  test("a token the in-process codec minted verifies back to the principal it asserts") {
    val token  = codec.sign(claims())
    val result = codec.verify(token, service, digest, now)

    assertEquals(
      result,
      Right(
        Principal(
          UserName.unsafe("ada"),
          Set(RoleName.unsafe("viewer"), RoleName.unsafe("operator")),
          PrincipalKind.Session
        )
      )
    )
  }

  test("a token minted for another service is refused, and the reason says so") {
    val token = codec.sign(claims(audience = ServiceId.unsafe("cluster")))
    assertEquals(
      codec.verify(token, service, digest, now),
      Left(PrincipalError.WrongAudience(service, ServiceId.unsafe("cluster")))
    )
  }

  test("a token that has expired is refused") {
    val expiry = now.minusSeconds(60)
    val token  = codec.sign(claims(expiresAt = expiry))
    assertEquals(codec.verify(token, service, digest, now), Left(PrincipalError.Expired(expiry)))
  }

  test("a token is still accepted five seconds past its expiry, and refused six") {
    val expiry = now.minusSeconds(5)
    val token  = codec.sign(claims(expiresAt = expiry))

    assert(codec.verify(token, service, digest, now).isRight, "five seconds of skew is allowed")
    assert(
      codec.verify(token, service, digest, now.plusSeconds(1)).isLeft,
      "six seconds of skew is not"
    )
  }

  test("a token minted for another call is refused even though everything else matches") {
    val token = codec.sign(claims(request = RequestDigest.ofRequestLine("DELETE", "/topics/orders")))
    assertEquals(codec.verify(token, service, digest, now), Left(PrincipalError.RequestMismatch))
  }

  test("something that is not a token at all is refused as malformed, not as a bad signature") {
    codec.verify(SignedPrincipal.unsafe("not json"), service, digest, now) match {
      case Left(PrincipalError.Malformed(_)) => ()
      case other                             => fail(s"expected a malformed token, got $other")
    }
  }

  test("an empty header is not a token") {
    assertEquals(SignedPrincipal.from(""), Left(PrincipalError.Missing))
    assertEquals(SignedPrincipal.from("   "), Left(PrincipalError.Missing))
    assertEquals(SignedPrincipal.from("abc").map(_.value), Right("abc"))
  }

  test("the claims round-trip through their JSON with every field intact") {
    val original = claims()
    val json     = io.circe.syntax.EncoderOps(original).asJson.noSpaces
    assertEquals(io.circe.parser.decode[PrincipalClaims](json), Right(original))
  }

  test("the claim names on the wire are the short ones ADR-020 fixes") {
    val json = io.circe.syntax.EncoderOps(claims()).asJson
    assertEquals(json.hcursor.keys.map(_.toList.sorted), Some(List("aud", "exp", "iat", "kind", "req", "roles", "sid", "sub")))
    assertEquals(json.hcursor.downField("req").keys.map(_.toList.sorted), Some(List("b", "m", "p")))
  }

  test("a request-line digest normalises the method and stands in a body hash of nothing") {
    val digestOfGet = RequestDigest.ofRequestLine("get", "/topics")
    assertEquals(digestOfGet.method, "GET")
    assertEquals(digestOfGet.bodySha256, RequestDigest.EmptyBodySha256)
    assertEquals(digestOfGet, RequestDigest.ofRequestLine("GET", "/topics"))
  }

  test("two different calls never share a digest") {
    assertNotEquals(
      RequestDigest.ofRequestLine("GET", "/topics"),
      RequestDigest.ofRequestLine("GET", "/topics/orders")
    )
    assertNotEquals(
      RequestDigest.ofRequestLine("GET", "/topics"),
      RequestDigest.ofRequestLine("DELETE", "/topics")
    )
  }

  test("the anonymous principal holds no roles") {
    assertEquals(Principal.Anonymous.roles, Set.empty[RoleName])
    assertEquals(Principal.Anonymous.kind, PrincipalKind.Anonymous)
    assertEquals(Principal.Anonymous.name.value, "anonymous")
  }

  test("every rejection reason has a distinct lowercase metric label") {
    val reasons = List(
      PrincipalError.Missing,
      PrincipalError.Malformed("x"),
      PrincipalError.BadSignature,
      PrincipalError.UnknownKeyId("k1"),
      PrincipalError.Expired(now),
      PrincipalError.WrongAudience(service, service),
      PrincipalError.RequestMismatch
    ).map(_.metricLabel)

    assertEquals(reasons.distinct.size, reasons.size)
    reasons.foreach(reason => assertEquals(reason, reason.toLowerCase))
  }
}
