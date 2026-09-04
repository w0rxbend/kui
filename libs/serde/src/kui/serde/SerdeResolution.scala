package kui.serde

import scala.util.matching.Regex

import kui.kernel.TopicName
import kui.kernel.error.{ApplicationError, KuiError}

/** Which serde reads a topic, as a pure function.
  *
  * It is a pure function of the rules and the available names rather than four `if`s inside an effect,
  * because the order is the thing operators most often get wrong and a table test is the only way to state it
  * unambiguously.
  *
  * ## The order
  *
  *   1. **What the user explicitly asked for.** They picked it from the picker; nothing overrides that. An
  *      explicit name that is not configured is an error, never a silent fall-through — they asked for
  *      something specific and got something else without being told, which is how a user concludes their
  *      data is wrong.
  *   2. **The first matching pattern, in configuration order.** First match wins, and "first" is the order
  *      the operator wrote, not the iteration order of a map. That distinction is the classic silent
  *      difference between a developer's machine and production.
  *   3. **The cluster's default for this target**, if one is configured.
  *   4. **`String`**, which every deployment has.
  *
  * Step 4 is why this function never fails without an explicit name: there is always an answer. And behind
  * even that, `FallbackSerde` catches a `String` decode that fails, so there is always a rendered record.
  *
  * ADR-028 takes this order from Kafbat so that a migrating operator's configuration file keeps meaning what
  * it meant.
  */
object SerdeResolution {

  /** One `topicKeysPattern` or `topicValuesPattern` entry, resolved to the serde it selects. */
  final case class PatternRule(pattern: Regex, serde: SerdeName, target: Target)

  final case class Rules(
      patterns: List[PatternRule],
      defaultKey: Option[SerdeName],
      defaultValue: Option[SerdeName]
  )

  object Rules {
    val empty: Rules = Rules(Nil, None, None)
  }

  def resolve(
      rules: Rules,
      available: Set[SerdeName],
      topic: TopicName,
      target: Target,
      explicit: Option[SerdeName]
  ): Either[KuiError, SerdeName] =
    explicit match {
      case Some(name) if available.contains(name) => Right(name)
      case Some(name) =>
        Left(
          ApplicationError.Unsupported(
            s"the serde '${name.value}' is not configured on this cluster"
          )
        )
      case None =>
        Right(
          firstMatchingPattern(rules, topic, target)
            .filter(available.contains)
            // Each step is filtered on its own, so an unavailable pattern match falls through to the
            // *cluster default* rather than skipping straight to `String`. The operator configured both;
            // honouring the second when the first cannot work is closer to what they asked for than
            // ignoring their configuration entirely.
            .orElse(defaultFor(rules, target).filter(available.contains))
            // A configured serde that is not available right now — a Schema-Registry serde whose registry is
            // down — falls through to `String` rather than failing the request. The browse continues, the
            // records render through the fallback, and each row carries the marker saying the decode fell
            // back, which is ADR-035's contract and exit criterion 8. Asking for it *explicitly* is the
            // case that gets an error: then the user made a choice and deserves to be told it cannot be
            // honoured, before the stream starts rather than as a screen of mojibake.
            .getOrElse(SerdeName.String)
        )
    }

  /** What the *configuration* selects for this topic and target, ignoring whether it can work now.
    *
    * [[resolve]] deliberately filters each step by availability, so a Schema-Registry serde whose registry is
    * down disappears from its answer and the browse falls through to `String`. That is the right behaviour
    * and it destroys the one fact the reader of a mojibake row needs: that a serde *was* configured for this
    * topic and could not be used. This function recovers that fact, so the caller can put the reason on the
    * record instead of leaving the row to say "the payload is not valid UTF-8" — true of the fallback's
    * attempt, and a description of the wrong problem.
    *
    * `None` when the operator configured nothing for this topic, in which case falling through to `String` is
    * not a degradation and there is nothing to report.
    */
  def configuredFor(rules: Rules, topic: TopicName, target: Target): Option[SerdeName] =
    firstMatchingPattern(rules, topic, target).orElse(defaultFor(rules, target))

  /** The first pattern that matches, in the order the operator wrote them. */
  private def firstMatchingPattern(rules: Rules, topic: TopicName, target: Target): Option[SerdeName] =
    rules.patterns
      .find(rule => rule.target == target && rule.pattern.matches(topic.value))
      .map(_.serde)

  private def defaultFor(rules: Rules, target: Target): Option[SerdeName] =
    target match {
      case Target.Key => rules.defaultKey
      case Target.Value => rules.defaultValue
    }
}
