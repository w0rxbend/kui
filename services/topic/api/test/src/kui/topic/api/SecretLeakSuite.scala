package kui.topic.api

import java.time.Instant

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import sttp.client4.*

import kui.kernel.{BrokerId, PartitionId, TopicName}
import kui.topic.application.{Fresh, TopicCapability}
import kui.topic.domain.*

/** That nothing a topic response touches can carry a credential out of the process.
  *
  * The topic service holds real Kafka credentials: it fetches them over `/internal/v1` from the cluster
  * service and builds admin clients from them. It must never put one on the wire, in a log line, or in a span
  * attribute. This suite says so about every endpoint at once.
  *
  * The sentinel is planted where a secret KUI *holds* really travels: in the value of a sensitive
  * `describeConfigs` key and in that key's synonym chain, which is what a broker returns for a topic with an
  * `ssl.key.password` override. Every endpoint is then called and the string must appear nowhere.
  *
  * The assertion is deliberately blunt — nowhere in any body, any log entry or any span attribute. That
  * bluntness is the point: it keeps holding when somebody later adds a diagnostic field to a response, which
  * is exactly when a targeted assertion would have stopped covering it.
  *
  * ==What this suite does not claim==
  *
  * It does not claim that arbitrary text from a broker is safe. A section's `message` and a capability's
  * `reason` are `KuiError.message` passed through, deliberately, because an operator needs to read why a
  * scrape failed — and a KUI error message that embedded a credential would therefore reach a browser. That
  * boundary is `libs/kafka`'s: it is the module that turns a Kafka exception into a `KuiError`, and it is
  * where the equivalent assertion belongs. Repeating it here would test this suite's own fixture rather than
  * anything this module decides.
  */
final class SecretLeakSuite extends CatsEffectSuite {

  private val Sentinel = "SENTINEL-c0ffee"

  private val at = Instant.parse("2026-09-03T10:11:12Z")
  private val since = Instant.parse("2026-09-03T10:10:00Z")

  private def path(rest: String): String =
    s"/internal/v1/clusters/${TopicTestServer.Cluster.value}/topics$rest"

  private val summary =
    TopicSummary(
      name = TopicName.unsafe("orders"),
      isInternal = false,
      partitionCount = 1,
      replicationFactor = Some(1),
      outOfSyncReplicas = 0,
      offlinePartitions = 0,
      messageCount = Some(1L),
      sizeBytes = Some(1L)
    )

  private val snapshot = TopicSnapshot.of(Vector(summary), at)

  private val partition =
    PartitionView
      .from(
        PartitionId.unsafe(0),
        Some(BrokerId.unsafe(1)),
        replicas = List(BrokerId.unsafe(1)),
        inSync = List(BrokerId.unsafe(1)),
        earliestOffset = Some(0L),
        latestOffset = Some(1L),
        sizeBytes = Some(1L)
      )
      .fold(error => fail(error.message), identity)

  private val detail =
    TopicDetail.of(TopicName.unsafe("orders"), isInternal = false, List(partition), cleanupPolicy = Some("delete"))

  /** A sensitive configuration key whose value and default both hold the sentinel.
    *
    * This is the realistic shape: `describeConfigs` on a topic with a `ssl.key.password` override returns the
    * key, marked sensitive, and — on some brokers — a synonym chain that still carries a value.
    */
  private val config = TopicConfigView.of(
    List(
      TopicConfigEntry(
        name = "ssl.key.password",
        value = Some(Sentinel),
        source = ConfigSource.DynamicTopic,
        isSensitive = true,
        isReadOnly = true,
        documentation = None,
        synonyms = List(ConfigSynonym("ssl.key.password", Some(Sentinel), ConfigSource.Default))
      )
    )
  )

  private val scrapeFailed =
    kui.kernel.error.InfrastructureError.Unreachable("kafka", "connection refused")

  private def request(server: TopicTestServer, requestPath: String, method: String = "GET"): IO[String] =
    TopicTestServer
      .token(requestPath, method = method)
      .flatMap { token =>
        val base =
          if method == "POST" then basicRequest.post(uri"${TopicTestServer.uri(requestPath)}")
          else basicRequest.get(uri"${TopicTestServer.uri(requestPath)}")

        base
          .header(kui.contracts.KuiEndpoint.PrincipalHeader, token.value)
          .send(server.backend)
          .map(_.body.fold(identity, identity))
      }

  test("the sentinel appears in no response body, no log line and no span attribute") {
    TopicTestServer
      .resource(
        // A stale snapshot, so the list, the detail and the settings tab are all exercised in the state
        // where a screen renders the most: data plus a reason.
        TopicTestServer.stale(snapshot, scrapeFailed, since),
        detail = Right(Fresh.FromSnapshot(detail, at, "the describe timed out")),
        config = Right(config),
        capabilities =
          List(TopicTestServer.Cluster -> TopicCapability.Degraded("the scrape timed out", since, Some(at)))
      )
      .use { server =>
        for {
          bodies <- List(
            path(""),
            path("/orders"),
            path("/orders/config"),
            path("/orders/partitions")
          ).traverse(request(server, _))
          refresh <- request(server, path("/refresh"), method = "POST")
          capabilities <- basicRequest
            .get(uri"${TopicTestServer.uri("/capabilities")}")
            .send(server.backend)
            .map(_.body.fold(identity, identity))
          entries <- server.logger.entries
          spans <- server.telemetry.finishedSpans
        } yield {
          val responses = bodies :+ refresh :+ capabilities

          responses.zipWithIndex.foreach((text, index) =>
            assert(!text.contains(Sentinel), s"response $index leaked the sentinel: $text")
          )

          val logged = entries.map(entry => entry.message + entry.context.mkString(",")).mkString("\n")
          assert(!logged.contains(Sentinel), s"a log line leaked the sentinel: $logged")

          val attributes = spans
            .flatMap(span => span.getAttributes.asMap().values().toArray.toList.map(_.toString))
            .mkString("\n")
          assert(!attributes.contains(Sentinel), s"a span attribute leaked the sentinel: $attributes")
        }
      }
  }

  test("a sensitive setting still tells the screen that the key exists") {
    // The point of dropping the value rather than the row: an operator must be able to see that
    // `ssl.key.password` is set without being shown what it is set to.
    TopicTestServer
      .resource(TopicTestServer.online(snapshot), config = Right(config))
      .use { server =>
        request(server, path("/orders/config")).map { text =>
          assert(text.contains("ssl.key.password"), text)
          assert(!text.contains(Sentinel), text)
        }
      }
  }
}
