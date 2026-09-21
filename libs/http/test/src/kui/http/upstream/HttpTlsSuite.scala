package kui.http.upstream

import java.security.KeyStore
import java.util.Base64
import javax.net.ssl.{SSLHandshakeException, TrustManagerFactory, X509KeyManager, X509TrustManager}

import scala.concurrent.duration.DurationInt

import cats.effect.IO
import cats.syntax.all.*
import sttp.client4.{asStringAlways, basicRequest}
import sttp.model.Uri

import kui.config.{HttpKeyStore, HttpStoreFormat, HttpStoreMaterial, HttpTlsConfig, HttpTrustStore}
import kui.kernel.Secret
import kui.testkit.KuiIOSuite

final class HttpTlsSuite extends KuiIOSuite {

  test("default TLS delegates trust and client identity to JVM defaults") {
    HttpTls.prepared[IO](HttpTlsConfig.Default).map { prepared =>
      assert(prepared.usesSystemTrust)
      assertEquals(prepared.trustManagers, Nil)
      assertEquals(prepared.keyManagers, Nil)
      assertEquals(prepared.context, javax.net.ssl.SSLContext.getDefault)
    }
  }

  test("PKCS12 path stores build a custom-trust mTLS context") {
    HttpsUpstreamFixture.resource.use { materials =>
      val config = HttpTlsConfig(
        Some(materials.truststore(HttpStoreFormat.Pkcs12, inline = false)),
        Some(materials.keystore(HttpStoreFormat.Pkcs12, inline = false))
      )

      HttpTls.prepared[IO](config).map(assertCustomMtls) *>
        HttpTls.resource[IO](config).use(backend => IO(assert(backend != null)))
    }
  }

  test("inline JKS stores build a custom-trust mTLS context") {
    HttpsUpstreamFixture.resource.use { materials =>
      val config = HttpTlsConfig(
        Some(materials.truststore(HttpStoreFormat.Jks, inline = true)),
        Some(materials.keystore(HttpStoreFormat.Jks, inline = true))
      )

      HttpTls.prepared[IO](config).map(assertCustomMtls) *>
        HttpTls.resource[IO](config).use(backend => IO(assert(backend != null)))
    }
  }

  test("a custom truststore replaces rather than augments JVM trust") {
    HttpsUpstreamFixture.resource.use { materials =>
      val config = HttpTlsConfig(
        Some(materials.truststore(HttpStoreFormat.Pkcs12, inline = false)),
        None
      )

      HttpTls.prepared[IO](config).map { prepared =>
        val custom = prepared.trustManagers
          .collectFirst { case manager: X509TrustManager => manager }
          .getOrElse(fail("the custom context has no X509 trust manager"))
        val system = systemTrustManager
        val customSubjects = custom.getAcceptedIssuers.map(_.getSubjectX500Principal).toSet
        val systemSubjects = system.getAcceptedIssuers.map(_.getSubjectX500Principal).toSet

        assert(!prepared.usesSystemTrust)
        assertEquals(customSubjects.size, 1)
        assert(customSubjects.forall(_.getName.contains("CN=kui-testkit-ca")), customSubjects.toString)
        assert(systemSubjects.nonEmpty, "the JVM fixture has no system trust anchors")
        assertEquals(customSubjects.intersect(systemSubjects), Set.empty)
      }
    }
  }

  test("the sttp transport owns and deterministically closes its Java client") {
    for {
      client <- HttpTls
        .clientResource[IO](HttpTlsConfig.Default)
        .use(client => IO(assert(!client.isTerminated)) *> IO.pure(client))
      _ <- IO(assert(client.isTerminated, "the Java HTTP client survived its Resource"))
      _ <- HttpTls.resource[IO](HttpTlsConfig.Default).use(backend => IO(assert(backend != null)))
    } yield ()
  }

  test("invalid base64, files, passwords, stores and keys fail with sanitized errors") {
    HttpsUpstreamFixture.resource.use { materials =>
      val missingPath = "/missing/path-canary/private-store.p12"
      val badPassword = "password-canary"
      val badBase64 = "base64-canary!!!!"
      val badStore =
        Base64.getEncoder.encodeToString("store-canary".getBytes(java.nio.charset.StandardCharsets.UTF_8))
      val emptyStore = HttpsUpstreamFixture.emptyStoreBase64(HttpStoreFormat.Pkcs12, materials.password)

      val invalid = List(
        HttpTlsConfig(
          Some(
            HttpTrustStore(
              HttpStoreMaterial.Inline(Secret(badBase64)),
              Secret(materials.password),
              HttpStoreFormat.Pkcs12
            )
          ),
          None
        ) -> List(badBase64),
        HttpTlsConfig(
          Some(
            HttpTrustStore(
              HttpStoreMaterial.Location(missingPath),
              Secret(materials.password),
              HttpStoreFormat.Pkcs12
            )
          ),
          None
        ) -> List(missingPath),
        HttpTlsConfig(
          Some(
            HttpTrustStore(
              HttpStoreMaterial.Location(materials.pkcs12Truststore.toString),
              Secret(badPassword),
              HttpStoreFormat.Pkcs12
            )
          ),
          None
        ) -> List(materials.pkcs12Truststore.toString, badPassword),
        HttpTlsConfig(
          Some(
            HttpTrustStore(
              HttpStoreMaterial.Inline(Secret(badStore)),
              Secret(materials.password),
              HttpStoreFormat.Jks
            )
          ),
          None
        ) -> List(badStore, "store-canary"),
        HttpTlsConfig(
          Some(materials.truststore(HttpStoreFormat.Pkcs12, inline = false)),
          Some(
            materials
              .keystore(HttpStoreFormat.Pkcs12, inline = false)
              .copy(keyPassword = Secret(badPassword))
          )
        ) -> List(materials.pkcs12Keystore.toString, badPassword),
        HttpTlsConfig(
          Some(materials.truststore(HttpStoreFormat.Pkcs12, inline = false)),
          Some(
            HttpKeyStore(
              HttpStoreMaterial.Inline(Secret(emptyStore)),
              Secret(materials.password),
              Secret(materials.password),
              HttpStoreFormat.Pkcs12
            )
          )
        ) -> List(emptyStore, materials.password)
      )

      invalid.traverse_ { case (config, canaries) =>
        HttpTls.prepared[IO](config).attempt.map {
          case Right(_) => fail("invalid TLS material was accepted")
          case Left(error) =>
            val rendered = render(error)
            assert(error.isInstanceOf[HttpTls.InitializationFailure], rendered)
            canaries.foreach(canary => assert(!rendered.contains(canary), rendered))
        }
      }
    }
  }

