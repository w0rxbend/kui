package kui.connect.app

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.duration.DurationInt

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.kernel.Resource
import cats.syntax.all.*
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import io.circe.Json
import io.circe.parser.parse
import sttp.client4.*
import sttp.client4.impl.cats.implicits.*
import sttp.client4.testing.BackendStub
import sttp.model.Uri
import sttp.tapir.server.stub4.TapirStubInterpreter

import kui.config.*
import kui.connect.api.ConnectApi
import kui.connect.infrastructure.{ConfiguredConnectSource, ConnectCredentials, ConnectHttp}
import kui.contracts.KuiEndpoint
import kui.kernel.cluster.{AdminTuning, BootstrapServers, ClientProperties, ClusterSecurity}
import kui.kernel.{ClusterId, ConnectName, Secret, UserName}
import kui.observability.Telemetry
import kui.security.*
import kui.security.rbac.RbacPolicy
import kui.testkit.KuiIOSuite
import kui.testkit.fakes.FakeStructuredLogger

/** What the composition root builds, asserted with as little socket as each rule needs.
  *
  * ==Two halves, and why both exist==
  *
  * Four facts decide where a request goes and they were unassertable while they lived inside a `Resource`:
  * which addresses the worker's client fails over between, and which address the OAuth token request is sent
  * to. `SchemaWiring` made both `private[app]` for exactly this reason after the alternative — a suite that
  * starts a server and watches which port is dialled — turned out to be no case at all.
  *
  * Three more could not be reached that way, because they are decided by `make` itself and by nothing it
  * exposes: the `(ClusterId, ConnectName)` key the worker map is built under, the `RbacGuard` read-only
  * predicate built from this process's own `kui.clusters[]`, and the audit sink threaded into
  * `MutationGuard`. **Nothing in this repository constructed `ConnectWiring.make` at all**, so all three were
  * passed into something and never observed. The cases at the end of this file build it against a Connect
  * worker on a loopback port — a real one, because `make` opens a real connection pool and refuses a private
  * address unless the policy allows it — and drive its own routes through Tapir's stub interpreter, which is
  * what a browser reaches.
  */
final class ConnectWiringSuite extends KuiIOSuite {

  private val cluster: ClusterId = ClusterId.unsafe("prod-eu")
  private val frozen: ClusterId = ClusterId.unsafe("prod-us")
  private val payments: ConnectName = ConnectName.unsafe("payments")
  private val analytics: ConnectName = ConnectName.unsafe("analytics")

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

