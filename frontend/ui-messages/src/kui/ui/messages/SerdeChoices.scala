package kui.ui.messages

import kui.kernel.serde.SerdeName

/** The serdes the two pickers on this screen offer, as `(wire value, label)`, with "Automatic" first.
  *
  * ==One list, two directions==
  *
  * The browse bar chooses how a record is *decoded*; the publish form chooses how one is *encoded*. They are
  * the same set of serdes and they have to stay the same set, because the mistake this screen can make that
  * leaves a topic worse than it found it is publishing with a serde the reader cannot read back. Two lists
  * maintained separately would drift the moment one of them gained an entry, and the drift would be invisible
  * until somebody published with it.
  *
  * ==Why "Automatic" is the empty string and the default==
  *
  * The service already resolves a serde per topic and per half of the record, says which it used on every
  * record it returns, and is right for almost every topic. Sending no name at all is what makes the produce
  * form agree with the browse screen by default: the record is written with the serde it would have been read
  * with. The picker exists for the topics where the service's choice is wrong — a key written as a big-endian
  * long that autodetection reads as four characters of nonsense, a value the producer double-encoded — where
  * without an override the only way to work with the topic is to edit the deployment's configuration, which
  * somebody investigating an incident cannot do.
  *
  * ==Why the names are constants and not literals==
  *
  * They are the spellings the service resolves against and the ones an operator already has in their
  * configuration file. A twelfth spelling typed here would be a menu entry that sends a name nothing answers
  * to. A deployment that configures a serde beyond these is not offered it: that needs the service to publish
  * its own list, which no endpoint does yet, and inventing a name in the browser would be a control that
  * fails on exactly the deployments it was added for.
  */
object SerdeChoices {

  /** The wire value that means "let the service choose". */
  val Automatic: String = ""

  val AutomaticLabel: String = Messages.SerdeAutomatic

  val names: List[SerdeName] =
    List(
      SerdeName.String,
      SerdeName.Json,
      SerdeName.Int32,
      SerdeName.Int64,
      SerdeName.UInt32,
      SerdeName.UInt64,
      SerdeName.Uuid,
      SerdeName.Base64,
      SerdeName.Hex,
      SerdeName.SchemaRegistry,
      SerdeName.Fallback
    )

  val options: List[(String, String)] =
    (Automatic -> AutomaticLabel) :: names.map(name => name.value -> name.value)

  /** A serde the picker can actually show, or [[Automatic]] for anything else.
    *
    * Used when a record is opened for republishing: the record says which serde decoded it, and defaulting
    * the form to that one is what makes "republish" produce a record the same reader can read. A serde this
    * build's menu does not list — one a deployment configured itself, or one from a newer KUI — falls back to
    * "Automatic" rather than being written into a `select` that has no such option, which would leave the
    * control showing one thing and the form holding another.
    */
  def offered(raw: String): String =
    if names.exists(_.value == raw) then raw else Automatic
}
