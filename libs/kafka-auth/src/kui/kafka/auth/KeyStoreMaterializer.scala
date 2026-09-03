package kui.kafka.auth

import java.util.Base64

import cats.effect.{Async, Resource}
import cats.syntax.all.*
import fs2.Stream
import fs2.io.file.{Files, Flags, Path, PosixPermissions}

import kui.kafka.auth.ClientPropertyRenderer.StoreRole
import kui.kernel.cluster.*
import kui.kernel.error.{DomainError, InfrastructureError, KuiError}
import kui.kernel.{ClusterId, Secret, ValidationError}

/** Writes inline keystores to disk for exactly as long as a client needs them.
  *
  * Kafka's SSL engine takes a *path* for a JKS or PKCS12 store; there is no client property that carries
  * those bytes. So a store an operator pasted into their configuration has to become a file somewhere, and
  * the only two questions worth arguing about are where and for how long. The answers here are: in a
  * directory the process owner alone can read, and only while the `Resource` is open.
  *
  * A PEM store never reaches this object at all — Kafka has properties that carry PEM text directly, and
  * `ClientPropertyRenderer` uses them — so the common secure configuration writes no private key to any
  * filesystem.
  */
object KeyStoreMaterializer {

  /** `rwx------`: the process owner, and nobody else. */
  private val DirectoryPermissions: PosixPermissions =
    PosixPermissions.fromOctal("700").getOrElse(PosixPermissions())

  /** `rw-------`. A private key on a path a sidecar container can read is not a degraded mode, it is a
    * different product, so a filesystem that cannot express this fails the materialization rather than
    * falling back.
    */
  private val FilePermissions: PosixPermissions =
    PosixPermissions.fromOctal("600").getOrElse(PosixPermissions())

  /** How many times a file is overwritten before it is deleted. */
  private val ZeroFillChunk: Int = 8192

  /** Materializes whichever of the connection's stores need a path, and returns the paths that
    * `ClientPropertyRenderer.render` takes as its `materialized` argument.
    *
    * The result is empty in the common case: a `StoreSource.FromPath` already has a file, a PEM store carries
    * its bytes in a property, and `TlsConfig.default` has no store at all.
    *
    * Failures are values, not exceptions. A store whose base64 does not decode is a `ValidationError` naming
    * the field and never the value; an unwritable or non-POSIX filesystem is an
    * `InfrastructureError.Unreachable` naming the directory it tried, because the operator's fix — mount a
    * writable tmpfs — is an infrastructure fix.
    */
  def resource[F[_]: {Async, Files}](
      connection: ClusterConnection,
      baseDirectory: Option[Path] = None
  ): Resource[F, Either[KuiError, Map[StoreRole, String]]] =
    decodeAll(connection.security.tlsConfig) match {
      case Left(error) => Resource.pure(Left(error))
      case Right(Nil) => Resource.pure(Right(Map.empty))
      case Right(stores) => write(connection.id, stores, baseDirectory)
    }

  /** The directory name under the base directory.
    *
    * The cluster id is in the name so that an operator who finds a stray directory in a running container can
    * tell which cluster it belonged to; the random suffix is what keeps two clusters, or two restarts, from
    * sharing one — sharing would mean the first client's shutdown deleting the second client's truststore.
    */
  def directoryName(id: ClusterId, random: String): String =
    s"kui-kafka-auth-${id.value}-$random"

  // ------------------------------------------------------------------ decoding

  /** Which stores need a file, decoded, before any I/O happens.
    *
    * Decoding first means a malformed base64 fails without having created a directory, which is both tidier
    * and the reason the acceptance criterion about "no file is written" can hold.
    */
  private def decodeAll(
      tls: Option[TlsConfig]
  ): Either[KuiError, List[(StoreRole, Array[Byte])]] = {
    val candidates: List[(StoreRole, String, StoreSource, StoreType)] = tls.toList.flatMap { config =>
      config.truststore.toList.map(ref =>
        (StoreRole.TrustStore, "ssl.truststore.location", ref.source, ref.storeType)
      ) ++
        config.keystore.toList.map(ref =>
          (StoreRole.KeyStore, "ssl.keystore.location", ref.source, ref.storeType)
        )
    }

    val needed = candidates.collect {
      case (role, field, StoreSource.Inline(base64), storeType) if storeType != StoreType.Pem =>
        (role, field, base64)
    }

    needed.foldLeft[Either[KuiError, List[(StoreRole, Array[Byte])]]](Right(Nil)) { (acc, entry) =>
      val (role, field, base64) = entry

      for {
        soFar <- acc
        bytes <- decode(base64, field)
      } yield soFar :+ (role -> bytes)
    }
  }

