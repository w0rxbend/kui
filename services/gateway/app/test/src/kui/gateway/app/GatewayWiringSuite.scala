package kui.gateway.app

import java.net.{InetAddress, ServerSocket, SocketTimeoutException}

import scala.concurrent.duration.DurationInt

import cats.effect.IO
import cats.effect.kernel.Resource

import kui.config.{GatewayConfig, ServerConfig, UpstreamServiceConfig}
import kui.gateway.contract.dto.TopicOverviewDto
import kui.kernel.{Host, Port, PositiveInt, ServiceId}
import kui.observability.Telemetry
import kui.testkit.KuiIOSuite
import kui.testkit.fakes.FakeStructuredLogger

/** That the gateway can always be built, whatever the rest of the system is doing.
  *
  * This is the one property of the composition root that has to hold at three in the morning. The gateway is
  * Core tier (PLAN §15): when it is down, the browser has nowhere to go and shows the single full-screen
  * "cannot reach gateway" page, so a gateway that refused to start because some service was unreachable
  * would turn one service's outage into a total blackout — at exactly the moment an operator most needs a
  * working UI to find out what is wrong.
  *
  * AIO-001 extends this suite for the all-in-one process, which wires the same function.
  */
final class GatewayWiringSuite extends KuiIOSuite {

  /** A configuration naming two services that certainly do not answer: port 1 on the loopback interface is
    * privileged and nothing in a test environment is listening on it.
    */
  private val unreachableUpstreams: GatewayServiceConfig = {
    val nowhere = UpstreamServiceConfig(
      url = kui.config.SafeUrl.unsafe("http://127.0.0.1:1"),
      timeout = 1.second,
      maxConcurrent = PositiveInt.unsafe(1)
    )

    GatewayServiceConfig.Default.copy(
      server = ServerConfig(Host.unsafe("localhost"), Port.unsafe(0), "/"),
      gateway = GatewayConfig.Default.copy(
        services = Map(ServiceId.unsafe("cluster") -> nowhere, ServiceId.unsafe("topic") -> nowhere)
      )
    )
  }

  /** A socket that listens and never answers, so that "nobody connected" is an observation.
    *
    * A backlog is what makes it work: the operating system completes the handshake for a queued connection
    * whether or not this test ever calls `accept`, so a probe issued during wiring is recorded even though
    * nothing was reading at the time.
    */
  private def listener: Resource[IO, ServerSocket] =
    Resource.make(IO(new ServerSocket(0, 8, InetAddress.getLoopbackAddress)))(socket => IO(socket.close()))

  /** The same two-service configuration, aimed at a port something really is listening on. */
  private def pointedAt(server: ServerSocket): GatewayServiceConfig = {
    val there = UpstreamServiceConfig(
      url = kui.config.SafeUrl.unsafe(s"http://127.0.0.1:${server.getLocalPort}"),
      timeout = 1.second,
      maxConcurrent = PositiveInt.unsafe(1)
    )

    unreachableUpstreams.copy(gateway =
      unreachableUpstreams.gateway.copy(services =
        Map(ServiceId.unsafe("cluster") -> there, ServiceId.unsafe("topic") -> there)
      )
    )
  }

  private def wire(config: GatewayServiceConfig) =
    FakeStructuredLogger[IO].flatMap { logger =>
      GatewayWiring.make[IO](config, Telemetry.noop[IO], logger).use(IO.pure)
    }

  test("theGatewayStartsWithNothingConfigured") {
    wire(GatewayServiceConfig.Default).map { gateway =>
      assert(gateway.routes.nonEmpty, "a gateway with no configuration still serves its health endpoints")
      assert(gateway.interceptors.nonEmpty)
    }
  }

