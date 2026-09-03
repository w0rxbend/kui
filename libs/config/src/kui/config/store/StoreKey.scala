package kui.config.store

/** The sections of `__kui_config` this KUI knows about.
  *
  * `Other` exists instead of a parse failure because the store is a compacted log that several KUI versions
  * may read at once. A newer KUI writing `connect/prod` must not stop an older one's replay: the older one
  * carries the record along as an opaque section it does not model, and every section it *does* model keeps
  * working. A parse failure here would turn a forward-compatible addition into an outage.
  */
enum StoreSection(val name: String) {
  case Cluster extends StoreSection("cluster")
  case Settings extends StoreSection("settings")
  case Rbac extends StoreSection("rbac")
  case Masking extends StoreSection("masking")
  case File extends StoreSection("file")
  case Other(raw: String) extends StoreSection(raw)
}

object StoreSection {

  /** The known sections, in declaration order, excluding the open `Other` case. */
  private val known: List[StoreSection] =
    List(
      StoreSection.Cluster,
      StoreSection.Settings,
      StoreSection.Rbac,
      StoreSection.Masking,
      StoreSection.File
    )

  def fromName(raw: String): StoreSection =
    known.find(_.name == raw).getOrElse(StoreSection.Other(raw))

  given CanEqual[StoreSection, StoreSection] = CanEqual.derived
}

/** A record key: `<section>/<id>`, for example `cluster/prod-eu`, `settings/global`, `rbac/roles`.
  *
  * Exactly two segments. Not three: a deeper hierarchy invites a "list everything under x/y/z" query, which a
  * compacted topic replayed into a `Map` answers no faster than a prefix scan does, and every extra segment
  * is one more thing two writers can disagree about.
  */
final case class StoreKey(section: StoreSection, id: String) {
  def render: String = s"${section.name}/$id"
  override def toString: String = render
}

object StoreKey {

  /** What an id segment may look like: a lowercase slug, 1 to 128 characters, never starting or ending with
    * punctuation. It matches `ClusterId`'s slug rule so that a cluster's configured id is its store id
    * without a second normalisation step.
    */
  val IdPattern: String = "^[a-z0-9]([a-z0-9-_.]{0,126}[a-z0-9])?$"

  /** The longest key KUI writes. Kafka itself allows far more; the limit exists so that a key stays readable
    * in a console-consumer dump and in a log line.
    */
  val MaxLength: Int = 255

  private val idRegex = IdPattern.r

  def parse(raw: String): Either[StoreError, StoreKey] =
    if raw.length > MaxLength then
      Left(
        StoreError.InvalidKey(raw, s"a key may be at most $MaxLength characters, this one is ${raw.length}")
      )
    else
      raw.split("/", -1).toList match {
        case section :: id :: Nil =>
          if section.isEmpty then Left(StoreError.InvalidKey(raw, "the section segment is empty"))
          else if idRegex.matches(id) then Right(StoreKey(StoreSection.fromName(section), id))
          else Left(StoreError.InvalidKey(raw, s"the id segment '$id' does not match $IdPattern"))
        case segments =>
          Left(
            StoreError.InvalidKey(
              raw,
              s"a key is exactly two '/'-separated segments, this one has ${segments.length}"
            )
          )
      }

  def cluster(clusterId: String): Either[StoreError, StoreKey] =
    parse(s"${StoreSection.Cluster.name}/$clusterId")

  val SettingsGlobal: StoreKey = StoreKey(StoreSection.Settings, "global")

  val RbacRoles: StoreKey = StoreKey(StoreSection.Rbac, "roles")

  given CanEqual[StoreKey, StoreKey] = CanEqual.derived
}
