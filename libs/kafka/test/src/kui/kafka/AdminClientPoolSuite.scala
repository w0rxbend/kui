package kui.kafka

import java.io.ByteArrayOutputStream
import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.nio.file.{Files as JFiles, Path as JPath}
import java.security.KeyStore
import java.util.Base64

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import org.apache.kafka.clients.admin.{Admin, AdminClientConfig}
import org.apache.kafka.common.errors.{TimeoutException, TopicAuthorizationException}

import kui.kernel.cluster.*
import kui.kernel.{ClusterId, Secret}
import kui.testkit.KuiIOSuite

/** The pool's own behaviour, without a broker.
  *
  * What is under test here is sharing, generations, invalidation and measurement — not whether
  * `Admin.create` works, which is a different question answered against a real broker in KAFKA-007.
  * So the client factory is a parameter and the client is a do-nothing proxy: a fake that can be
  * counted, closed and handed out, which is everything the pool does with one.
  */
final class AdminClientPoolSuite extends KuiIOSuite {

  private val id: ClusterId = ClusterId.unsafe("prod")

  private val plaintext: ClusterConnection = ClusterConnection(
    id = id,
    bootstrapServers = BootstrapServers.unsafe("broker:9092"),
    security = ClusterSecurity.Plaintext,
    overrides = ClientProperties.empty,
    admin = AdminTuning.default
  )

  /** An `Admin` that does nothing. `Admin` has some fifty methods and the pool calls none of them;
    * a reflective proxy is a fake with no maintenance cost rather than fifty stubs.
    */
  private def fakeAdmin(): Admin = {
    val handler: InvocationHandler = (_: Any, method: Method, _: Array[Object]) =>
      if method.getName == "toString" then "FakeAdmin"
      else if method.getName == "hashCode" then Integer.valueOf(0)
      else if method.getName == "equals" then java.lang.Boolean.FALSE
      else null

    Proxy
      .newProxyInstance(classOf[Admin].getClassLoader, Array(classOf[Admin]), handler)
      .asInstanceOf[Admin]
  }

  /** What the factory did, so a test can assert on it. */
  private final case class Tracker(
      created: Ref[IO, Int],
      closed: Ref[IO, Int],
      properties: Ref[IO, List[Map[String, String]]]
  )

  private def tracker: IO[Tracker] =
    (Ref.of[IO, Int](0), Ref.of[IO, Int](0), Ref.of[IO, List[Map[String, String]]](Nil))
      .mapN(Tracker.apply)

  private def factoryOf(t: Tracker): AdminClientPool.Factory[IO] =
    (_, _, rendered) =>
      Resource.make(
        t.properties.update(_ :+ rendered.unsafeValues) >> t.created.update(_ + 1) >> IO(fakeAdmin())
      )(_ => t.closed.update(_ + 1))

  private def poolOf(t: Tracker, metrics: AdminMetrics[IO]): Resource[IO, AdminClientPool[IO]] =
    AdminClientPool.resourceWith[IO](metrics, factoryOf(t))

  private def succeed(client: Admin): IO[String] = IO.pure(s"ok-${client.hashCode}")

  test("oneClientIsCreatedForTenConcurrentCalls") {
    for {
      t <- tracker
      _ <- poolOf(t, AdminMetrics.noop[IO]).use { pool =>
        List.fill(10)(pool.run(plaintext, "describeCluster")(succeed)).parSequence.void
      }
      created <- t.created.get
    } yield assertEquals(created, 1)
  }

  test("theAdminTuningTimeoutsAndTheClientIdReachTheClient") {
    for {
      t <- tracker
      _ <- poolOf(t, AdminMetrics.noop[IO]).use(_.run(plaintext, "describeCluster")(succeed))
      seen <- t.properties.get
    } yield {
      val properties = seen.headOption.getOrElse(fail("no client was created"))

      assertEquals(properties.get(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG), Some("30000"))
      assertEquals(properties.get(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG), Some("60000"))
      assert(
        properties.get(AdminClientConfig.CLIENT_ID_CONFIG).exists(_.startsWith("kui-admin-prod-")),
        properties.toString
      )
      assertEquals(properties.get("bootstrap.servers"), Some("broker:9092"))
    }
  }

