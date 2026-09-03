package kui.kafka

import cats.effect.Concurrent
import cats.effect.syntax.all.*
import cats.syntax.all.*

import kui.kernel.cluster.AdminTuning

/** Splitting a large admin request into chunks, running a bounded number of them at once, and merging what
  * comes back without losing a key.
  *
  * Every reference product learned the same lesson the same way: one `describeConfigs` for forty brokers, or
  * one `describeLogDirs` for a cluster with ten thousand partitions, exceeds the request timeout and the
  * whole page fails. All of them fixed it by chunking with a bounded number of chunks in flight
  * (`research/kafka/admin-capabilities.md` §0, "Batching"). KUI writes it once, reads its numbers from
  * configuration rather than from constants in this file, and makes a failed chunk cost that chunk's keys
  * instead of the whole answer.
  *
  * This is a combinator over `List[K] => F[Map[K, A]]`, not a port. It handles an arbitrary `K`, so it can be
  * written before its callers exist without designing a port ahead of its first caller.
  */
object AdminBatch {

  /** Splits `keys` into chunks of `chunkSize`, runs at most `parallelism` at once, and merges the results.
    *
    * A chunk that fails does not fail the batch: its keys become `skipped` entries carrying the reason
    * `KafkaErrorMapper.suppressible` gives, or a `SkipReason.Failed` with the mapped code when the failure is
    * not suppressible. The alternative — one bad chunk failing everything — is how a cluster with a single
    * unreachable broker ends up showing an empty broker list.
    *
    * The merge is deterministic. Chunks are cut in the order `keys` arrives and merged in that same order
    * regardless of which finished first, because a result that depends on scheduling cannot be asserted
    * against a golden file and cannot be reproduced from a bug report.
    */
  def chunked[F[_]: Concurrent, K, A](
      keys: List[K],
      chunkSize: Int,
      parallelism: Int,
      operation: String
  )(run: List[K] => F[Map[K, A]]): F[BatchResult[K, A]] =
    if keys.isEmpty then BatchResult.empty[K, A].pure[F]
    else
      chunks(keys, chunkSize)
        .parTraverseN(bounded(parallelism)) { chunk =>
          run(chunk).attempt.map {
            case Right(values) => complete(chunk, values, operation)
            case Left(failure) => skipAll(chunk, failure, operation)
          }
        }
        // `foldLeft` in the order the chunks were cut, not in the order they finished.
        .map(_.foldLeft(BatchResult.empty[K, A])((acc, part) => acc.combine(part)))

  /** For a call that returns one future per key, such as `describeConfigs().values()`.
    *
    * Each key succeeds or fails on its own. `parallelism` bounds how many are awaited at once: the requests
    * were all issued by one call, so this bounds KUI's own work rather than the broker's — and it matters,
    * because each continuation runs on the way off the admin client's single I/O thread.
    */
  def perKey[F[_]: Concurrent, K, A](
      perKeyEffects: Map[K, F[A]],
      parallelism: Int,
      operation: String
  ): F[BatchResult[K, A]] =
    if perKeyEffects.isEmpty then BatchResult.empty[K, A].pure[F]
    else
      perKeyEffects.toList
        .parTraverseN(bounded(parallelism)) { entry =>
          val (key, effect) = entry
          effect.attempt.map {
            case Right(value) => BatchResult[K, A](Map(key -> value), Map.empty)
            case Left(failure) => skipAll(List(key), failure, operation)
          }
        }
        .map(_.foldLeft(BatchResult.empty[K, A])((acc, part) => acc.combine(part)))

  /** A bounded fan-out: one independent call per key, merged the same way.
    *
    * This is the shape `describeLogDirs` needs, one call per broker. The research is explicit that one call
    * for all brokers is wrong: a single slow or offline disk then stalls the request for every broker, and
    * the operator sees an empty table rather than eleven good rows and one bad one
    * (`research/kafka/admin-capabilities.md` §1, "Log dirs").
    */
  def perBroker[F[_]: Concurrent, K, A](
      keys: List[K],
      parallelism: Int,
      operation: String
  )(run: K => F[A]): F[BatchResult[K, A]] =
    if keys.isEmpty then BatchResult.empty[K, A].pure[F]
    else
      keys
        .parTraverseN(bounded(parallelism)) { key =>
          run(key).attempt.map {
            case Right(value) => BatchResult[K, A](Map(key -> value), Map.empty)
            case Left(failure) => skipAll(List(key), failure, operation)
          }
        }
        .map(_.foldLeft(BatchResult.empty[K, A])((acc, part) => acc.combine(part)))

  /** The chunks `chunked` would produce. Exposed so a test can assert the partition and a log line can report
    * the count.
    */
  def chunks[K](keys: List[K], chunkSize: Int): List[List[K]] =
    if keys.isEmpty then Nil else keys.grouped(math.max(1, chunkSize)).toList

  def topicChunk(t: AdminTuning): Int = t.topicChunkSize
  def partitionChunk(t: AdminTuning): Int = t.partitionChunkSize
  def groupChunk(t: AdminTuning): Int = t.groupChunkSize

  /** A duplicate key is a programming error, not a data condition: a caller that asks about the same broker
    * twice has a bug worth seeing rather than silently deduplicating.
    */
  def rejectDuplicates[K](keys: List[K]): Either[String, List[K]] = {
    val duplicates = keys.diff(keys.distinct).distinct

    if duplicates.isEmpty then Right(keys)
    else
      Left(
        s"the same key was requested more than once: " +
          duplicates.take(5).map(_.toString).mkString(", ")
      )
  }

  // ------------------------------------------------------------------ internals

  /** `parTraverseN` refuses a non-positive bound, and a configuration that produced one would fail at
    * validation (`AdminTuning.validate`) — but a defensive floor here costs nothing and turns a hypothetical
    * crash into a serialised batch.
    */
  private def bounded(parallelism: Int): Int = math.max(1, parallelism)

  /** A chunk that answered. Any key the broker did not mention is a skip with a stated reason, never a silent
    * absence: `describeConfigs` on a partly-authorized cluster returns fewer entries than it was asked about,
    * and "the key is missing" without a reason is precisely the shape `BatchResult` exists to prevent.
    */
  private def complete[K, A](
      chunk: List[K],
      values: Map[K, A],
      operation: String
  ): BatchResult[K, A] = {
    val missing = chunk.filterNot(values.contains)

    BatchResult(
      values.view.filterKeys(chunk.contains).toMap,
      missing.map(_ -> SkipReason.NotFound(s"$operation returned no entry for it")).toMap
    )
  }

  private def skipAll[K, A](
      chunk: List[K],
      failure: Throwable,
      operation: String
  ): BatchResult[K, A] = {
    val reason = KafkaErrorMapper
      .suppressible(failure)
      .getOrElse {
        val mapped = KafkaErrorMapper.map(operation, failure)
        SkipReason.Failed(mapped.code, mapped.message)
      }

    BatchResult.allSkipped[K, A](chunk.toSet, reason)
  }
}
