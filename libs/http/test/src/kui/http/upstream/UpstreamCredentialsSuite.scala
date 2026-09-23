package kui.http.upstream

import java.io.IOException

import scala.concurrent.duration.DurationInt

import cats.effect.testkit.TestControl
import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.*
import sttp.client4.impl.cats.implicits.*
import sttp.client4.testing.{BackendStub, ResponseStub, StubBody}
import sttp.model.StatusCode

import kui.config.{SafeUrl, UpstreamAuthConfig}
import kui.kernel.Secret
import kui.testkit.KuiIOSuite

final class UpstreamCredentialsSuite extends KuiIOSuite {

  test("anonymous authentication removes every caller-supplied Authorization header") {
    val supplied = request
      .header("Authorization", "Bearer caller-one", DuplicateHeaderBehavior.Add)
      .header("authorization", "Bearer caller-two", DuplicateHeaderBehavior.Add)
      .header("X-Request-Id", "request-canary")

    credentials(UpstreamAuthConfig.Anonymous).authenticate(supplied).map { authenticated =>
      assertEquals(authenticated.map(authorizationHeaders), Right(Nil))
      assertEquals(authenticated.map(_.header("X-Request-Id")), Right(Some("request-canary")))
    }
  }

  test("basic authentication applies the exact RFC 7617 header") {
    credentials(
      UpstreamAuthConfig.Basic("kui", Secret("hunter2"))
    ).authenticate(request).map { authenticated =>
      assertEquals(authenticated.map(authorizationHeaders), Right(List("Basic a3VpOmh1bnRlcjI=")))
    }
  }

  test("bearer authentication applies the exact token header") {
    credentials(
      UpstreamAuthConfig.Bearer(Secret("bearer-canary"))
    ).authenticate(request).map { authenticated =>
      assertEquals(authenticated.map(authorizationHeaders), Right(List("Bearer bearer-canary")))
    }
  }

  test("configured credentials replace every caller-supplied Authorization header") {
    val supplied = request
      .header("Authorization", "Bearer caller-one", DuplicateHeaderBehavior.Add)
      .header("authorization", "Bearer caller-two", DuplicateHeaderBehavior.Add)

    for {
      basic <- credentials(UpstreamAuthConfig.Basic("kui", Secret("hunter2"))).authenticate(supplied)
      bearer <- credentials(UpstreamAuthConfig.Bearer(Secret("server-token"))).authenticate(supplied)
    } yield {
      assertEquals(basic.map(authorizationHeaders), Right(List("Basic a3VpOmh1bnRlcjI=")))
      assertEquals(bearer.map(authorizationHeaders), Right(List("Bearer server-token")))
    }
  }

  test("configured credentials replace caller authorization on streaming requests") {
    val supplied = basicRequest
      .get(uri"https://upstream.invalid/events")
      .header("Authorization", "Bearer caller-token")
      .response(asStreamAlwaysUnsafe(Fs2Streams[IO]))

    credentials(UpstreamAuthConfig.Basic("kui", Secret("hunter2")))
      .authenticateStream(supplied)
      .map(authenticated =>
        assertEquals(authenticated.map(_.header("Authorization")), Right(Some("Basic a3VpOmh1bnRlcjI=")))
      )
  }

  test("OAuth fetches one token without redirects and reuses it before early refresh") {
    val requests = scala.collection.mutable.ListBuffer.empty[GenericRequest[?, ?]]
    val issuer: Backend[IO] = BackendStub[IO](summon[sttp.monad.MonadError[IO]]).whenAnyRequest
      .thenRespondF { sent =>
        requests += sent
        IO.pure(
          ResponseStub.adjust(
            """{"access_token":"issued-token","expires_in":3600}""",
            StatusCode.Ok
          ): Response[StubBody]
        )
      }

    UpstreamCredentials.withBackend[IO](oauthConfig, issuer).use { credential =>
      for {
        first <- credential.authenticate(request)
        second <- credential.authenticate(request)
      } yield {
        assertEquals(first.map(authorizationHeaders), Right(List("Bearer issued-token")))
        assertEquals(second.map(authorizationHeaders), Right(List("Bearer issued-token")))
        assertEquals(requests.size, 1)
        assertEquals(requests.head.options.followRedirects, false)
        assertEquals(credential.describe, "oauth client credentials")
        assert(!credential.toString.contains("oauth-client"), credential.toString)
        assert(!credential.toString.contains("issuer.example"), credential.toString)
        assert(!credential.toString.contains("issued-token"), credential.toString)
      }
    }
  }

