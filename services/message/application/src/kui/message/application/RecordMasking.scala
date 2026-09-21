package kui.message.application

import cats.Applicative

import kui.kernel.{ClusterId, TopicName}
import kui.message.domain.DecodedRecord

/** What masking does to the records of one read: a total function from record to record.
  *
  * It is a value rather than a method on [[RecordMasking]] because the *decision* — which rules reach this
  * topic, and whether any do — is made once per read, and the *application* happens once per record. A port
  * that asked "mask this record" per record would re-resolve the rules a million times during one browse of a
  * million records, and would have no place to record the "masking applied to this read" fact that
  * `kui.masking.applied` counts.
  */
trait RecordMask {
  def apply(record: DecodedRecord): DecodedRecord
}

object RecordMask {

  /** The mask of a topic no rule reaches: the record, unchanged.
    *
    * The identity is a legal mask rather than an `Option[RecordMask]` so that the masked and unmasked paths
    * through the read cannot drift apart. They have drifted in every product where one of them was an `if`,
    * and the drift here would be a record that skipped masking on whichever branch nobody exercised.
    */
  val identity: RecordMask = record => record
}

/** Where the browse learns what a reader may not see (DM-001, ADR-023).
  *
  * ==Why it is stated as a port, and why it is stated HERE==
  *
  * Rule A3 keeps circe out of the use cases, and a masking rule is a regular expression over a JSON document
  * applied by a library. So the layer that reads records says what it needs — "give me the mask for this
  * topic" — and `infrastructure` satisfies it with `kui.security.masking.MaskingEngine` over
  * `kui.clusters[].masking[]`.
  *
  * Beside [[RecordSource]] rather than in `domain/ports`, and the two are the same shape from opposite ends:
  * `RecordSource` is where a browse's records come from and this is what is done to them before they leave.
  * Neither is a rule the domain states about a Kafka record — `DecodedRecord`, `BrowseRequest` and
  * `TrackQuery` are — and neither is consumed by anything but the two use cases in this package.
  *
  * There is a second reason and it is worth writing down, because it is a fact about this repository and not
  * about this feature. **A trait `X[F[_]]` declared under `services/<name>/domain/src` must be named in
  * `ARCHITECTURE.md` §3's row for that service**, and `services/gateway`'s `ArchitectureDocumentSuite` fails
  * the whole build when it is not — measured here: declaring this trait in `domain/ports` reddened
  * `everyPortTraitInAServicesDomainIsNamedInItsRow` in `./scripts/run-tests.sh`, in a suite three services
  * away, over a document owned by nobody. Any packet that adds a domain port also needs that row.
  *
  * ==Where it is called, and why that placement is the whole feature==
  *
  * Immediately after a record is decoded and before anything else in the service sees it: before the string
  * filter, before the smart filter, before a `DecodedRecord` becomes a DTO. ADR-023 requires masking "after
  * deserialization and before any DTO leaves the service", and doing it at the decode is the only placement
  * that satisfies that for **every** reader of a record at once rather than for whichever ones somebody
  * remembered.
  *
  * Masking before the filters, and not after them, is deliberate. A filter that ran on the unmasked text
  * would answer questions about the hidden value — `stringFilter=4111111111111111` returning one row tells
  * the reader the card number without ever drawing it — which is a search oracle over exactly the data the
  * rule exists to hide. The cost is that a masked field cannot be searched on, and that is the honest
  * consequence of hiding it: `MaskingRule`'s own scaladoc says masking "hides a field from every reader
  * equally", and a reader's filter is that reader.
  *
  * ==What this is not==
  *
  * Not applied on produce, ever. `ProduceUseCase` states that rule and it is right: masking a value on the
  * way in writes the mask into the topic and destroys the original. Not applied on a resend either, for the
  * same reason — a resend reads a record in order to write it back.
  *
  * ==What a decode error carries, which is a decision and not an omission==
  *
  * A `DecodedRecord` has one more payload-derived field than the three a mask obviously reaches:
  * `DecodeError.cause`, the sentence a serde writes when it cannot read the bytes. At least one serde quotes
  * them — `libs/serde`'s `JsonSerde` names the payload's first printable character — so a record whose value
  * is hidden by a whole-value rule could arrive carrying the first character of that value in the field
  * beside it.
  *
  * **The decision is that a masked half's decode error keeps its `target` and its `serde` and loses its
  * `cause`**, which becomes a fixed sentence saying the detail is withheld because a rule is in force.
  * `ConfiguredRecordMasking.withheldWhereMasked` implements it and argues the three alternatives down; the
  * short version is that masking the cause would throw away "this record could not be decoded" along with the
  * character, and leaving it would let the size of the leak be decided by whichever serde is added next. A
  * half no rule reaches keeps its cause exactly as the serde wrote it.
  *
  * `Decoded.properties` is the field in the same position that is **not** treated this way, and the reason is
  * a contract rather than a judgement: `DeserializeResult` says it carries `{type, id, subjects}` — the
  * schema's identity — and nothing from the payload. A serde that put payload text in there would be breaking
  * that contract, and the repair belongs at the serde.
  *
  * ==`originalValue`==
  *
  * ADR-023 says masking reaches `originalValue` too. This service has no such field: a record crosses the
  * boundary as one `MessageDto` carrying one `DecodedPayloadDto` per half, and `grep -rn originalValue` over
  * `services/` and `libs/` matches nothing but that sentence in the ADR and the engine's scaladoc. The clause
  * is therefore satisfied by there being exactly one text per half and this port masking it — not by a second
  * code path. If a raw-value field is ever added to the wire, it is added downstream of here and has to come
  * back through this mask.
  */
trait RecordMasking[F[_]] {

  /** The mask in force for one cluster and topic, resolved once per read. */
  def forTopic(cluster: ClusterId, topic: TopicName): F[RecordMask]
}

object RecordMasking {

  /** The masking of a deployment that configures none.
    *
    * Named rather than written inline at each call site, because "no masking" is what nearly every test in
    * this service wants and a lambda per suite is a lambda per suite that can be got subtly wrong.
    */
  def none[F[_]: Applicative]: RecordMasking[F] =
    (_, _) => Applicative[F].pure(RecordMask.identity)
}