  private def clusterConfig(
      connect: List[ConnectClusterSettings],
      id: ClusterId = cluster,
      readOnly: Boolean = false
  ): ClusterConfig =
    ClusterConfig(
      id = id,
      name = "Production EU",
      bootstrapServers = BootstrapServers.unsafe("broker:9092"),
      security = ClusterSecurity.Plaintext,
      properties = ClientProperties.empty,
      readOnly = readOnly,
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

  // -----------------------------------------------------------------------------------------------
  // `ConnectWiring.make` itself, which nothing in this repository constructed
  // -----------------------------------------------------------------------------------------------

  /** A Connect worker on a loopback port that answers one connector and remembers the last path it served.
    *
    * A real socket rather than a stub backend, and not for realism's sake: `make` builds its own
    * `HttpClientFs2Backend` and there is no seam to hand a stub through. That is also what makes the
    * `UrlPolicy` parameter load-bearing here — a loopback address is private, so `UrlPolicy.Dev` is what the
    * operator's `KUI_ALLOW_PRIVATE_UPSTREAMS` would have set.
    */
  final private class Worker(val port: Int, val lastPath: AtomicReference[String], val name: String) {
    def url: SafeUrl = SafeUrl.unsafe(s"http://127.0.0.1:$port")
  }

  private def worker(name: String): Resource[IO, Worker] =
    Resource
      .make(
        IO.blocking {
          val seen = new AtomicReference[String]("")
          val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
          server.setExecutor(Executors.newCachedThreadPool())
          server.createContext(
            "/",
            (exchange: HttpExchange) => {
              seen.set(exchange.getRequestURI.getPath)
              // One connector, named after the worker, so a document proves *which* address answered.
              val body =
                s"""{"$name-sink":{"status":{"connector":{"state":"RUNNING"},"tasks":[]}}}"""
                  .getBytes(StandardCharsets.UTF_8)
              exchange.getResponseHeaders.add("Content-Type", "application/json")
              exchange.sendResponseHeaders(200, body.length.toLong)
              exchange.getResponseBody.write(body)
              exchange.close()
            }
          )
          server.start()
          (server, new Worker(server.getAddress.getPort, seen, name))
        }
      )((server, _) => IO.blocking(server.stop(0)))
      .map((_, handle) => handle)

  private def connectSettings(name: ConnectName, at: Worker): ConnectClusterSettings =
    ConnectClusterSettings(
      name = name,
      urls = NonEmptyList.one(at.url),
      auth = UpstreamAuthConfig.Anonymous,
      callTimeout = 5.seconds
    )

  /** Thirty-two bytes, which is the shortest key HS256 accepts. */
  private val key: SigningKey =
    SigningKey("test-1", Secret(Array.fill[Byte](32)(7)), Instant.parse("2020-01-01T00:00:00Z"))

  private val codec: PrincipalCodec[IO] =
    JwsPrincipalCodec
      .make[IO](NonEmptyList.of(key), "kui-gateway")
      .getOrElse(throw new IllegalStateException("the test signing key is too short for HS256"))

  private def wiring(
      clusters: List[ClusterConfig],
      logger: FakeStructuredLogger[IO],
      rbac: RbacPolicy = RbacPolicy.Disabled
  ): Resource[IO, ConnectServer[IO]] =
    ConnectWiring.make[IO](clusters, UrlPolicy.Dev, rbac, Telemetry.noop[IO], codec, logger)

  private def ask(server: ConnectServer[IO], method: String, path: String): IO[Response[String]] = {
    val backend = TapirStubInterpreter(server.interceptors, BackendStub[IO](summon))
      .whenServerEndpointsRunLogic(server.routes)
      .backend()

    IO.realTimeInstant
      .flatMap(now =>
        codec.sign(
          PrincipalClaims(
            subject = UserName.unsafe("alice"),
            roles = Set.empty,
            kind = PrincipalKind.Session,
            sessionRef = None,
            issuedAt = now,
            expiresAt = now.plusSeconds(60L),
            audience = ConnectApi.Id,
            requestDigest = RequestDigest.ofRequestLine(method, path)
          )
        )
      )
      .flatMap { signed =>
        val request = basicRequest
          .header(KuiEndpoint.PrincipalHeader, signed.value)
          .header(kui.contracts.HttpHeaders.Csrf, "test-csrf")
          .response(asStringAlways)
        val address = Uri.unsafeParse(s"http://connect$path")

        if method == "POST" then request.post(address).send(backend) else request.get(address).send(backend)
      }
  }

  private def document(response: Response[String]): Json =
    parse(response.body).fold(failure => fail(s"not JSON: ${failure.message} in ${response.body}"), identity)

  private def connectorsPath(id: ClusterId): String =
    s"/internal/v1/clusters/${id.value}/connect/connectors"

  private def pausePath(id: ClusterId, connect: String, connector: String): String =
    s"/internal/v1/clusters/${id.value}/connect/$connect/connectors/$connector/pause"

  test("a wiring built by ConnectWiring.make reads each Connect cluster at its own address") {
    // **The worker map's key.** `workersFor` builds it under `(cluster.id, settings.name)`, and nothing
    // observed either half: keyed by the Connect cluster's name alone, a deployment with two Kafka clusters
    // both naming their Connect cluster `payments` would read one worker's connectors and draw them on the
    // other cluster's screen. Two workers on two ports, two names, and the document says which answered.
    (worker("payments"), worker("analytics")).tupled.use { (first, second) =>
      for {
        log <- FakeStructuredLogger[IO]
        body <- wiring(
          List(
            clusterConfig(List(connectSettings(payments, first), connectSettings(analytics, second)))
          ),
          log
        ).use(server => ask(server, "GET", connectorsPath(cluster)).map(document))
      } yield {
        val workers = body.hcursor.downField("connectors").downField("data").downField("workers")
        val named = workers.as[List[Json]].getOrElse(Nil).flatMap(_.hcursor.get[String]("connect").toOption)
        val connectors = workers
          .as[List[Json]]
          .getOrElse(Nil)
          .flatMap(
            _.hcursor
              .downField("connectors")
              .downField("data")
              .downField("items")
              .as[List[Json]]
              .getOrElse(Nil)
              .flatMap(_.hcursor.get[String]("name").toOption)
          )

        assertEquals(named, List("payments", "analytics"), clue = body.noSpaces)
        assertEquals(connectors, List("payments-sink", "analytics-sink"), clue = body.noSpaces)
        assertEquals(first.lastPath.get(), "/connectors")
        assertEquals(second.lastPath.get(), "/connectors")
      }
    }
  }

  test("a wiring built by ConnectWiring.make refuses a pause on a cluster its own config calls read-only") {
    // **The `RbacGuard` policy.** `make` builds it with
    // `clusters.find(_.id == cluster).exists(_.readOnly)`, read from *this process's* `kui.clusters[]` —
    // so the refusal holds whether or not the gateway was asked. A predicate that answered `false` here
    // would leave a read-only cluster's Restart button working, which is not read-only in any sense an
    // operator means (ADR-054 §3). The writable cluster beside it is what makes this a gate rather than a
    // service that refuses everything.
    (worker("payments"), worker("payments")).tupled.use { (open, closed) =>
      for {
        log <- FakeStructuredLogger[IO]
        answers <- wiring(
          List(
            clusterConfig(List(connectSettings(payments, open))),
            clusterConfig(List(connectSettings(payments, closed)), id = frozen, readOnly = true)
          ),
          log,
          rbac = RbacPolicy.Disabled
        ).use(server =>
          (
            ask(server, "POST", pausePath(cluster, "payments", "orders-sink")),
            ask(server, "POST", pausePath(frozen, "payments", "orders-sink"))
          ).tupled
        )
        entries <- log.entries
      } yield {
        val (writable, readOnly) = answers
        // The refusal's own sentence is `RbacGuard`'s generic one — ADR-034 does not publish which rule
        // refused — so *which* cluster and *which* reason is read off the WARN it writes beside it. That is
        // also the only place the flag this case is about is named.
        val refusals = entries.filter(_.context.get("cluster").contains(frozen.value))

        assertEquals(writable.code.code, 200, clue = writable.body)
        assertEquals(readOnly.code.code, 403, clue = readOnly.body)
        assertEquals(refusals.size, 1, clue = entries.map(_.context).mkString("\n"))
        assert(clue(refusals.head.context.getOrElse("reason", "")).contains("read-only"))
      }
    }
  }

  test("a wiring built by ConnectWiring.make audits the operation it accepted") {
    // **The audit sink.** `LoggingConnectorOperationSink.make` is threaded into `MutationGuard` by `make`
    // and by nothing a case could see: every other suite in this service builds the guard over
    // `ConnectorOperationSink.noop`. Under a wiring that passed the noop sink, every pause, resume and
    // restart this product performs would leave no trace in the one trail an operator queries, with
    // every case in this service green.
    //
    // Asserted on `LoggingAuditSink.Field`'s own key names, which is what makes the line findable beside
    // every other mutation's rather than being a second audit trail (ADR-051 §5).
    worker("payments").use { serving =>
      for {
        log <- FakeStructuredLogger[IO]
        _ <- wiring(List(clusterConfig(List(connectSettings(payments, serving)))), log)
          .use(server => ask(server, "POST", pausePath(cluster, "payments", "orders-sink")))
        entries <- log.entries
      } yield {
        val audited = entries.filter(_.context.get("audit.operation").contains("connect.connector.pause"))

        assertEquals(audited.size, 1, clue = entries.map(_.context).mkString("\n"))
        assertEquals(audited.head.context.get("audit.cluster"), Some(cluster.value))
        assertEquals(audited.head.context.get("audit.resource"), Some("payments/orders-sink"))
        assertEquals(audited.head.context.get("audit.principal"), Some("alice"))
        assertEquals(audited.head.context.get("audit.outcome"), Some("succeeded"))
      }
    }
  }
}