  test("OAuth replaces every caller-supplied Authorization header") {
    val supplied = request
      .header("Authorization", "Bearer caller-one", DuplicateHeaderBehavior.Add)
      .header("authorization", "Bearer caller-two", DuplicateHeaderBehavior.Add)

    UpstreamCredentials.withBackend[IO](oauthConfig, responding(StatusCode.Ok, validTokenBody)).use {
      credential =>
        credential.authenticate(supplied).map { authenticated =>
          assertEquals(
            authenticated.map(authorizationHeaders),
            Right(List("Bearer Ab9-._~+/=="))
          )
        }
    }
  }

  test("OAuth sends only the grant and scope in the form body") {
    val bodies = scala.collection.mutable.ListBuffer.empty[String]
    val issuer: Backend[IO] = BackendStub[IO](summon[sttp.monad.MonadError[IO]]).whenAnyRequest
      .thenRespondF { sent =>
        bodies += sent.body.show
        IO.pure(tokenResponse("issued-token", 3600L))
      }

    UpstreamCredentials.withBackend[IO](oauthConfig, issuer).use(_.authenticate(request)).map { _ =>
      val body = bodies.mkString
      assert(clue(body).contains("client_credentials"))
      assert(clue(body).contains("metrics.read"))
      assert(!body.contains("oauth-client-secret"), body)
      assert(!body.contains("client_secret"), body)
    }
  }

  test("a non-success issuer response is typed and never echoes client, token, or body canaries") {
    val bodyCanary = "issuer-body-canary"
    val issuer = responding(StatusCode.Unauthorized, s"""{"error":"$bodyCanary"}""")

    UpstreamCredentials.withBackend[IO](oauthConfig, issuer).use { credential =>
      credential.authenticate(request).map { result =>
        val failure = result.left.toOption.getOrElse(fail("expected the issuer refusal"))
        assertEquals(failure, UpstreamCredentials.Failure.Rejected(401))
        val rendered = List(failure.toString, failure.message, credential.toString).mkString("\n")
        List("oauth-client-secret", "issued-token", bodyCanary).foreach { canary =>
          assert(!rendered.contains(canary), rendered)
        }
      }
    }
  }

  test("malformed token and expiry responses have typed, secret-safe reasons") {
    val cases = List(
      "not-json body-canary" -> UpstreamCredentials.MalformedReason.InvalidJson,
      """{"expires_in":3600}""" -> UpstreamCredentials.MalformedReason.MissingToken,
      """{"access_token":"   ","expires_in":3600}""" -> UpstreamCredentials.MalformedReason.EmptyToken,
      """{"access_token":"token-canary"}""" -> UpstreamCredentials.MalformedReason.MissingExpiry,
      """{"access_token":"token-canary","expires_in":0}""" ->
        UpstreamCredentials.MalformedReason.InvalidExpiry,
      """{"access_token":"token-canary","expires_in":1.5}""" ->
        UpstreamCredentials.MalformedReason.InvalidExpiry,
      """{"access_token":"token-canary","expires_in":"3600"}""" ->
        UpstreamCredentials.MalformedReason.InvalidExpiry
    )

    cases.traverse_ { case (body, reason) =>
      UpstreamCredentials.withBackend[IO](oauthConfig, responding(StatusCode.Ok, body)).use { credential =>
        credential.authenticate(request).map { result =>
          val failure = result.left.toOption.getOrElse(fail(s"accepted malformed token response: $body"))
          assertEquals(failure, UpstreamCredentials.Failure.Malformed(reason))
          assert(!failure.message.contains("canary"), failure.message)
        }
      }
    }
  }

  test("OAuth rejects unsafe or syntactically invalid bearer tokens without echoing them") {
    val bodies = List(
      """{"access_token":" leading","expires_in":3600}""",
      """{"access_token":"trailing ","expires_in":3600}""",
      """{"access_token":"token\r\nheader-canary","expires_in":3600}""",
      """{"access_token":"token:invalid-canary","expires_in":3600}""",
      """{"access_token":"token=middle-canary","expires_in":3600}"""
    )

    bodies.traverse_ { body =>
      UpstreamCredentials.withBackend[IO](oauthConfig, responding(StatusCode.Ok, body)).use { credential =>
        credential.authenticate(request).map { result =>
          val failure = result.left.toOption.getOrElse(fail(s"accepted invalid bearer token: $body"))
          assertEquals(
            failure,
            UpstreamCredentials.Failure.Malformed(UpstreamCredentials.MalformedReason.InvalidToken)
          )
          assert(!failure.message.contains("canary"), failure.message)
        }
      }
    }
  }

