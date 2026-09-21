package kui.ksql.app

import scala.concurrent.duration.DurationInt

import cats.data.NonEmptyList
import cats.effect.IO
import munit.CatsEffectSuite

import kui.config.{ClusterConfig, KsqlSettings, SafeUrl, UpstreamAuthConfig, UrlPolicy}
import kui.kernel.cluster.{AdminTuning, BootstrapServers, ClientProperties, ClusterSecurity}
import kui.kernel.{ClusterId, Secret}
import kui.testkit.fakes.FakeStructuredLogger

/** The composition root's decisions, as values.
  *
  * `upstreamConfig`, `tokenUpstreamConfig`, `signingKey` and `startupLog` are `private[app]` for the reason
  * `SchemaWiring`'s seam is: reaching them through `make` would need a ksqlDB to dial, so the alternative to
  * these cases is nothing at all — which is what "the token endpoint gets its own upstream" had before the
  * keyword was added.
  */
final class KsqlWiringSuite extends CatsEffectSuite {

  private val cluster = ClusterId.unsafe("prod-eu")

  private val settings = KsqlSettings(
    urls = NonEmptyList.of(SafeUrl.unsafe("http://ksqldb-1:8088"), SafeUrl.unsafe("http://ksqldb-2:8088")),
    auth = UpstreamAuthConfig.Anonymous,
    callTimeout = 7.seconds,
    streamTimeout = 11.minutes
  )

  private def configOf(ksql: Option[KsqlSettings]): ClusterConfig =
    ClusterConfig(
      id = cluster,
      name = "Production EU",
      bootstrapServers = BootstrapServers.unsafe("broker:9092"),
      security = ClusterSecurity.Plaintext,
      properties = ClientProperties.empty,
      readOnly = false,
      admin = AdminTuning.default,
      ksql = ksql
    )

  test("the calls upstream is aimed at the ksqlDB's own addresses, with the call budget") {
    // `callTimeout` and **not** `streamTimeout`: this is the budget for the requests that finish. Aiming
    // it at the stream timeout would give an object listing eleven minutes to answer, which is a screen
    // that hangs rather than one that says the server is slow.
    val config = KsqlWiring.upstreamConfig(cluster, settings, UrlPolicy.Strict)

    assertEquals(config.urls, settings.urls)
    assertEquals(config.callTimeout, 7.seconds)
    assertNotEquals(config.callTimeout, settings.streamTimeout)
    assertEquals(config.maxConcurrent, KsqlWiring.MaxConcurrentPerServer)
    // Named per cluster, because a dashboard that said "ksqldb is failing" would not say whose.
    assert(clue(config.name).endsWith(cluster.value))
  }

  test("the token endpoint's upstream is aimed at the issuer and at nothing else") {
    // The rule this seam exists for: aimed at `settings.urls` instead, every case in this service stays
    // green and KUI posts a client secret to a ksqlDB server.
    val issuer = SafeUrl.unsafe("https://issuer.example/token")
    val config = KsqlWiring.tokenUpstreamConfig(cluster, settings, issuer, UrlPolicy.Strict)

    assertEquals(config.urls, NonEmptyList.one(issuer))
    assertEquals(config.urls.toList.intersect(settings.urls.toList), Nil)
    assert(clue(config.name).startsWith(kui.ksql.infrastructure.KsqlCredentials.TokenUpstreamName))
    // A token request is a POST and a duplicate one costs an extra token rather than an extra side effect.
    assertEquals(config.maxRetries, 1)
  }

  test("a configured cursor key is used, and the line says so") {
    FakeStructuredLogger[IO].flatMap { logger =>
      KsqlWiring.signingKey[IO](Some(Secret("a-configured-secret")), logger).flatMap { key =>
        logger.entries.map { entries =>
          assertEquals(new String(key.value, "UTF-8"), "a-configured-secret")
          assert(clue(entries.map(_.message)).exists(_.contains("configured kui.streaming.cursorKey")))
        }
      }
    }
  }

