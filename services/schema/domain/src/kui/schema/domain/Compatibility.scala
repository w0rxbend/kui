package kui.schema.domain

/** How strictly a subject's next version has to fit the versions before it.
  *
  * These seven values are the registry's, not KUI's, and the wire spellings are exactly what the registry
  * sends and accepts. They are written out rather than derived from the case names so that renaming a case
  * cannot silently change what KUI puts on the wire — a rename that turned `BackwardTransitive` into
  * `BACKWARD_TRANSITIVE_2` would be accepted by the compiler and refused by every registry.
  *
  * What each one means, because the names are not self-explanatory and getting this wrong loosens a
  * production safety net:
  *
  *   - `Backward` — a **reader** using the new schema can read data written with the *previous* version. This
  *     is the registry's own default, and it is the one to want when consumers are upgraded before producers.
  *   - `Forward` — a reader using the *previous* schema can read data written with the new one; producers
  *     first.
  *   - `Full` — both directions.
  *   - the three `*Transitive` variants check against **every** earlier version rather than only the previous
  *     one. That is the difference between "you may not break the last release" and "you may not break
  *     anything still on the topic", and a topic with a long retention wants the second.
  *   - `None` — the registry checks nothing. It is a legitimate setting and a dangerous one, which is why the
  *     screen has to be able to display it rather than treating it as "unset".
  */
enum CompatibilityLevel {
  case Backward, BackwardTransitive, Forward, ForwardTransitive, Full, FullTransitive, None

  /** The registry's own spelling. */
  def wire: String = this match {
    case Backward => "BACKWARD"
    case BackwardTransitive => "BACKWARD_TRANSITIVE"
    case Forward => "FORWARD"
    case ForwardTransitive => "FORWARD_TRANSITIVE"
    case Full => "FULL"
    case FullTransitive => "FULL_TRANSITIVE"
    case None => "NONE"
  }
}

object CompatibilityLevel {

  /** The registry's default when a subject has no level of its own and the global level has never been set.
    */
  val RegistryDefault: CompatibilityLevel = Backward

  def fromWire(raw: String): Option[CompatibilityLevel] =
    values.find(_.wire == raw.trim.toUpperCase)

  given CanEqual[CompatibilityLevel, CompatibilityLevel] = CanEqual.derived
}

/** A subject's compatibility level, and where it came from.
  *
  * The distinction is the point. A registry answers `404` for a subject with no level of its own, meaning
  * "this subject follows the global setting" — and a screen that turned that 404 into a blank, or into
  * `BACKWARD` with no explanation, would tell an operator two different lies. The first hides that the
  * subject is governed elsewhere; the second invites them to "confirm" a level that is not actually set here,
  * which writes an override they never intended.
  */
final case class SubjectCompatibility(level: CompatibilityLevel, inheritedFromGlobal: Boolean)

object SubjectCompatibility {

  /** The subject has its own level. */
  def own(level: CompatibilityLevel): SubjectCompatibility = SubjectCompatibility(level, false)

  /** The subject has none, and follows the global one. */
  def inherited(level: CompatibilityLevel): SubjectCompatibility = SubjectCompatibility(level, true)

  given CanEqual[SubjectCompatibility, SubjectCompatibility] = CanEqual.derived
}

/** What the registry said about a proposed schema.
  *
  * `messages` carries the registry's own explanations — "reader field 'total' is missing a default value" and
  * the like. They are the whole value of the check to whoever is about to register the schema, so they are
  * passed through rather than summarised, and they are the registry's words rather than KUI's because KUI
  * cannot re-derive them without reimplementing the compatibility rules of three schema languages.
  *
  * An empty `messages` on an incompatible verdict is normal: older registries answer the check with a bare
  * `{"is_compatible": false}` unless asked for verbose output, and a screen has to be able to say "the
  * registry says no and gave no reason" rather than pretending it said nothing at all.
  */
final case class CompatibilityVerdict(compatible: Boolean, messages: List[String])

object CompatibilityVerdict {

  val compatible: CompatibilityVerdict = CompatibilityVerdict(true, Nil)

  given CanEqual[CompatibilityVerdict, CompatibilityVerdict] = CanEqual.derived
}
