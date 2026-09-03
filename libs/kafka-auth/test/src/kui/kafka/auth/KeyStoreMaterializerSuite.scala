package kui.kafka.auth

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.{Files as JFiles, Path as JPath}
import java.security.KeyStore
import java.util.Base64

import scala.jdk.CollectionConverters.*

import cats.effect.{IO, Ref}
import fs2.io.file.Path

import kui.kafka.auth.ClientPropertyRenderer.StoreRole
import kui.kernel.cluster.*
import kui.kernel.{ClusterId, Secret}
import kui.testkit.KuiIOSuite

/** That a keystore pasted into configuration becomes a real, openable, private file — and stops
  * existing the moment the client that needed it does.
  */
final class KeyStoreMaterializerSuite extends KuiIOSuite {

  private val id: ClusterId = ClusterId.unsafe("prod")
  private val storePassword = "changeit".toCharArray

  /** An empty but structurally valid store of the given type, base64-encoded the way an operator
    * would paste it into YAML. Empty is enough: what is under test is that the bytes survive the
    * round trip to disk intact, which `KeyStore.load` proves by not failing.
    */
  private def storeBase64(kind: String): String = {
    val store = KeyStore.getInstance(kind)
    store.load(null, storePassword)

    val bytes = new ByteArrayOutputStream()
    store.store(bytes, storePassword)

    Base64.getEncoder.encodeToString(bytes.toByteArray)
  }

  private def connection(security: ClusterSecurity): ClusterConnection = ClusterConnection(
    id = id,
    bootstrapServers = BootstrapServers.unsafe("broker:9093"),
    security = security,
    overrides = ClientProperties.empty,
    admin = AdminTuning.default
  )

  private def inlineTruststore(kind: StoreType, base64: String): ClusterSecurity =
    ClusterSecurity.Ssl(
      TlsConfig.default.copy(
        truststore =
          Some(TrustStoreRef(StoreSource.Inline(Secret(base64)), Some(Secret("changeit")), kind))
      )
    )

  private def temporaryBase: IO[Path] =
    IO(Path.fromNioPath(JFiles.createTempDirectory("kui-materializer-suite")))

  private def loadable(path: String, kind: String): IO[Unit] = IO {
    val store = KeyStore.getInstance(kind)
    store.load(JFiles.newInputStream(JPath.of(path)), storePassword)
  }

  test("inlineJksBecomesALoadableKeyStore") {
    val security = inlineTruststore(StoreType.Jks, storeBase64("JKS"))

    KeyStoreMaterializer
      .resource[IO](connection(security))
      .use {
        case Left(error) => IO(fail(error.message))
        case Right(paths) =>
          val path = paths.getOrElse(StoreRole.TrustStore, fail("no truststore path"))
          loadable(path, "JKS")
      }
  }

  test("inlinePkcs12BecomesALoadableKeyStore") {
    val security = inlineTruststore(StoreType.Pkcs12, storeBase64("PKCS12"))

    KeyStoreMaterializer
      .resource[IO](connection(security))
      .use {
        case Left(error) => IO(fail(error.message))
        case Right(paths) =>
          val path = paths.getOrElse(StoreRole.TrustStore, fail("no truststore path"))
          loadable(path, "PKCS12")
      }
  }

  test("pemNeedsNoFile") {
    val pem = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----"
    val security = inlineTruststore(
      StoreType.Pem,
      Base64.getEncoder.encodeToString(pem.getBytes(StandardCharsets.UTF_8))
    )

    KeyStoreMaterializer
      .resource[IO](connection(security))
      .use(result => IO(assertEquals(result, Right(Map.empty[StoreRole, String]))))
  }

  test("fromPathIsPassedThroughUnchanged") {
    // KUI never copies a file the operator already mounted: the copy would be a second private key
    // on disk with a lifetime nobody thought about.
    val security = ClusterSecurity.Ssl(
      TlsConfig.default.copy(
        truststore =
          Some(TrustStoreRef(StoreSource.FromPath("/etc/kui/t.p12"), None, StoreType.Pkcs12))
      )
    )

    KeyStoreMaterializer
      .resource[IO](connection(security))
      .use(result => IO(assertEquals(result, Right(Map.empty[StoreRole, String]))))
  }

