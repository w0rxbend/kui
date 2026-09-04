package kui.http.principal

import java.nio.charset.StandardCharsets
import java.time.Instant

import cats.effect.IO
import cats.syntax.all.*
import io.circe.parser.parse
import io.circe.syntax.*
import io.circe.{Codec, HCursor, Json}
import munit.CatsEffectSuite
import org.typelevel.otel4s.metrics.Counter
import org.typelevel.otel4s.oteljava.testkit.OtelJavaTestkit
import sttp.client4.*
import sttp.client4.impl.cats.implicits.*
import sttp.client4.testing.StreamBackendStub
import sttp.capabilities.fs2.Fs2Streams
import sttp.model.Uri
import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.stub4.TapirStreamStubInterpreter

import kui.contracts.{ErrorEnvelope, KuiEndpoint}
import kui.kernel.error.KuiError
import kui.kernel.{RoleName, ServiceId, UserName}
import kui.security.*
import kui.testkit.fakes.FakeStructuredLogger

/** ADR-020 Amendment 1, as an executable statement.
  *
  * The rule the amendment settles is invisible in every suite written before it, because an endpoint with an
  * empty body hashes the same either way. So these cases are all about a body: that a token bound to the
  * bytes is accepted, that one bound to the request line alone is not, and — the case that actually protects
  * an operator — that a token minted for one body cannot be replayed with another.
  */
final class SecuredRoutesSuite extends CatsEffectSuite {

  private val Service: ServiceId = ServiceId.unsafe("message")
  private val Path: String = "/internal/v1/echo"

  /** A tiny bodied contract, written out rather than borrowed from a service: `libs/http` may not see one
    * (rule A5), and the point being tested is the mechanism, not any particular service's shape.
    */
  final case class Echo(text: String)

  object Echo {
    given Codec[Echo] = Codec.from(
      (cursor: HCursor) => cursor.get[String]("text").map(Echo.apply),
      (echo: Echo) => Json.obj("text" -> echo.text.asJson)
    )

    given Schema[Echo] = Schema.derived[Echo]
    given CanEqual[Echo, Echo] = CanEqual.derived
  }

  private val endpoint: Endpoint[SignedPrincipal, Echo, ErrorEnvelope, Echo, Any] =
    KuiEndpoint.internal.post.in("internal" / "v1" / "echo").in(jsonBody[Echo]).out(jsonBody[Echo])

  private def server(counter: Counter[IO, Long]): IO[Backend[IO]] =
    FakeStructuredLogger[IO].map { logger =>
      val secured = new SecuredRoutes[IO](PrincipalCodec.inProcess[IO], Service, counter, logger)

      TapirStreamStubInterpreter[IO, Fs2Streams[IO]](StreamBackendStub[IO, Fs2Streams[IO]](summon[sttp.monad.MonadError[IO]]))
        .whenServerEndpointRunLogic(
          secured.withBody(endpoint)(SecuredRoutes.bodyBytes[Echo]) { _ => input =>
            input.asRight[KuiError].pure[IO]
          }
        )
        .backend()
    }

  /** A token for this call, bound to whatever digest the case wants to try. */
  private def token(digest: RequestDigest): IO[SignedPrincipal] =
    IO.realTimeInstant.flatMap(now =>
      PrincipalCodec
        .inProcess[IO]
        .sign(
          PrincipalClaims(
            subject = UserName.unsafe("operator"),
            roles = Set(RoleName.unsafe("Reader")),
            kind = PrincipalKind.Session,
            sessionRef = None,
            issuedAt = now,
            expiresAt = now.plusSeconds(60L),
            audience = Service,
            requestDigest = digest
          )
        )
    )

  private def post(sent: Echo, digest: Echo => RequestDigest): IO[Response[String]] =
    for {
      counter <- OtelJavaTestkit.inMemory[IO]().use(_.meterProvider.get("test").flatMap(rejectionCounter))
      backend <- server(counter)
      header <- token(digest(sent))
      response <- basicRequest
        .post(Uri.unsafeParse(s"http://message$Path"))
        .header(KuiEndpoint.PrincipalHeader, header.value)
        .header("Content-Type", "application/json")
        .body(SecuredRoutes.bodyBytes(sent))
        .response(asStringAlways)
        .send(backend)
    } yield response

  private def rejectionCounter(meter: org.typelevel.otel4s.metrics.Meter[IO]): IO[Counter[IO, Long]] =
    PrincipalVerification.rejectionCounter[IO](meter)

