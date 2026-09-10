package kui.connect.infrastructure

import cats.effect.kernel.Resource
import cats.effect.{IO, Ref}
import sttp.client4.impl.cats.implicits.*
import sttp.client4.testing.{BackendStub, ResponseStub, StubBody}
import sttp.client4.{asStringAlways, basicRequest, Backend, Request}
import sttp.model.{StatusCode, Uri}

import kui.config.{SafeUrl, UpstreamAuthConfig}
import kui.kernel.Secret
import kui.kernel.error.ErrorCode
import kui.testkit.KuiIOSuite
import kui.testkit.fakes.FakeStructuredLogger

/** How each request to a Connect worker proves who KUI is.
  *
  * Three mechanisms, one call site: `ConnectHttp` contains no branch on how it authenticates, so everything
  * that could go wrong with a credential has to go wrong here — and a token that cannot be obtained has to
  * fail *this cluster's* request rather than the process.
  */
final class ConnectCredentialsSuite extends KuiIOSuite {

  private val issuer: SafeUrl = SafeUrl.unsafe("https://issuer.example/oauth/token")

  private def request: Request[String] =
    basicRequest.get(Uri.unsafeParse("http://kafka-connect:8083/connectors")).response(asStringAlways)

  private def authorization(sent: Request[String]): Option[String] =
    sent.headers.find(_.name.equalsIgnoreCase("Authorization")).map(_.value)

  /** An issuer that answers `body` with `status`, recording the `Authorization` header it was sent. */
  private def issuerStub(
      body: String,
      status: StatusCode = StatusCode.Ok,
      seen: Option[Ref[IO, List[String]]] = None
  ): Backend[IO] =
    BackendStub[IO](summon[sttp.monad.MonadError[IO]]).whenAnyRequest.thenRespondF { asked =>
      val record = seen.fold(IO.unit)(
        _.update(_ :+ asked.headers.find(_.name.equalsIgnoreCase("Authorization")).fold("")(_.value))
      )
      record.as(ResponseStub.adjust(body, status): sttp.client4.Response[StubBody])
    }

  private def credentials(
      auth: UpstreamAuthConfig,
      backend: Option[Backend[IO]]
  ): Resource[IO, ConnectCredentials[IO]] =
    Resource
      .eval(FakeStructuredLogger[IO])
      .flatMap(logger => ConnectCredentials.fromConfig[IO](auth, backend, logger))

  private def oauth(scope: Option[String] = None): UpstreamAuthConfig.OAuth =
    UpstreamAuthConfig.OAuth(issuer, "kui", Secret("s3cret"), scope)

  test("anonymous credentials add nothing at all") {
    // The ordinary arrangement: a Connect cluster on a private network with no authentication. Adding an
    // empty header would make a worker that rejects unknown schemes refuse a request KUI could have made.
    credentials(UpstreamAuthConfig.Anonymous, None)
      .use(_.authenticate(request))
      .map(sent => assertEquals(sent.map(authorization), Right(None)))
  }

  test("basic authentication puts the password in the header and nowhere else") {
    credentials(UpstreamAuthConfig.Basic("kui", Secret("hunter2")), None).use(_.authenticate(request)).map {
      case Right(sent) =>
        assertEquals(authorization(sent), Some("Basic a3VpOmh1bnRlcjI="))
        // The `Secret` is unwrapped exactly once, at the header. It must not have reached the URI or any
        // other header on the way.
        assert(!clue(sent.uri.toString).contains("hunter2"))
      case Left(error) => fail(s"expected a request, got $error")
    }
  }

