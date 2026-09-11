package kui.ksql.infrastructure

import cats.effect.IO
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.impl.cats.implicits.*
import sttp.client4.testing.{BackendStub, ResponseStub, StubBody}
import sttp.client4.{asStreamAlwaysUnsafe, asStringAlways, basicRequest, Backend}
import sttp.model.StatusCode

import kui.config.{SafeUrl, UpstreamAuthConfig}
import kui.kernel.Secret
import kui.kernel.error.ErrorCode
import kui.testkit.KuiIOSuite

/** How each request to a ksqlDB proves who KUI is.
  *
  * Both request shapes are asserted for every mechanism, and that is the point of the suite rather than a
  * completeness exercise: a push query is a `StreamRequest` and a listing is a `Request`, and if the two
  * authenticated differently then a secured ksqlDB would answer the listing and refuse the stream — which on
  * the screen is a ksqlDB screen that draws its objects and then shows an empty result region for ever.
  */
final class KsqlCredentialsSuite extends KuiIOSuite {

  private def headerOf(credentials: KsqlCredentials[IO]): IO[Option[String]] =
    credentials
      .authenticate(
        basicRequest.get(sttp.model.Uri.unsafeParse("http://ksqldb/ksql")).response(asStringAlways)
      )
      .map(_.toOption.flatMap(_.header("Authorization")))

  private def streamHeaderOf(credentials: KsqlCredentials[IO]): IO[Option[String]] =
    credentials
      .authenticateStream(
        basicRequest
          .post(sttp.model.Uri.unsafeParse("http://ksqldb/query"))
          .response(asStreamAlwaysUnsafe(Fs2Streams[IO]))
      )
      .map(_.toOption.flatMap(_.header("Authorization")))

  test("anonymous sends no Authorization header at all, on either shape") {
    // Which is what a ksqlDB on a private network ordinarily wants, and an empty header would be worse
    // than none: some proxies treat it as a malformed credential rather than as an absent one.
    for {
      call <- headerOf(KsqlCredentials.anonymous[IO])
      stream <- streamHeaderOf(KsqlCredentials.anonymous[IO])
    } yield assertEquals((call, stream), (None, None))
  }

  test("basic sends the same header on a call and on a push query") {
    val credentials = KsqlCredentials.basic[IO]("kui", Secret("hunter2"))

    for {
      call <- headerOf(credentials)
      stream <- streamHeaderOf(credentials)
    } yield {
      assert(clue(call.getOrElse("")).startsWith("Basic "))
      // The load-bearing assertion: the two shapes authenticate identically, so a secured ksqlDB cannot
      // answer one and refuse the other.
      assertEquals(call, stream)
    }
  }

  test("an OAuth configuration with no token backend refuses every request rather than throwing") {
    // A ksqlDB KUI cannot authenticate to must show one unavailable section, exactly like a ksqlDB that is
    // down, and never stop a service that is also serving three other clusters.
    val config = UpstreamAuthConfig.OAuth(
      SafeUrl.unsafe("https://issuer.example/token"),
      "client",
      Secret("secret"),
      None
    )

    kui.testkit.fakes.FakeStructuredLogger[IO].flatMap { logger =>
      KsqlCredentials
        .fromConfig[IO](config, None, logger)
        .use(credentials =>
          credentials
            .authenticate(
              basicRequest
                .get(sttp.model.Uri.unsafeParse("http://ksqldb/ksql"))
                .response(asStringAlways)
            )
            .map {
              case Left(error) =>
                assertEquals(error.code, ErrorCode.UpstreamAuth)
                // The cause is KUI's own wiring and contains nothing of the operator's, which is what
                // makes it safe to show — and it is the only thing that tells them where to look.
                assert(clue(error.message).contains("no token endpoint client was built"))
              case Right(other) => fail(s"expected a refusal, got ${other.uri}")
            }
        )
    }
  }

