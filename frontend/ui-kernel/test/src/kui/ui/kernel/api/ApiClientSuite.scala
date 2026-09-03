package kui.ui.kernel.api

import java.time.Instant

import scala.concurrent.{ExecutionContext, Future, Promise}

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.*
import io.circe.syntax.*
import munit.{FunSuite, ScalaCheckSuite}
import org.scalacheck.Prop.forAll
import org.scalacheck.{Arbitrary, Gen}
import sttp.client4.testing.BackendStub
import sttp.client4.{Backend, GenericRequest}
import sttp.model.{StatusCode, Uri}

import kui.contracts.{ErrorEnvelope, HttpHeaders}
import kui.contracts.ErrorEnvelope.given
import kui.ui.kernel.state.{Auth, AuthInfo}

/** What the client does to a request on the way out and to a response on the way back.
  *
  * Every test here runs the real `SttpApiClient` against `BackendStub`, so the header logic, the Tapir
  * interpretation, the `401` interception and the failure classification are the production ones. Only the
  * transport is replaced, which means there is no browser, no server and no port to be flaky about.
  */
class ApiClientSuite extends FunSuite with ApiClientFixtures {

  test("addsTheCsrfHeaderToMutationsAndNotToGets") {
    val auth = new Auth
    auth.markSignedIn(AuthInfo(principal = None, csrfToken = Some("token-42"), authType = "session"))
    val recorder = new RequestRecorder

    val client = clientFor(recorder.backend("pong"), auth)

    for {
      _ <- one(client.call(ApiEndpoints.ping, ()))
      getRequest = recorder.last
      _ <- one(client.call(ApiEndpoints.poke, "body"))
      postRequest = recorder.last
    } yield {
      assertEquals(getRequest.header(ApiClient.CsrfHeader), None)
      assertEquals(postRequest.header(ApiClient.CsrfHeader), Some("token-42"))
      // The request id is on both: it is a support aid, not a defence, so it is not tied to method.
      assert(getRequest.header(ApiClient.RequestIdHeader).isDefined)
      assert(postRequest.header(ApiClient.RequestIdHeader).isDefined)
    }
  }

  test("theCsrfHeaderIsNamedExactlyWhatTheGatewayReads") {
    // The one assertion that would have caught the bug this test was written for. The browser used to
    // send `X-Kui-Csrf`; the gateway reads `X-Csrf-Token`, and it strips the whole `X-Kui-*` family at
    // the edge before anything can look at it (ADR-040), so every mutation from the browser was
    // rejected as a forgery. Both halves now read the name from `kui.contracts.HttpHeaders`, and this
    // pins the literal so that a rename has to be a deliberate change to the shared contract rather
    // than a silent one on either side.
    assertEquals(ApiClient.CsrfHeader, "X-Csrf-Token")
    assertEquals(ApiClient.CsrfHeader, HttpHeaders.Csrf)
    assert(
      !ApiClient.CsrfHeader.toLowerCase.startsWith("x-kui-"),
      "the CSRF header must stay outside the X-Kui-* family, which the gateway strips from every " +
        "inbound request"
    )

    val auth = new Auth
    auth.markSignedIn(AuthInfo(principal = None, csrfToken = Some("token-42"), authType = "session"))
    val recorder = new RequestRecorder

    // And the name is asserted on a real outgoing request too, not only on the constant: a client that
    // held the right constant and sent a different header would still be broken.
    one(clientFor(recorder.backend("pong"), auth).call(ApiEndpoints.poke, "body")).map { _ =>
      assertEquals(recorder.last.header("X-Csrf-Token"), Some("token-42"))
    }
  }

  test("aMutationWithNoTokenYetIsStillSentSoTheServerCanExplainWhyItRefused") {
    val recorder = new RequestRecorder
    val client = clientFor(recorder.backend("pong"), new Auth)

    one(client.call(ApiEndpoints.poke, "body")).map { _ =>
      assertEquals(recorder.last.header(ApiClient.CsrfHeader), None)
    }
  }

  test("decodesAnErrorEnvelopeIntoApiErrorEnvelopeWithTheCode") {
    val envelope = ErrorEnvelope(
      code = "KUI-TOPIC-NOT-FOUND",
      message = "The topic does not exist on this cluster.",
      details = Nil,
      correlationId = "corr-1",
      timestamp = Instant.parse("2026-09-03T10:11:12Z"),
      retryable = false
    )
    val backend = stubBackend(_ => true, envelope.asJson.noSpaces, 404)

    one(clientFor(backend, new Auth).call(ApiEndpoints.ping, ())).map {
      case Left(ApiError.Envelope(code, message, _, correlationId, retryable)) =>
        assertEquals(code, "KUI-TOPIC-NOT-FOUND")
        assertEquals(message, envelope.message)
        assertEquals(correlationId, "corr-1")
        assertEquals(retryable, false)
      case other => fail(s"expected a decoded envelope, got $other")
    }
  }

