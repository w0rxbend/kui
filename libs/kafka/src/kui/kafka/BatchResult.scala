package kui.kafka

/** A per-key result where some keys may be missing, and every missing key says why.
  *
  * One invariant makes this type worth having: `values` and `skipped` have disjoint key sets, and their union
  * is exactly the set of keys that were asked for. A key cannot vanish.
  *
  * That is the whole difference from the reference implementations, which return an empty map when a key
  * fails and leave the caller to guess whether the cluster has no log directories or simply would not say
  * (`research/kafka/admin-capabilities.md` §1, "Log dirs" and "Broker configs"). The distinction matters at
  * the pixel: "0 bytes" and "we are not allowed to look" are different screens, and only one of them should
  * make an operator start an investigation.
  */
final case class BatchResult[K, A](values: Map[K, A], skipped: Map[K, SkipReason]) {

  /** Every key that was asked about, whether or not it answered. */
  def requested: Set[K] = values.keySet ++ skipped.keySet

  def isComplete: Boolean = skipped.isEmpty

  def isEmpty: Boolean = values.isEmpty && skipped.isEmpty

  def map[B](f: A => B): BatchResult[K, B] = BatchResult(values.map((k, v) => k -> f(v)), skipped)

  /** The value, or the reason there is none. Never an `Option`, because "no value" without a reason is the
    * thing this type exists to prevent.
    */
  def get(key: K): Either[SkipReason, A] =
    values.get(key) match {
      case Some(value) => Right(value)
      case None =>
        skipped.get(key) match {
          case Some(reason) => Left(reason)
          case None => Left(SkipReason.NotFound(s"$key was not part of this request"))
        }
    }

  /** Merges two results over disjoint key sets, which is what a chunked call produces (KAFKA-006).
    *
    * A key present in both halves is a programming error rather than a data condition, so this takes the left
    * side's value silently and `combineChecked` is the version that says so.
    */
  def combine(that: BatchResult[K, A]): BatchResult[K, A] = {
    val mergedValues = that.values ++ values
    // A key that succeeded anywhere is not skipped: a chunk that failed and a retry that worked
    // must not leave the key in both halves and break the disjointness invariant.
    val mergedSkipped = (that.skipped ++ skipped) -- mergedValues.keySet

    BatchResult(mergedValues, mergedSkipped)
  }

  def combineChecked(that: BatchResult[K, A]): Either[String, BatchResult[K, A]] = {
    val overlap = requested.intersect(that.requested)

    if overlap.isEmpty then Right(combine(that))
    else
      Left(
        s"the two halves of a batch both carry ${overlap.size} key(s): " +
          overlap.toList.map(_.toString).sorted.take(5).mkString(", ")
      )
  }
}

object BatchResult {

  def empty[K, A]: BatchResult[K, A] = BatchResult(Map.empty, Map.empty)

  def complete[K, A](values: Map[K, A]): BatchResult[K, A] = BatchResult(values, Map.empty)

  /** Every key failed for the same reason. A valid result, not an error: it is the shape a cluster that
    * authenticates but authorizes nothing produces, and the page it renders is a full list of brokers with a
    * lock icon on each — not an error banner.
    */
  def allSkipped[K, A](keys: Set[K], reason: SkipReason): BatchResult[K, A] =
    BatchResult(Map.empty, keys.map(_ -> reason).toMap)

  /** A deterministic rendering, so that a chunked result is reproducible and a golden file over one does not
    * flap with map iteration order.
    */
  extension [K: Ordering, A](result: BatchResult[K, A]) {
    def orderedValues: List[(K, A)] = result.values.toList.sortBy(_._1)
    def orderedSkipped: List[(K, SkipReason)] = result.skipped.toList.sortBy(_._1)
  }

  given [K, A] => CanEqual[BatchResult[K, A], BatchResult[K, A]] = CanEqual.derived
}
