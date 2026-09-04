package kui.ui.clusters.admin

import kui.cluster.contract.dto.{AdminTuningWrite, ClusterSecurityWrite, ClusterWriteRequest}
import kui.contracts.cluster.ClusterRowDto
import kui.kernel.Secret

/** What the operator has typed, as plain strings, and the rules for turning it into a request.
  *
  * ==Strings, not parsed values==
  *
  * Every field is a `String`, including the three numbers. A form that held an `Int` would have to decide
  * what an empty box or a half-typed `12x` *is* while the operator is still typing, and every answer to that
  * is wrong: zero is a number they did not write, and refusing the keystroke stops them deleting a digit.
  * Parsing happens once, in [[toRequest]], at the moment they ask for something to be done.
  *
  * ==What is deliberately not here==
  *
  * Truststore and keystore material. The write contract accepts both as base64 blobs and this form does not
  * offer them, which means TLS configured with a *private* certificate authority still has to be written into
  * the deployment's configuration file. That is a real gap and it is named here rather than papered over: a
  * textarea for a base64 keystore is not a usable way to give KUI a certificate, and the file upload that
  * would be is a piece of work in its own right. `SSL` and `SASL_SSL` against a cluster whose certificate the
  * JVM's default trust store already accepts — which is every managed service — work from this form today.
  */
final case class ClusterForm(
    name: String,
    bootstrapServers: String,
    readOnly: Boolean,
    protocol: String,
    mechanism: String,
    username: String,
    password: String,
    verifyHostname: Boolean,
    timeoutMs: String,
    batchSize: String,
    parallelism: String
) {

  /** Whether this protocol needs a mechanism, a username and a password. */
  def isSasl: Boolean = protocol == ClusterForm.SaslPlaintext || protocol == ClusterForm.SaslSsl

  /** Whether hostname verification is a question at all. It is a TLS setting; on a plaintext connection there
    * is no certificate to verify a hostname against, and showing the control would suggest otherwise.
    */
  def isTls: Boolean = protocol == ClusterForm.Ssl || protocol == ClusterForm.SaslSsl

  /** The request, or every reason this form is not one yet.
    *
    * Every reason, accumulated, and not the first: someone who got three fields wrong should be told about
    * all three rather than discovering them one save at a time. The server accumulates for the same reason
    * (ADR-013) and refuses the same things; these checks exist to answer *before* a round trip and are
    * deliberately a subset — the server remains the authority, and this form never claims something is valid
    * that the server then rejects.
    *
    * The password is the one field this cannot check on an edit. KUI never sends a stored credential back to
    * the browser, so an edit form starts with the box empty, and an empty box means "leave the password
    * alone" — which this cannot express, because the write contract has no such value. See
    * [[ClusterForm.passwordWarning]] for what the screen says about it instead.
    */
  def toRequest: Either[List[String], ClusterWriteRequest] = {
    val problems = List.newBuilder[String]

    if name.trim.isEmpty then problems += "A name is required."
    if bootstrapServers.trim.isEmpty then problems += "At least one broker address is required."

    if isSasl && mechanism.trim.isEmpty then problems += "A SASL connection needs a mechanism."
    if isSasl && username.trim.isEmpty then problems += "A SASL connection needs a username."
    if isSasl && password.isEmpty then problems += "A SASL connection needs a password."

    val timeout = timeoutMs.trim.toLongOption
    val batch = batchSize.trim.toIntOption
    val threads = parallelism.trim.toIntOption

    if timeout.isEmpty then problems += "The admin timeout must be a whole number of milliseconds."
    if batch.isEmpty then problems += "The batch size must be a whole number."
    if threads.isEmpty then problems += "The parallelism must be a whole number."

    val found = problems.result()

    (found, timeout, batch, threads) match {
      case (Nil, Some(timeoutValue), Some(batchValue), Some(threadValue)) =>
        Right(
          ClusterWriteRequest(
            name = name.trim,
            readOnly = readOnly,
            bootstrapServers = bootstrapServers.trim,
            security = ClusterSecurityWrite(
              protocol = protocol,
              mechanism = Option.when(isSasl)(mechanism.trim),
              username = Option.when(isSasl)(username.trim),
              password = Option.when(isSasl)(Secret(password)),
              truststore = None,
              keystore = None,
              verifyHostname = verifyHostname
            ),
            properties = Map.empty,
            admin = AdminTuningWrite(timeoutValue, batchValue, threadValue)
          )
        )
      case _ => Left(found)
    }
  }
}

object ClusterForm {

  val Plaintext: String = "PLAINTEXT"
  val Ssl: String = "SSL"
  val SaslPlaintext: String = "SASL_PLAINTEXT"
  val SaslSsl: String = "SASL_SSL"

  val Protocols: List[(String, String)] =
    List(
      Plaintext -> "PLAINTEXT — no encryption, no authentication",
      Ssl -> "SSL — encrypted, no authentication",
      SaslPlaintext -> "SASL_PLAINTEXT — authenticated, not encrypted",
      SaslSsl -> "SASL_SSL — authenticated and encrypted"
    )

  /** The three mechanisms KUI is integration-tested against a real broker with.
    *
    * The vendor mechanisms — `AWS_MSK_IAM`, `OAUTHBEARER` and the rest — are configuration-file only, and the
    * write endpoint refuses them by name rather than accepting a value it cannot exercise. Offering them here
    * would produce a form whose Save is always refused.
    */
  val Mechanisms: List[(String, String)] =
    List(
      "PLAIN" -> "PLAIN",
      "SCRAM-SHA-256" -> "SCRAM-SHA-256",
      "SCRAM-SHA-512" -> "SCRAM-SHA-512"
    )

  /** An empty form, with the defaults a new cluster starts from.
    *
    * The three admin numbers are the domain's own defaults spelled out rather than left blank, because a
    * blank timeout is not a smaller decision than a wrong one — it is the same decision made by whoever wrote
    * the fallback, invisibly.
    */
  val Empty: ClusterForm =
    ClusterForm(
      name = "",
      bootstrapServers = "",
      readOnly = false,
      protocol = Plaintext,
      mechanism = "SCRAM-SHA-512",
      username = "",
      password = "",
      verifyHostname = true,
      timeoutMs = "60000",
      batchSize = "200",
      parallelism = "4"
    )

  /** The form for editing a cluster the dashboard already shows.
    *
    * Everything except the credentials, because KUI never sends a stored credential back to the browser — the
    * read model has a `truststoreConfigured` boolean and no password field, by design. The admin numbers are
    * not on the read model either, so they come back as the defaults; the screen says so.
    */
  def of(row: ClusterRowDto): ClusterForm =
    Empty.copy(
      name = row.name,
      bootstrapServers = row.bootstrapServers,
      readOnly = row.readOnly,
      protocol = row.security.protocol,
      mechanism = row.security.mechanism.getOrElse(Empty.mechanism)
    )

  /** What an edit form has to say about the password box being empty.
    *
    * The honest sentence, because the alternative — showing dots and pretending there is a value — would make
    * an operator who did not touch the field wipe the credential by saving. The write endpoint has no "leave
    * it as it was" value: a `PUT` replaces the record.
    */
  val passwordWarning: String =
    "KUI never sends a stored password back to the browser, so this box starts empty. Saving replaces " +
      "the whole cluster definition, so type the password again even if it has not changed."

  /** And what it has to say about the three admin numbers, for the same reason. */
  val tuningWarning: String =
    "The admin timeouts are not on the read model, so these show the defaults rather than what is stored. " +
      "Saving writes whatever is in these boxes."
}
