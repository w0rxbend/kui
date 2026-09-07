package kui.schema.infrastructure

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all.*
import sttp.client4.impl.cats.implicits.*
import sttp.client4.testing.{BackendStub, ResponseStub}
import sttp.client4.{basicRequest, Backend, Request}
import sttp.model.{StatusCode, Uri}

import kui.config.{RegistryAuthConfig, SafeUrl}
import kui.kernel.Secret
import kui.kernel.error.ErrorCode
import kui.testkit.KuiIOSuite
import kui.testkit.fakes.FakeStructuredLogger

/** How a request to a registry proves who KUI is, and what happens when it cannot.
  *
  * This file had no suite at all until wave 5, and everything in it is a rule somebody argued for in a
  * comment: the client secret goes in the `Authorization` header rather than the form body *because a proxy
  * logs a form body*; a cluster configured for OAuth with no issuer client refuses every call rather than
  * sending it unauthenticated; an issuer's response body is never quoted, because it is the one response in
  * KUI most likely to contain the credential that was just sent. Every one of them could be reversed with
  * the whole schema service green.
  *
  * The transport is a stub for the reason `RegistryHttpSuite` gives: each promise here is a promise about a
  * *request* or about one *response*, and a real issuer can produce almost none of the responses that matter.
  */
final class RegistryCredentialsSuite extends KuiIOSuite {

  private val issuer: SafeUrl = SafeUrl.unsafe("https://issuer.example/oauth/token")

  private val request: Request[String] =
    basicRequest.get(Uri.unsafeParse("http://registry:8081/subjects")).response(sttp.client4.asStringAlways)

  private def oauth(scope: Option[String] = None): RegistryAuthConfig.OAuth =
    RegistryAuthConfig.OAuth(issuer, "kui-schema", Secret("s3cr3t-client-secret"), scope)

  /** An issuer that answers one canned response and records every request it was sent. */
  private def issuerStub(
      seen: Ref[IO, List[sttp.client4.GenericRequest[?, ?]]],
      status: StatusCode,
      body: String
  ): Backend[IO] =
    BackendStub[IO](summon[sttp.monad.MonadError[IO]]).whenAnyRequest
      .thenRespondF { sent =>
        seen.update(_ :+ sent) *> IO.pure(ResponseStub.adjust(body, status))
      }

  private def header(sent: sttp.client4.GenericRequest[?, ?], name: String): String =
    sent.header(name).getOrElse("")

  // -----------------------------------------------------------------------------------------------

  test("basic authentication puts the password in the Authorization header and nowhere else") {
    // The password is a `Secret` everywhere in KUI and is unwrapped exactly here, at the moment it goes
    // into a header. A mechanism that attached it as a query parameter or an ad-hoc header would put it
    // somewhere a proxy access log keeps, which is the whole reason the type exists.
    val credentials = RegistryCredentials.basic[IO]("kui", Secret("hunter2"))

    credentials.authenticate(request).map {
      case Right(authenticated) =>
        val authorization = header(authenticated, "Authorization")
        assert(clue(authorization).startsWith("Basic "), "basic credentials belong in Authorization")
        val decoded = new String(java.util.Base64.getDecoder.decode(authorization.stripPrefix("Basic ")))
        assertEquals(decoded, "kui:hunter2")
        assert(!authenticated.uri.toString.contains("hunter2"), "a credential must never reach a URL")
      case Left(error) => fail(s"expected an authenticated request, got $error")
    }
  }

  test("an anonymous registry gets the request unchanged") {
    RegistryCredentials.anonymous[IO].authenticate(request).map {
      case Right(authenticated) => assertEquals(header(authenticated, "Authorization"), "")
      case Left(error) => fail(s"expected an authenticated request, got $error")
    }
  }

  test("a cluster configured for OAuth with no issuer client refuses rather than sending it bare") {
    // A wiring mistake, and it must fail the *request* rather than the process: a registry KUI cannot
    // authenticate to degrades that one cluster's panel exactly like a registry that is down. What it
    // must never do is send the call without a credential, which a registry behind a proxy may well
    // answer — and KUI would then be reading a registry it was never authorised for.
    for {
      logger <- FakeStructuredLogger[IO]
      result <- RegistryCredentials
        .fromConfig[IO](oauth(), tokenBackend = None, logger)
        .use(_.authenticate(request))
    } yield result match {
      case Left(error) =>
        assertEquals(error.code, ErrorCode.UpstreamUnavailable)
        assert(clue(error.message).contains("could not be reached"))
      case Right(sent) => fail(s"an unauthenticated request was produced: ${header(sent, "Authorization")}")
    }
  }