  test("theGatewayStartsWhenEveryUpstreamIsUnreachable") {
    // The claim is that no upstream is *contacted* while wiring, and it is asserted by watching a socket
    // rather than a clock.
    //
    // This case used to be `wire(...).timeout(5.seconds)`, and that measured the wrong thing in both
    // directions. It could not fail for the defect it was written for — the upstreams are at 127.0.0.1:1,
    // which refuses a connection instantly, so a composition root that probed every one of them would still
    // have returned in milliseconds. And it failed on one of three full-suite runs during wave 4's
    // verification and passed in isolation, because five seconds of wall clock is not a bound on anything
    // when sixteen forked suites are loading the sttp, tapir and otel class graphs at once: the same
    // module's six cases take 1.5 s in isolation and its siblings take 40 to 90 s each under load.
    //
    // So: a real listening socket that accepts nothing, and afterwards an `accept` that must time out. A
    // connection opened during wiring would already be sitting in the backlog and would be accepted at once,
    // which makes this fail for the defect and for nothing else. MUnit's own 30-second timeout is what
    // catches a composition root that hangs.
    listener.use { server =>
      wire(pointedAt(server)).map { gateway =>
        assert(gateway.routes.nonEmpty)
        server.setSoTimeout(250)
        val connected =
          try { server.accept().close(); true }
          catch { case _: SocketTimeoutException => false }

        assert(!connected, "the composition root opened a connection to an upstream while wiring")
      }
    }
  }

  test("readinessDependsOnNoUpstream") {
    // The gateway answers `/health/ready` truthfully with every service down, because "can I serve
    // requests" is a question about this process. Reporting the services' state is the capability
    // registry's job (GW-003), and it dims a feature rather than taking the whole product out of rotation.
    wire(unreachableUpstreams).map { gateway =>
      assertEquals(gateway.readiness.map(_.name), List("process"))
    }
  }

  test("aConfiguredConsumerServiceFillsTheTopicPagesConsumersSection") {
    // The defect this catches was invisible from every layer that had a test. `TopicOverviewUseCase`
    // reports a section with no registered source as `not_configured` — correct, and the answer a fully
    // configured deployment was getting, because this composition root called it without ever passing a
    // source for `consumerGroups`. The consumer service was deployed, its capability read `available`, its
    // own endpoint answered when called directly, and the topic page's Consumers tab said the deployment
    // did not track consumer groups.
    //
    // The assertion is about the *set of sections this build can fill*, which is read from what is wired
    // rather than hard-coded, so it is the one thing that could not have been true by accident.
    val configured = unreachableUpstreams.gateway.services +
      (ServiceId.unsafe("consumer") -> unreachableUpstreams.gateway.services(ServiceId.unsafe("topic")))

    val withConsumers =
      unreachableUpstreams.copy(gateway = unreachableUpstreams.gateway.copy(services = configured))

    wire(withConsumers).map { gateway =>
      assert(
        gateway.fillableTopicSections.contains(TopicOverviewDto.ConsumerGroupsSection),
        s"the topic overview can fill ${gateway.fillableTopicSections}, " +
          "and a consumer service is configured"
      )
    }
  }

  test("aDeploymentWithNoConsumerServiceStillSaysSoRatherThanPretending") {
    // The other half, and the reason `not_configured` exists at all: a build with no consumer service must
    // report the section as one it cannot fill, so the panel says this deployment has no such thing rather
    // than showing a permanent error.
    wire(unreachableUpstreams).map { gateway =>
      assert(!gateway.fillableTopicSections.contains(TopicOverviewDto.ConsumerGroupsSection))
    }
  }

  test("corsIsOffUnlessADeploymentAsksForIt") {
    // Off by default (ADR-019): the shipped deployment serves the shell from this same origin, so there is
    // no cross-origin request to permit, and a permissive default is how a logged-in user's Kafka data
    // becomes readable by any website they happen to visit.
    for {
      plain <- wire(GatewayServiceConfig.Default)
      permissive <- wire(
        GatewayServiceConfig.Default.copy(
          gateway = GatewayConfig.Default.copy(
            cors = kui.config.CorsConfig(enabled = true, origins = List("https://example.com"))
          )
        )
      )
    } yield assertEquals(
      permissive.interceptors.size,
      plain.interceptors.size + 1,
      "enabling CORS adds exactly one interceptor, and disabling it adds none"
    )
  }
}