  private def bound(echo: Echo): RequestDigest =
    RequestDigests.of("POST", Path, SecuredRoutes.bodyBytes(echo))

  private def code(response: Response[String]): Option[String] =
    parse(response.body).toOption.flatMap(_.hcursor.get[String]("code").toOption)

  // -----------------------------------------------------------------------------------------------

  test("aTokenBoundToTheBodyIsAccepted") {
    post(Echo("orders"), bound).map { response =>
      assertEquals(response.code.code, 200)
      assertEquals(parse(response.body).toOption.flatMap(_.hcursor.get[String]("text").toOption), Some("orders"))
    }
  }

  test("aTokenBoundToTheRequestLineAloneIsRefused") {
    // This is the shape the gateway would produce under the alternative the amendment rejected, and the
    // shape every service checked before it. It must not be accepted, or the body binding is decoration.
    post(Echo("orders"), _ => RequestDigest.ofRequestLine("POST", Path)).map { response =>
      assertEquals(response.code.code, 401)
      assertEquals(code(response), Some("KUI-UNAUTHENTICATED"))
    }
  }

  test("aTokenMintedForADifferentBodyCannotBeReplayed") {
    // The property that protects an operator: a token intercepted on its way to publish one record is
    // useless for publishing another.
    post(Echo("orders"), _ => bound(Echo("payments"))).map { response =>
      assertEquals(response.code.code, 401)
      assertEquals(code(response), Some("KUI-UNAUTHENTICATED"))
    }
  }

  test("theRefusalIsTheSameFourOhOneWhicheverCheckFailed") {
    // Two different forgeries, one indistinguishable answer. An endpoint that answered "wrong body" for
    // one and "wrong audience" for another is an oracle.
    for {
      wrongBody <- post(Echo("orders"), _ => bound(Echo("payments")))
      wrongLine <- post(Echo("orders"), _ => RequestDigest.ofRequestLine("POST", Path))
    } yield {
      assertEquals(wrongBody.code.code, wrongLine.code.code)
      assertEquals(messageOf(wrongBody), messageOf(wrongLine))
    }
  }

  private def messageOf(response: Response[String]): Option[String] =
    parse(response.body).toOption.flatMap(_.hcursor.get[String]("message").toOption)

  test("bodyBytesIsTheCompactPrinterBothSidesUse") {
    // The two hashes agree by construction only while this stays `Printer.noSpaces` over the contract's
    // own encoder. A pretty printer here would refuse every bodied call with a 401 naming nothing.
    assertEquals(
      new String(SecuredRoutes.bodyBytes(Echo("orders")), StandardCharsets.UTF_8),
      """{"text":"orders"}"""
    )
  }

  test("theRequestLineDigestIgnoresTheQueryString") {
    // Documented here because the gateway hashes `request.uri.path` and the service hashes the same, and a
    // change to either that started including the query would refuse every filtered call.
    val digest = RequestDigest.ofRequestLine("GET", "/internal/v1/topics")
    assertEquals(digest.path, "/internal/v1/topics")
    assertEquals(digest.method, "GET")
  }

  test("anExpiredTokenIsStillRefusedOnABodiedCall") {
    // `withBody` verifies later than `apply` does; it must not verify *less*. Everything `checkClaims`
    // does still runs, expiry included.
    for {
      counter <- OtelJavaTestkit.inMemory[IO]().use(_.meterProvider.get("test").flatMap(rejectionCounter))
      backend <- server(counter)
      sent = Echo("orders")
      stale <- staleToken(bound(sent))
      response <- basicRequest
        .post(Uri.unsafeParse(s"http://message$Path"))
        .header(KuiEndpoint.PrincipalHeader, stale.value)
        .header("Content-Type", "application/json")
        .body(SecuredRoutes.bodyBytes(sent))
        .response(asStringAlways)
        .send(backend)
    } yield {
      assertEquals(response.code.code, 401)
      assertEquals(code(response), Some("KUI-UNAUTHENTICATED"))
    }
  }

  private def staleToken(digest: RequestDigest): IO[SignedPrincipal] =
    PrincipalCodec
      .inProcess[IO]
      .sign(
        PrincipalClaims(
          subject = UserName.unsafe("operator"),
          roles = Set.empty,
          kind = PrincipalKind.Session,
          sessionRef = None,
          issuedAt = Instant.EPOCH,
          expiresAt = Instant.EPOCH.plusSeconds(60L),
          audience = Service,
          requestDigest = digest
        )
      )
}
