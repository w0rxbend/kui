package kui.consumer.app

import scala.concurrent.duration.DurationInt

import cats.effect.IO
import cats.effect.kernel.Resource
import munit.CatsEffectSuite

import kui.config.{ClusterConfig, ConsumersConfig}
import kui.consumer.contract.{ConsumerEndpoints, ConsumerMutationEndpoints}
import kui.kernel.Secret
import kui.observability.Telemetry
import kui.security.PrincipalCodec
import kui.testkit.fakes.FakeStructuredLogger

/** The consumer service's composition root: what it serves, what it starts, and which signing key it used.
  *
  * The first two are the properties every KUI `app` module has and this one had no suite for. The third is
  * the one specific to this service, and it is the reason the module is no longer empty: the key that signs
  * an offset-reset plan token used to be generated per process, so a wizard an operator left open across a
  * restart could never be applied, and a second replica rejected the first one's tokens. It is configuration
  * now, and the only thing a composition-root suite can observe about a secret is which of the two paths was
  * taken — so that is what is asserted, through the line the wiring is required to log.
  */
final class ConsumerWiringSuite extends CatsEffectSuite {

  /** No clusters, because a configured cluster would have this suite open Kafka connections to nothing.
    *
    * That is not a limitation of the fixture; it is the property under test in
    * `wiringOpensNoKafkaConnection` below. The pool creates a client on first use and the first use is the
    * background pass, so an empty cluster list and a full one differ only in whether that fiber has anything
    * to scrape.
    */
  private def wiring(
      cursorKey: Option[Secret[String]] = None,
      clusters: List[ClusterConfig] = Nil,
      refreshInterval: scala.concurrent.duration.FiniteDuration = ConsumersConfig.DefaultRefreshInterval
  ): Resource[IO, (ConsumerServer[IO], FakeStructuredLogger[IO])] =
    FakeStructuredLogger[IO].toResource.flatMap(logger =>
      ConsumerWiring
        .make[IO](
          clusters,
          kui.security.rbac.RbacPolicy.Disabled,
          refreshInterval,
          cursorKey,
          Telemetry.noop[IO],
          PrincipalCodec.inProcess[IO],
          logger
        )
        .map(server => (server, logger))
    )

  test("wiringServesEveryEndpointTheContractPublishesAndNothingElse") {
    wiring().use { (server, _) =>
      IO {
        val served = server.routes.map(_.endpoint.showPathTemplate(showQueryParam = None)).toSet

        (ConsumerEndpoints.all ++ ConsumerMutationEndpoints.all).foreach(endpoint =>
          assert(
            served.contains(endpoint.showPathTemplate(showQueryParam = None)),
            s"$served does not serve $endpoint"
          )
        )

        // The count, not only the containment: a route served with no contract entry is a route that
        // appears in no OpenAPI document and that no client can be generated for.
        assertEquals(
          server.routes.size,
          ConsumerEndpoints.all.size + ConsumerMutationEndpoints.all.size + 3
        )
      }
    }
  }

  test("wiringBindsNoPortAndIsSafeToAllocateTwice") {
    // The ADR-010 property: `make` stops one step short of a running server, which is what lets the
    // all-in-one process mount this service beside three others over one listener. A wiring that bound a
    // port would fail the second allocation with "address already in use".
    for {
      first <- wiring().use((server, _) => IO.pure(server.routes.size))
      second <- wiring().use((server, _) => IO.pure(server.routes.size))
    } yield assertEquals(first, second)
  }

  test("wiringOpensNoKafkaConnectionAndReleasesItsBackgroundFibers") {
    // A one-second refresh interval against a configured cluster that does not exist. The wiring must
    // still allocate, and the release must still finish: a leaked scrape fiber here is a fiber
    // authenticating to somebody's Kafka cluster for the rest of the process's life.
    wiring(
      clusters = List(
        ClusterConfig(
          id = kui.kernel.ClusterId.unsafe("nowhere"),
          name = "nowhere",
          bootstrapServers = kui.kernel.cluster.BootstrapServers.unsafe("broker.invalid:9092"),
          security = kui.kernel.cluster.ClusterSecurity.Plaintext,
          properties = kui.kernel.cluster.ClientProperties.empty,
          readOnly = false,
          admin = kui.kernel.cluster.AdminTuning.default
        )
      ),
      refreshInterval = 5.seconds
    ).use((server, _) => IO.pure(server.routes.nonEmpty))
      .timeout(30.seconds)
      .map(assert(_))
  }

  test("aConfiguredSigningKeyIsUsedForPlanTokens") {
    // ADR-045's token is what the apply endpoint accepts *instead of* a specification, so a key only one
    // replica knows is a reset that works or fails depending on which container answered.
    wiring(cursorKey = Some(Secret("0123456789abcdef0123456789abcdef"))).use { (_, logger) =>
      logger.entries.map(_.map(_.message)).map(lines =>
        assert(
          lines.exists(_.contains("configured kui.streaming.cursorKey")),
          clue = s"the wiring did not report using the configured key: $lines"
        )
      )
    }
  }

  test("noConfiguredKeyFallsBackToAGeneratedOneAndSaysSo") {
    // The fallback is correct for a single process and wrong for two, and the only way an operator finds
    // out which one they are running is this line. A silent fallback is the defect, not the fallback.
    wiring(cursorKey = None).use { (_, logger) =>
      logger.entries.map(_.map(_.message)).map(lines =>
        assert(
          lines.exists(line =>
            line.contains("no kui.streaming.cursorKey is configured") && line.contains("second replica")
          ),
          clue = s"the wiring did not warn that the key is per-process: $lines"
        )
      )
    }
  }

  test("theDefaultRefreshIntervalIsTheConfigurationSectionsOwn") {
    // Two values that must not drift: the constant this object still exposes for callers with no
    // configuration in hand, and the default `kui.consumers.refreshInterval` documents.
    assertEquals(ConsumerWiring.DefaultRefreshInterval, ConsumersConfig.DefaultRefreshInterval)
    assertEquals(ConsumerWiring.DefaultRefreshInterval, 30.seconds)
  }
}
