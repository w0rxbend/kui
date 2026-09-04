package kui.message.api

import java.time.Instant

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import cats.data.NonEmptyList
import cats.effect.IO
import fs2.Stream
import munit.CatsEffectSuite
import org.typelevel.otel4s.oteljava.testkit.OtelJavaTestkit
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.*
import sttp.client4.impl.cats.implicits.*
import sttp.client4.testing.StreamBackendStub
import sttp.tapir.server.stub4.TapirStreamStubInterpreter

import kui.contracts.KuiEndpoint
import kui.http.principal.{PrincipalVerification, RbacGuard}
import kui.kernel.browse.PollBudget
import kui.kernel.error.KuiError
import kui.kernel.{ClusterId, RoleName, Secret, ServiceId, TopicName, UserName}
import kui.message.application.{BrowseEvent, BrowseUseCase}
import kui.message.domain.{BrowseLimits, BrowseRequest}
import kui.observability.Telemetry
import kui.security.*
import kui.security.rbac.*
import kui.testkit.fakes.FakeStructuredLogger

/** The message service refusing on its own account.
  *
  * ==Why this suite exists at all==
  *
  * Every other KUI service is reached through the gateway's contract router, which decides permission at the
  * edge before it forwards anything. The browse stream is the one endpoint that is *not*: a stream needs its
  * own cancellation and heartbeat handling, so `MessageStreamRoutes` carries it over a stream proxy rather
  * than through `ContractRouting`. That makes this service's own check the enforcement point for the single
  * endpoint in KUI that hands back the contents of a topic.
  *
  * And even where the gateway does check — produce, resend, purge — the second check is what stands between
  * a topic's records and anything that can reach this service's port with a signed principal. A KUI service
  * listens on its own port and trusts a signed token; the gateway is a door, not a wall (ADR-021).
  *
  * So every request below goes straight to this service's own routes, with a valid principal and no gateway
  * anywhere, which is exactly the shape of the call the second check exists for.
  */
final class ServiceRbacGuardSuite extends CatsEffectSuite {

  private val cluster: ClusterId = ClusterId.unsafe("local")
  private val reader: RoleName = RoleName.unsafe("reader")

  private def path(topic: String): String =
    s"/internal/v1/clusters/${cluster.value}/topics/$topic/messages/stream"

  // ----------------------------------------------------------------------------------------------
  // The fixture: this service's real routes, its real principal codec, and no socket
  // ----------------------------------------------------------------------------------------------

  /** A browse that emits one phase event and stops. Nothing here is about Kafka. */
  private object StubBrowse extends BrowseUseCase[IO] {
    def browse(request: BrowseRequest, budget: PollBudget): Stream[IO, BrowseEvent] =
      Stream.emit(BrowseEvent.Phase("assigned"))

    def resume(
        cluster: ClusterId,
        topic: TopicName,
        cursor: String,
        stringFilter: Option[String],
        limits: BrowseLimits
    ): IO[Either[KuiError, BrowseRequest]] =
      IO.pure(Left(kui.kernel.error.ApplicationError.NotFound("cursor", cursor, kui.kernel.error.ErrorCode.TopicNotFound)))
  }

  /** Thirty-two bytes, the shortest key HS256 accepts. A real codec, because a fake one would verify a
    * token by agreeing with itself and would pass just as happily against a service that had stopped
    * checking signatures.
    */
  private val key: SigningKey =
    SigningKey("test-1", Secret(Array.fill[Byte](32)(7)), Instant.parse("2020-01-01T00:00:00Z"))

  private val codec: PrincipalCodec[IO] =
    JwsPrincipalCodec
      .make[IO](NonEmptyList.of(key), "kui-gateway")
      .getOrElse(throw new IllegalStateException("the test signing key is too short for HS256"))

  private def token(
      requestPath: String,
      roles: Set[RoleName],
      validFor: FiniteDuration = 60.seconds,
      audience: ServiceId = MessageApi.Id
  ): IO[SignedPrincipal] =
    IO.realTimeInstant.flatMap(now =>
      codec.sign(
        PrincipalClaims(
          subject = UserName.unsafe("alice"),
          roles = roles,
          kind = PrincipalKind.Session,
          sessionRef = None,
          issuedAt = now,
          expiresAt = now.plusSeconds(validFor.toSeconds),
          audience = audience,
          requestDigest = RequestDigest.ofRequestLine("GET", requestPath.takeWhile(_ != '?'))
        )
      )
    )

