package kui.config.store

import io.circe.{Decoder, Encoder, Json}

import kui.kernel.Secret

/** How a payload says "this string is a secret" before it is encrypted, and after it is decrypted.
  *
  * A plaintext secret is the object `{"$secret":"hunter2"}` and never a bare string. The marker exists so
  * that the crypto layer (STORE-002) can walk any section's JSON and find every secret without a per-section
  * list of field paths — a list that would have to be extended, and forgotten, the first time a section
  * gained a field. Encryption replaces the whole marker object with `{"$enc":{...}}`; decryption puts it
  * back. Nothing outside `kui.config.store` ever sees an `$enc` node.
  */
object SecretJson {

  /** The field name that marks a decrypted secret. */
  val PlaintextField: String = "$secret"

  /** The field name that marks an encrypted secret. STORE-002 owns what is inside it. */
  val CipherField: String = "$enc"

  def encoder: Encoder[Secret[String]] =
    Encoder.instance(secret => Json.obj(PlaintextField -> Json.fromString(secret.value)))

  def decoder: Decoder[Secret[String]] =
    Decoder.instance(cursor => cursor.downField(PlaintextField).as[String].map(Secret.apply))

  /** Every JSON path in `payload` holding a plaintext secret marker, in document order.
    *
    * Paths are rendered the way a person reading a failure message would write them: `security.password` for
    * an object field and `listeners[0].password` for an array element. STORE-002 uses them to build the
    * additional authenticated data that binds a ciphertext to the field it came from, and the STORE-009 leak
    * test uses them to know what to look for.
    */
  def plaintextPaths(payload: Json): List[String] = collect(payload, "", PlaintextField)

  /** True when no `$secret` marker survives anywhere in the tree.
    *
    * The write path asserts this after encryption, so a section that gains a secret field can never reach the
    * topic in the clear because somebody forgot to register it. The assertion is the whole reason the marker
    * convention was chosen over a registry of field paths.
    */
  def isFullyEncrypted(payload: Json): Boolean = plaintextPaths(payload).isEmpty

  /** Whether `json` is exactly a marker object for `field` — one field, of that name, holding the expected
    * shape. A payload field that merely *contains* a `$secret` key among others is not a marker, because
    * replacing it wholesale on encryption would silently drop its siblings.
    */
  private[store] def isMarker(json: Json, field: String): Boolean =
    json.asObject.exists(obj => obj.keys.toList == List(field))

  private def collect(json: Json, path: String, field: String): List[String] =
    if isMarker(json, field) then List(path)
    else
      json.asObject match {
        case Some(obj) =>
          obj.toList.flatMap((name, value) => collect(value, join(path, name), field))
        case None =>
          json.asArray match {
            case Some(items) =>
              items.toList.zipWithIndex.flatMap((item, index) => collect(item, s"$path[$index]", field))
            case None => Nil
          }
      }

  private def join(prefix: String, name: String): String =
    if prefix.isEmpty then name else s"$prefix.$name"
}