  test("filesAreOwnerOnly") {
    val security = inlineTruststore(StoreType.Pkcs12, storeBase64("PKCS12"))

    KeyStoreMaterializer
      .resource[IO](connection(security))
      .use {
        case Left(error) => IO(fail(error.message))
        case Right(paths) =>
          IO {
            val file = JPath.of(paths.getOrElse(StoreRole.TrustStore, fail("no path")))
            val directory = file.getParent

            assertEquals(
              JFiles.getPosixFilePermissions(file).asScala.toSet,
              Set(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            )
            assertEquals(
              JFiles.getPosixFilePermissions(directory).asScala.toSet,
              Set(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
              )
            )
          }
      }
  }

  test("filesAreDeletedOnRelease") {
    val security = inlineTruststore(StoreType.Pkcs12, storeBase64("PKCS12"))

    for {
      path <- KeyStoreMaterializer
        .resource[IO](connection(security))
        .use {
          case Left(error) => IO(fail(error.message))
          case Right(paths) => IO.pure(paths.getOrElse(StoreRole.TrustStore, fail("no path")))
        }
      file = JPath.of(path)
    } yield {
      assert(!JFiles.exists(file), s"$file survived the resource")
      assert(!JFiles.exists(file.getParent), s"${file.getParent} survived the resource")
    }
  }

  test("filesAreDeletedWhenTheBodyFails") {
    val security = inlineTruststore(StoreType.Pkcs12, storeBase64("PKCS12"))
    val captured = Ref.unsafe[IO, Option[String]](None)

    val failing = KeyStoreMaterializer
      .resource[IO](connection(security))
      .use {
        case Left(error) => IO(fail(error.message))
        case Right(paths) =>
          captured.set(paths.get(StoreRole.TrustStore)) >>
            IO.raiseError[Unit](new RuntimeException("the body failed"))
      }

    for {
      outcome <- failing.attempt
      path <- captured.get
    } yield {
      assert(outcome.isLeft)
      assert(path.exists(p => !JFiles.exists(JPath.of(p))), "the keystore survived a failed body")
    }
  }

  test("filesAreDeletedWhenTheBodyIsCancelled") {
    // The release has to run on cancellation too. A cancelled client startup that left a private
    // key on disk would be a leak nobody sees until an image is inspected.
    val security = inlineTruststore(StoreType.Pkcs12, storeBase64("PKCS12"))
    val captured = Ref.unsafe[IO, Option[String]](None)

    for {
      fiber <- KeyStoreMaterializer
        .resource[IO](connection(security))
        .use {
          case Left(error) => IO(fail(error.message))
          case Right(paths) => captured.set(paths.get(StoreRole.TrustStore)) >> IO.never[Unit]
        }
        .start
      _ <- captured.get.iterateUntil(_.isDefined)
      _ <- fiber.cancel
      path <- captured.get
    } yield assert(
      path.exists(p => !JFiles.exists(JPath.of(p))),
      "the keystore survived cancellation"
    )
  }

  test("theStoreIsWrittenIntoTheDirectoryItWasGiven") {
    // The `baseDirectory` parameter exists so that a deployment with a read-only `/tmp` can point
    // materialization at a mounted tmpfs. This is the test that it is honoured at all.
    val security = inlineTruststore(StoreType.Pkcs12, storeBase64("PKCS12"))

    for {
      base <- temporaryBase
      path <- KeyStoreMaterializer
        .resource[IO](connection(security), Some(base))
        .use {
          case Left(error) => IO(fail(error.message))
          case Right(paths) => IO.pure(paths.getOrElse(StoreRole.TrustStore, fail("no path")))
        }
    } yield {
      assert(path.startsWith(base.toString), s"$path is not under $base")
      assert(!JFiles.exists(JPath.of(path)), "the store outlived its resource")
    }
  }

  test("noSecretAppearsInTheErrorWhenTheBase64IsInvalid") {
    val security = inlineTruststore(StoreType.Pkcs12, "!!!! not base64 !!!!")

    KeyStoreMaterializer
      .resource[IO](connection(security))
      .use {
        case Right(_) => IO(fail("a malformed base64 store was accepted"))
        case Left(error) =>
          IO {
            assert(error.message.contains("ssl.truststore.location"), error.message)
            assert(!error.message.contains("!!!!"), "the error echoed the configured value")
          }
      }
  }

  test("theDirectoryNameCarriesTheClusterId") {
    assertEquals(KeyStoreMaterializer.directoryName(id, "abcd1234"), "kui-kafka-auth-prod-abcd1234")
  }
}
