package kui.connect.app

import scala.concurrent.duration.DurationInt

import cats.data.NonEmptyList
import cats.effect.IO

import kui.config.*
import kui.connect.infrastructure.{ConfiguredConnectSource, ConnectCredentials, ConnectHttp}
import kui.kernel.cluster.{AdminTuning, BootstrapServers, ClientProperties, ClusterSecurity}
import kui.kernel.{ClusterId, ConnectName, Secret}
import kui.testkit.KuiIOSuite
import kui.testkit.fakes.FakeStructuredLogger

/** What the composition root builds, asserted without building a socket.
  *
  * Two facts here decide where a request goes, and both were unassertable while they lived inside a
  * `Resource`: which addresses the worker's client fails over between, and which address the OAuth token
  * request is sent to. `SchemaWiring` made both `private[app]` for exactly this reason after the alternative
  * — a suite that starts a server and watches which port is dialled — turned out to be no case at all.
  */
final class ConnectWiringSuite extends KuiIOSuite {

  private val cluster: ClusterId = ClusterId.unsafe("prod-eu")
  private val payments: ConnectName = ConnectName.unsafe("payments")

  private def settings(
      auth: UpstreamAuthConfig = UpstreamAuthConfig.Anonymous,
      urls: List[String] = List("http://connect-a:8083", "http://connect-b:8083")
  ): ConnectClusterSettings =
    ConnectClusterSettings(
      name = payments,
      urls = NonEmptyList.fromListUnsafe(urls.map(SafeUrl.unsafe)),
      auth = auth,
      callTimeout = 7.seconds
    )

  private def clusterConfig(connect: List[ConnectClusterSettings]): ClusterConfig =
    ClusterConfig(
      id = cluster,
      name = "Production EU",
      bootstrapServers = BootstrapServers.unsafe("broker:9092"),
      security = ClusterSecurity.Plaintext,
      properties = ClientProperties.empty,
      readOnly = false,
      admin = AdminTuning.default,
      connect = connect
    )

  test("a worker's client fails over between the addresses the operator configured, and no others") {
    val config = ConnectWiring.upstreamConfig(cluster, settings(), UrlPolicy.Strict)

    assertEquals(config.urls.toList.map(_.value), List("http://connect-a:8083", "http://connect-b:8083"))
    assertEquals(config.callTimeout, 7.seconds)
    assertEquals(config.maxConcurrent, ConnectWiring.MaxConcurrentPerWorker)
    assertEquals(config.maxRetries, ConnectWiring.MaxRetries)
  }

  test("the bulkhead in front of one Connect cluster is eight requests wide, and the retries are two") {
    // The literals, not the constants, and the difference is the whole point of this case. Asserting
    // `config.maxConcurrent == ConnectWiring.MaxConcurrentPerWorker` compares the value with itself:
    // measured here, widening the bulkhead from 8 to 512 left all 128 of this service's cases green, and
    // the only bound the connector list keeps was therefore gated by nothing.
    //
    // Eight is a real number rather than a round one. A Connect worker serves its REST API from the same
    // JVM that runs the connector tasks and its herder serialises anything touching the configuration, so
    // a burst of KUI requests is felt by the data plane; eight is more than a screen can generate and few
    // enough that KUI cannot be why a connector falls behind. Two retries is `RetryPolicy`'s bound on the
    // reads — the writes are never retried, whatever this says, because a `PUT` and a `POST` are not in
    // `RetryPolicy.IdempotentMethods`.
    assertEquals(ConnectWiring.MaxConcurrentPerWorker.value, 8)
    assertEquals(ConnectWiring.MaxRetries, 2)
  }

  test("the upstream is named after both the Connect cluster and the Kafka cluster") {
    // One Kafka cluster routinely has two Connect clusters — that is what `ConnectClusterSettings` exists
    // for — so a dashboard that said "kafka-connect is failing" would not say which.
    assertEquals(
      ConnectWiring.upstreamConfig(cluster, settings(), UrlPolicy.Strict).name,
      s"${ConnectHttp.upstreamName(payments)}.${cluster.value}"
    )
  }

