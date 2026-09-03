package kui.allinone

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all.*

import kui.cluster.contract.ClusterEndpoints
import kui.gateway.application.client.ServiceClient
import kui.http.health.HealthEndpoints
import kui.kernel.Secret
import kui.kernel.error.{ErrorCode, KuiError}
import kui.security.{JwsPrincipalCodec, PrincipalCodec, SigningKey}
import kui.testkit.KuiIOSuite
import kui.testkit.fakes.FakeStructuredLogger

/** That calling a service in memory and calling it over a socket produce the same answer.
  *
  * This is the enforcement of ADR-005, and it is the only test in the module that has to exist. The ADR's
  * argument is that a gateway with two ways of calling a service will grow a bug that appears in one shape
  * and not the other, and the way KUI avoids that is by having one client implementation and two transports
  * under it. That claim is worth exactly as much as the evidence for it, so every case here asks the same
  * question twice — once through `InProcessServiceClient`, once through `SttpServiceClient` against a real
  * loopback listener — and asserts the two answers are *equal*, not merely both plausible.
  *
  * The failures matter more than the successes. A success is a JSON body that either decodes or does not; a
  * failure travels as a status code, an error envelope and an error code, and it is the half where a second
  * code path would show up first — as a business error that became a generic upstream error, or a 400 that
  * became a 500.
  */
final class InProcessServiceClientSuite extends KuiIOSuite {

  /** The two clients over one service, run for every case rather than shared, so that no case can be made to
    * pass by state another one left behind.
    */
  private def both[A](use: AllInOneFixture.BothTransports => IO[A]): IO[A] =
    AllInOneFixture.bothTransports.use(use)

  /** Asks both clients the same thing and hands back the two answers. */
  private def ask[A](
      transports: AllInOneFixture.BothTransports
  )(call: ServiceClient[IO] => IO[Either[KuiError, A]]): IO[(Either[KuiError, A], Either[KuiError, A])] =
    (call(transports.inProcess), call(transports.overHttp)).tupled

  test("producesTheSameResultAsTheHttpClientForEverySampleEndpoint") {
    both { transports =>
      for {
        ping <- ask(transports)(
          _.call(ClusterEndpoints.ping, "hello")(AllInOneFixture.context)
        )
        capabilities <- ask(transports)(
          _.callPublic(HealthEndpoints.capabilities, ())(AllInOneFixture.context)
        )
        readiness <- ask(transports)(
          _.callPublic(HealthEndpoints.ready, ())(AllInOneFixture.context)
        )
      } yield {
        // The echoed message and the reporting service are the whole of the ping response that is not a
        // timestamp. The timestamp is read from the real clock and is genuinely different between two
        // calls a millisecond apart, in either transport, so comparing it would be asserting that time
        // does not pass.
        assertEquals(
          ping._1.map(response => (response.message, response.service)),
          ping._2.map(response => (response.message, response.service)),
          "the ping response must not depend on how the service was called"
        )
        assertEquals(capabilities._1, capabilities._2, "the capability document must be transport-agnostic")
        assertEquals(
          readiness._1.map(_.ready),
          readiness._2.map(_.ready),
          "the readiness verdict must be transport-agnostic"
        )
      }
    }
  }

  test("producesTheSameFailureAsTheHttpClientWhenTheUseCaseRefuses") {
    // An empty message breaks `Ping`'s domain rule, so this is a real `KuiError` raised by a use case and
    // carried back as an error envelope — not a transport failure. If the in-process path skipped the
    // envelope and reported "the upstream answered 400", the two codes would differ here and nowhere else.
    both { transports =>
      ask(transports)(_.call(ClusterEndpoints.ping, "")(AllInOneFixture.context)).map {
        (inProcess, overHttp) =>
          assertEquals(
            inProcess.leftMap(_.code),
            overHttp.leftMap(_.code),
            "a business failure must survive both transports as the same error code"
          )
          assertEquals(
            inProcess.leftMap(_.code),
            Left(ErrorCode.Validation),
            "a message the domain refuses is a validation failure, not an upstream failure"
          )
      }
    }
  }

  test("propagatesThePrincipalAsAValueWithoutASignature") {
    // Two halves, and both are needed. The first says the unsigned token is genuinely accepted: the call
    // travels through the service's real `PrincipalInterceptor` and `PrincipalVerification`, so a success
    // means the claims arrived, the audience matched and the request digest matched.
    val accepted = both { transports =>
      transports.inProcess
        .call(ClusterEndpoints.ping, "hello")(AllInOneFixture.context)
        .map(result => assert(result.isRight, s"the in-process principal must be accepted, got $result"))
    }

    // The second says the service is not simply waving everything through. Handed a codec that expects a
    // real signature, the very same unsigned token is refused — which is what proves the first half was an
    // acceptance rather than an absence of checking.
    val refusedWhenSigningIsExpected =
      FakeStructuredLogger[IO].flatMap { logger =>
        AllInOneFixture
          .cluster(logger, codec = signingCodec)
          .use { service =>
            AllInOneFixture
              .inProcessClient(service, codec = PrincipalCodec.inProcess[IO])
              .call(ClusterEndpoints.ping, "hello")(AllInOneFixture.context)
              .map(result =>
                assertEquals(
                  result.leftMap(_.code),
                  Left(ErrorCode.Unauthenticated),
                  "a service configured to expect signatures must refuse an unsigned claim set"
                )
              )
          }
      }

    accepted *> refusedWhenSigningIsExpected
  }

  /** A codec that verifies real HS256 signatures. Thirty-two bytes is the shortest key the algorithm accepts.
    */
  private def signingCodec: PrincipalCodec[IO] =
    JwsPrincipalCodec
      .make[IO](
        NonEmptyList.one(
          SigningKey(
            "aio-test-1",
            Secret(Array.fill[Byte](32)(7)),
            java.time.Instant.parse("2020-01-01T00:00:00Z")
          )
        ),
        "kui-gateway"
      )
      .fold(weak => sys.error(s"the test signing key is unusable: ${weak.message}"), identity)

  test("anInProcessClientHasNoCircuitToReport") {
    // The empty stream is the truthful answer rather than a stub. There is no connection to a peer, so
    // there is nothing a breaker could open on, and the capability fold reads "no breaker signal" — which
    // is different from, and must not be reported as, "the breaker is closed and healthy".
    AllInOneFixture.bothTransports.use { transports =>
      transports.inProcess.circuitStates.compile.toList.map(events =>
        assertEquals(events, Nil, "an in-process client reports no circuit transitions, ever")
      )
    }
  }
}
