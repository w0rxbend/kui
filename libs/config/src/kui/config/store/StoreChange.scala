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

  def key: StoreKey = this match {
    case Upserted(record) => record.key
    case Deleted(deletedKey, _, _) => deletedKey
  }
}

object StoreChange {
  given CanEqual[StoreChange, StoreChange] = CanEqual.derived
}