  test("anUnknownCodeStillDecodesSoAnOlderBrowserCanRenderANewerGatewaysFailure") {
    val envelope = ErrorEnvelope(
      code = "KUI-INVENTED-TOMORROW",
      message = "Something new went wrong.",
      details = Nil,
      correlationId = "corr-2",
      timestamp = Instant.parse("2026-09-03T10:11:12Z"),
      retryable = true
    )
    val backend = stubBackend(_ => true, envelope.asJson.noSpaces, 500)

    one(clientFor(backend, new Auth).call(ApiEndpoints.ping, ())).map { outcome =>
      assertEquals(outcome, Left(ApiError.of(envelope)))
      assertEquals(outcome.left.map(_.userMessage), Left("Something new went wrong."))
      assertEquals(outcome.left.map(_.isRetryable), Left(true))
    }
  }

  test("aNetworkFailureBecomesUnreachableNotAThrownException") {
    val backend = BackendStub
      .asynchronousFuture(using executionContext)
      .whenAnyRequest
      .thenThrow(new RuntimeException("Failed to fetch"))

    one(clientFor(backend, new Auth).call(ApiEndpoints.ping, ())).map { outcome =>
      assertEquals(outcome, Left(ApiError.Unreachable("Failed to fetch")))
      assert(outcome.left.exists(_.isTransport))
    }
  }

  test("aMalformedResponseBecomesDecodingAndIsNotSwallowed") {
    val backend = stubBackend(_ => true, "<html>502 Bad Gateway</html>", 502)

    one(clientFor(backend, new Auth).call(ApiEndpoints.ping, ())).map { outcome =>
      outcome match {
        case Left(ApiError.Decoding(cause)) => assert(cause.contains("502"), cause)
        case other => fail(s"expected a decoding failure, got $other")
      }
      // A contract mismatch is not an outage: escalating it to the full-screen state would send an
      // operator to look at the network for a bug that is in the code.
      assert(!outcome.left.exists(_.isTransport))
    }
  }

  test("aFourZeroOneSignalsAuthStateExpiredExactlyOnceForConcurrentCalls") {
    val auth = new Auth
    auth.markSignedIn(AuthInfo(principal = None, csrfToken = Some("token"), authType = "session"))
    val backend = stubBackend(_ => true, unauthenticated.asJson.noSpaces, 401)
    val client = clientFor(backend, auth)

    val owner = new ManualOwner
    var expiries = 0
    auth.expired.foreach(_ => expiries += 1)(using owner): Unit

    val calls = List.fill(3)(one(client.call(ApiEndpoints.ping, ())))
    Future.sequence(calls).map { outcomes =>
      assertEquals(outcomes.count(_.left.exists(_.isAuth)), 3)
      assertEquals(expiries, 1, "three concurrent 401s must ask for one session refresh, not three")
      assertEquals(auth.csrfToken.now(), None)
      owner.killSubscriptions()
    }
  }

  test("aSuccessfulRefreshReArmsTheExpirySignal") {
    val auth = new Auth
    val owner = new ManualOwner
    var expiries = 0
    auth.expired.foreach(_ => expiries += 1)(using owner): Unit

    auth.markExpired()
    auth.markExpired()
    auth.markSignedIn(AuthInfo(principal = None, csrfToken = Some("fresh"), authType = "session"))
    auth.markExpired()

    assertEquals(expiries, 2)
    owner.killSubscriptions()
  }

  test("aFailedRefreshLeavesThePreviousIdentityAlone") {
    val auth = new Auth
    auth.markSignedIn(AuthInfo(principal = None, csrfToken = Some("still-good"), authType = "session"))

    one(auth.refresh(() => EventStream.fromValue(Left(ApiError.Timeout)))).map { outcome =>
      assertEquals(outcome, Left(ApiError.Timeout))
      assertEquals(auth.csrfToken.now(), Some("still-good"))
    }
  }

  test("sendsCredentialsSoTheSessionCookieTravels") {
    // Not observable through `BackendStub`: `credentials` is an option of the browser's own `fetch`,
    // not a header, so there is nothing on the request to assert. What can be pinned is the option the
    // real backend is built with, which is the line that would be deleted by accident.
    assertEquals(ApiClient.BrowserFetchOptions.credentials, Some(org.scalajs.dom.RequestCredentials.include))
  }

