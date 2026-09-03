package kui.kernel.cluster

import kui.kernel.Secret

/** Where the bytes of a keystore or truststore come from.
  *
  * `Inline` carries **base64 text** rather than `Array[Byte]` for two reasons. An array has reference
  * equality, so every case class holding one would compare wrongly — two identical configurations would look
  * different, and a change-detection loop would reload for ever. And the two places a store can actually come
  * from, a YAML value and an environment variable, both carry text anyway (ADR-013). Decoding happens exactly
  * once, in `libs/kafka-auth`, on the JVM.
  */
enum StoreSource {

  /** The store itself, base64-encoded. Secret because a PKCS#12 file contains a private key. */
  case Inline(base64: Secret[String])

  /** A path the process can read. Not a secret: an operator needs to see it in an error message. */
  case FromPath(path: String)
}

object StoreSource {
  given CanEqual[StoreSource, StoreSource] = CanEqual.derived
}

/** The store formats Kafka's `ssl.truststore.type` / `ssl.keystore.type` accept. */
enum StoreType {
  case Jks
  case Pkcs12
  case Pem

  /** The value the Kafka property takes, in the spelling the client expects. */
  def wireName: String = this match {
    case Jks => "JKS"
    case Pkcs12 => "PKCS12"
    case Pem => "PEM"
  }
}

object StoreType {
  given CanEqual[StoreType, StoreType] = CanEqual.derived
}

final case class TrustStoreRef(
    source: StoreSource,
    password: Option[Secret[String]],
    storeType: StoreType
)

final case class KeyStoreRef(
    source: StoreSource,
    password: Option[Secret[String]],
    keyPassword: Option[Secret[String]],
    storeType: StoreType
)

/** Everything TLS needs, and nothing that is not TLS. */
final case class TlsConfig(
    truststore: Option[TrustStoreRef],
    keystore: Option[KeyStoreRef],
    /** `false` renders `ssl.endpoint.identification.algorithm=""`, which turns hostname verification off and
      * makes the connection vulnerable to an in-path attacker. It is a plain field with no default rather
      * than an option with one, so that turning verification off is always visible in the configuration file
      * that did it.
      */
    verifyHostname: Boolean,
    enabledProtocols: Option[List[String]],
    cipherSuites: Option[List[String]]
)

object TlsConfig {

  /** Server certificates validated against the JVM's default trust store, hostname verification on, no client
    * certificate. This is what a cluster with a certificate from a public CA needs, and it is the safe end of
    * every axis.
    */
  val default: TlsConfig = TlsConfig(
    truststore = None,
    keystore = None,
    verifyHostname = true,
    enabledProtocols = None,
    cipherSuites = None
  )
}

/** Whether SASL runs over a plaintext socket or a TLS one. */
enum SaslProtocol {
  case SaslPlaintext
  case SaslSsl
}

object SaslProtocol {
  given CanEqual[SaslProtocol, SaslProtocol] = CanEqual.derived
}

/** The authentication mechanisms KUI can present to a broker (ADR-022).
  *
  * Every credential is a `Secret[String]`: there is no case in this enum in which a password can be held as a
  * bare `String`, so there is no `toString` anywhere in KUI that can print one.
  */
enum SaslMechanism {
  case Plain(username: String, password: Secret[String])
  case ScramSha256(username: String, password: Secret[String])
  case ScramSha512(username: String, password: Secret[String])
  case Gssapi(
      serviceName: String,
      principal: String,
      keyTab: Option[String],
      useTicketCache: Boolean,
      storeKey: Boolean
  )
  case OAuthBearer(
      tokenEndpoint: String,
      clientId: String,
      clientSecret: Secret[String],
      scope: Option[String]
  )
  case AwsMskIam(profile: Option[String], roleArn: Option[String], stsRegion: Option[String])
  case AzureEntra(namespace: String, tokenEndpoint: Option[String])
  case GcpManagedKafka

  /** The value Kafka's `sasl.mechanism` takes.
    *
    * Azure Entra and GCP Managed Kafka both authenticate with an OAuth token obtained by a vendor callback
    * handler, so both are `OAUTHBEARER` on the wire and differ only in which handler class `libs/kafka-auth`
    * names. The mapping lives here, on the ADT, so that adding a mechanism without deciding its wire name is
    * a compile error rather than a runtime surprise.
    */
  def wireName: String = this match {
    case _: Plain => "PLAIN"
    case _: ScramSha256 => "SCRAM-SHA-256"
    case _: ScramSha512 => "SCRAM-SHA-512"
    case _: Gssapi => "GSSAPI"
    case _: OAuthBearer => "OAUTHBEARER"
    case _: AwsMskIam => "AWS_MSK_IAM"
    case _: AzureEntra => "OAUTHBEARER"
    case GcpManagedKafka => "OAUTHBEARER"
  }
}

object SaslMechanism {
  given CanEqual[SaslMechanism, SaslMechanism] = CanEqual.derived
}

/** How KUI authenticates and encrypts its connection to one cluster. */
enum ClusterSecurity {
  case Plaintext
  case Ssl(tls: TlsConfig)
  case Sasl(protocol: SaslProtocol, mechanism: SaslMechanism, tls: Option[TlsConfig])

  /** The value Kafka's `security.protocol` takes.
    *
    * Derived, never configured separately: two fields that must agree are two fields that will eventually
    * disagree, and the disagreement shows up as an authentication failure against a production cluster rather
    * than as a validation error at startup.
    */
  def securityProtocol: String = this match {
    case Plaintext => "PLAINTEXT"
    case Ssl(_) => "SSL"
    case Sasl(SaslProtocol.SaslPlaintext, _, _) => "SASL_PLAINTEXT"
    case Sasl(SaslProtocol.SaslSsl, _, _) => "SASL_SSL"
  }

  /** `true` when the connection is encrypted, i.e. `Ssl` or `Sasl` over `SaslSsl`. */
  def usesTls: Boolean = this match {
    case Plaintext => false
    case Ssl(_) => true
    case Sasl(SaslProtocol.SaslSsl, _, _) => true
    case Sasl(SaslProtocol.SaslPlaintext, _, _) => false
  }

  /** The TLS settings, if the connection has any.
    *
    * `Sasl` over `SaslSsl` with no `tls` block means "TLS with the defaults", which is the common case for a
    * managed cluster with a publicly trusted certificate, so it answers with `TlsConfig.default` rather than
    * `None`.
    */
  def tlsConfig: Option[TlsConfig] = this match {
    case Plaintext => None
    case Ssl(tls) => Some(tls)
    case Sasl(SaslProtocol.SaslSsl, _, tls) => Some(tls.getOrElse(TlsConfig.default))
    case Sasl(SaslProtocol.SaslPlaintext, _, _) => None
  }

  /** The mechanism, for the connections that have one. */
  def saslMechanism: Option[SaslMechanism] = this match {
    case Sasl(_, mechanism, _) => Some(mechanism)
    case Plaintext | Ssl(_) => None
  }
}

object ClusterSecurity {
  given CanEqual[ClusterSecurity, ClusterSecurity] = CanEqual.derived
}
