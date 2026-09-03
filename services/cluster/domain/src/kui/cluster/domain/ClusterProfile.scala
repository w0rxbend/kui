package kui.cluster.domain

import cats.data.{NonEmptyList, Validated, ValidatedNel}
import cats.syntax.all.*

import kui.kernel.cluster.{
  AdminTuning,
  BootstrapServers,
  ClientProperties,
  ClusterConnection,
  ClusterSecurity
}
import kui.kernel.error.{DomainError, FieldError}
import kui.kernel.{ClusterId, ValidationError}

/** The optimistic-concurrency version of one stored profile.
  *
  * `0` means "this profile has never been written to the metadata store": it came from static configuration
  * only. The store's own record version is mapped onto this by the infrastructure adapter; the domain
  * deliberately does not know that a version is a Kafka offset or a counter, only that it increases and that
  * a stale one loses a write.
  */
opaque type ProfileVersion = Long

object ProfileVersion {

  /** The version of a profile that exists only in this process's configuration file. */
  val Static: ProfileVersion = 0L

  def from(raw: Long): Either[ValidationError, ProfileVersion] =
    if raw >= 0L then Right(raw)
    else Left(ValidationError.Range("version", Some("0"), None, raw.toString))

  def unsafe(raw: Long): ProfileVersion = raw

  extension (v: ProfileVersion) {
    def value: Long = v
    def next: ProfileVersion = v + 1L
    def isStatic: Boolean = v == 0L
  }

  given Ordering[ProfileVersion] = Ordering.Long
  given CanEqual[ProfileVersion, ProfileVersion] = CanEqual.derived
}

/** Where a profile's field values came from.
  *
  * The UI shows it — a stored cluster can be edited in M8, a statically configured one cannot — and the
  * registry's precedence rule is stated in terms of it.
  */
enum ProfileOrigin {

  /** Only this process's configuration describes this cluster. */
  case Static

  /** Only a `cluster/<id>` record in the metadata store describes it. */
  case Stored

  /** Both do, and the stored record won, in full. */
  case StaticThenStored
}

object ProfileOrigin {
  given CanEqual[ProfileOrigin, ProfileOrigin] = CanEqual.derived
}

/** The colour an operator assigned to a cluster so that production and staging do not look alike in the
  * switcher.
  *
  * A closed set, not a free CSS colour: an arbitrary string here would be user-controlled text interpolated
  * into a stylesheet, and the design system has a fixed palette anyway.
  */
enum ColourTag {
  case Slate, Blue, Green, Amber, Red, Violet, Teal

  /** The lowercase name used as a CSS token suffix, and as the wire value. */
  def token: String = toString.toLowerCase(java.util.Locale.ROOT)
}

object ColourTag {

  /** Every colour's token, in declaration order, for an error message that lists the alternatives. */
  val tokens: List[String] = values.toList.map(_.token)

  def from(raw: String): Either[ValidationError, ColourTag] = {
    val lowered = raw.trim.toLowerCase(java.util.Locale.ROOT)

    values.find(_.token == lowered) match {
      case Some(colour) => Right(colour)
      case None =>
        Left(ValidationError.Format("colour", s"one of ${tokens.mkString(", ")}", raw))
    }
  }

  given CanEqual[ColourTag, ColourTag] = CanEqual.derived
}

/** Everything KUI needs in order to talk to one configured Kafka cluster.
  *
  * The private constructor plus `from` is not decoration: a profile with a blank display name or a forbidden
  * property override would otherwise fail at the point a Kafka client is built, which is inside an adapter,
  * inside a refresh loop, on a background fiber — the furthest possible place from the operator who typed it.
  *
  * `toString` is overridden so that a profile on a log line prints its identity and shape and never its
  * connection settings. Every secret-bearing field is a `Secret` and would redact itself, but a future field
  * that is not would leak silently, and the generated rendering is a wall of text nobody reads.
  */
final case class ClusterProfile private (
    id: ClusterId,
    displayName: String,
    bootstrap: BootstrapServers,
    security: ClusterSecurity,
    properties: ClientProperties,
    admin: AdminTuning,
    readOnly: Boolean,
    colour: Option[ColourTag],
    version: ProfileVersion,
    origin: ProfileOrigin
) {

  /** The cheap identity of this profile, for logs, map keys and list rows. */
  def ref: ClusterRef = ClusterRef(id, displayName)

  /** `displayName` when the operator set one, else the id's own text. Error messages must name a cluster the
    * way the operator wrote it.
    */
  def label: String = if displayName.isEmpty then id.value else displayName

  /** Everything a Kafka client needs, in the one shape `libs/kafka` takes.
    *
    * The four connection fields are held separately rather than as a nested `ClusterConnection` because
    * `from` validates them individually and the store encodes them individually; this assembles them on
    * demand so that no adapter has to know the order.
    */
  def connection: ClusterConnection =
    ClusterConnection(id, bootstrap, security, properties, admin)

  /** The same profile at a new version and origin, for the registry's overlay and for a store write's
    * read-back. It exists because `copy` is private alongside the constructor, and re-running `from` for a
    * value that is already valid would be a second place the rules could drift.
    */
  def at(version: ProfileVersion, origin: ProfileOrigin): ClusterProfile =
    copy(version = version, origin = origin)

  override def toString: String =
    s"ClusterProfile(${id.value}, $displayName, origin=$origin, version=${version.value})"
}