  test("aDeploymentUnderAPathPrefixSendsTheRequestToThatPrefix") {
    val bootstrap = Bootstrap(basePath = "/kafka", apiBase = "/kafka/api/v1", buildVersion = "test")
    val recorder = new RequestRecorder
    val client = new SttpApiClient(
      Uri.unsafeParse(Bootstrap.absoluteApiBase(bootstrap, "http://gateway.test")),
      new Auth,
      recorder.backend("pong")
    )(using executionContext)

    one(client.call(ApiEndpoints.ping, ())).map { _ =>
      assertEquals(recorder.last.uri.path, List("kafka", "api", "v1", "ping"))
    }
  }

  test("everyCallGetsItsOwnRequestId") {
    val recorder = new RequestRecorder
    val client = clientFor(recorder.backend("pong"), new Auth)

    for {
      _ <- one(client.call(ApiEndpoints.ping, ()))
      first = recorder.last.header(ApiClient.RequestIdHeader)
      _ <- one(client.call(ApiEndpoints.ping, ()))
      second = recorder.last.header(ApiClient.RequestIdHeader)
    } yield {
      assert(first.isDefined)
      assertNotEquals(first, second)
    }
  }
}

/** The base-URL rule, as a property: whatever prefix a deployment is mounted under, the API base is that
  * prefix followed by the API's own path — with no slash doubled and none lost.
  *
  * A property rather than three examples because the failure mode is a boundary one. Every bug this rule can
  * have is an off-by-one slash at a join, and those hide behind whichever single example the author happened
  * to pick.
  */
class ApiClientBaseUrlSuite extends ScalaCheckSuite with ApiClientFixtures {

  private val basePath: Gen[String] =
    Gen.listOf(Gen.nonEmptyListOf(Gen.alphaLowerChar).map(_.mkString)).map(_.map("/" + _).mkString)

  private given Arbitrary[String] = Arbitrary(basePath)

  property("usesTheBootstrapApiBaseIncludingABasePath") {
    forAll { (prefix: String) =>
      val bootstrap = Bootstrap(basePath = prefix, apiBase = s"$prefix/api/v1", buildVersion = "test")
      val absolute = Uri.unsafeParse(Bootstrap.absoluteApiBase(bootstrap, "http://gateway.test"))
      val expected = prefix.split('/').filter(_.nonEmpty).toList ++ List("api", "v1")

      absolute.host.contains("gateway.test") && absolute.path == expected
    }
  }

  property("anAbsoluteApiBaseIsLeftAloneSoASplitOriginDeploymentStillWorks") {
    forAll { (prefix: String) =>
      val bootstrap =
        Bootstrap(basePath = prefix, apiBase = s"https://api.example.com$prefix/api/v1", buildVersion = "test")
      Bootstrap.absoluteApiBase(bootstrap, "http://gateway.test") == bootstrap.apiBase
    }
  }
}

/** The plumbing the two suites above share. */
trait ApiClientFixtures { self: munit.Suite =>

  given executionContext: ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue

  val unauthenticated: ErrorEnvelope = ErrorEnvelope(
    code = "KUI-UNAUTHENTICATED",
    message = "Sign in to continue.",
    details = Nil,
    correlationId = "corr-401",
    timestamp = Instant.parse("2026-09-03T10:11:12Z"),
    retryable = false
  )

  def clientFor(backend: Backend[Future], auth: Auth): ApiClient =
    new SttpApiClient(Uri.unsafeParse("http://gateway.test/api/v1"), auth, backend)(using executionContext)

  def stubBackend(matches: GenericRequest[?, ?] => Boolean, body: String, status: Int): Backend[Future] =
    BackendStub.asynchronousFuture(using executionContext).whenRequestMatches(matches).thenRespondAdjust(body, StatusCode(status))

  /** The first value an `EventStream` emits, as a `Future` MUnit can await.
    *
    * The owner is never killed: these streams emit once and complete, and the suite's lifetime is a few
    * milliseconds. Holding it would add ceremony to every test for no behaviour.
    */
  def one[A](stream: EventStream[A]): Future[A] = {
    val arrived = Promise[A]()
    stream.foreach(value => arrived.trySuccess(value): Unit)(using new ManualOwner): Unit
    arrived.future
  }
}

/** A `BackendStub` that remembers what it was asked, so a test can assert on the outgoing request. */
final class RequestRecorder(using ExecutionContext) {

  private var seen: List[GenericRequest[?, ?]] = Nil

  def backend(body: String): Backend[Future] =
    BackendStub
      .asynchronousFuture
      .whenRequestMatches { request =>
        seen = request :: seen
        true
      }
      .thenRespondAdjust(body, StatusCode.Ok)

  /** The most recent request. Fails the test rather than throwing a `NoSuchElementException` three frames
    * away from the line that is actually wrong.
    */
  def last: GenericRequest[?, ?] =
    seen.headOption.getOrElse(throw new AssertionError("no request reached the backend"))
}
