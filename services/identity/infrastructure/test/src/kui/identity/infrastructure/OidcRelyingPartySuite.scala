package kui.identity.infrastructure

import java.time.Instant
import java.util.Date

import cats.effect.IO
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.{JWKSet, RSAKey}
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import sttp.client4.impl.cats.implicits.*
import sttp.client4.testing.BackendStub
import sttp.client4.Backend
import sttp.model.StatusCode

import kui.config.OidcConfig
import kui.identity.application.PendingLogin
import kui.kernel.error.ErrorCode
import kui.kernel.Secret
import kui.testkit.KuiIOSuite
import kui.testkit.fakes.FakeStructuredLogger

/** The OpenID Connect relying party, against a provider this suite plays the part of.
  *
  * ==What is being pinned==
  *
  * Every one of the five checks on an ID token, individually. A token that is correctly signed by the right
  * provider but issued for somebody else's client is not a login here; nor is one that is expired, one whose
  * nonce belongs to another sign-in, or one signed by a key the provider does not publish. Each of those is a
  * real, documented way that relying parties have been broken, and each of them is one line to get wrong.
  *
  * The signing key is generated per run and the provider is a stub backend, so nothing here reaches the
  * network and nothing depends on a secret checked into the repository.
  */
final class OidcRelyingPartySuite extends KuiIOSuite {

  private val issuer: String = "https://accounts.example.com"

  private val config: OidcConfig =
    OidcConfig(
      issuer = issuer,
      clientId = "kui",
      clientSecret = Secret("s3cret"),
      redirectUri = "http://localhost:8080/api/v1/auth/oidc/callback",
      scopes = List("openid", "profile", "email"),
      usernameClaim = "email",
      groupsClaim = Some("groups"),
      label = "Example"
    )

  private val key: RSAKey =
    new RSAKeyGenerator(2048).keyID("test-key").generate()

  /** Another provider's key, for the case where the signature is valid but not by anybody we trust. */
  private val otherKey: RSAKey = new RSAKeyGenerator(2048).keyID("test-key").generate()

  private val discovery: String =
    s"""{
       |  "issuer": "$issuer",
       |  "authorization_endpoint": "$issuer/authorize",
       |  "token_endpoint": "$issuer/token",
       |  "jwks_uri": "https://keys.example.com/jwks"
       |}""".stripMargin

  private val jwks: String = new JWKSet(key.toPublicJWK).toString