  test("an OAuth token is fetched once and reused, on both request shapes") {
    val config = UpstreamAuthConfig.OAuth(
      SafeUrl.unsafe("https://issuer.example/token"),
      "client",
      Secret("secret"),
      None
    )

    val requests = scala.collection.mutable.ListBuffer.empty[String]

    val issuer: Backend[IO] = BackendStub[IO](summon[sttp.monad.MonadError[IO]]).whenAnyRequest
      .thenRespondF { request =>
        requests += request.uri.toString
        IO.pure(
          ResponseStub.adjust(
            """{"access_token":"a-token","expires_in":3600}""",
            StatusCode.Ok
          ): sttp.client4.Response[StubBody]
        )
      }

    kui.testkit.fakes.FakeStructuredLogger[IO].flatMap { logger =>
      KsqlCredentials
        .fromConfig[IO](config, Some(issuer), logger)
        .use(credentials =>
          for {
            call <- headerOf(credentials)
            stream <- streamHeaderOf(credentials)
          } yield {
            assertEquals(call, Some("Bearer a-token"))
            assertEquals(stream, Some("Bearer a-token"))
            // One fetch for two requests: a token re-fetched per request would turn KUI's own
            // authentication into a denial of service against the issuer.
            assertEquals(requests.size, 1)
          }
        )
    }
  }

  test("an issuer that refuses is an auth failure, and its body is never echoed") {
    val config = UpstreamAuthConfig.OAuth(
      SafeUrl.unsafe("https://issuer.example/token"),
      "client",
      Secret("secret"),
      None
    )

    val issuer: Backend[IO] = BackendStub[IO](summon[sttp.monad.MonadError[IO]]).whenAnyRequest
      .thenRespondF(_ =>
        IO.pure(
          ResponseStub.adjust(
            """{"error":"invalid_client","client_secret":"secret"}""",
            StatusCode.Unauthorized
          ): sttp.client4.Response[StubBody]
        )
      )

    kui.testkit.fakes.FakeStructuredLogger[IO].flatMap { logger =>
      KsqlCredentials
        .fromConfig[IO](config, Some(issuer), logger)
        .use(credentials =>
          credentials
            .authenticate(
              basicRequest
                .get(sttp.model.Uri.unsafeParse("http://ksqldb/ksql"))
                .response(asStringAlways)
            )
            .map {
              case Left(error) =>
                assertEquals(error.code, ErrorCode.UpstreamAuth)
                // A token response's body is the last thing that should reach a screen: it is the one
                // response in KUI most likely to quote the credential that was sent.
                assert(!clue(error.message).contains("secret"))
              case Right(other) => fail(s"expected a refusal, got ${other.uri}")
            }
        )
    }
  }

  test("an issuer that answers rubbish is a failure that names the shape and quotes nothing") {
    val config = UpstreamAuthConfig.OAuth(
      SafeUrl.unsafe("https://issuer.example/token"),
      "client",
      Secret("secret"),
      None
    )

    val issuer: Backend[IO] = BackendStub[IO](summon[sttp.monad.MonadError[IO]]).whenAnyRequest
      .thenRespondF(_ =>
        IO.pure(
          ResponseStub.adjust("""{"nothing":"useful"}""", StatusCode.Ok): sttp.client4.Response[
            StubBody
          ]
        )
      )

    kui.testkit.fakes.FakeStructuredLogger[IO].flatMap { logger =>
      KsqlCredentials
        .fromConfig[IO](config, Some(issuer), logger)
        .use(credentials =>
          credentials
            .authenticate(
              basicRequest
                .get(sttp.model.Uri.unsafeParse("http://ksqldb/ksql"))
                .response(asStringAlways)
            )
            .map {
              case Left(error) => assert(clue(error.message).contains("access_token"))
              case Right(other) => fail(s"expected a refusal, got ${other.uri}")
            }
        )
    }
  }

  test("the shortest lifetime KUI believes is a minute, so a zero expiry is not a fetch per request") {
    assertEquals(KsqlCredentials.MinimumLifetime, scala.concurrent.duration.Duration(1, "minute"))
    assertEquals(KsqlCredentials.RefreshMargin, scala.concurrent.duration.Duration(30, "seconds"))
  }

  test("the token upstream has a name of its own, so a metric says which system was slow") {
    assertEquals(KsqlCredentials.TokenUpstreamName, "ksqldb-oauth")
    assertNotEquals(KsqlCredentials.TokenUpstreamName, KsqlHttp.UpstreamName)
  }
}
