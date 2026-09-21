package kui.config

import kui.kernel.Secret

/** A Java key-store format accepted by an HTTP upstream.
  *
  * PEM is deliberately absent: its certificate/private-key pairing and secret contract are not part of the
  * Prometheus query-core configuration.
  */
enum HttpStoreFormat {
  case Jks
  case Pkcs12

  def wireName: String = this match {
    case Jks => "JKS"
    case Pkcs12 => "PKCS12"
  }
}

object HttpStoreFormat {
  val All: List[HttpStoreFormat] = List(Jks, Pkcs12)

  def fromWire(raw: String): Option[HttpStoreFormat] =
    All.find(_.wireName.equalsIgnoreCase(raw.trim))

  given CanEqual[HttpStoreFormat, HttpStoreFormat] = CanEqual.derived
}

/** Where the encoded bytes of an HTTP trust/key store come from. */
enum HttpStoreMaterial {
  case Location(path: String)
  case Inline(base64: Secret[String])
}

object HttpStoreMaterial {
  given CanEqual[HttpStoreMaterial, HttpStoreMaterial] = CanEqual.derived
}

final case class HttpTrustStore(
    material: HttpStoreMaterial,
    password: Secret[String],
    format: HttpStoreFormat
)

object HttpTrustStore {
  given CanEqual[HttpTrustStore, HttpTrustStore] = CanEqual.derived
}

final case class HttpKeyStore(
    material: HttpStoreMaterial,
    password: Secret[String],
    keyPassword: Secret[String],
    format: HttpStoreFormat
)

object HttpKeyStore {
  given CanEqual[HttpKeyStore, HttpKeyStore] = CanEqual.derived
}

/** Optional custom trust and mutual-TLS material for one HTTP upstream.
  *
  * `Default` means JVM trust roots, mandatory hostname verification and no client certificate. There is no
  * hostname-verification field: an unsafe state cannot be expressed by this model.
  */
final case class HttpTlsConfig(
    truststore: Option[HttpTrustStore],
    keystore: Option[HttpKeyStore]
)

object HttpTlsConfig {
  val Default: HttpTlsConfig = HttpTlsConfig(None, None)

  /** Every legal leaf below a `tls` block, shared by discovery and unknown-key validation. */
  def keysUnder(prefix: String): List[String] =
    List(
      "truststore.type",
      "truststore.location",
      "truststore.inline",
      "truststore.password",
      "keystore.type",
      "keystore.location",
      "keystore.inline",
      "keystore.password",
      "keystore.keyPassword"
    ).map(leaf => s"$prefix.$leaf")

  given CanEqual[HttpTlsConfig, HttpTlsConfig] = CanEqual.derived
}