  test("an OAuth token is fetched once and reused while it is comfortably valid") {
    // Two requests, one token: an issuer asked once per request would be a denial of service KUI performs
    // against its own identity provider.
    Ref.of[IO, List[String]](Nil).flatMap { seen =>
      val backend = issuerStub("""{"access_token":"abc","expires_in":3600}""", seen = Some(seen))

      credentials(oauth(Some("connect")), Some(backend)).use { authenticating =>
        for {
          first <- authenticating.authenticate(request)
          second <- authenticating.authenticate(request)
          asked <- seen.get
        } yield {
          assertEquals(first.map(authorization), Right(Some("Bearer abc")))
          assertEquals(second.map(authorization), Right(Some("Bearer abc")))
          assertEquals(asked.size, 1)
          // The client id and secret travel in the header, never in the form body, where a proxy's access
          // log routinely ends up keeping them.
          assertEquals(asked, List("Basic a3VpOnMzY3JldA=="))
        }
      }
    }
  }

  test("an issuer that refuses KUI's credentials is KUI's problem, and it is named as its own upstream") {
    // "KUI could not get a token from your identity provider" and "your Connect cluster rejected KUI's
    // token" send an operator to two different systems, which is why the token endpoint has a name of its
    // own in every error it produces.
    val backend = issuerStub("", StatusCode.Unauthorized)

    credentials(oauth(), Some(backend)).use(_.authenticate(request)).map {
      case Left(error) =>
        assertEquals(error.code, ErrorCode.UpstreamAuth)
        assert(clue(error.message).contains(ConnectCredentials.TokenUpstreamName))
      case Right(sent) => fail(s"expected a refusal, got ${authorization(sent)}")
    }
  }

  test("a token response KUI cannot read is refused, and nothing the issuer sent is quoted back") {
    // A token response's body is the last thing that should reach a screen: it is the one response in KUI
    // most likely to quote the credential that was sent.
    val backend = issuerStub("""{"oops":"s3cret"}""")

    credentials(oauth(), Some(backend)).use(_.authenticate(request)).map {
      case Left(error) =>
        assert(clue(error.message).contains("could not be understood"))
        assert(!clue(error.message).contains("s3cret"))
      case Right(sent) => fail(s"expected a refusal, got ${authorization(sent)}")
    }
  }

  test("an issuer that omits expires_in gets the minimum lifetime rather than a cache of zero") {
    // A token cached forever becomes a 401 that outlives every restart; one re-fetched a minute later
    // costs nothing. What must not happen is a fetch per request, which is what `expires_in: 0` — sent by
    // issuers that mean "does not expire" — would otherwise produce.
    Ref.of[IO, List[String]](Nil).flatMap { seen =>
      val backend = issuerStub("""{"access_token":"abc","expires_in":0}""", seen = Some(seen))

      credentials(oauth(), Some(backend))
        .use(authenticating =>
          authenticating.authenticate(request) >> authenticating.authenticate(request) >> seen.get
        )
        .map(asked => assertEquals(asked.size, 1))
    }
  }

  test("an OAuth configuration with no token client refuses every call rather than killing the process") {
    // A wiring mistake must degrade one cluster's Connect row, exactly like a Connect cluster that is down,
    // and never stop a service that is also serving three other clusters.
    credentials(oauth(), None).use(_.authenticate(request)).map {
      case Left(error) =>
        // The sentence names KUI's own wiring, because that is where the mistake is. A row saying the
        // issuer could not be reached would send an operator to an identity provider KUI never called.
        assert(clue(error.message).contains("no token endpoint client was built"))
        assertEquals(error.code, ErrorCode.UpstreamAuth)
      case Right(sent) => fail(s"expected a refusal, got ${authorization(sent)}")
    }
  }

  test("the two mechanisms that need no issuer are built with no backend at all") {
    for {
      anonymous <- credentials(UpstreamAuthConfig.Anonymous, None).use(_.authenticate(request))
      basic <- credentials(UpstreamAuthConfig.Basic("kui", Secret("p")), None).use(_.authenticate(request))
    } yield {
      assertEquals(anonymous.map(authorization), Right(None))
      assert(basic.map(authorization).toOption.flatten.isDefined)
    }
  }
}
