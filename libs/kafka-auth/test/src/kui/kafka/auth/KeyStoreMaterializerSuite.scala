package kui.kafka.auth

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.{PosixFilePermission, PosixFilePermissions}
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

/** That a keystore pasted into configuration becomes a real, openable, private file — and stops existing the
  * moment the client that needed it does.
  */
final class KeyStoreMaterializerSuite extends KuiIOSuite {

  private val id: ClusterId = ClusterId.unsafe("prod")
  private val storePassword = "changeit".toCharArray

  /** An empty but structurally valid store of the given type, base64-encoded the way an operator would paste
    * it into YAML. Empty is enough: what is under test is that the bytes survive the round trip to disk
    * intact, which `KeyStore.load` proves by not failing.
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
        truststore = Some(TrustStoreRef(StoreSource.Inline(Secret(base64)), Some(Secret("changeit")), kind))
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
        truststore = Some(TrustStoreRef(StoreSource.FromPath("/etc/kui/t.p12"), None, StoreType.Pkcs12))
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

  test("a directory that already exists is narrowed to the owner rather than left as it was found") {
    /*
     * Ungated until wave 12, and filed in wave 11 as "not portably closable": deleting the second
     * `setPosixPermissions(directory, DirectoryPermissions)` from `createDirectory` left all 395 cases of
     * `libs.kafkaAuth.test` green, `filesAreOwnerOnly` included. The reason is arithmetic rather than
     * coverage — `createDirectories` reaches `mkdir(2)` with mode 0700, and a umask can only *clear* bits,
     * so on any ordinary umask the leaf is 0700 before the second call runs and `filesAreOwnerOnly` cannot
     * tell the two versions apart.
     *
     * It is closable, and portably, once the second call is asked about the case where it is the only thing
     * that acts: a directory that ALREADY EXISTS. `createDirectories` is then a no-op which applies no
     * permissions at all, and whatever the directory was found at is what a private key would be written
     * into. So the case pre-creates the directory at 0777 — the widest thing a reused tmpfs mount or a
     * careless `mkdir -p` in an entrypoint leaves behind — and requires the materializer to narrow it.
     *
     * This asserts the production helper `createDirectory` itself calls, not a copy of it.
     */
    val wideOpen = java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx")

    IO(JFiles.createTempDirectory("kui-keystore-perms")).flatMap { base =>
      val directory = base.resolve(KeyStoreMaterializer.directoryName(id, "abcd1234"))

      for {
        _ <- IO(JFiles.createDirectory(directory, PosixFilePermissions.asFileAttribute(wideOpen)))
        // Whatever the umask did to the line above, the starting state is stated rather than assumed.
        _ <- IO(JFiles.setPosixFilePermissions(directory, wideOpen))
        found <- IO(JFiles.getPosixFilePermissions(directory).asScala.toSet)
        _ <- KeyStoreMaterializer.secureDirectory[IO](Path.fromNioPath(directory))
        after <- IO(JFiles.getPosixFilePermissions(directory).asScala.toSet)
        _ <- IO(JFiles.deleteIfExists(directory)) >> IO(JFiles.deleteIfExists(base))
      } yield {
        assertEquals(found.size, 9, "the fixture did not start wide open, so the case proves nothing")
        assertEquals(
          after,
          Set(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE
          ),
          "a pre-existing directory kept the permissions it was found with, so a keystore would be " +
            "written into a directory every user on the host can read"
        )
      }
    }
  }

  test("a materialized store is overwritten before it is unlinked, not merely deleted") {
    /*
     * Ungated until now: dropping the zero-fill from `cleanUp` — deleting the directory without
     * overwriting anything first — left `./mill libs.kafkaAuth.test` green. The directory goes in the same
     * release, so every other case here can only observe that it is gone, which it is either way.
     *
     * A second hard link to the same inode, taken while the resource is open, is what makes the overwrite
     * observable without a seam in the production class. Unlinking the materialized path leaves the inode
     * alive under the second name, and what is in it is whatever the last write left there. That is not a
     * contrivance: it is the shape of the failure the overwrite exists for. `cleanUp`'s own comment names
     * the two — "a container image layer or a heap dump taken while the process is still running" — and
     * both are second references to bytes KUI believes it has deleted.
     *
     * The link is made inside the base directory the materializer was given rather than in a second
     * temporary one, so it is certainly on the same filesystem; `deleteRecursively` removes the
     * materializer's own directory under it and not the base.
     */
    val security = inlineTruststore(StoreType.Pkcs12, storeBase64("PKCS12"))

    for {
      base <- temporaryBase
      link = base.toNioPath.resolve("witness")
      original <- KeyStoreMaterializer
        .resource[IO](connection(security), Some(base))
        .use {
          case Left(error) => IO(fail(error.message))
          case Right(paths) =>
            IO {
              val file = JPath.of(paths.getOrElse(StoreRole.TrustStore, fail("no path")))
              // A filesystem with no hard links cannot answer the question this case asks, and a
              // failure there would be a fact about the filesystem rather than about KUI.
              val linked =
                try { val _ = JFiles.createLink(link, file); true }
                catch { case _: UnsupportedOperationException => false }

              assume(linked, "this filesystem does not support hard links")
              JFiles.readAllBytes(file).toList
            }
        }
      survived <- IO(JFiles.readAllBytes(link).toList)
    } yield {
      assert(original.nonEmpty, "the fixture wrote no bytes at all, so the case would pass vacuously")
      assert(
        original.exists(_ != 0.toByte),
        "an empty PKCS12 store is already all zeroes, so this case could not tell the two apart"
      )
      assertEquals(
        survived.length,
        original.length,
        "the file was truncated rather than overwritten, which leaves the old bytes in the freed blocks"
      )
      assert(
        survived.forall(_ == 0.toByte),
        "the keystore's bytes survived the release under a second reference to the same inode"
      )
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