  private def decode(base64: Secret[String], field: String): Either[KuiError, Array[Byte]] =
    scala.util
      .Try(Base64.getDecoder.decode(base64.value.replaceAll("\\s", "")))
      .toEither
      .left
      .map(_ =>
        DomainError.fromValidation(
          ValidationError.Format(field, "valid base64", "<the configured value>")
        )
      )

  // ------------------------------------------------------------------ writing

  private def write[F[_]: {Async, Files}](
      id: ClusterId,
      stores: List[(StoreRole, Array[Byte])],
      baseDirectory: Option[Path]
  ): Resource[F, Either[KuiError, Map[StoreRole, String]]] =
    Resource
      // `Resource.make` runs its acquire uncancellably, so a directory that was created is always
      // handed to the release — there is no window in which a cancellation leaves one behind.
      .make(createDirectory(id, baseDirectory).attempt)(cleanUp(_))
      .evalMap {
        case Left(failure) =>
          Async[F].pure(unreachable(baseDirectory, failure))
        case Right(directory) =>
          stores
            .traverse { (role, bytes) =>
              val path = directory / fileNameFor(role)
              writeStore(path, bytes).as(role -> path.toString)
            }
            .attempt
            .map {
              case Right(paths) => Right(paths.toMap)
              case Left(failure) => unreachable(Some(directory), failure)
            }
      }

  private def fileNameFor(role: StoreRole): String = role match {
    case StoreRole.TrustStore => "truststore"
    case StoreRole.KeyStore => "keystore"
  }

  private def createDirectory[F[_]: {Async, Files}](
      id: ClusterId,
      baseDirectory: Option[Path]
  ): F[Path] =
    for {
      random <- Async[F].delay(java.util.UUID.randomUUID().toString.take(8))
      base <- baseDirectory.fold(
        Async[F].delay(Path(System.getProperty("java.io.tmpdir", "/tmp")))
      )(_.pure[F])
      directory = base / directoryName(id, random)
      _ <- Files[F].createDirectories(directory, Some(DirectoryPermissions))
      // `createDirectories` on a path whose parent already exists with other permissions can leave
      // the leaf with the umask applied, so the permissions are set again explicitly.
      _ <- Files[F].setPosixPermissions(directory, DirectoryPermissions)
    } yield directory

  private def writeStore[F[_]: {Async, Files}](path: Path, bytes: Array[Byte]): F[Unit] =
    Files[F].createFile(path, Some(FilePermissions)) >>
      Stream
        .chunk(fs2.Chunk.array(bytes))
        .through(Files[F].writeAll(path, Flags.Write))
        .compile
        .drain >>
      Files[F].setPosixPermissions(path, FilePermissions)

  /** Overwrites every file with zeroes and then deletes the directory.
    *
    * The overwrite is not a claim about forensic erasure on a copy-on-write filesystem; it is about the far
    * more likely case, which is a container image layer or a heap dump taken while the process is still
    * running. Zeroing costs a few milliseconds and removes the easy read.
    */
  private def cleanUp[F[_]: {Async, Files}](acquired: Either[Throwable, Path]): F[Unit] =
    acquired match {
      case Left(_) => Async[F].unit
      case Right(directory) =>
        val zeroThenDelete = Files[F]
          .list(directory)
          .evalMap { file =>
            Files[F]
              .size(file)
              .flatMap(size =>
                Stream
                  .constant[F, Byte](0, ZeroFillChunk)
                  .take(size)
                  .through(Files[F].writeAll(file, Flags.Write))
                  .compile
                  .drain
              )
              .attempt
              .void
          }
          .compile
          .drain

        (zeroThenDelete >> Files[F].deleteRecursively(directory)).attempt.void
    }

  private def unreachable(
      directory: Option[Path],
      failure: Throwable
  ): Either[KuiError, Map[StoreRole, String]] =
    Left(
      InfrastructureError.Unreachable(
        "keystore-materializer",
        s"could not write an inline keystore under " +
          s"${directory.fold("the JVM temporary directory")(_.toString)}: " +
          s"${failure.getClass.getSimpleName}: ${failure.getMessage}"
      )
    )
}