  private def server(rbac: RbacPolicy): cats.effect.kernel.Resource[IO, StreamBackend[IO, Fs2Streams[IO]]] =
    OtelJavaTestkit.inMemory[IO]().evalMap { testkit =>
      val telemetry = Telemetry.fromProviders(testkit.tracerProvider, testkit.meterProvider)

      for {
        logger <- FakeStructuredLogger[IO]
        meter <- testkit.meterProvider.get("kui.message")
        rejections <- PrincipalVerification.rejectionCounter[IO](meter)
        interceptors <- MessageApi.interceptors[IO](telemetry, rejections, logger)
      } yield {
        val routes = MessageRoutes[IO](
          StubBrowse,
          codec,
          rejections,
          logger,
          telemetry,
          RbacGuard.fromPolicy[IO](rbac, _ => ClusterFlags.Writable, logger)
        )

        TapirStreamStubInterpreter(interceptors, StreamBackendStub[IO, Fs2Streams[IO]](summon))
          .whenServerEndpointsRunLogic(routes)
          .backend()
      }
    }

  /** A policy granting `reader` the named actions over topics matching `pattern`, on this cluster only. */
  private def policy(pattern: String, actions: Action*): RbacPolicy =
    RbacPolicy(
      roles = List(
        Role(
          name = reader,
          clusters = Set(cluster),
          subjects = Nil,
          permissions = List(
            RbacPolicy.permission(
              Resource.Topic,
              Some(ResourcePattern.compile(pattern).getOrElse(fail(s"'$pattern' does not compile"))),
              actions.toSet
            )
          )
        )
      ),
      defaultRole = None
    )

  /** A browse that is expected to be *refused*: its body is an ordinary JSON error envelope.
    *
    * There are two of these rather than one because Tapir's stub backend hands a body back in the shape
    * the caller asked for and will not convert between the two. A refusal never opens the stream — that
    * is the whole point of checking before the logic runs — so it arrives as bytes, while a browse that
    * is allowed arrives as server-sent events.
    */
  private def refused(
      backend: StreamBackend[IO, Fs2Streams[IO]],
      topic: String,
      roles: Set[RoleName]
  ): IO[(Int, String)] = {
    val at = path(topic)
    token(at, roles)
      .flatMap(principal =>
        basicRequest
          .get(uri"${s"http://message$at"}")
          .header(KuiEndpoint.PrincipalHeader, principal.value)
          .response(asStringAlways)
          .send(backend)
      )
      .map(response => (response.code.code, response.body))
  }

  /** A browse that is expected to be *allowed*: its body is the event stream, read to the end. */
  private def browse(
      backend: StreamBackend[IO, Fs2Streams[IO]],
      topic: String,
      roles: Set[RoleName]
  ): IO[(Int, String)] = {
    val at = path(topic)
    for {
      principal <- token(at, roles)
      response <- basicRequest
        .get(uri"${s"http://message$at"}")
        .header(KuiEndpoint.PrincipalHeader, principal.value)
        .response(asStreamAlwaysUnsafe(Fs2Streams[IO]))
        .send(backend)
      body <- response.body.through(fs2.text.utf8.decode).compile.string
    } yield (response.code.code, body)
  }

  // ----------------------------------------------------------------------------------------------
  // The cases
  // ----------------------------------------------------------------------------------------------

  test("a topic the caller's pattern does not match is refused by the service itself") {
    server(policy("payments\\..*", Action.TopicMessagesRead)).use { backend =>
      refused(backend, "secrets", Set(reader)).map { (status, body) =>
        assertEquals(status, 403, body)
        assert(body.contains("KUI-FORBIDDEN"), body)
        // The refusal names neither the missing permission nor the pattern that was checked.
        assert(!body.contains("payments"), s"the refusal leaked the pattern: $body")
      }
    }
  }

  test("a topic the caller's pattern does match is streamed") {
    server(policy("payments\\..*", Action.TopicMessagesRead)).use { backend =>
      browse(backend, "payments.orders", Set(reader)).map { (status, body) =>
        assertEquals(status, 200, body)
        assert(body.contains("assigned"), body)
      }
    }
  }

  test("holding TopicView but not TopicMessagesRead is not enough to read a topic's records") {
    // The distinction the message service exists to enforce: seeing that a topic exists and reading what
    // is in it are different permissions, and only the second one hands over customer data.
    server(policy("payments\\..*", Action.TopicView)).use { backend =>
      refused(backend, "payments.orders", Set(reader)).map { (status, body) =>
        assertEquals(status, 403, body)
      }
    }
  }

  test("a valid principal holding no role at all is refused, however it reached this port") {
    server(policy("payments\\..*", Action.TopicMessagesRead)).use { backend =>
      refused(backend, "payments.orders", Set.empty).map { (status, body) =>
        assertEquals(status, 403, body)
      }
    }
  }

  test("a deployment with no roles configured is unaffected") {
    server(RbacPolicy.Disabled).use { backend =>
      browse(backend, "payments.orders", Set.empty).map { (status, body) =>
        assertEquals(status, 200, body)
      }
    }
  }
}
