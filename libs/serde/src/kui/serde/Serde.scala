package kui.serde

import kui.kernel.TopicName

/** The bytes-to-text boundary, as one interface (`ARCHITECTURE.md` §4.4, ADR-028).
  *
  * A `Serde[F]` is a *factory* rather than a codec: `deserializer` and `serializer` are effects that return
  * the thing that does the work. That shape is Kafbat's and it is kept for a concrete reason — a
  * Schema-Registry serde has to look a subject up before it can decode anything, and a codec that could not
  * perform an effect to prepare itself would have to do that lookup once per record instead of once per
  * stream.
  *
  * The whole interface is per `(topic, target)` for the same reason: a serde is configured against topic
  * patterns, and the answer to "can you read this?" depends on which topic is being read.
  *
  * A `Serde[F]` has no lifetime. Serdes that own one — the Confluent ones, with their HTTP client and their
  * caches — are handed out by a `Resource` in `libs/serde-confluent` and held by the cluster's
  * `ClusterSerdes`, so that a pure serde stays a pure value.
  */
trait Serde[F[_]] {

  def name: SerdeName

  /** The sentence the picker shows and `docs/operations/serdes.md` prints. */
  def describe: SerdeDescription

  /** Whether this serde is willing to be *chosen* for this topic and target at all. `false` for a
    * Schema-Registry serde on a topic with no subject, so the picker does not offer a choice that cannot
    * work.
    */
  def canDeserialize(topic: TopicName, target: Target): F[Boolean]

  def canSerialize(topic: TopicName, target: Target): F[Boolean]

  /** Whether this serde is the *best* guess for this topic and target, not merely a possible one.
    *
    * Exactly the distinction auto-detection needs: `String` can decode any bytes and so `canDeserialize` is
    * always true for it, but it is only `preferable` when the bytes really do look like text.
    */
  def preferable(topic: TopicName, target: Target): F[Boolean]

  def schema(topic: TopicName, target: Target): F[Option[SchemaDescription]]

  def parameters(topic: TopicName, target: Target): F[List[SerdeParameter]]

  def deserializer(topic: TopicName, target: Target): F[Deserializer[F]]

  def serializer(topic: TopicName, target: Target, params: Map[String, String]): F[Serializer[F]]
}

/** One prepared decoder, for one topic and target.
  *
  * Returns `Either` rather than failing the effect. A serde that raises has not broken the contract — every
  * caller goes through [[Deserializers.attempt]], which catches that too — but the `Either` is what says the
  * failure is expected and belongs on the record.
  */
trait Deserializer[F[_]] {

  /** Which serde produced this decoder.
    *
    * Carried on the decoder rather than passed alongside it, so that `Deserializers.attempt` can name the
    * serde in a failure it built itself from a thrown exception. A caller that had to supply the name is a
    * caller that can supply the wrong one.
    */
  def serde: SerdeName

  /** @param headers
    *   the record's headers, which some formats need: a decoder can be told the schema by a header rather
    *   than by the payload.
    * @param bytes
    *   the payload. Never `null`: a record with no payload is handled by [[Deserializers.attempt]] before a
    *   deserializer is called at all.
    */
  def deserialize(
      headers: List[RawHeader],
      bytes: Array[Byte]
  ): F[Either[DeserializeFailure, DeserializeResult]]
}

/** One prepared encoder, for one topic, target and set of parameters. */
trait Serializer[F[_]] {

  /** Which serde produced this encoder. */
  def serde: SerdeName

  def serialize(input: String, headers: List[RawHeader]): F[Either[SerializeFailure, Array[Byte]]]
}
