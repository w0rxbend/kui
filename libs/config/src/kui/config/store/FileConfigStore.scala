package kui.config.store

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import cats.effect.{Async, Resource}
import cats.syntax.all.*
import io.circe.parser.parse
import org.typelevel.log4cats.LoggerFactory

/** The read-only adapter over a directory: a mounted ConfigMap, a mounted Secret, a folder checked into a
  * GitOps repository.
  *
  * The layout is `<root>/<section>/<id>.json`, one `StoreRecord` envelope per file — exactly what the Kafka
  * adapter writes, and exactly what an export from `docs/operations/metadata-store.md` §5 can be reshaped
  * into. One file per key rather than one large document, for two reasons: a Kubernetes ConfigMap mounts as
  * one file per data entry, and a file that will not parse then costs one key rather than the whole store.
  *
  * It does not decrypt. Its files are plaintext JSON and a `$secret` marker is handed back to the caller as a
  * marker. The whole point of the file adapter is running with no encryption key at all (`metadata-store.md`
  * §7), so requiring a keyring to read a file would defeat it; the file's confidentiality is the filesystem's
  * job, which is the same guarantee the static YAML configuration already relies on. An `$enc` node found in
  * a file is left alone and its key is recorded as unreadable, because handing a caller a ciphertext as if it
  * were a password fails much later, at connection time, as an unexplainable authentication error.
  */
object FileConfigStore {

  /** Reads the tree once, at construction, and serves it from memory afterwards.
    *
    * There is no watcher. ADR-036 keeps the static file as the canonical base that Ciris loads at startup,
    * and a watcher here would give KUI two reload mechanisms with different semantics for one directory.
    *
    * A missing root is an empty store, not an error: "no directory" and "an empty directory" are the same
    * statement about a deployment. A file that is not readable JSON, whose envelope version is unsupported,
    * whose embedded key disagrees with its path, or which still holds a ciphertext is skipped with a `WARN`
    * and recorded in `health.unreadableKeys`. One bad file must not stop KUI from starting.
    */
  def resource[F[_]: {Async, LoggerFactory}](root: Path): Resource[F, ConfigStore[F]] =
    Resource.eval(load(root))

  private def load[F[_]: {Async, LoggerFactory}](root: Path): F[ConfigStore[F]] = {
    val logger = LoggerFactory[F].getLogger
    for {
      scanned <- Async[F].blocking(scan(root))
      _ <- scanned.skipped.traverse_((path, why) => logger.warn(s"skipping store file $path: $why"))
      _ <- logger.info(
        s"metadata store read from $root: ${scanned.records.size} records, ${scanned.skipped.size} skipped"
      )
    } yield ConfigStore.readOnly(
      scanned.records,
      StoreHealth.ReadOnly(s"the metadata store is the read-only directory $root", scanned.unreadable)
    )
  }

  final private case class Scanned(
      records: Map[StoreKey, StoreRecord],
      skipped: List[(Path, String)],
      unreadable: List[StoreKey]
  )

  /** Walks `<root>/<section>/<id>.json`. Blocking, and called once inside `Sync.blocking`.
    *
    * Only two levels are looked at. A deeper tree is not an error and is not read: `StoreKey` has exactly two
    * segments, so a third level of directory could not name a key anyway.
    */
  private def scan(root: Path): Scanned =
    if !Files.isDirectory(root) then Scanned(Map.empty, Nil, Nil)
    else {
      val files =
        listing(root).filter(Files.isDirectory(_)).flatMap(listing).filter(_.toString.endsWith(".json"))
      files.sortBy(_.toString).foldLeft(Scanned(Map.empty, Nil, Nil)) { (acc, path) =>
        readOne(root, path) match {
          case Right(record) if SecretJson.plaintextPaths(record.payload).isEmpty && hasCipher(record) =>
            // Decision 1's second half: a ciphertext in a file adapter is unusable, and pretending
            // otherwise defers the failure to a connection attempt that cannot explain itself.
            acc.copy(
              skipped =
                acc.skipped :+ (path, "it holds an encrypted field, which the file adapter cannot decrypt"),
              unreadable = acc.unreadable :+ record.key
            )
          case Right(record) => acc.copy(records = acc.records.updated(record.key, record))
          case Left((why, key)) =>
            acc.copy(skipped = acc.skipped :+ (path, why), unreadable = acc.unreadable ++ key.toList)
        }
      }
    }

  private def hasCipher(record: StoreRecord): Boolean =
    record.payload.noSpaces.contains(s""""${SecretJson.CipherField}"""")

  /** Reads one file. The `Left` carries why it was skipped and, when the path named a usable key, that key,
    * so that an operator sees which piece of their configuration went missing.
    */
  private def readOne(root: Path, path: Path): Either[(String, Option[StoreKey]), StoreRecord] = {
    val relative = root.relativize(path)
    val pathKey =
      StoreKey.parse(s"${relative.getParent.toString}/${stripExtension(relative.getFileName.toString)}")
    for {
      expected <- pathKey.left.map(error => (error.message, None))
      text <- readText(path).toRight(("the file could not be read", Some(expected)))
      json <- parse(text).left.map(failure => (s"it is not valid JSON (${failure.message})", Some(expected)))
      record <- StoreRecord.fromJson(json).left.map(error => (error.message, Some(expected)))
      _ <- Either.cond(
        record.key == expected,
        (),
        (s"its envelope names key '${record.key.render}', which its path does not", Some(expected))
      )
    } yield record
  }

  private def readText(path: Path): Option[String] =
    try Some(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
    catch { case NonFatal(_) => None }

  private def listing(directory: Path): List[Path] =
    try {
      val stream = Files.list(directory)
      try stream.iterator().asScala.toList
      finally stream.close()
    } catch { case NonFatal(_) => Nil }

  private def stripExtension(name: String): String = name.stripSuffix(".json")
}
