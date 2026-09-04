package kui.serde

import cats.effect.Sync
import cats.syntax.all.*

import kui.kernel.TopicName

/** Which serde to try for a payload nobody configured.
  *
  * Ranking, not guessing. Every candidate is asked two questions and both must be yes:
  *
  *   1. **Would you be chosen for this topic at all?** — `canDeserialize`, which is the question a
  *      Schema-Registry serde answers with a lookup and a pure codec answers with `true`.
  *   2. **Are these bytes yours?** — [[SampleDetector.claims]], and then an actual decode of the sample, so
  *      that a serde which claims a payload it cannot in fact read is dropped rather than recommended.
  *
  * The result is in candidate order, which for the built-ins is `BuiltinSerdes.all`. It is a pure function of
  * the sample and the candidate list, and the suite pins that: a picker whose order changes between two
  * refreshes of the same page is a picker a user stops trusting, and then stops using.
  *
  * An empty result means nobody claims the payload. That is not a failure — it is how the fallback becomes
  * the answer, which is the terminal case the whole resolution order is built around.
  */
object SerdeAutodetect {

  /** @param sample
    *   one record's payload, usually the first of a page. `None` when there is no record to look at yet — an
    *   empty topic, or a picker opened before anything was fetched — in which case the ranking falls back to
    *   each serde's topic-level `preferable`, which for the pure codecs is nobody and for a Schema-Registry
    *   serde is "yes, this topic has a subject".
    */
  def rank[F[_]: Sync](
      candidates: List[Serde[F]],
      topic: TopicName,
      target: Target,
      sample: Option[Array[Byte]]
  ): F[List[SerdeName]] =
    candidates
      .traverse(serde => wanted(serde, topic, target, sample).map(yes => Option.when(yes)(serde.name)))
      .map(_.flatten)

  private def wanted[F[_]: Sync](
      serde: Serde[F],
      topic: TopicName,
      target: Target,
      sample: Option[Array[Byte]]
  ): F[Boolean] =
    serde.canDeserialize(topic, target).flatMap {
      case false => false.pure[F]
      case true =>
        sample match {
          case None => serde.preferable(topic, target)
          case Some(bytes) =>
            serde match {
              case detector: SampleDetector if detector.claims(bytes) => decodes(serde, topic, target, bytes)
              // A serde that cannot look at bytes is never auto-detected. `Base64` and `Hex` are the
              // reason: they render any payload, so a rule that ranked whatever decodes successfully would
              // rank them for everything and rank them first for nothing anyone wanted.
              case _ => false.pure[F]
            }
        }
    }

  /** The claim, verified. `claims` is a cheap look at the bytes; this is the decode itself, and the two can
    * disagree — a JSON payload that parses but exceeds a nesting limit, say. Where they disagree the decode
    * wins, because it is the thing that will actually run.
    *
    * It goes through `Deserializers.attempt` like every other decode in KUI, which is why this function needs
    * `Sync` rather than the `Monad` the task spec named: a third-party serde that throws while being probed
    * would otherwise take down the picker, and a picker is exactly where an unfamiliar serde is first met.
    */
  private def decodes[F[_]: Sync](
      serde: Serde[F],
      topic: TopicName,
      target: Target,
      bytes: Array[Byte]
  ): F[Boolean] =
    serde
      .deserializer(topic, target)
      .flatMap(d => Deserializers.attempt(d, Nil, Some(bytes)))
      .map(_.isRight)
}
