# STORE-002 — `FieldCrypto`: AES-GCM envelope encryption, `keyId`, rotation reads

- **ID:** STORE-002
- **Title:** `FieldCrypto`: AES-GCM envelope encryption, `keyId`, rotation reads
- **Milestone / Feature:** M1 / OT-004, ADR-042 §4
- **Owner role:** Principal Scala Engineer
- **Context / service:** `libs/config`, package `kui.config.store`
- **Size:** M
- **Dependencies / blocked by:** STORE-001

## Goal (user value)

A Kafka record is plaintext to anybody who can read the topic. This task is the reason the
milestone's security exit criterion — *"a console-consumer dump of `__kui_config` contains no
plaintext password and no JAAS string"* — can pass: every secret field is AES-GCM encrypted
before it is produced, under a key that lives outside the store, tagged with the id of the key
that encrypted it so that an operator can rotate keys without a flag day.

## Scope

1. `FieldCrypto[F]`: encrypt/decrypt of a *single string*, and the JSON-tree walk that turns
   every `{"$secret": "..."}` marker (STORE-001) into `{"$enc": {...}}` and back.
2. `EncryptionKeyring`: several keys by id, exactly one of them active for writes; reads try the
   `keyId` in the record and nothing else.
3. The named failures: a record whose `keyId` is not in the keyring, and a record that fails the
   GCM authentication tag.
4. `Aad`: what goes into AES-GCM's additional authenticated data, and why.

## Non-goals

**No key management**: no KMS client, no key derivation from a passphrase, no key generation
inside KUI. The operator generates 32 random bytes and supplies them base64-encoded
(`docs/operations/metadata-store.md` §4.2); anything cleverer is a second secret store, which is
what ADR-042 exists to avoid. **No rekey endpoint.** `metadata-store.md` §4.2 step 3 documents
`POST /internal/v1/store/rekey`; it is **not built in M1** (see "Decisions" below). **No
configuration loading** — `EncryptionKeyring` is constructed from already-parsed material;
STORE-004 owns the Ciris keys and the base64 decode. **No file-payload encryption**: `__kui_files`
carries no records in M1 (STORE-005), and when it does, its payload is a byte array encrypted
with the same `FieldCrypto.encryptBytes`, which this task provides and nothing calls yet — it is
provided because writing it later would mean changing the record format, and because
`encryptString` is a one-line adapter over it anyway.

## Design references

