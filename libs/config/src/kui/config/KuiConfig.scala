package kui.config

import cats.data.NonEmptyList

/** Where one configuration value came from.
  *
  * It is reported with every problem because "port must be between 1 and 65535" is only half an answer: the
  * operator also has to know whether to fix the YAML file, the environment variable or the command line, and
  * those three can easily disagree.
  */
enum ConfigSourceName {
  case Cli
  case Env
  case File(path: String)
  case Default

  def label: String = this match {
    case Cli => "command line"
    case Env => "environment"
    case File(path) => s"file: $path"
    case Default => "default"
  }
}

object ConfigSourceName {
  given CanEqual[ConfigSourceName, ConfigSourceName] = CanEqual.derived
}

/** One thing wrong with the configuration.
  *
  * @param key
  *   the full dotted key, e.g. `kui.server.port`, so the operator can search for it
  * @param problem
  *   what was expected and what was found. When the key holds a secret the found value is replaced by `***`,
  *   so that printing the startup error to a terminal or a CI log never leaks it.
  * @param source
  *   which layer supplied the offending value
  */
final case class ConfigProblem(key: String, problem: String, source: ConfigSourceName) {

  /** One line, ready to print to standard error. */
  def render: String = s"$key: $problem   (${source.label})"
}

object ConfigProblem {
  given CanEqual[ConfigProblem, ConfigProblem] = CanEqual.derived
}

/** Every problem found in one load, never just the first.
  *
  * Reporting them all at once is the whole point of the task: an operator who fixes one key, restarts, and
  * finds a second error has paid the restart cost twice for no reason.
  */
final case class ConfigErrors(problems: NonEmptyList[ConfigProblem]) {

  /** One problem per line, in key order, ready to print to standard error before `exit(1)`. */
  def render: String = problems.toList.sortBy(_.key).map(_.render).mkString("\n")

  def keys: List[String] = problems.toList.map(_.key)
}

object ConfigErrors {
  def of(first: ConfigProblem, rest: ConfigProblem*): ConfigErrors =
    ConfigErrors(NonEmptyList.of(first, rest*))

  given CanEqual[ConfigErrors, ConfigErrors] = CanEqual.derived
}

/** The whole of KUI's static configuration, as one immutable value.
  *
  * `kui.rbac` is recognised — an operator may already have it in a file without the load failing — but
  * nothing is read out of it yet:
  *
  *   - `kui.clusters[]` is real as of **M1**: it is the static base of the cluster registry. The metadata
  *     store's records overlay it at runtime (ADR-036 as amended by ADR-042); the precedence chain built here
  *     stays as it is and the store is inserted as one more layer above the file.
  *   - `kui.rbac` becomes a real section in **M6**, with the authorization model.
  *
  * `clusters` is `Nil` when nothing is configured, and that is a supported deployment rather than a startup
  * failure: it is what an operator sees before they have registered anything, and the dashboard shows its "no
  * clusters configured" empty state.
  *
  * `kui.auth` is recognised too, and the only legal value in M0 is `type: disabled`; anything else is refused
  * with a message pointing at M6, rather than silently accepted and then ignored.
  */
final case class KuiConfig(
    server: ServerConfig,
    gateway: GatewayConfig,
    telemetry: TelemetryConfig,
    store: StoreConfig,
    topics: TopicsConfig,
    clusters: List[ClusterConfig]
)

object KuiConfig {

  /** What a process gets when nothing at all is configured. Every field here is also the default used per
    * key, so configuring one key never changes another.
    */
  val Default: KuiConfig =
    KuiConfig(
      ServerConfig.Default,
      GatewayConfig.Default,
      TelemetryConfig.Default,
      StoreConfig.Default,
      TopicsConfig.Default,
      clusters = Nil
    )

  given CanEqual[KuiConfig, KuiConfig] = CanEqual.derived
}
