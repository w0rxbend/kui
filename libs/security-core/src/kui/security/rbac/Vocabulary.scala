package kui.security.rbac

import scala.annotation.nowarn
import scala.util.matching.Regex

/** What a permission can be granted over.
  *
  * ADR-021 adopts Kafbat's resource list verbatim, because it is the vocabulary operators already write in
  * their configuration files and the one their existing `rbac.roles[]` blocks are expressed in. A KUI-shaped
  * renaming would buy nothing and would make every migration a translation.
  *
  * The `wire` string is the configuration and JSON spelling. It is written out per case rather than derived
  * from the case name so that renaming a case — `ConsumerGroup` reads better in Scala than `CONSUMER` — can
  * never silently change what a deployment's configuration file means.
  */
enum Resource(val wire: String) {

  /** KUI's own dynamic configuration. Not scoped to a cluster. */
  case ApplicationConfig extends Resource("APPLICATIONCONFIG")

  /** One cluster's connection settings. Scoped to a cluster, but names no resource inside it. */
  case ClusterConfig extends Resource("CLUSTERCONFIG")

  /** A topic, named by its topic name. */
  case Topic extends Resource("TOPIC")

  /** A consumer group, named by its group id. Kafbat spells it `CONSUMER`. */
  case ConsumerGroup extends Resource("CONSUMER")

  /** A Schema Registry subject, named by the subject name. */
  case Schema extends Resource("SCHEMA")

  /** A Kafka Connect cluster, named by the connect cluster's name. */
  case Connect extends Resource("CONNECT")

  /** One connector inside a connect cluster, named `"<connect>/<connector>"`. */
  case Connector extends Resource("CONNECTOR")

  /** ksqlDB. Not named. */
  case Ksql extends Resource("KSQL")

  /** Kafka ACLs. Not named. */
  case Acl extends Resource("ACL")

  /** KUI's own audit trail. Not named. */
  case Audit extends Resource("AUDIT")

  /** Kafka client quotas. Not named. */
  case ClientQuotas extends Resource("CLIENT_QUOTAS")

  /** Every action this resource has. `ALL` in a configuration file expands to exactly this set. */
  def allActions: Set[Action] = Action.values.filter(_.resource == this).toSet

  /** Whether an access to this resource names a particular one of them.
    *
    * A topic access names a topic; an audit access names nothing, because there is one audit trail. The
    * distinction was implicit until a configuration file had to be read against it: [[Permission.covers]]
    * already relies on it — a permission with no pattern matches only an unnamed access — so a `TOPIC`
    * permission with no `value` grants nothing at all. That is the correct evaluation and a terrible silence
    * at configuration time, which is why the loader now asks this question and refuses the file instead.
    */
  def isNamed: Boolean = this match {
    case Topic | ConsumerGroup | Schema | Connect | Connector => true
    case ApplicationConfig | ClusterConfig | Ksql | Acl | Audit | ClientQuotas => false
  }
}

object Resource {

  /** Parses the configuration spelling, case-insensitively as Kafbat does. */
  def fromWire(raw: String): Option[Resource] = {
    val normalised = raw.trim.toUpperCase
    values.find(_.wire == normalised)
  }

  given CanEqual[Resource, Resource] = CanEqual.derived
}

/** One thing that may be done to a resource.
  *
  * A single flat enum rather than one enum per resource. The per-resource shape reads well in isolation and
  * then forces every consumer — the evaluator, the JSON codec, the browser's gate — to match on eleven sealed
  * families instead of one; the `resource` field carries the same information and lets an action be stored in
  * a plain `Set[Action]`.
  *
  * @param resource
  *   the resource this action belongs to. `Action.MessagesRead.resource` is `Topic`, and an action can never
  *   be requested against a resource it does not belong to.
  * @param wire
  *   the configuration and JSON spelling, written out for the reason [[Resource.wire]] is.
  * @param isAlter
  *   whether this action changes the cluster. It decides two things at once: whether a read-only cluster
  *   refuses the request (ADR-047), and whether the audit level `ALTER_ONLY` records it. The two Kafbat
  *   exceptions — running a topic analysis and registering a smart filter — are modelled here as *non*-alter
  *   actions rather than as exceptions written somewhere else, which is the whole reason this is a field.
  */
