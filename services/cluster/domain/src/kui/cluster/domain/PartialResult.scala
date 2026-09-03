package kui.cluster.domain

import kui.kernel.error.{InfrastructureError, KuiError}

/** Why one key of a batch produced no value.
  *
  * A closed set, and every case is one a reference product hit in production. `Failed` carries a `KuiError`
  * and not a `Throwable`, so a skip reason can be rendered to a user without an exception class name reaching
  * a screen.
  */
enum SkipReason {

  /** The broker refused the request for this key — no `DESCRIBE_CONFIGS`, no `DESCRIBE` on the resource. The
    * rest of the batch is fine.
    */
  case Unauthorized

  /** The key vanished between listing and describing, or the managed service reports it as unknown. */
  case NotFound

  /** The cluster does not implement this call for this key at all. */
  case Unsupported

  /** Anything else, already classified into a `KuiError` by the adapter. */
  case Failed(error: KuiError)

  /** Display text, safe for a screen. */
  def describe: String = this match {
    case Unauthorized => "KUI is not authorized to read this"
    case NotFound => "it no longer exists"
    case Unsupported => "this cluster does not support the request"
    case Failed(error) => error.message
  }
}

object SkipReason {
  given CanEqual[SkipReason, SkipReason] = CanEqual.derived
}

/** A batch answer that says what it could not do rather than dropping it.
  *
  * The whole point is `skipped`. Returning an empty map on a per-key failure is why a reference product's
  * broker page cannot distinguish "this cluster has no dynamic configuration" from "KUI is not allowed to
  * read it" — and neither can its user. A silent drop is forbidden here: every key that went in comes out in
  * exactly one of the two maps.
  */
final case class PartialResult[K, A](values: Map[K, A], skipped: Map[K, SkipReason]) {

  def isComplete: Boolean = skipped.isEmpty

  def get(key: K): Option[A] = values.get(key)

  def keys: Set[K] = values.keySet ++ skipped.keySet

  def map[B](f: A => B): PartialResult[K, B] =
    PartialResult(values.map((key, value) => key -> f(value)), skipped)

  /** Merges two results, a value winning over a skip for a key that appears in both — which is what a retry
    * that succeeded looks like. Used to fold chunked calls back together.
    */
  def merge(other: PartialResult[K, A]): PartialResult[K, A] = {
    val mergedValues = values ++ other.values
    val mergedSkips = (skipped ++ other.skipped) -- mergedValues.keySet

    PartialResult(mergedValues, mergedSkips)
  }
}

object PartialResult {

  def complete[K, A](values: Map[K, A]): PartialResult[K, A] = PartialResult(values, Map.empty)

  def empty[K, A]: PartialResult[K, A] = PartialResult(Map.empty, Map.empty)

  /** Builds from the requested key set, so the invariant is established at construction rather than hoped
    * for: a requested key that appears in neither map becomes a `Failed` skip, and a key that appears in both
    * is a value.
    */
  def from[K, A](
      requested: Set[K],
      values: Map[K, A],
      skipped: Map[K, SkipReason]
  ): PartialResult[K, A] = {
    val kept = values.view.filterKeys(requested.contains).toMap
    val explained = (skipped -- kept.keySet).view.filterKeys(requested.contains).toMap
    val unaccounted = requested -- kept.keySet -- explained.keySet

    val filled = explained ++ unaccounted.map { key =>
      key -> SkipReason.Failed(
        InfrastructureError.Unreachable("the cluster", s"nothing was reported for '$key'")
      )
    }

    PartialResult(kept, filled)
  }
}