object ClusterProfile {

  val MaxDisplayNameLength: Int = 128

  /** Property keys KUI renders itself and an operator may not override.
    *
    * `sasl.jaas.config` is the one that matters: setting it here silently replaces the JAAS string KUI
    * assembled, defeating the quoting and escaping that exists to stop a password with a quote in it from
    * becoming an injection. The other four make the profile lie about which cluster it addresses and break
    * the per-client attribution KUI relies on to tell two clusters' admin calls apart.
    */
  val ReservedPropertyKeys: Set[String] =
    Set("bootstrap.servers", "client.id", "security.protocol", "sasl.mechanism", "sasl.jaas.config")

  /** Builds a profile, accumulating **every** violation.
    *
    * Accumulation rather than fail-fast because the caller is either the startup configuration validator —
    * whose requirement is that an unknown key, a missing secret and an invalid URL are reported together, in
    * one message — or a form whose fields must all light up at once.
    */
  def from(
      id: ClusterId,
      displayName: String,
      bootstrap: BootstrapServers,
      security: ClusterSecurity,
      properties: ClientProperties,
      admin: AdminTuning,
      readOnly: Boolean,
      colour: Option[String],
      version: ProfileVersion,
      origin: ProfileOrigin
  ): Either[DomainError, ClusterProfile] = {
    val checked =
      (
        validateDisplayName(displayName),
        validateColour(colour),
        validateProperties(properties),
        validateAdmin(admin)
      ).mapN { (name, tag, props, tuning) =>
        ClusterProfile(id, name, bootstrap, security, props, tuning, readOnly, tag, version, origin)
      }

    checked.toEither.leftMap { failures =>
      DomainError.InvariantViolation(
        s"cluster '${id.value}' is not configured correctly",
        failures.toList
      )
    }
  }

  private def validateDisplayName(raw: String): ValidatedNel[FieldError, String] = {
    val trimmed = raw.trim

    val length =
      if trimmed.nonEmpty && trimmed.length <= MaxDisplayNameLength then Validated.validNel(trimmed)
      else invalid("displayName", s"must be 1 to $MaxDisplayNameLength non-blank characters")

    val control =
      if trimmed.exists(isControl) then invalid("displayName", "must not contain control characters")
      else Validated.validNel(trimmed)

    // Both checks run and both can contribute, but the value carried forward is the trimmed name.
    (length, control).mapN((name, _) => name)
  }

  private def isControl(c: Char): Boolean = c < ' ' || c == '\u007f'

  private def validateColour(raw: Option[String]): ValidatedNel[FieldError, Option[ColourTag]] =
    raw match {
      case None => Validated.validNel(None)
      case Some(text) =>
        ColourTag.from(text) match {
          case Right(tag) => Validated.validNel(Some(tag))
          case Left(error) => Validated.invalidNel(FieldError.fromValidation(error))
        }
    }

  private def validateProperties(
      properties: ClientProperties
  ): ValidatedNel[FieldError, ClientProperties] = {
    val blank = properties.keys.filter(_.trim.isEmpty)

    val reserved = properties.keys
      .filter(key => ReservedPropertyKeys.contains(key.trim.toLowerCase(java.util.Locale.ROOT)))
      .toList
      .sorted

    val blankCheck =
      if blank.isEmpty then Validated.validNel(())
      else invalid("properties", "property names must not be blank")

    val reservedCheck =
      if reserved.isEmpty then Validated.validNel(())
      else
        Validated.invalid(
          NonEmptyList.fromListUnsafe(
            reserved.map { key =>
              FieldError.of(
                "properties",
                s"'$key' is rendered by KUI and cannot be overridden; " +
                  "change the cluster's security settings instead"
              )
            }
          )
        )

    (blankCheck, reservedCheck).mapN((_, _) => properties)
  }

  private def validateAdmin(admin: AdminTuning): ValidatedNel[FieldError, AdminTuning] =
    admin.validate match {
      case Right(valid) => Validated.validNel(valid)
      case Left(errors) => Validated.invalid(errors.map(FieldError.fromValidation))
    }

  private def invalid[A](field: String, restriction: String): ValidatedNel[FieldError, A] =
    Validated.invalidNel(FieldError.of(field, restriction))

  given CanEqual[ClusterProfile, ClusterProfile] = CanEqual.derived
}
