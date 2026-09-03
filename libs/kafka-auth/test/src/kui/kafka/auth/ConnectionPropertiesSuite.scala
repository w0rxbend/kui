package kui.kafka.auth

import java.io.ByteArrayOutputStream
import java.nio.file.{Files as JFiles, Path as JPath}
import java.security.KeyStore
import java.util.Base64

import cats.effect.{IO, Ref}

import kui.kernel.cluster.*
import kui.kernel.error.ErrorCode
import kui.kernel.{ClusterId, Secret}
import kui.testkit.KuiIOSuite

/** The seam between the three pieces of this module: the classpath check, the materializer and the
  * renderer.
  *
  * Each is tested on its own elsewhere. What is only testable here is the order they run in and the
  * lifetime they share — that a misconfigured deployment fails before writing a private key, and
  * that the path in the returned properties is gone when the properties are.
  */
final class ConnectionPropertiesSuite extends KuiIOSuite {

  private val storePassword = "changeit".toCharArray

  private def storeBase64: String = {
    val store = KeyStore.getInstance("PKCS12")
    store.load(null, storePassword)

    val bytes = new ByteArrayOutputStream()
    store.store(bytes, storePassword)

    Base64.getEncoder.encodeToString(bytes.toByteArray)
  }

  private def connection(security: ClusterSecurity): ClusterConnection = ClusterConnection(
    id = ClusterId.unsafe("prod"),
    bootstrapServers = BootstrapServers.unsafe("broker:9093"),
    security = security,
    overrides = ClientProperties.empty,
    admin = AdminTuning.default
  )

  private val inlineTls: ClusterSecurity = ClusterSecurity.Ssl(
    TlsConfig.default.copy(
      truststore = Some(
        TrustStoreRef(
          StoreSource.Inline(Secret(storeBase64)),
          Some(Secret("changeit")),
          StoreType.Pkcs12
        )
      )
    )
  )

  test("materializedPathsReachTheRenderedProperties") {
    ConnectionProperties
      .resource[IO](connection(inlineTls), ClientPurpose.Admin, "kui-admin-prod-1")
      .use {
        case Left(error) => IO(fail(error.message))
        case Right(properties) =>
          IO {
            val location = properties
              .get("ssl.truststore.location")
              .map(_.unsafeValue)
              .getOrElse(fail("no truststore location was rendered"))

            assert(JFiles.exists(JPath.of(location)), s"$location was named but not written")
            assertEquals(properties.get("ssl.truststore.type").map(_.unsafeValue), Some("PKCS12"))
            assertEquals(properties.get("client.id").map(_.unsafeValue), Some("kui-admin-prod-1"))
          }
      }
  }

  test("propertiesAreInvalidatedWithTheResource") {
    val located = Ref.unsafe[IO, Option[String]](None)

    for {
      _ <- ConnectionProperties
        .resource[IO](connection(inlineTls), ClientPurpose.Admin, "kui-admin-prod-1")
        .use {
          case Left(error) => IO(fail(error.message))
          case Right(properties) =>
            located.set(properties.get("ssl.truststore.location").map(_.unsafeValue))
        }
      location <- located.get
    } yield assert(
      location.exists(path => !JFiles.exists(JPath.of(path))),
      "the truststore outlived the properties that named it"
    )
  }

  test("aMissingCloudHandlerFailsBeforeAnyFileIsWritten") {
    // AWS MSK IAM's login module is not on this module's classpath and never will be — the
    // coordinate is deliberately optional. The check has to run before materialization, so a
    // deployment that got its configuration wrong does not leave a keystore behind.
    val security = ClusterSecurity.Sasl(
      SaslProtocol.SaslSsl,
      SaslMechanism.AwsMskIam(None, None, None),
      Some(
        TlsConfig.default.copy(
          truststore = Some(
            TrustStoreRef(
              StoreSource.Inline(Secret(storeBase64)),
              Some(Secret("changeit")),
              StoreType.Pkcs12
            )
          )
        )
      )
    )

    val temporaryDirectory = JPath.of(System.getProperty("java.io.tmpdir", "/tmp"))

    for {
      before <- IO(JFiles.list(temporaryDirectory).count())
      result <- ConnectionProperties
        .resource[IO](connection(security), ClientPurpose.Admin, "kui-admin-prod-1")
        .use(IO.pure)
      after <- IO(JFiles.list(temporaryDirectory).count())
    } yield {
      assertEquals(result.left.map(_.code), Left(ErrorCode.Unsupported))
      assert(result.left.exists(_.message.contains("aws-msk-iam-auth")), "no coordinate named")
      assertEquals(after, before, "a directory was created despite the classpath check failing")
    }
  }

  test("aPlaintextConnectionNeedsNoFilesystemAtAll") {
    ConnectionProperties
      .resource[IO](connection(ClusterSecurity.Plaintext), ClientPurpose.Consumer, "  ")
      .use {
        case Left(error) => IO(fail(error.message))
        case Right(properties) =>
          IO {
            assertEquals(properties.get("security.protocol").map(_.unsafeValue), Some("PLAINTEXT"))
            assertEquals(properties.get("client.id").map(_.unsafeValue), Some("kui-consumer-prod"))
            assertEquals(properties.keys.count(_.startsWith("ssl.")), 0)
          }
      }
  }

  test("aRenderFailureIsReportedAsAValueAndNamesEveryBadField") {
    // A PEM keystore with no private key: the render fails, and the failure has to arrive as a
    // `KuiError` rather than as a thrown exception, because CFGOP-001 accumulates it with every
    // other configuration problem into one startup message.
    val certificateOnly = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----"

    val security = ClusterSecurity.Ssl(
      TlsConfig.default.copy(
        keystore = Some(
          KeyStoreRef(
            StoreSource.Inline(Secret(Base64.getEncoder.encodeToString(certificateOnly.getBytes))),
            None,
            None,
            StoreType.Pem
          )
        )
      )
    )

    ConnectionProperties
      .resource[IO](connection(security), ClientPurpose.Admin, "kui-admin-prod-1")
      .use(result =>
        IO {
          assert(result.isLeft, "a PEM keystore with no private key was accepted")
          assert(
            result.left.exists(_.details.exists(_.field.contains("ssl.keystore.key"))),
            result.left.map(_.message).toString
          )
        }
      )
  }
}