  test("the client secret travels in the Authorization header and never in the form body") {
    // Both halves of RFC 6749 are legal and only one of them keeps the secret out of a proxy's access
    // log, which routinely records a form body and never records a header value. The bearer token the
    // issuer answered is what reaches the registry.
    for {
      seen <- Ref.of[IO, List[sttp.client4.GenericRequest[?, ?]]](Nil)
      logger <- FakeStructuredLogger[IO]
      backend = issuerStub(seen, StatusCode.Ok, """{"access_token":"tok-1","expires_in":3600}""")
      result <- RegistryCredentials
        .fromConfig[IO](oauth(Some("registry:read")), Some(backend), logger)
        .use(_.authenticate(request))
      sent <- seen.get
    } yield {
      assertEquals(sent.size, 1)
      val tokenRequest = sent.head
      val authorization = header(tokenRequest, "Authorization")
      assert(clue(authorization).startsWith("Basic "), "the client credentials belong in the header")
      val decoded = new String(java.util.Base64.getDecoder.decode(authorization.stripPrefix("Basic ")))
      assertEquals(decoded, "kui-schema:s3cr3t-client-secret")

      val form = tokenRequest.body.show
      assert(clue(form).contains("grant_type=client_credentials"))
      assert(clue(form).contains("scope=registry"), "the configured scope is what the token is asked for")
      assert(!form.contains("s3cr3t-client-secret"), "the secret must never be in a body a proxy logs")

      assertEquals(result.map(header(_, "Authorization")), Right("Bearer tok-1"))
    }
  }

  test("a token is fetched once for many requests, because an issuer is not asked per call") {
    // The cache is not an optimisation. A token request per registry call turns KUI's own authentication
    // into a denial of service against an identity provider, which is the failure `MinimumLifetime` and
    // this cache exist together to prevent.
    for {
      seen <- Ref.of[IO, List[sttp.client4.GenericRequest[?, ?]]](Nil)
      logger <- FakeStructuredLogger[IO]
      backend = issuerStub(seen, StatusCode.Ok, """{"access_token":"tok-1","expires_in":3600}""")
      results <- RegistryCredentials
        .fromConfig[IO](oauth(), Some(backend), logger)
        .use(credentials => credentials.authenticate(request).replicateA(4))
      sent <- seen.get
    } yield {
      assertEquals(sent.size, 1, "the issuer was asked more than once for one unexpired token")
      assertEquals(results.map(_.map(header(_, "Authorization"))), List.fill(4)(Right("Bearer tok-1")))
    }
  }

  test("an issuer that answers no usable token fails, and quotes nothing it sent") {
    // A token endpoint's body is the last thing that should reach a screen, so the sentence is written
    // from the *shape* of the answer and contains none of it. All three shapes are refused, because an
    // empty `access_token` is a state real issuers reach and a token of "" authenticates nothing.
    val answers = List(
      "<html>an HTML login page</html>" -> "it is not JSON",
      """{"error":"invalid_client","hint":"secret s3cr3t-client-secret is wrong"}""" ->
        "it has no 'access_token' field",
      """{"access_token":"   ","expires_in":3600}""" -> "its 'access_token' is empty"
    )

    answers.traverse { (body, expected) =>
      for {
        seen <- Ref.of[IO, List[sttp.client4.GenericRequest[?, ?]]](Nil)
        logger <- FakeStructuredLogger[IO]
        backend = issuerStub(seen, StatusCode.Ok, body)
        result <- RegistryCredentials
          .fromConfig[IO](oauth(), Some(backend), logger)
          .use(_.authenticate(request))
      } yield result match {
        case Left(error) =>
          assertEquals(error.code, ErrorCode.UpstreamAuth)
          assert(clue(error.message).contains(expected))
          assert(!error.message.contains("s3cr3t-client-secret"), "a token response must never be echoed")
        case Right(sent) => fail(s"expected a failure, got ${header(sent, "Authorization")}")
      }
    }.void
  }

  test("an issuer that rejects KUI's own credentials is an auth failure against the issuer") {
    // Two different systems and two different people to go and talk to: "KUI could not get a token from
    // your identity provider" and "your registry rejected KUI's token" are the same status code and are
    // never the same problem, which is why the token endpoint has an upstream name of its own.
    for {
      seen <- Ref.of[IO, List[sttp.client4.GenericRequest[?, ?]]](Nil)
      logger <- FakeStructuredLogger[IO]
      backend = issuerStub(seen, StatusCode.Unauthorized, """{"error":"invalid_client"}""")
      result <- RegistryCredentials
        .fromConfig[IO](oauth(), Some(backend), logger)
        .use(_.authenticate(request))
      warnings <- logger.entries
    } yield {
      assertEquals(result.left.map(_.code), Left(ErrorCode.UpstreamAuth))
      assert(
        clue(result.swap.toOption.map(_.message)).exists(_.contains(RegistryCredentials.TokenUpstreamName))
      )
      // And it is logged, because an operator watching the Schemas screen go grey has nothing else to
      // read: the failure never reaches a browser as anything but "the registry is unavailable".
      assertEquals(warnings.map(_.level), List("warn"))
    }
  }
}