  test("a 409 is not retried inside the call, because the screen is built to show a rebalance") {
    // `RetryPolicy.shouldRetry` only repeats a *response* whose status the caller named as retryable.
    // Naming 409 here would hide, for as long as the retries last, the one state this service reports
    // specially — and a rebalance routinely outlives two backoffs. `ErrorCode.ConnectRebalancing` is
    // retryable, which tells the caller to ask again; the screen polls.
    assertEquals(
      ConnectWiring.upstreamConfig(cluster, settings(), UrlPolicy.Strict).retryableStatuses,
      Set.empty[Int]
    )
  }

  test("the OAuth token request goes to the issuer and to nothing else") {
    // The rule: that one client fails over between the *Connect cluster's* addresses, so a token request
    // routed to a worker because the issuer was briefly slow would be a client secret sent to the wrong
    // system. Aimed at `settings.urls` instead, every case in this service stays green.
    val issuer = SafeUrl.unsafe("https://issuer.example/oauth/token")
    val config = ConnectWiring.tokenUpstreamConfig(cluster, settings(), issuer, UrlPolicy.Strict)

    assertEquals(config.urls.toList.map(_.value), List("https://issuer.example/oauth/token"))
    assertEquals(config.maxRetries, 1)
    assert(clue(config.name).startsWith(ConnectCredentials.TokenUpstreamName))
  }

  test("the address policy reaches both clients, so a private address is refused or allowed once") {
    assertEquals(ConnectWiring.upstreamConfig(cluster, settings(), UrlPolicy.Dev).urlPolicy, UrlPolicy.Dev)
    assertEquals(
      ConnectWiring
        .tokenUpstreamConfig(cluster, settings(), SafeUrl.unsafe("https://issuer.example/t"), UrlPolicy.Dev)
        .urlPolicy,
      UrlPolicy.Dev
    )
  }

  test("a deployment with no Connect cluster says so at startup rather than being silently idle") {
    // The line an operator who expected a Kafka Connect row reads to learn that KUI is behaving as
    // configured rather than failing.
    for {
      logger <- FakeStructuredLogger[IO]
      _ <- ConnectWiring.startupLog[IO](List(clusterConfig(Nil)), logger)
      entries <- logger.entries
    } yield {
      assertEquals(entries.size, 1)
      assert(clue(entries.head.message).contains("kui.clusters.<n>.connect[].url"))
    }
  }

  test("each configured Connect cluster is logged with its addresses and its mechanism, never its secret") {
    val basic = UpstreamAuthConfig.Basic("kui", Secret("hunter2"))

    for {
      logger <- FakeStructuredLogger[IO]
      _ <- ConnectWiring.startupLog[IO](List(clusterConfig(List(settings(auth = basic)))), logger)
      entries <- logger.entries
    } yield {
      assertEquals(entries.size, 1)
      assertEquals(entries.head.context.get("connect.name"), Some("payments"))
      assertEquals(
        entries.head.context.get("connect.urls"),
        Some("http://connect-a:8083,http://connect-b:8083")
      )
      assertEquals(entries.head.context.get("connect.auth"), Some("basic (user 'kui')"))
      assert(!entries.mkString.contains("hunter2"), clue = "a password reached the log")
    }
  }

  test("the profiles a configuration produces carry the Connect cluster names in configuration order") {
    val configured = ConfiguredConnectSource.profilesOf(
      List(clusterConfig(List(settings(), settings().copy(name = ConnectName.unsafe("analytics")))))
    )

    assertEquals(configured.map(_.cluster), List(cluster))
    assertEquals(configured.head.connects.map(_.value), List("payments", "analytics"))
    assert(configured.head.configured)
  }

  test("a cluster with no Connect block is configured = false, which is what hides the row") {
    val bare = ConfiguredConnectSource.profilesOf(List(clusterConfig(Nil)))

    assert(!bare.head.configured)
    assertEquals(bare.head.connects, Nil)
  }
}
