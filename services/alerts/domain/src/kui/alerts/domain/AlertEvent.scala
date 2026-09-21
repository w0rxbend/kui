package kui.alerts.domain

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

/** What an open event is *about*, as an equality.
  *
  * Two evaluation passes over the same unchanged cluster must produce one event and not two, and this is the
  * comparison that decides it: same rule, same subject, same event. Without it a rule evaluated every minute
  * would open one event per minute, and a feed whose whole promise is an open count would be counting how
  * long KUI had been running.
  *
  * `subject` is empty for a rule whose subject is the cluster itself — there is one offline-partition count
  * per cluster, not one per partition. That is a deliberate loss of detail: an operator with fourteen offline
  * partitions needs one row saying fourteen, not fourteen rows.
  */
final case class AlertKey(rule: AlertRule, subject: String)

object AlertKey {

  /** The key of a rule that speaks for the whole cluster. */
  def cluster(rule: AlertRule): AlertKey = AlertKey(rule, "")

  given CanEqual[AlertKey, AlertKey] = CanEqual.derived
}

/** An event's identity on the wire and in a URL.
  *
  * Derived from the key and the opening instant rather than randomly generated, and both halves of that are
  * load bearing. Derived, so that [[AlertRules.evaluate]] stays a pure function that a suite can call twice
  * and compare: an id from a random source would make every assertion about an opened event an assertion
  * about a stub. From the *opening instant* as well as the key, so that a condition that clears and returns
  * is a second event with its own age rather than the first one silently reopening — which is what an
  * operator means when they ask "how long has this been going on".
  *
  * A short subject already made only of `[A-Za-z0-9._-]` keeps the original readable representation. Anything
  * else carries a readable prefix and a digest of the full subject, because replacement alone is not an
  * encoding: `/a/b` and `/a-b` both become `-a-b`. The digest makes those distinct, and the fixed prefix
  * makes the result one bounded path segment even when a broker is configured with a very long log-directory
  * path.
  */
opaque type AlertEventId = String

object AlertEventId {

  def of(key: AlertKey, openedAt: Instant): AlertEventId = {
    val timestamp = openedAt.toEpochMilli.toString
    val legacySubject = if key.subject.isEmpty then ClusterSubject else key.subject
    val legacy = s"${key.rule.wire}.$legacySubject.$timestamp"

    if legacyCompatible(key.subject, legacy) then legacy
    else {
      val preview = sanitise(key.subject).take(MaxReadableSubject)
      s"${key.rule.wire}.$HashNamespace.$preview.${digestOf(key.subject)}.$timestamp"
    }
  }

  /** Reads an id back off a URL. It is not parsed into its parts: an id is an opaque handle to the store, and
    * re-deriving a key from one would make a caller able to name an event that never existed.
    */
  def from(raw: String): Option[AlertEventId] = {
    val trimmed = raw.trim
    Option.when(trimmed.nonEmpty && trimmed.length <= MaxLength && trimmed == sanitise(trimmed))(trimmed)
  }

  /** The maximum accepted and generated length. Hashed ids cap their readable subject prefix so even a log
    * directory near the filesystem's own path limit stays below this request-line bound.
    */
  val MaxLength: Int = 256

  extension (id: AlertEventId) def value: String = id

  private def sanitise(raw: String): String =
    raw.map(character => if Allowed.contains(character) then character else '-')

  /** Keeps existing ids where their subject was already an injective path segment. `cluster` and `_h.` are
    * reserved so a real subject cannot collide with the cluster-wide label or the hashed namespace.
    */
  private def legacyCompatible(raw: String, id: String): Boolean =
    raw.isEmpty ||
      (raw == sanitise(raw) &&
        raw != ClusterSubject &&
        !raw.startsWith(s"$HashNamespace.") &&
        id.length <= MaxLength)

  /** The first 128 bits of SHA-256. That keeps an id compact while leaving collision resistance far beyond
    * the number of events the bounded in-memory store can hold.
    */
  private def digestOf(raw: String): String = {
    val digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))
    val encoded = new StringBuilder(DigestCharacters)

    digest.take(DigestBytes).foreach { byte =>
      val unsigned = byte & 0xff
      encoded.append(Hex.charAt(unsigned >>> 4))
      encoded.append(Hex.charAt(unsigned & 0x0f))
    }

    encoded.result()
  }

  private val ClusterSubject: String = "cluster"
  private val HashNamespace: String = "_h"
  private val MaxReadableSubject: Int = 80
  private val DigestBytes: Int = 16
  private val DigestCharacters: Int = DigestBytes * 2
  private val Hex: String = "0123456789abcdef"

  private val Allowed: Set[Char] =
    (('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9') ++ Seq('.', '_', '-')).toSet

  given CanEqual[AlertEventId, AlertEventId] = CanEqual.derived
}

