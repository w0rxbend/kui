package kui.config.store

import kui.kernel.error.{ErrorCode, InfrastructureError, KuiError}

/** Failures of the metadata store that are not failures of a request.
  *
  * These are values, not exceptions (ADR-034): the store's readers hand them back up the call chain and the
  * adapter boundary is the only place that ever catches something thrown by a library. Each case names one
  * `ErrorCode` so that an operator searching a log for `KUI-STORE-ENVELOPE` finds every occurrence of the
  * same problem, whatever produced it.
  */
enum StoreError(val code: ErrorCode, val message: String) extends Product, Serializable {

  /** A record key that is not `<section>/<id>`, or whose id is not a slug. */
  case InvalidKey(raw: String, why: String)
      extends StoreError(ErrorCode.StoreEnvelope, s"'$raw' is not a store key: $why")

  /** A record written by a KUI whose envelope this one does not understand.
    *
    * Deliberately an error rather than a skip. Skipping would make a newer writer's record invisible to an
    * older reader, which then serves a stale view of the world and reports itself healthy while doing it.
    */
  case UnsupportedEnvelope(found: Int, supported: Set[Int])
      extends StoreError(
        ErrorCode.StoreEnvelope,
        s"store record envelopeVersion $found is not readable by this KUI, which supports ${supported.toList.sorted.mkString(", ")}"
      )

  /** The envelope parsed but says something impossible — the key inside it disagreeing with the Kafka record
    * key, for instance.
    */
  case MalformedRecord(key: String, why: String)
      extends StoreError(ErrorCode.StoreEnvelope, s"store record '$key' is malformed: $why")
}

object StoreError {

  /** Lifts a store failure into the error hierarchy the HTTP layer serves.
    *
    * All three cases are `InfrastructureError`: nothing a user typed produced them, and there is no request
    * the caller could reword to make them go away. `Remote` is the case that carries a code and a message
    * verbatim, which is exactly what is wanted here — re-deriving the message from the code would lose the
    * key and the version numbers that make the failure diagnosable.
    */
  def toKuiError(e: StoreError): KuiError =
    InfrastructureError.Remote(e.code, e.message, Nil)

  given CanEqual[StoreError, StoreError] = CanEqual.derived
}