  private def idToken(
      signingKey: RSAKey = key,
      audience: String = "kui",
      tokenIssuer: String = issuer,
      nonce: String = "the-nonce",
      expiresAt: Instant = Instant.now().plusSeconds(300),
      subject: String = "ada@example.com",
      groups: List[String] = List("platform", "oncall")
  ): String = {
    val claims = new JWTClaimsSet.Builder()
      .issuer(tokenIssuer)
      .audience(audience)
      .subject(subject)
      .claim("email", subject)
      .claim("groups", scala.jdk.CollectionConverters.SeqHasAsJava(groups).asJava)
      .claim("nonce", nonce)
      .expirationTime(Date.from(expiresAt))
      .build()

    val jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID).build(), claims)
    jwt.sign(new RSASSASigner(signingKey))
    jwt.serialize
  }

  /** A provider that answers discovery, the token exchange and the key set. */
  private def provider(token: String): Backend[IO] =
    BackendStub[IO](summon)
      .whenRequestMatches(_.uri.toString.contains("openid-configuration"))
      .thenRespondAdjust(discovery)
      .whenRequestMatches(_.uri.toString.endsWith("/token"))
      .thenRespondAdjust(s"""{"access_token":"opaque","id_token":"$token","token_type":"Bearer"}""")
      .whenRequestMatches(_.uri.toString.contains("jwks"))
      .thenRespondAdjust(jwks)

  private def party(backend: Backend[IO]) =
    FakeStructuredLogger[IO].flatMap(logger =>
      OidcRelyingParty.resource[IO](config, backend, logger).allocated.map(_._1)
    )

  private val pending: PendingLogin = PendingLogin("the-nonce", Secret("the-verifier"))

  // -----------------------------------------------------------------------------------------------

  test("the authorization URL carries everything the provider needs, and the PKCE challenge is a hash") {
    party(provider(idToken())).flatMap(_.start("the-state")).map {
      case Left(error) => fail(error.message)
      case Right((url, waiting)) =>
        assert(url.startsWith(s"$issuer/authorize?"), url)
        assert(url.contains("response_type=code"), url)
        assert(url.contains("client_id=kui"), url)
        assert(url.contains("state=the-state"), url)
        assert(url.contains("code_challenge_method=S256"), url)
        assert(url.contains("scope=openid+profile+email"), url)

        // The verifier stays here; only its hash was sent. That is the whole of PKCE.
        assert(!url.contains(waiting.codeVerifier.value), "the PKCE verifier was sent to the provider")
        assert(
          url.contains(s"code_challenge=${OidcRelyingParty.codeChallenge(waiting.codeVerifier.value)}"),
          url
        )
        // And the nonce that has to come back inside the token.
        assert(url.contains(s"nonce=${waiting.nonce}"), url)
    }
  }

  test("a good token becomes an identity, with the username claim and the groups claim the operator named") {
    party(provider(idToken())).flatMap(_.complete("the-code", pending)).map {
      case Left(error) => fail(error.message)
      case Right(identity) =>
        assertEquals(identity.name.value, "ada@example.com")
        assertEquals(identity.groups, Set("platform", "oncall"))
    }
  }

  test("a token signed by a key the provider does not publish is refused") {
    party(provider(idToken(signingKey = otherKey))).flatMap(_.complete("the-code", pending)).map { result =>
      assertEquals(result.left.toOption.map(_.code), Some(ErrorCode.Unauthenticated))
    }
  }

  test("a token for another client is refused, however correctly it is signed") {
    party(provider(idToken(audience = "somebody-else"))).flatMap(_.complete("the-code", pending)).map {
      result => assertEquals(result.left.toOption.map(_.code), Some(ErrorCode.Unauthenticated))
    }
  }

  test("a token from another issuer is refused") {
    party(provider(idToken(tokenIssuer = "https://evil.example.com")))
      .flatMap(_.complete("the-code", pending))
      .map(result => assertEquals(result.left.toOption.map(_.code), Some(ErrorCode.Unauthenticated)))
  }

  test("an expired token is refused") {
    party(provider(idToken(expiresAt = Instant.now().minusSeconds(60))))
      .flatMap(_.complete("the-code", pending))
      .map(result => assertEquals(result.left.toOption.map(_.code), Some(ErrorCode.Unauthenticated)))
  }

  test("a token carrying somebody else's nonce is refused, which is what stops a replay") {
    party(provider(idToken(nonce = "another-sign-in")))
      .flatMap(_.complete("the-code", pending))
      .map(result => assertEquals(result.left.toOption.map(_.code), Some(ErrorCode.Unauthenticated)))
  }

  test("every refusal says the same thing, so nothing tells an attacker which check failed") {
    for {
      badKey <- party(provider(idToken(signingKey = otherKey))).flatMap(_.complete("c", pending))
      badAudience <- party(provider(idToken(audience = "somebody-else"))).flatMap(_.complete("c", pending))
      badNonce <- party(provider(idToken(nonce = "another"))).flatMap(_.complete("c", pending))
    } yield {
      val messages = List(badKey, badAudience, badNonce).map(_.left.toOption.map(_.message))
      assertEquals(messages.distinct.size, 1, messages.toString)
    }
  }

  test("a token with no username claim is refused with a message naming the claim to configure") {
    val nameless = party(provider(idToken(subject = "")))

    nameless.flatMap(_.complete("the-code", pending)).map { result =>
      assert(result.left.toOption.exists(_.message.contains("email")), result.toString)
    }
  }

  test("a provider that is down is an upstream failure, not a refused login") {
    val down = BackendStub[IO](summon).whenAnyRequest.thenRespondServerError()

    party(down).flatMap(_.complete("the-code", pending)).map { result =>
      // `KUI-UPSTREAM-*` rather than `KUI-UNAUTHENTICATED`: the person's credentials were never the
      // problem, and telling them to try their password again would be wrong advice.
      assert(
        result.left.toOption.exists(_.code != ErrorCode.Unauthenticated),
        result.toString
      )
    }
  }

  test("the provider's error body is never echoed, because it routinely repeats the request") {
    val refusing = BackendStub[IO](summon)
      .whenRequestMatches(_.uri.toString.contains("openid-configuration"))
      .thenRespondAdjust(discovery)
      .whenRequestMatches(_.uri.toString.endsWith("/token"))
      .thenRespondAdjust(
        """{"error":"invalid_client","error_description":"client kui secret s3cret is wrong"}""",
        StatusCode.BadRequest
      )

    party(refusing).flatMap(_.complete("the-code", pending)).map { result =>
      val message = result.left.toOption.map(_.message).getOrElse("")
      assert(!message.contains("s3cret"), message)
      assert(!message.contains("invalid_client"), message)
    }
  }

  test("a deployment with no provider configured says so rather than failing obscurely") {
    UnconfiguredOidcProvider[IO].start("state").map { result =>
      assertEquals(result.left.toOption.map(_.code), Some(ErrorCode.Unsupported))
      assert(result.left.toOption.exists(_.message.contains("kui.auth.oidc")), result.toString)
    }
  }
}