enum Action(val resource: Resource, val wire: String, val isAlter: Boolean) {

  case ApplicationConfigView extends Action(Resource.ApplicationConfig, "VIEW", false)
  case ApplicationConfigEdit extends Action(Resource.ApplicationConfig, "EDIT", true)

  case ClusterConfigView extends Action(Resource.ClusterConfig, "VIEW", false)
  case ClusterConfigEdit extends Action(Resource.ClusterConfig, "EDIT", true)

  case TopicView extends Action(Resource.Topic, "VIEW", false)
  case TopicCreate extends Action(Resource.Topic, "CREATE", true)
  case TopicEdit extends Action(Resource.Topic, "EDIT", true)
  case TopicDelete extends Action(Resource.Topic, "DELETE", true)
  case TopicMessagesRead extends Action(Resource.Topic, "MESSAGES_READ", false)
  case TopicMessagesProduce extends Action(Resource.Topic, "MESSAGES_PRODUCE", true)
  case TopicMessagesDelete extends Action(Resource.Topic, "MESSAGES_DELETE", true)

  /** Reading a topic analysis. Not an alter: it computes over records and writes nothing. */
  case TopicAnalysisView extends Action(Resource.Topic, "ANALYSIS_VIEW", false)

  /** Starting a topic analysis. Kafbat's read-only filter lets this through on a read-only cluster, and
    * ADR-021 keeps that by classifying it as a read rather than by carving an exception into the filter.
    */
  case TopicAnalysisRun extends Action(Resource.Topic, "ANALYSIS_RUN", false)

  case ConsumerGroupView extends Action(Resource.ConsumerGroup, "VIEW", false)
  case ConsumerGroupDelete extends Action(Resource.ConsumerGroup, "DELETE", true)
  case ConsumerGroupResetOffsets extends Action(Resource.ConsumerGroup, "RESET_OFFSETS", true)

  case SchemaView extends Action(Resource.Schema, "VIEW", false)
  case SchemaCreate extends Action(Resource.Schema, "CREATE", true)
  case SchemaEdit extends Action(Resource.Schema, "EDIT", true)
  case SchemaDelete extends Action(Resource.Schema, "DELETE", true)

  /** Changing the registry's *global* compatibility level. It implies nothing: it is not about any one
    * subject, so being allowed to change it says nothing about being allowed to read one.
    */
  case SchemaModifyGlobalCompatibility extends Action(Resource.Schema, "MODIFY_GLOBAL_COMPATIBILITY", true)

  case ConnectView extends Action(Resource.Connect, "VIEW", false)
  case ConnectCreate extends Action(Resource.Connect, "CREATE", true)
  case ConnectEdit extends Action(Resource.Connect, "EDIT", true)
  case ConnectDelete extends Action(Resource.Connect, "DELETE", true)

  /** Restarting, pausing or resuming. Kafbat accepts the alias `RESTART` for it. */
  case ConnectOperate extends Action(Resource.Connect, "OPERATE", true)
  case ConnectResetOffsets extends Action(Resource.Connect, "RESET_OFFSETS", true)

  case ConnectorView extends Action(Resource.Connector, "VIEW", false)
  case ConnectorCreate extends Action(Resource.Connector, "CREATE", true)
  case ConnectorEdit extends Action(Resource.Connector, "EDIT", true)
  case ConnectorDelete extends Action(Resource.Connector, "DELETE", true)
  case ConnectorOperate extends Action(Resource.Connector, "OPERATE", true)
  case ConnectorResetOffsets extends Action(Resource.Connector, "RESET_OFFSETS", true)

  case KsqlExecute extends Action(Resource.Ksql, "EXECUTE", true)

  case AclView extends Action(Resource.Acl, "VIEW", false)
  case AclEdit extends Action(Resource.Acl, "EDIT", true)

  case AuditView extends Action(Resource.Audit, "VIEW", false)

  case ClientQuotasView extends Action(Resource.ClientQuotas, "VIEW", false)
  case ClientQuotasEdit extends Action(Resource.ClientQuotas, "EDIT", true)

