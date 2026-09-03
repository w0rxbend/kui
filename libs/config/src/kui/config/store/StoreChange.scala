package kui.config.store

import java.time.Instant

/** What happened to one key in the store.
  *
  * `Upserted` carries the whole record rather than only its key, so that a consumer never has to call back
  * into the store to find out what changed — a call that would race with the next change and could return a
  * *later* record than the one the consumer is being told about.
  */
enum StoreChange {
  case Upserted(record: StoreRecord)
  case Deleted(deletedKey: StoreKey, version: Long, at: Instant)

  /** This subscriber fell behind and lost changes; its view is incomplete and it must re-read.
    *
    * It exists so that "a slow subscriber loses the oldest changes" is a signal rather than silence. A
    * subscriber that quietly missed a deletion would show a cluster that is gone for as long as the process
    * lives, and nothing would ever tell it otherwise. Re-reading the whole section is a handful of records
    * and is the honest response.
    */
  case Desynchronized(missed: Long)

  /** The key this change is about, for the two cases that are about one. */
  def keyOption: Option[StoreKey] = this match {
    case Upserted(record) => Some(record.key)
    case Deleted(deletedKey, _, _) => Some(deletedKey)
    case Desynchronized(_) => None
  }
}

object StoreChange {
  given CanEqual[StoreChange, StoreChange] = CanEqual.derived
}
