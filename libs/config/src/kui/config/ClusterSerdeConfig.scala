package kui.config

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.Try
import scala.util.matching.Regex

import cats.syntax.all.*

import kui.kernel.serde.SerdeName

/** One `kui.clusters.<n>.serde.patterns[]` entry: a serde, and the topics it reads.
  *
  * A single entry can name a pattern for keys, one for values, or both, because the two halves of a record
  * are routinely written by different serializers — a string key beside an Avro value is the commonest
  * arrangement there is.
  *
  * The regular expression is anchored by `Regex.matches`, which is what `SerdeResolution` calls: `orders.*`
  * matches `orders.v1` and does not match `legacy.orders.v1`. That is the same anchoring Kafbat applies to
  * the same setting, so a migrated configuration file keeps meaning what it meant (ADR-028).
  */
final case class SerdePatternConfig(
    serde: SerdeName,
    topicKeysPattern: Option[Regex],
    topicValuesPattern: Option[Regex]
)

object SerdePatternConfig {

  /** A pattern entry that selects nothing is a typo, not a preference.
    *
    * An operator who wrote a `serde:` and neither pattern has configured a rule that can never fire, and the
    * symptom — records that decode through some other serde entirely — looks like a KUI bug rather than like
    * a missing line in their file.
    */
  def validate(index: Int, entry: SerdePatternConfig): Either[String, SerdePatternConfig] =
    if entry.topicKeysPattern.isEmpty && entry.topicValuesPattern.isEmpty then
      Left(
        s"entry $index names the serde '${entry.serde.value}' and neither topicKeysPattern nor " +
          "topicValuesPattern, so it can never select anything; set at least one"
      )
    else Right(entry)
}

/** Which serde reads which topic on one cluster, and how much of the registry's answers to remember.
  *
  * This is SD-003: the configuration slice that turns `SerdeResolution.Rules` — built, tested and until now
  * fed nothing but `Rules.empty` — into something an operator can actually write down. "This cluster is
  * Avro" is one line:
  *
  * {{{
  * kui:
  *   clusters:
  *     - name: "Production"
  *       bootstrapServers: ["kafka:9092"]
  *       schemaRegistry:
  *         url: ["http://schema-registry:8081"]
  *       serde:
  *         defaultValue: SchemaRegistry
  * }}}
  *
  * Nothing here is required. A cluster with no `serde` section resolves exactly as it did before this type
  * existed: auto-detection first, then `String`, then the fallback.
  *
  * ==Why the two cache knobs live here and not under `schemaRegistry`==
  *
  * `kui.clusters.<n>.schemaRegistry` describes the registry as a *service*: where it is, who KUI is, how
  * long a call may take. These two describe what the decoder keeps in memory, which is a property of the
  * reader and not of the registry — the schema service reads the same registry and caches nothing of the
  * kind. Putting them under the section that owns the caches is what keeps a future second reader from
  * inheriting a limit that was tuned for message browsing.
  */
final case class ClusterSerdeConfig(
    defaultKey: Option[SerdeName] = None,
    defaultValue: Option[SerdeName] = None,
    patterns: List[SerdePatternConfig] = Nil,
    schemaCacheSize: Long = ClusterSerdeConfig.DefaultSchemaCacheSize,
    subjectCacheTtl: FiniteDuration = ClusterSerdeConfig.DefaultSubjectCacheTtl
)

object ClusterSerdeConfig {

  /** How many schemas to hold by id.
    *
    * A registry never reissues a schema id, so a cached schema can never be wrong — only unused. That is why
    * this cache is bounded by size and never expires, and why the default is generous: a thousand schemas is
    * far more than a cluster's browse traffic touches, and each is a few kilobytes of text.
    */
  val DefaultSchemaCacheSize: Long = 1000L
  val MinSchemaCacheSize: Long = 1L
  val MaxSchemaCacheSize: Long = 100000L

  /** How long the *latest* version of a subject may be reused.
    *
    * This one must expire, and that asymmetry is the whole reason there are two caches. Registering a new
    * version is how a schema evolves; a KUI that cached "latest" forever would keep validating produced
    * records against a schema the topic has moved on from, and would show an operator a schema panel that
    * disagreed with their registry's own screen.
    */
  val DefaultSubjectCacheTtl: FiniteDuration = 30.seconds
  val MinSubjectCacheTtl: FiniteDuration = 1.second
  val MaxSubjectCacheTtl: FiniteDuration = 10.minutes

  /** A cluster that configures no serde section at all.
    *
    * Declared *after* the two defaults above and not before them, which is not a style choice: a `val` in an
    * object is initialised in source order, so an `empty` written above them would capture a zero cache size
    * and a null TTL. The suite that caught this asserts the values rather than only the absence of patterns,
    * which is the only way that mistake is visible.
    */
  val empty: ClusterSerdeConfig = ClusterSerdeConfig()

  /** Reads a serde name, refusing one this build cannot possibly provide.
    *
    * The check is against the names KUI knows rather than against the names this *cluster* has, because a
    * cluster's serde set is not assembled until the message service starts and the loader must be able to
    * refuse `defaultValue: Avro` — a plausible spelling that no KUI has ever had — while the operator is
    * still looking at their file.
    */
  def readSerdeName(raw: String): Either[String, SerdeName] =
    SerdeName.fromString(raw.trim).flatMap { name =>
      if Known.contains(name) then Right(name)
      else
        Left(
          s"'$raw' is not a serde KUI knows; the names are " +
            Known.map(_.value).mkString(", ")
        )
    }

  /** Every serde name a KUI build can offer, in the spelling configuration uses.
    *
    * `Fallback` is deliberately absent. It is where resolution *ends*, not something an operator selects, and
    * naming it as a default would ask for the one serde that cannot fail and therefore cannot be wrong —
    * which is another way of switching decoding off without saying so.
    */
  val Known: List[SerdeName] = List(
    SerdeName.String,
    SerdeName.Int32,
    SerdeName.Int64,
    SerdeName.UInt32,
    SerdeName.UInt64,
    SerdeName.Uuid,
    SerdeName.Base64,
    SerdeName.Hex,
    SerdeName.Json,
    SerdeName.SchemaRegistry
  )

  /** Compiles a topic pattern, naming the offending expression rather than Java's parser noise. */
  def readPattern(raw: String): Either[String, Regex] =
    Try(raw.trim.r).toEither.leftMap(failure =>
      s"'$raw' is not a valid regular expression: ${Option(failure.getMessage).getOrElse("no detail")}"
    )

  given CanEqual[ClusterSerdeConfig, ClusterSerdeConfig] = CanEqual.derived
}