  test("configured JKS and PKCS12 path trust succeeds while JVM default trust fails") {
    HttpsUpstreamFixture.server(requireClientCertificate = false).use { running =>
      for {
        defaultResult <- get(running.url, HttpTlsConfig.Default)
        configured <- HttpStoreFormat.All.traverse { format =>
          get(
            running.url,
            HttpTlsConfig(Some(running.materials.truststore(format, inline = false)), None)
          ).map(format -> _)
        }
      } yield {
        assert(defaultResult.isLeft, "the generated CA unexpectedly exists in JVM trust")
        configured.foreach { case (format, result) =>
          assertEquals(result, Right("200:ok"), clue = format.wireName)
        }
      }
    }
  }

  test("hostname verification rejects a trusted certificate for the wrong loopback address") {
    HttpsUpstreamFixture.server(requireClientCertificate = false).use { running =>
      val config = HttpTlsConfig(
        Some(running.materials.truststore(HttpStoreFormat.Pkcs12, inline = false)),
        None
      )

      for {
        correct <- get(running.url, config)
        wrong <- get(running.wrongHostnameUrl, config)
      } yield {
        assertEquals(correct, Right("200:ok"))
        assertHandshakeFailure(wrong, "the transport accepted a certificate that does not name 127.0.0.2")
      }
    }
  }

  test("a server requiring client authentication refuses trust-only clients") {
    HttpsUpstreamFixture.server(requireClientCertificate = true).use { running =>
      val trustOnly = HttpTlsConfig(
        Some(running.materials.truststore(HttpStoreFormat.Pkcs12, inline = false)),
        None
      )

      get(running.url, trustOnly).map(result => assert(result.isLeft, "mTLS accepted no client key"))
    }
  }

  test("JKS and PKCS12 path client stores complete mutual TLS") {
    HttpsUpstreamFixture.server(requireClientCertificate = true).use { running =>
      HttpStoreFormat.All.traverse_ { format =>
        val config = HttpTlsConfig(
          Some(running.materials.truststore(format, inline = false)),
          Some(running.materials.keystore(format, inline = false))
        )

        get(running.url, config).map(result => assertEquals(result, Right("200:ok"), clue = format.wireName))
      }
    }
  }

  private def assertCustomMtls(prepared: HttpTls.Prepared): Unit = {
    assert(!prepared.usesSystemTrust)
    val trust = prepared.trustManagers.collectFirst { case manager: X509TrustManager => manager }
    val keys = prepared.keyManagers.collectFirst { case manager: X509KeyManager => manager }
    assert(trust.exists(_.getAcceptedIssuers.nonEmpty), "the context has no custom trust anchor")
    assert(
      keys.exists(manager => Option(manager.getClientAliases("RSA", null)).exists(_.nonEmpty)),
      "the context has no usable client key"
    )
  }

  private def systemTrustManager: X509TrustManager = {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    factory.init(Option.empty[KeyStore].orNull)
    factory.getTrustManagers
      .collectFirst { case manager: X509TrustManager => manager }
      .getOrElse(fail("the JVM has no default X509 trust manager"))
  }

  private def render(error: Throwable): String = {
    val causes =
      Iterator.iterate(Option(error))(_.flatMap(value => Option(value.getCause))).takeWhile(_.nonEmpty)
    causes.flatten.map(value => s"${value.getClass.getName}: ${value.getMessage}").mkString("\n")
  }

  private def assertHandshakeFailure(result: Either[Throwable, String], accepted: String): Unit =
    result match {
      case Right(_) => fail(accepted)
      case Left(error) =>
        val causes = Iterator.iterate(Option(error))(_.flatMap(value => Option(value.getCause)))
        assert(
          causes.takeWhile(_.nonEmpty).flatten.exists(_.isInstanceOf[SSLHandshakeException]),
          render(error)
        )
    }

  private def get(url: String, config: HttpTlsConfig): IO[Either[Throwable, String]] = {
    val target = Uri.parse(url).fold(error => fail(error), identity)
    HttpTls
      .resource[IO](config)
      .use(
        basicRequest
          .get(target)
          .readTimeout(5.seconds)
          .response(asStringAlways)
          .send(_)
          .map(response => s"${response.code.code}:${response.body}")
      )
      .attempt
  }
}