  test("token response bodies retain a defensive byte bound when a backend ignores request options") {
    val settings = UpstreamCredentials.Settings(maxResponseBytes = 8L)

    UpstreamCredentials
      .withBackend[IO](oauthConfig, responding(StatusCode.Ok, "x" * 9), settings)
      .use(_.authenticate(request))
      .map(result =>
        assertEquals(result.left.toOption, Some(UpstreamCredentials.Failure.ResponseTooLarge(8L)))
      )
  }

  test("a token endpoint that does not finish is a typed timeout") {
    val settings = UpstreamCredentials.Settings(requestTimeout = 1.second)
    val issuer: Backend[IO] = BackendStub[IO](summon[sttp.monad.MonadError[IO]]).whenAnyRequest
      .thenRespondF(_ => IO.never)
    val program = UpstreamCredentials
      .withBackend[IO](oauthConfig, issuer, settings)
      .use(_.authenticate(request))

    TestControl
      .executeEmbed(program)
      .map(result => assertEquals(result.left.toOption, Some(UpstreamCredentials.Failure.TimedOut(1.second))))
  }

  test("concurrent requests to one credential source coalesce into one token refresh") {
    val program = for {
      calls <- Ref.of[IO, Int](0)
      started <- Deferred[IO, Unit]
      release <- Deferred[IO, Unit]
      issuer = BackendStub[IO](summon[sttp.monad.MonadError[IO]]).whenAnyRequest
        .thenRespondF(_ =>
          calls.update(_ + 1) >> started.complete(()).void >> release.get.as(tokenResponse("shared", 3600L))
        )
      observed <- UpstreamCredentials.withBackend[IO](oauthConfig, issuer).use { credential =>
        for {
          requests <- List.fill(20)(credential.authenticate(request)).parSequence.start
          _ <- started.get
          during <- calls.get
          _ <- release.complete(())
          results <- requests.joinWithNever
          after <- calls.get
        } yield (during, after, results)
      }
    } yield observed

    program.map { case (during, after, results) =>
      assertEquals(during, 1)
      assertEquals(after, 1)
      assert(results.forall(_.map(authorizationHeaders) == Right(List("Bearer shared"))))
    }
  }

  test("cancelling one waiter does not cancel the shared token refresh") {
    val program = for {
      calls <- Ref.of[IO, Int](0)
      started <- Deferred[IO, Unit]
      release <- Deferred[IO, Unit]
      issuer = BackendStub[IO](summon[sttp.monad.MonadError[IO]]).whenAnyRequest
        .thenRespondF(_ =>
          calls.update(_ + 1) >> started.complete(()).void >> release.get.as(tokenResponse("shared", 3600L))
        )
      observed <- UpstreamCredentials.withBackend[IO](oauthConfig, issuer).use { credential =>
        for {
          cancelled <- credential.authenticate(request).start
          _ <- started.get
          _ <- cancelled.cancel
          waiter <- credential.authenticate(request).start
          _ <- release.complete(())
          result <- waiter.joinWithNever
          count <- calls.get
        } yield (result, count)
      }
    } yield observed

    program.map { case (result, count) =>
      assertEquals(result.map(authorizationHeaders), Right(List("Bearer shared")))
      assertEquals(count, 1)
    }
  }

  test("releasing credentials cancels the producer and completes its waiters with a typed failure") {
    val program = for {
      started <- Deferred[IO, Unit]
      cancelled <- Deferred[IO, Unit]
      issuer = BackendStub[IO](summon[sttp.monad.MonadError[IO]]).whenAnyRequest
        .thenRespondF(_ => started.complete(()).void >> IO.never.onCancel(cancelled.complete(()).void))
      allocated <- UpstreamCredentials.withBackend[IO](oauthConfig, issuer).allocated
      (credential, release) = allocated
      waiter <- credential.authenticate(request).start
      _ <- started.get
      _ <- release
      result <- waiter.joinWithNever.timeoutTo(
        1.second,
        UpstreamCredentials.Failure.TimedOut(1.second).asLeft[Request[Either[String, String]]].pure[IO]
      )
      producerWasCancelled <- cancelled.tryGet
    } yield (result, producerWasCancelled)

    TestControl.executeEmbed(program).map { case (result, producerWasCancelled) =>
      assertEquals(result.left.toOption, Some(UpstreamCredentials.Failure.Transport))
      assertEquals(producerWasCancelled, Some(()))
    }
  }