  /** The actions granting this one also grants, one step out.
    *
    * Kafbat calls these "dependants" and unnests them recursively when it loads a role. The rule behind the
    * table is that a write on a thing you cannot see is not a coherent grant: someone allowed to delete a
    * topic must be allowed to know it is there. [[Action.closure]] is what applies it transitively.
    *
    * Note what is *not* here: `Connector.*` does not imply the same-named `Connect.*`. That relationship is
    * real, but it is a relationship between two differently-*named* resources — `payments` the connect
    * cluster and `payments/sink` the connector — so it cannot be expressed as a set of actions carried on one
    * permission. It is modelled where it belongs, as [[ResourceAccess.fallback]], and
    * [[Action.onParentConnect]] is the mapping it uses.
    */
  def directlyImplies: Set[Action] = this match {
    case ApplicationConfigView => Set.empty
    case ApplicationConfigEdit => Set(ApplicationConfigView)

    case ClusterConfigView => Set.empty
    case ClusterConfigEdit => Set(ClusterConfigView)

    case TopicView => Set.empty
    case TopicAnalysisRun => Set(TopicAnalysisView, TopicView)
    case TopicCreate | TopicEdit | TopicDelete | TopicMessagesRead | TopicMessagesProduce |
        TopicMessagesDelete | TopicAnalysisView =>
      Set(TopicView)

    case ConsumerGroupView => Set.empty
    case ConsumerGroupDelete | ConsumerGroupResetOffsets => Set(ConsumerGroupView)

    case SchemaView => Set.empty
    case SchemaModifyGlobalCompatibility => Set.empty
    case SchemaCreate | SchemaEdit | SchemaDelete => Set(SchemaView)

    case ConnectView => Set.empty
    case ConnectCreate | ConnectEdit | ConnectDelete | ConnectOperate | ConnectResetOffsets =>
      Set(ConnectView)

    case ConnectorView => Set.empty
    case ConnectorCreate | ConnectorEdit | ConnectorDelete | ConnectorOperate | ConnectorResetOffsets =>
      Set(ConnectorView)

    case KsqlExecute => Set.empty

    case AclView => Set.empty
    case AclEdit => Set(AclView)

    case AuditView => Set.empty

    case ClientQuotasView => Set.empty
    case ClientQuotasEdit => Set(ClientQuotasView)
  }

  /** The action on the *parent connect cluster* that this connector action falls back to.
    *
    * `None` for every action that is not a connector action. Kafbat's rule: someone granted `EDIT` on the
    * connect cluster `payments` may edit the connectors inside it without a second permission naming each
    * one.
    */
  def onParentConnect: Option[Action] = this match {
    case ConnectorView => Some(ConnectView)
    case ConnectorCreate => Some(ConnectCreate)
    case ConnectorEdit => Some(ConnectEdit)
    case ConnectorDelete => Some(ConnectDelete)
    case ConnectorOperate => Some(ConnectOperate)
    case ConnectorResetOffsets => Some(ConnectResetOffsets)
    case _ => None
  }
}

object Action {

  /** The alias Kafbat accepts in a configuration file for [[Action.ConnectOperate]]. */
  val ConnectRestartAlias: String = "RESTART"

  /** The literal a configuration file uses to mean "every action this resource has". */
  val AllWire: String = "ALL"

  given CanEqual[Action, Action] = CanEqual.derived

  /** Parses one action name against one resource, case-insensitively as Kafbat does.
    *
    * It is scoped to a resource because the names are not unique on their own: `VIEW` means eleven different
    * things, and an action parsed without a resource would be a permission granted over the wrong one.
    */
  def fromWire(resource: Resource, raw: String): Option[Action] = {
    val normalised = raw.trim.toUpperCase
    val aliased =
      if resource == Resource.Connect && normalised == ConnectRestartAlias then ConnectOperate.wire
      else normalised

    values.find(action => action.resource == resource && action.wire == aliased)
  }