/** Why an event is no longer open.
  *
  * Two causes and they are not the same fact, which is why this is an enum and not a boolean. `Cleared` is
  * the cluster: the rule stopped firing, so the thing an operator was told about has stopped happening.
  * `Acknowledged` is a person: the thing may still be happening and somebody has said they know. A feed that
  * collapsed the two would answer "is it fixed?" with "somebody looked at it".
  */
enum AlertResolutionKind(val wire: String) {
  case Cleared extends AlertResolutionKind("cleared")
  case Acknowledged extends AlertResolutionKind("acknowledged")
}

object AlertResolutionKind {

  def fromWire(raw: String): Option[AlertResolutionKind] =
    values.find(_.wire == raw.trim.toLowerCase)

  given CanEqual[AlertResolutionKind, AlertResolutionKind] = CanEqual.derived
}

/** How and when an event closed.
  *
  * @param by
  *   who acknowledged it, rendered the way an audit record renders a principal. `None` for a `Cleared`
  *   resolution, because nobody did it — and a `system` placeholder there would make "who cleared this" a
  *   question with a misleading answer rather than no answer.
  */
final case class AlertResolution(at: Instant, kind: AlertResolutionKind, by: Option[String])

object AlertResolution {
  given CanEqual[AlertResolution, AlertResolution] = CanEqual.derived
}

/** One thing KUI noticed about a cluster.
  *
  * @param openedAt
  *   when the rule first fired. It is the age the feed renders, and it is KUI's own observation rather than
  *   the cluster's: a broker publishes no "this partition went offline at" timestamp, so this is the moment
  *   KUI *saw* it and the wire says so in the field's description.
  * @param lastSeenAt
  *   the most recent pass on which the rule was still firing. It exists so that "opened four hours ago and
  *   last seen four hours ago" — an event whose rule has not been evaluated since, because the facts behind
  *   it are unreadable — is distinguishable from one that is still happening now.
  * @param detail
  *   the indented line under the title in `M01` and `M05`: the entity, and the number that made the rule
  *   fire. It names what a reader would otherwise have to go and look up.
  */
final case class AlertEvent(
    id: AlertEventId,
    key: AlertKey,
    severity: AlertSeverity,
    openedAt: Instant,
    lastSeenAt: Instant,
    title: String,
    detail: String,
    resolution: Option[AlertResolution]
) {

  def category: AlertCategory = key.rule.category

  def isOpen: Boolean = resolution.isEmpty

  /** The tone the row is drawn in.
    *
    * Not simply `severity.tone`: a resolved row is drawn in the success tone whatever it opened at, which is
    * the fourth dot in `SCREENS-V4.md` §3.8 and the reason [[AlertSeverity]] has no `Success` case. Decided
    * here rather than in the browser so that the bell, the card and the notifications panel cannot each
    * decide it differently.
    */
  def tone: String = if isOpen then severity.tone else AlertEvent.ResolvedTone

  def resolvedBy(at: Instant, kind: AlertResolutionKind, by: Option[String]): AlertEvent =
    copy(resolution = Some(AlertResolution(at, kind, by)))

  def seenAt(at: Instant): AlertEvent = copy(lastSeenAt = at)
}

object AlertEvent {

  /** The tone a resolved row is drawn in, whatever severity it opened at. */
  val ResolvedTone: String = "success"

  /** Opens one. The id is derived from the same two values, so an event cannot be constructed with an id that
    * disagrees with its own key.
    */
  def open(
      key: AlertKey,
      severity: AlertSeverity,
      at: Instant,
      title: String,
      detail: String
  ): AlertEvent =
    AlertEvent(
      id = AlertEventId.of(key, at),
      key = key,
      severity = severity,
      openedAt = at,
      lastSeenAt = at,
      title = title,
      detail = detail,
      resolution = None
    )

  /** Newest first, and ties broken by id so that two events opened in the same millisecond order the same way
    * on every request. A feed whose rows swap places between two polls of the same unchanged data is one
    * nobody can read.
    */
  given Ordering[AlertEvent] =
    Ordering.by[AlertEvent, (Long, String)](event => (-event.openedAt.toEpochMilli, event.id.value))

  given CanEqual[AlertEvent, AlertEvent] = CanEqual.derived
}
