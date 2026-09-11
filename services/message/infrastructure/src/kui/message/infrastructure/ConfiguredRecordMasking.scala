package kui.message.infrastructure

import cats.Monad
import cats.syntax.all.*

import kui.config.ClusterConfig
import kui.kernel.serde.Target
import kui.kernel.{ClusterId, TopicName}
import kui.message.application.{RecordMask, RecordMasking}
import kui.message.domain.{Decoded, RenderedHeader}
import kui.security.masking.{MaskingEngine, MaskingRule}

/** The message domain's `RecordMasking`, answered from this process's own `kui.clusters[].masking[]`.
  *
  * The adapter DM-001 has been waiting for. `MaskingEngine` has been built, unit-tested and callerless since
  * wave 1; `kui.masking.applied` has been a declared metric with no writer for just as long; and
  * `docs/FEATURE_MATRIX.md` has carried DM-001 as the clearest instance of this project's failure mode. This
  * file is the caller.
  *
  * ==Where the rules come from==
  *
  * The same place every other per-cluster setting in this service comes from: the configuration this process
  * was started with, exactly as `ConfiguredClusterProfiles` reads the cluster list. There is no upstream to
  * ask and nothing to cache — a masking rule changes when the process is restarted with a different file, and
  * a restart replaces everything.
  *
  * A cluster this deployment has never heard of gets no rules rather than an error. The read that asked about
  * it is already failing with `KUI-CLUSTER-NOT-FOUND` from `ClusterProfileSource`, one layer up and before
  * any record is fetched, and answering "the empty mask" here is the only answer that cannot make that
  * failure worse — a mask is not the place to discover a cluster does not exist.
  */
final class ConfiguredRecordMasking[F[_]: Monad] private (
    rulesFor: ClusterId => List[MaskingRule],
    metrics: MaskingMetrics[F]
) extends RecordMasking[F] {

  def forTopic(cluster: ClusterId, topic: TopicName): F[RecordMask] = {
    val rules = rulesFor(cluster)

    // `applies` per target, and both answers kept, because the two halves of a record are scoped
    // separately: `topicKeysPattern` selects keys and `topicValuesPattern` selects values, and a rule
    // that reaches one must not be allowed to reach the other. This is the fast path too — a cluster with
    // no rules, which is nearly every cluster, gets `false` twice from a `List.exists` over `Nil` and the
    // identity mask, so not one record is re-walked or re-parsed.
    val keys = MaskingEngine.applies(rules, topic, Target.Key)
    val values = MaskingEngine.applies(rules, topic, Target.Value)

    if !keys && !values then RecordMask.identity.pure[F]
    else
      metrics.applied(cluster, topic, Target.Key).whenA(keys) *>
        metrics.applied(cluster, topic, Target.Value).whenA(values) *>
        ConfiguredRecordMasking.maskWith(rules, topic, keys, values).pure[F]
  }
}

object ConfiguredRecordMasking {

  /** Built from the same `kui.clusters[]` every other adapter in this service is built from. */
  def of[F[_]: Monad](
      clusters: List[ClusterConfig],
      metrics: MaskingMetrics[F]
  ): ConfiguredRecordMasking[F] = {
    val byId: Map[ClusterId, List[MaskingRule]] =
      clusters.map(cluster => cluster.id -> cluster.masking.rules).toMap

    new ConfiguredRecordMasking[F](id => byId.getOrElse(id, Nil), metrics)
  }

  /** The mask itself: one pure function from record to record, closed over the rules of one topic.
    *
    * `keys` and `values` are passed in rather than recomputed per record. They are a property of the topic
    * and the rule list, both of which are fixed for the whole read, and computing them per record would run
    * every rule's regex against the topic name once per record — a few hundred thousand times on a browse
    * that the fast path above exists to keep cheap.
    */
  private[infrastructure] def maskWith(
      rules: List[MaskingRule],
      topic: TopicName,
      keys: Boolean,
      values: Boolean
  ): RecordMask =
    record =>
      record.copy(
        key = if keys then maskPayload(rules, topic, Target.Key, record.key) else record.key,
        value = if values then maskPayload(rules, topic, Target.Value, record.value) else record.value,
        // HEADERS FOLLOW THE VALUE SCOPE AND NEVER THE KEY SCOPE. `MaskingEngine.maskHeaders` filters its
        // rules under `Target.Value` and argues for it: a header belongs to the record, not to its key or
        // its value, so only value-scoped and unscoped rules reach one. Asking for headers here when only
        // `keys` is true would re-decide that in a second place and get the opposite answer.
        headers = if values then maskHeaders(rules, topic, record.headers) else record.headers
      )

  /** One decoded half, masked in whichever form the serde produced.
    *
    * ==Why an empty payload is left alone==
    *
    * A record with no key, and a tombstone's absent value, both arrive as `Decoded.absent` — empty text.
    * There is nothing in zero bytes to hide, and a whole-value `replace` rule applied to one would write the
    * replacement *into* it, so a tombstone would come back carrying a value it does not have. That is not
    * masking; it is fabricating a record, and it would be visible on the screen as a deleted key that still
    * has data.
    */
  private def maskPayload(
      rules: List[MaskingRule],
      topic: TopicName,
      target: Target,
      decoded: Decoded
  ): Decoded =
    if decoded.text.isEmpty then decoded
    else decoded.copy(text = MaskingEngine.maskPayload(rules, topic, target, decoded.kind, decoded.text))

  /** The record's headers, masked by name against the same field rules.
    *
    * Rendered headers are an ordered list here and a `Map` in the engine, because the engine states the rule
    * once for every caller and this service keeps the order a producer wrote. The conversion is by name, and
    * a header the engine dropped — a `remove` rule matching its name — disappears from the list, which is the
    * same thing `remove` does to an object key.
    *
    * Duplicate header names are legal in Kafka and survive this: the engine is asked about the *name*, so two
    * headers sharing one are masked identically, and neither is lost to the `Map` the engine takes because
    * each is converted on its own.
    */
  private def maskHeaders(
      rules: List[MaskingRule],
      topic: TopicName,
      headers: List[RenderedHeader]
  ): List[RenderedHeader] =
    headers.flatMap { header =>
      MaskingEngine
        .maskHeaders(rules, topic, Map(header.key -> header.value))
        .get(header.key)
        .map(masked => RenderedHeader(header.key, masked))
    }
}