  test("anOperatorOverrideBeatsTheAdminTuningDefault") {
    val overridden = plaintext.copy(
      overrides = ClientProperties.fromRaw(Map(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG -> "1234"))
    )

    for {
      t <- tracker
      _ <- poolOf(t, AdminMetrics.noop[IO]).use(_.run(overridden, "describeCluster")(succeed))
      seen <- t.properties.get
    } yield assertEquals(
      seen.headOption.flatMap(_.get(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG)),
      Some("1234")
    )
  }

  test("aReconnectClassFailureReplacesTheClientExactlyOnce") {
    val failing: Admin => IO[String] = _ => IO.raiseError(new TimeoutException("timed out"))

    for {
      t <- tracker
      _ <- poolOf(t, AdminMetrics.noop[IO]).use { pool =>
        for {
          _ <- pool.run(plaintext, "describeCluster")(succeed)
          // Two failures at once, on the same generation. Without the generation check the second
          // would evict the replacement the first had just created — a reconnect storm.
          _ <- List
            .fill(2)(pool.run(plaintext, "describeCluster")(failing).attempt)
            .parSequence
          _ <- pool.run(plaintext, "describeCluster")(succeed)
        } yield ()
      }
      created <- t.created.get
      closed <- t.closed.get
    } yield {
      assertEquals(created, 2, "the client was rebuilt more than once for one dead connection")
      assertEquals(closed, 2, "not every client was closed")
    }
  }

  test("aRequestLevelFailureDoesNotReplaceTheClient") {
    // The regression guard against Kafbat's "invalidate on any error": asking about a topic you are
    // not authorized for must not cost a reconnect and a fresh SASL handshake.
    val refused: Admin => IO[String] = _ => IO.raiseError(new TopicAuthorizationException("no"))

    for {
      t <- tracker
      _ <- poolOf(t, AdminMetrics.noop[IO]).use { pool =>
        pool.run(plaintext, "describeConfigs")(succeed) >>
          pool.run(plaintext, "describeConfigs")(refused).attempt >>
          pool.run(plaintext, "describeConfigs")(succeed)
      }
      created <- t.created.get
    } yield assertEquals(created, 1)
  }

  test("theFailingCallStillFails") {
    // Invalidation does not retry: the caller sees one failure with its original error, and the
    // next call gets a fresh client. Retrying inside the pool would double every timeout and hide
    // the failure from the metric.
    val boom = new TimeoutException("timed out")

    for {
      t <- tracker
      result <- poolOf(t, AdminMetrics.noop[IO])
        .use(_.run(plaintext, "describeCluster")(_ => IO.raiseError[String](boom)))
        .attempt
    } yield assertEquals(result.left.toOption.map(_.getMessage), Some("timed out"))
  }

  test("invalidationRunsTheClientsFinalizer") {
    // The materialized keystore has to go with the client that used it: it is a private key on
    // disk, and the client that named it no longer exists.
    val store = {
      val keystore = KeyStore.getInstance("PKCS12")
      keystore.load(null, "changeit".toCharArray)

      val bytes = new ByteArrayOutputStream()
      keystore.store(bytes, "changeit".toCharArray)
      Base64.getEncoder.encodeToString(bytes.toByteArray)
    }

    val withKeystore = plaintext.copy(
      security = ClusterSecurity.Ssl(
        TlsConfig.default.copy(
          truststore = Some(
            TrustStoreRef(StoreSource.Inline(Secret(store)), Some(Secret("changeit")), StoreType.Pkcs12)
          )
        )
      )
    )

    for {
      t <- tracker
      location <- poolOf(t, AdminMetrics.noop[IO]).use { pool =>
        for {
          _ <- pool.run(withKeystore, "describeCluster")(succeed)
          seen <- t.properties.get
          path = seen.headOption
            .flatMap(_.get("ssl.truststore.location"))
            .getOrElse(fail("no truststore was materialized"))
          existedBefore <- IO(JFiles.exists(JPath.of(path)))
          _ <- IO(assert(existedBefore, s"$path was never written"))
          _ <- pool.invalidate(id)
        } yield path
      }
      closed <- t.closed.get
    } yield {
      assertEquals(closed, 1)
      assert(!JFiles.exists(JPath.of(location)), s"$location outlived the client that used it")
    }
  }