ADR-042 §4 ("encrypted with AES-GCM before they reach the topic, under a key from
`kui.store.encryptionKey`, with a `keyId` in the envelope so keys can be rotated"), and its
Consequences ("a wrong or lost key makes secrets unreadable").
`docs/operations/metadata-store.md` §4.2 — the operator-facing contract this implements, verbatim.
`research/scala/security-research.md` §5 (secret leakage through config endpoints and logs).
DEVPLAN §7 row "Crypto", §8 risk R-3, §10 D-none (this area's decisions are taken below).
ADR-034 for the `ErrorCode` values STORE-001 added.

## Files to create

```
libs/config/src/kui/config/store/FieldCrypto.scala
libs/config/src/kui/config/store/EncryptionKeyring.scala
libs/config/test/src/kui/config/store/FieldCryptoSuite.scala
libs/config/test/src/kui/config/store/EncryptionKeyringSuite.scala
libs/config/test/resources/store/record-cluster-encrypted-k1.json
```

## Files to change

```
libs/config/src/kui/config/store/StoreError.scala   (the two crypto cases)
```

## Public Scala signatures to implement

```scala
package kui.config.store

import cats.effect.Sync
import io.circe.Json
import kui.kernel.Secret

/** One AES-256 key and the id it is known by. The id is short, operator-chosen text: it appears
  * in every record the key encrypts and is how a reader picks the right key after a rotation. */
final case class EncryptionKey(id: String, material: Secret[Array[Byte]])

object EncryptionKey:
  val KeyLengthBytes: Int = 32
  val IdPattern: String   = "^[a-z0-9][a-z0-9-]{0,31}$"
  /** Decodes base64, checks the length, checks the id. The error never contains the material. */
  def fromBase64(id: String, base64: String): Either[StoreError, EncryptionKey]

/** Every key KUI can decrypt with, and the one it encrypts with. */
final class EncryptionKeyring private (val active: EncryptionKey, val all: Map[String, EncryptionKey]):
  def find(keyId: String): Option[EncryptionKey]
  /** `EncryptionKeyring(***, 2 keys: k1, k2)` — ids yes, material never. */
  override def toString: String

object EncryptionKeyring:
  /** Fails when `activeKeyId` is not among `keys`, or `keys` is empty, naming the ids present. */
  def of(keys: List[EncryptionKey], activeKeyId: String): Either[StoreError, EncryptionKeyring]

/** Encrypts and decrypts the secret-marked fields of a store payload.
  *
  * `F` is here rather than being a pure API because the JCE calls are effects: they touch a
  * `SecureRandom`, they can throw, and a `Cipher` is not thread-safe, so each call gets its own
  * instance inside `Sync[F].blocking`. A pure signature would either lie or force the caller to
  * hold a mutable cipher. */
trait FieldCrypto[F[_]]:
  /** Replaces every `{"$secret": s}` node with `{"$enc": {...}}`. A payload with no marker is
    * returned unchanged and costs no cipher. */
  def encryptPayload(key: StoreKey, payload: Json): F[Json]

  /** The inverse. A payload with no `$enc` node is returned unchanged. Fails with
    * `StoreError.UnknownKeyId` or `StoreError.DecryptionFailed`; never returns a partially
    * decrypted payload, because a caller that received one would have no way to tell. */
  def decryptPayload(key: StoreKey, payload: Json): F[Either[StoreError, Json]]

  def encryptBytes(aad: Array[Byte], plaintext: Array[Byte]): F[CipherBlob]
  def decryptBytes(aad: Array[Byte], blob: CipherBlob): F[Either[StoreError, Array[Byte]]]

object FieldCrypto:
  def apply[F[_]: Sync](keyring: EncryptionKeyring): FieldCrypto[F]

/** The `$enc` node's contents. `iv` and `ct` are base64 (`Base64.getEncoder`, no wrapping);
  * `ct` includes the 16-byte GCM tag, because that is what `Cipher.doFinal` returns and
  * splitting it would be a second thing to get wrong. */
final case class CipherBlob(alg: String, keyId: String, iv: String, ct: String)

object CipherBlob:
  val Algorithm: String = "AES-256-GCM"
  given Encoder[CipherBlob]
  given Decoder[CipherBlob]
```

New `StoreError` cases:

```scala
case UnknownKeyId(keyId: String, known: Set[String])   // ErrorCode.StoreCrypto
case DecryptionFailed(keyId: String, where: String)    // ErrorCode.StoreCrypto
case InvalidKeyMaterial(keyId: String, why: String)    // ErrorCode.StoreCrypto
```

`where` is the JSON path of the field, never its value. `DecryptionFailed` carries no cause
string: a JCE exception message is safe today and is not a thing to bet a secret on.

## Cryptographic parameters, decided here

A worker must implement exactly this and change none of it without an ADR amendment:

| Parameter | Value | Why |
| --- | --- | --- |
| Transformation | `AES/GCM/NoPadding` | ADR-042 §4 says AES-GCM; GCM authenticates, so a tampered record fails rather than decrypting to garbage |
| Key length | 256 bits (32 bytes) | what `openssl rand -base64 32` in the operator doc produces |
| IV | 12 bytes from one process-wide `SecureRandom`, **fresh per encryption** | 96 bits is GCM's native IV size; reusing an IV under one key destroys GCM's security completely, so the IV is generated inside `encryptBytes` and is not a parameter any caller can supply |
| Tag | 128 bits, appended to the ciphertext | the JCE default and the maximum |
| Encoding | Base64 (RFC 4648, no line wrapping) for `iv` and `ct` | the record must survive `kafka-console-consumer` and a TSV export (metadata-store.md §5) |
| AAD | `s"${key.render}|${path}"` as UTF-8 | binds a ciphertext to the record key **and the field path** it belongs to, so a password copied from `cluster/a`'s `password` field into `cluster/b`'s, or into `cluster/a`'s `truststorePassword`, fails authentication instead of silently decrypting. This is the cheap defence against an operator with topic-write access rearranging records |

**Consequence a worker must know:** because the AAD contains the field path, moving a secret field
to a different path in a section's payload makes existing records undecryptable at that field.
That is a migration, not a refactor. It is written into the scaladoc of `encryptPayload`.

## Decisions taken here (no ADR covers them)

1. **No rekey endpoint in M1.** `docs/operations/metadata-store.md` §4.2 step 3 documents
   `POST /internal/v1/store/rekey`, and no task in the M1 plan builds it. Rotation *reads* work
   from day one (that is the `keyId`'s whole purpose), and the only write surface M1 ships is
   `PUT /internal/v1/clusters/{id}` (DEVPLAN §10 D6), which re-encrypts the record it writes under
   the active key as a side effect. So an operator can complete a rotation in M1 by re-saving each
   cluster; the endpoint that does it in one call needs the M8 CRUD surface it belongs to.
   **CFGOP-008 must amend §4.2 step 3** to say so and to give the manual procedure. This task adds
   nothing to that document itself (§4 is inside the STORE area's §2–6 range, but the amendment
   belongs with the rest of CFGOP-008's operator pass, and duplicating it here would produce two
   edits to one paragraph).
2. **The keyring is required whenever the Kafka store is configured, and forbidden to be empty.**
   Starting with a Kafka store and no key would work until the first secret and then fail at
   write time, which is the worst place to find out. STORE-004 makes `encryptionKey` mandatory
   when `kui.store.kafka.*` is present; this task's `EncryptionKeyring.of` refuses an empty list
   so the type cannot represent the bad state.
3. **A payload with a `$secret` marker is never produced.** `isFullyEncrypted` (STORE-001) is
   asserted by `encryptPayload` on its own output, as an `assert`-style guard that raises
   `StoreError.MalformedRecord` rather than a Scala assertion. It costs one tree walk on a write
   that happens a few times a day and it is the difference between "we encrypt secrets" and "we
   encrypt the secrets we remembered".

## Library coordinates

None new. `javax.crypto` and `java.security.SecureRandom` are JDK 21 (`Versions.jdk = 21`).
Bouncy Castle (`org.bouncycastle:bcpkix-jdk18on:1.85`) is a **test-scope** dependency of
`libs/testkit` only and is *not* used here: the JDK's own AES-GCM provider is the one thing every
deployment already has.

## Acceptance criteria

```
$ ./mill libs.config.test
$ ./mill libs.config.test.testOnly kui.config.store.FieldCryptoSuite
$ ./mill __.checkFormat && ./mill __.fix --check
```

Behavioural acceptance, reproducible by hand once STORE-006 exists:

```
$ grep -o '"\$secret"' libs/config/test/resources/store/record-cluster-encrypted-k1.json | wc -l
0
```

## Tests required

- `FieldCryptoSuite` (unit + property, `munit-cats-effect`):
  - `roundTripsArbitraryBytes` — property over byte arrays of length 0…8 KiB.
  - `roundTripsArbitraryStrings` — property including empty, 4 KiB, and strings of quotes,
    backslashes, newlines, `=`, and non-BMP code points (the same alphabet the JAAS property test
    of KAFKA-002 uses; a password that breaks one usually breaks the other).
  - `everyEncryptionUsesAFreshIv` — encrypt the same plaintext 100 times, assert 100 distinct
    `iv` values. This is the test that catches the single worst implementation mistake.
  - `payloadWithNoMarkerIsUnchanged` — reference-equal is not asserted, structural equality is.
  - `encryptPayloadLeavesNoPlaintextMarker` — property: for any payload with markers,
    `SecretJson.isFullyEncrypted` holds afterwards.
  - `decryptPayloadIsTheInverse` — property: `decrypt(encrypt(p)) == p`.
  - `wrongKeyProducesANamedErrorAndNoPlaintext` — encrypt under `k1`, decrypt with a keyring
    holding a *different* 32 bytes under the id `k1`, assert `StoreError.DecryptionFailed`, and
    assert the returned `Either` is a `Left` (there is no partial result to inspect).
  - `unknownKeyIdIsANamedError` — a record with `keyId: "gone"` gives
    `StoreError.UnknownKeyId("gone", Set("k1"))`.
  - `tamperedCiphertextFailsAuthentication` — flip one bit of `ct`, expect `DecryptionFailed`.
  - `aadBindsTheFieldPath` — take the `$enc` node from `cluster/a`'s `password` field, splice it
    into the `truststorePassword` field of the same record, assert `DecryptionFailed` naming the
    path.
  - `aadBindsTheRecordKey` — the same node moved from `cluster/a` to `cluster/b` fails.
  - `rotationReadsOldRecords` — encrypt with a keyring whose active key is `k1`; decrypt with a
    keyring whose active key is `k2` and which still holds `k1`; succeeds. Then drop `k1` and
    assert `UnknownKeyId`. This is `metadata-store.md` §4.2's rotation procedure as a test.
  - `nothingLeaksThroughToString` — `keyring.toString`, `key.toString`, every `StoreError`'s
    `message`, and the `toString` of a `CipherBlob` contain neither the base64 key material nor
    any plaintext. Property-driven over generated key material and plaintexts.
- `EncryptionKeyringSuite` (unit):
  - `rejectsWrongKeyLength` — 16 and 31 bytes, message names the expected 32 and never the value.
  - `rejectsBadBase64` — message says "not valid base64" and does not echo the input.
  - `rejectsActiveKeyIdNotPresent` — names the ids that are present.
  - `rejectsEmptyKeyring`.
  - `rejectsBadKeyId` — uppercase, empty, 40 characters.

## Observability

`FieldCrypto` logs nothing. There is no useful log line it could emit that is not either
uninteresting ("encrypted a field") or dangerous. Failures are returned as values and logged by
the caller (STORE-006) with the record key, the field path and the `keyId` — never the ciphertext.

Two metrics, registered by STORE-008 when it owns the health surface and named here so the names
do not diverge: `kui.store.crypto.failures{reason=unknown_key|auth_tag}`.

## Degraded behavior

A decryption failure during replay is **not** fatal to the process. Rationale: one unreadable
record (a key rotated away too early, a hand-edited topic) must not stop KUI from serving the
other ninety-nine clusters, which is exactly the failure mode ADR-042's "keep serving from last
known state" is about. STORE-006 defines what replay does with it: the record is skipped, its key
is recorded in `StoreHealth.unreadableKeys`, a `WARN` names the key and the `keyId`, and the store
capability reports `Degraded`. A decryption failure on a **write** is fatal to that request and
returns `KUI-STORE-CRYPTO`.

## Docs to update

None in this task — see decision 1 above; the `metadata-store.md` §4.2 amendment is CFGOP-008's.
