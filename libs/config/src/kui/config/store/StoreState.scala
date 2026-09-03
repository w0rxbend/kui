package kui.config.store

/** What happened when one record from the log was folded into the state. */
enum StoreApplied {

  case Accepted(change: StoreChange)

  /** A writer whose base version was stale. Normal, and worth seeing in a log. */
  case Ignored(key: StoreKey, recordVersion: Long, expectedVersion: Long)

  /** A record that could not be decoded or decrypted. That key is missing; everything else works. */
  case Unreadable(key: StoreKey, reason: String)
}

object StoreApplied {
  given CanEqual[StoreApplied, StoreApplied] = CanEqual.derived
}

/** The in-memory projection of `__kui_config`, and the rule that decides what the log means.
  *
  * Pure and free of any platform on purpose. Every interesting decision in the store — which of two racing
  * writers won, whether a record is a lost race, what the next version is — lives here and is testable
  * without a broker. What is left in the Kafka adapter is plumbing.
  */
final case class StoreState(
    records: Map[StoreKey, StoreRecord],
    lastAppliedOffset: Long,
    unreadable: Map[StoreKey, String]
) {

  /** The live record for a key. `None` for a key that was never written and for one that was deleted: a
    * tombstone is kept in `records` so that versions keep counting, but it is not a value anybody reads.
    */
  def get(key: StoreKey): Option[StoreRecord] = records.get(key).filterNot(_.deleted)

  def list(section: StoreSection): List[StoreRecord] =
    records.toList
      .filter((key, record) => key.section == section && !record.deleted)
      .sortBy((key, _) => key.render)
      .map(_._2)

  /** The version the next accepted write to this key must carry. */
  def nextVersion(key: StoreKey): Long = records.get(key).fold(1L)(_.version + 1L)

  /** Folds one decoded record in.
    *
    * **The version rule, which is the heart of the design.** A record is accepted only when its version is
    * exactly the next one for its key. Anything else is ignored.
    *
    * A *stale* version is a writer that lost a race: two replicas both read version 2, both produced version
    * 3, and the one whose record landed second in the partition lost. A *future* version is a gap, which
    * means records were lost or the log was edited by hand — and accepting it would let a writer skip the
    * conflict check entirely by inventing a large version number.
    *
    * Every replica applies this rule to the same ordered log, so every replica agrees on who won without any
    * of them talking to each other. That is what makes "the partition, not a lock, is the serialization
    * point" (ADR-042 §3) true rather than aspirational — and it is why the version rule, not the pre-write
    * check, is the correctness guarantee.
    */
  def apply(record: StoreRecord, offset: Long): (StoreState, StoreApplied) = {
    val expected = nextVersion(record.key)
    val advanced = copy(lastAppliedOffset = math.max(lastAppliedOffset, offset))
    if record.version != expected then (advanced, StoreApplied.Ignored(record.key, record.version, expected))
    else {
      val change =
        if record.deleted then StoreChange.Deleted(record.key, record.version, record.updatedAt)
        else StoreChange.Upserted(record)
      (
        advanced.copy(
          records = advanced.records.updated(record.key, record),
          unreadable = advanced.unreadable - record.key
        ),
        StoreApplied.Accepted(change)
      )
    }
  }

  /** Records that one entry of the log could not be read.
    *
    * The version is deliberately not advanced. An unreadable record is one this KUI cannot interpret, so it
    * cannot know what version the key is at, and guessing would make the next legitimate write look like a
    * lost race.
    */
  def markUnreadable(key: StoreKey, offset: Long, reason: String): (StoreState, StoreApplied) =
    (
      copy(
        lastAppliedOffset = math.max(lastAppliedOffset, offset),
        unreadable = unreadable.updated(key, reason)
      ),
      StoreApplied.Unreadable(key, reason)
    )

  def unreadableKeys: List[StoreKey] = unreadable.keys.toList.sortBy(_.render)
}

object StoreState {

  /** Offset `-1` means "nothing applied yet", which is also what a store with no log reports. */
  val empty: StoreState = StoreState(Map.empty, -1L, Map.empty)

  given CanEqual[StoreState, StoreState] = CanEqual.derived
}