  test("evictClosesAndForgets") {
    for {
      t <- tracker
      _ <- poolOf(t, AdminMetrics.noop[IO]).use { pool =>
        pool.run(plaintext, "describeCluster")(succeed) >>
          pool.evict(id) >>
          pool.run(plaintext, "describeCluster")(succeed)
      }
      created <- t.created.get
      closed <- t.closed.get
    } yield {
      assertEquals(created, 2)
      assertEquals(closed, 2)
    }
  }

  test("aPropertyRenderingFailureCachesNothing") {
    // AWS MSK IAM's login module is deliberately not on KUI's classpath. A cluster configured for
    // it must fail every call with the actionable error and must not occupy a pool slot.
    val misconfigured = plaintext.copy(
      security = ClusterSecurity.Sasl(
        SaslProtocol.SaslSsl,
        SaslMechanism.AwsMskIam(None, None, None),
        None
      )
    )

    for {
      t <- tracker
      result <- poolOf(t, AdminMetrics.noop[IO])
        .use(_.run(misconfigured, "describeCluster")(succeed))
        .attempt
      created <- t.created.get
    } yield {
      assertEquals(created, 0)
      assert(
        result.left.toOption.exists {
          case KafkaClientConfigurationFailure(_) => true
          case _ => false
        },
        result.toString
      )
      assert(result.left.exists(_.getMessage.contains("aws-msk-iam-auth")), result.toString)
    }
  }

  test("everyRunIsMeasured") {
    for {
      t <- tracker
      metrics <- FakeAdminMetrics.create[IO]
      _ <- poolOf(t, metrics).use { pool =>
        pool.run(plaintext, "describeCluster")(succeed) >>
          pool
            .run(plaintext, "describeLogDirs")(_ => IO.raiseError[String](new TimeoutException("x")))
            .attempt
      }
      entries <- metrics.entries
    } yield assertEquals(
      entries,
      List(
        FakeAdminMetrics.Entry(id, "describeCluster", succeeded = true),
        FakeAdminMetrics.Entry(id, "describeLogDirs", succeeded = false)
      )
    )
  }

  test("closeIsCalledOnResourceReleaseForEveryCluster") {
    val staging = plaintext.copy(id = ClusterId.unsafe("staging"))

    for {
      t <- tracker
      _ <- poolOf(t, AdminMetrics.noop[IO]).use { pool =>
        pool.run(plaintext, "describeCluster")(succeed) >>
          pool.run(staging, "describeCluster")(succeed)
      }
      created <- t.created.get
      closed <- t.closed.get
    } yield {
      assertEquals(created, 2)
      assertEquals(closed, 2)
    }
  }

  test("aCancelledPoolStillClosesEveryClientItOpened") {
    // The `Resource` release runs on the cancellation path too. Without it, a cancelled startup
    // leaves a Kafka network thread alive for the life of the process.
    for {
      t <- tracker
      started <- Ref.of[IO, Boolean](false)
      fiber <- poolOf(t, AdminMetrics.noop[IO])
        .use(pool =>
          pool.run(plaintext, "describeCluster")(succeed) >> started.set(true) >> IO.never[Unit]
        )
        .start
      _ <- started.get.iterateUntil(identity)
      _ <- fiber.cancel
      closed <- t.closed.get
    } yield assertEquals(closed, 1)
  }
}