  test("a cached token refreshes early and not before its refresh margin") {
    val settings = UpstreamCredentials.Settings(refreshBefore = 20.seconds)
    val program = for {
      calls <- Ref.of[IO, Int](0)
      issuer = BackendStub[IO](summon[sttp.monad.MonadError[IO]]).whenAnyRequest
        .thenRespondF(_ => calls.updateAndGet(_ + 1).map(n => tokenResponse(s"token-$n", 100L)))
      observed <- UpstreamCredentials.withBackend[IO](oauthConfig, issuer, settings).use { credential =>
        for {
          first <- credential.authenticate(request)
          _ <- IO.sleep(79.seconds)
          beforeMargin <- credential.authenticate(request)
          _ <- IO.sleep(2.seconds)
          afterMargin <- credential.authenticate(request)
          count <- calls.get
        } yield (first, beforeMargin, afterMargin, count)
      }
    } yield observed

    TestControl.executeEmbed(program).map { case (first, beforeMargin, afterMargin, count) =>
      assertEquals(first.map(authorizationHeaders), Right(List("Bearer token-1")))
      assertEquals(beforeMargin.map(authorizationHeaders), Right(List("Bearer token-1")))
      assertEquals(afterMargin.map(authorizationHeaders), Right(List("Bearer token-2")))
      assertEquals(count, 2)
    }
  }

  test("transport failures retain only their safe type") {
    val issuer: Backend[IO] = BackendStub[IO](summon[sttp.monad.MonadError[IO]]).whenAnyRequest
      .thenThrow(new IOException("transport-secret-canary"))

    UpstreamCredentials.withBackend[IO](oauthConfig, issuer).use(_.authenticate(request)).map { result =>
      val failure = result.left.toOption.getOrElse(fail("expected a transport failure"))
      assertEquals(failure, UpstreamCredentials.Failure.Transport)
      assert(!failure.message.contains("transport-secret-canary"), failure.message)
    }
  }

  test("credential rendering and diagnostics never disclose secrets") {
    val canaries = List("basic-secret-canary", "bearer-secret-canary")
    val configured = List(
      credentials(UpstreamAuthConfig.Basic("kui", Secret(canaries.head))),
      credentials(UpstreamAuthConfig.Bearer(Secret(canaries.last)))
    )

    configured.zip(canaries).foreach { case (credential, canary) =>
      val rendered = List(credential.toString, credential.describe).mkString("\n")
      assert(!rendered.contains(canary), rendered)
    }
  }

  private val request = basicRequest.get(uri"https://upstream.invalid/resource")

  private val oauthConfig: UpstreamAuthConfig.OAuth = UpstreamAuthConfig.OAuth(
    SafeUrl.unsafe("https://issuer.example/token"),
    "oauth-client",
    Secret("oauth-client-secret"),
    Some("metrics.read")
  )

  private val validTokenBody = """{"access_token":"Ab9-._~+/==","expires_in":3600}"""

  private def credentials(config: UpstreamAuthConfig): UpstreamCredentials[IO] =
    UpstreamCredentials
      .static[IO](config)
      .getOrElse(fail(s"expected static credentials for ${config.describe}"))

  private def authorizationHeaders[T](authenticated: Request[T]): List[String] =
    authenticated.headers.collect {
      case header if header.name.equalsIgnoreCase("Authorization") => header.value
    }.toList

  private def responding(status: StatusCode, body: String): Backend[IO] =
    BackendStub[IO](summon[sttp.monad.MonadError[IO]]).whenAnyRequest
      .thenRespond(tokenResponse(body, status))

  private def tokenResponse(token: String, expiresIn: Long): Response[StubBody] =
    tokenResponse(s"""{"access_token":"$token","expires_in":$expiresIn}""", StatusCode.Ok)

  private def tokenResponse(body: String, status: StatusCode): Response[StubBody] =
    ResponseStub.adjust(body, status): Response[StubBody]
}