  test("an unconfigured cursor key is generated and the consequence is logged rather than hidden") {
    // Its consequence is invisible until a second replica exists: a plan minted by one process is refused
    // by the other, and the operator sees a confirmation that will not confirm. A silent fallback is how
    // that becomes a bug report about ksqlDB.
    FakeStructuredLogger[IO].flatMap { logger =>
      KsqlWiring.signingKey[IO](None, logger).flatMap { key =>
        logger.entries.map { entries =>
          assertEquals(key.value.length, 32)
          val line = entries.map(_.message).mkString(" ")
          assert(clue(line).contains("second replica"))
          assert(clue(line).contains("restart"))
        }
      }
    }
  }

  test("two generated keys differ, so the fallback is a key and not a constant") {
    FakeStructuredLogger[IO].flatMap(logger =>
      for {
        first <- KsqlWiring.signingKey[IO](None, logger)
        second <- KsqlWiring.signingKey[IO](None, logger)
      } yield assert(!first.value.sameElements(second.value))
    )
  }

  test("a deployment with a ksqlDB logs its address, its mechanism and its stream budget") {
    // "Which ksqlDB is this reading?" is the first question asked when the screen shows something
    // unexpected, and after the fact it is unanswerable unless the process said so at startup.
    FakeStructuredLogger[IO].flatMap { logger =>
      KsqlWiring.startupLog[IO](List(configOf(Some(settings))), logger) >> logger.entries.map { entries =>
        assertEquals(entries.size, 1)
        val context = entries.head.context

        assertEquals(context.get("cluster.id"), Some(cluster.value))
        assertEquals(context.get("ksql.urls"), Some("http://ksqldb-1:8088,http://ksqldb-2:8088"))
        assertEquals(context.get("ksql.auth"), Some(UpstreamAuthConfig.Anonymous.describe))
        assertEquals(context.get("ksql.streamTimeout"), Some(11.minutes.toString))
      }
    }
  }

  test("a deployment with no ksqlDB says so, because silence reads as a failure") {
    // It is what tells an operator who expected a ksqlDB row that KUI is behaving as configured rather
    // than failing.
    FakeStructuredLogger[IO].flatMap { logger =>
      KsqlWiring.startupLog[IO](List(configOf(None)), logger) >> logger.entries.map { entries =>
        assertEquals(entries.size, 1)
        assert(clue(entries.head.message).contains("kui.clusters.<n>.ksql.url"))
        assert(clue(entries.head.message).contains("no ksqlDB client is opened"))
      }
    }
  }

  test("a startup line never carries a credential") {
    val secret = Secret("hunter2")
    val secured = settings.copy(auth = UpstreamAuthConfig.Basic("kui", secret))

    FakeStructuredLogger[IO].flatMap { logger =>
      KsqlWiring.startupLog[IO](List(configOf(Some(secured))), logger) >> logger.entries.map { entries =>
        val rendered = entries.map(entry => entry.message + entry.context.mkString).mkString(" ")

        assert(!clue(rendered).contains("hunter2"))
        // The mechanism is named without its secret, which is what `describe` is for.
        assert(clue(rendered).contains(UpstreamAuthConfig.Basic("kui", secret).describe))
      }
    }
  }

  test("the retry count is stated, and no ksqlDB request is retried whatever it says") {
    // `RetryPolicy.IdempotentMethods` is GET, HEAD and OPTIONS, and every ksqlDB request is a POST. The
    // number exists because `UpstreamConfig` requires one; the behaviour is what this comment and this
    // case pin, because a repeated `DROP ... DELETE TOPIC` is a second deletion.
    assertEquals(KsqlWiring.MaxRetries, 2)
    assertEquals(KsqlWiring.upstreamConfig(cluster, settings, UrlPolicy.Strict).maxRetries, 2)
  }

  test("the instrumentation scope is the one the audit line and the metrics share") {
    assertEquals(KsqlWiring.Instrumentation, "kui.ksql")
  }
}
