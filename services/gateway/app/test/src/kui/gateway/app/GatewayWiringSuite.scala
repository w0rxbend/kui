package kui.gateway.app

import scala.concurrent.duration.DurationInt

import cats.effect.IO

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
    // No upstream is contacted while wiring, so this returns as fast as the empty case. That is the
    // assertion: a timeout here would mean the composition root had started probing, which is what turns a
    // slow upstream into a gateway that never finishes booting.
    wire(unreachableUpstreams).timeout(5.seconds).map { gateway =>
      assert(gateway.routes.nonEmpty)
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
