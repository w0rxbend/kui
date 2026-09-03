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

  /** A record encrypted under a key this KUI no longer holds — a rotation that dropped the old key too early,
    * or a topic written by a different deployment.
    */
  case UnknownKeyId(keyId: String, known: Set[String])
      extends StoreError(
        ErrorCode.StoreCrypto,
        s"no encryption key with id '$keyId' is configured; the keyring holds ${known.toList.sorted.mkString(", ")}"
      )

  /** AES-GCM refused the ciphertext: the wrong key, a tampered record, or a field moved to a path the
    * ciphertext was not bound to. `where` is the JSON path of the field and never its value, and there is
    * deliberately no cause string — a JCE exception message is safe today and is not a thing to bet a secret
    * on.
    */
  case DecryptionFailed(keyId: String, where: String)
      extends StoreError(
        ErrorCode.StoreCrypto,
        s"the field '$where' could not be decrypted with key '$keyId'"
      )

  /** Key material that is not 32 bytes, not valid base64, or carries an id that is not a slug. */
  case InvalidKeyMaterial(keyId: String, why: String)
      extends StoreError(ErrorCode.StoreCrypto, s"encryption key '$keyId' is unusable: $why")

  /** A store topic already exists with a setting KUI cannot work with.
    *
    * The message is the one `docs/operations/metadata-store.md` §2 prints, word for word: the topic, the
    * setting, what KUI expected, what it found, and what an operator can do about it. KUI never rewrites an
    * existing topic's configuration, so the two ways out are to fix the topic or to point
    * `kui.store.topicPrefix` somewhere else, and the message says both.
    */
  case TopicIncompatible(topic: String, setting: String, expected: String, found: String)
      extends StoreError(
        ErrorCode.StoreTopicIncompatible,
        s"topic $topic has $setting=$found, expected $expected. KUI will not change an existing topic's " +
          "configuration. Fix the topic or point kui.store.topicPrefix at a different prefix."
      )

  /** Replay could not reach the log's end inside `kui.store.replayTimeout`.
    *
    * All three numbers are in the message on purpose. "Replayed 40000 of 41200 records in 30s" tells an
    * operator to raise the timeout; "replay timed out" tells them nothing and costs them an hour. This error
    * existing at all is the mitigation for the milestone's worst startup failure shape, which is a process
    * that hangs rather than failing.
    */
  case ReplayTimeout(topic: String, reached: Long, endOffset: Long, afterMs: Long)
      extends StoreError(
        ErrorCode.StoreReplayTimeout,
        s"replaying $topic reached offset $reached of $endOffset in ${afterMs}ms and did not finish; " +
          "raise kui.store.replayTimeout, or check that the store cluster is keeping up"
      )

  /** The store cluster could not be reached, or refused the operation.
    *
    * `why` is a short classification written by the adapter — "not authorized to create topics", "no broker
    * answered" — and never an exception's message, which routinely carries hosts, ports and occasionally
    * credentials.
    */
  case Unreachable(bootstrapServers: String, why: String)
      extends StoreError(
        ErrorCode.StoreUnavailable,
        s"the metadata store at $bootstrapServers could not be used: $why"
      )
}

/** A store failure on a path whose signature is `F[A]` rather than `F[Either[StoreError, A]]`.
  *
  * Exactly one thing raises it: `FieldCrypto.encryptPayload`'s guard that no plaintext secret marker survives
  * encryption. That guard is an invariant of KUI's own code, not a condition a caller can recover from, so it
  * is not in the return type — but it still has to carry the named error rather than a bare assertion, so
  * that what an operator sees is `KUI-STORE-CRYPTO` and not a stack trace.
  */
final class StoreFailure(val error: StoreError) extends RuntimeException(error.message)

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