  /** Every action reachable from `actions` by following [[Action.directlyImplies]] to exhaustion.
    *
    * This runs once, when a policy is built, so that the evaluator on the hot path is a set membership test
    * rather than a graph walk — and so that the permission list the browser is handed over `/auth/me` is
    * already expanded and cannot be expanded differently there.
    *
    * It is a fixpoint rather than a single pass because the graph is two deep in one place already
    * (`ANALYSIS_RUN → ANALYSIS_VIEW → VIEW`), and a single pass would quietly drop the third step the day
    * somebody adds a fourth.
    */
  def closure(actions: Set[Action]): Set[Action] = {
    @annotation.tailrec
    def grow(current: Set[Action]): Set[Action] = {
      val next = current ++ current.flatMap(_.directlyImplies)
      if next.size == current.size then current else grow(next)
    }

    grow(actions)
  }
}

/** A resource-name pattern from a role's `value`, compiled once.
  *
  * Compiled once because a regular expression compiled per request is both slower and a place where a
  * malformed pattern surfaces at 3am instead of at start-up. A permission with no pattern matches only an
  * *unnamed* access — `ACL`, `AUDIT`, `KSQL` — which is Kafbat's rule and is why this is an `Option` on
  * [[Permission]] rather than a pattern that defaults to `.*`.
  *
  * The match is a full match, not a search: `orders` does not grant `orders-dlq`.
  */
final class ResourcePattern private (val raw: String, private val compiled: Regex) {

  def matches(name: String): Boolean = compiled.matches(name)

  override def toString: String = s"ResourcePattern($raw)"

  /** Two patterns are the same pattern when they were written the same way. A compiled `Regex` has no useful
    * equality of its own, so the source text is what stands in for it.
    */
  // `equals` is handed an `Any` by `AnyRef`, and Scala 3 warns when an `Any` is the selector of a match:
  // a value that is not `Matchable` may be an opaque type whose runtime shape is not its static one. There
  // is nowhere else to do the narrowing, and the alternative the compiler leaves — `asInstanceOf` — is what
  // `DisableSyntax` forbids and what this code used to do. Suppressed once, here, with the same message
  // filter `TopicsRoutes` uses for the same reason.
  @nowarn("msg=unmatchable type Any")
  override def equals(other: Any): Boolean =
    other match {
      case that: ResourcePattern => that.raw == raw
      case _ => false
    }

  override def hashCode: Int = raw.hashCode
}

object ResourcePattern {

  given CanEqual[ResourcePattern, ResourcePattern] = CanEqual.derived

  /** The pattern that matches every name. Built here rather than through [[compile]] so that the one pattern
    * KUI itself depends on cannot be a runtime failure.
    */
  val Everything: ResourcePattern = new ResourcePattern(".*", ".*".r)

  /** Compiles a pattern, or says why it will not compile.
    *
    * The failure is a `String` rather than an exception because this is called while reading a configuration
    * file, and the caller's job is to collect every bad pattern and print them together — not to die on the
    * first one.
    */
  def compile(raw: String): Either[String, ResourcePattern] =
    try Right(new ResourcePattern(raw, new Regex(raw)))
    catch {
      case failure: IllegalArgumentException =>
        Left(s"'$raw' is not a valid regular expression: ${Option(failure.getMessage).getOrElse("")}")
    }

  /** Patterns whose shape can take exponential time on a non-matching input.
    *
    * A cheap syntactic check, not a decision procedure: it looks for a quantifier applied to a group that
    * itself ends in a quantifier — `(a+)+`, `(a*)*`, `(a|a)*` — which is the shape behind essentially every
    * reported regular-expression denial of service. It returns warnings rather than refusing the pattern,
    * because the operator who wrote it is the only one who can say whether it was deliberate, and because a
    * false positive that refused start-up would be worse than the risk it guards.
    */
  def backtrackingWarnings(raw: String): List[String] =
    if NestedQuantifier.findFirstIn(raw).isDefined then
      List(
        s"'$raw' nests one quantifier inside another, which can take exponential time to fail to match; " +
          "consider rewriting it, for example 'orders-.*' rather than '(orders-)+.*'"
      )
    else List.empty

  /** `(`, anything, a quantifier, `)`, a quantifier. Deliberately crude — see the caller's comment. */
  private val NestedQuantifier: Regex = """\((?:[^()]*[*+])\)[*+{]""".r
}
